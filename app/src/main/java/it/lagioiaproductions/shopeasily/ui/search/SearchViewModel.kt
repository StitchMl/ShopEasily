package it.lagioiaproductions.shopeasily.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.lagioiaproductions.shopeasily.data.local.SourceState
import it.lagioiaproductions.shopeasily.data.model.CatalogPrice
import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.preferences.CartEntry
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferences
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.repository.NearbyStore
import it.lagioiaproductions.shopeasily.data.repository.OnDeviceCatalogRepository
import it.lagioiaproductions.shopeasily.data.repository.PriceWatchRepository
import it.lagioiaproductions.shopeasily.data.repository.StoreDeduplicator
import it.lagioiaproductions.shopeasily.domain.BasketGoal
import it.lagioiaproductions.shopeasily.domain.BasketOptimizer
import it.lagioiaproductions.shopeasily.domain.EcoBasketEstimate
import it.lagioiaproductions.shopeasily.domain.EcoBasketEstimator
import it.lagioiaproductions.shopeasily.domain.EcoStoreCandidate
import it.lagioiaproductions.shopeasily.domain.OfferRanking
import it.lagioiaproductions.shopeasily.domain.ProductMatcher
import it.lagioiaproductions.shopeasily.domain.SearchFilters
import it.lagioiaproductions.shopeasily.domain.SortMode
import it.lagioiaproductions.shopeasily.domain.TransportProfile
import it.lagioiaproductions.shopeasily.domain.StoreSustainabilityResult
import it.lagioiaproductions.shopeasily.domain.StoreAssessment
import it.lagioiaproductions.shopeasily.domain.StoreAssessmentEngine
import it.lagioiaproductions.shopeasily.sync.CatalogSyncWorker
import it.lagioiaproductions.shopeasily.sync.SyncProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SearchUiState(
    val query: String = "",
    val offers: List<Offer> = emptyList(),
    val isLoading: Boolean = false,
    val filters: SearchFilters = SearchFilters(),
    val availableStores: List<String> = emptyList(),
    val storeWebsites: Map<String, String?> = emptyMap(),
    val selectedStore: String? = null,
    val shoppingTotal: Double = 0.0,
    val matchedShoppingItems: Int = 0,
    val pendingShoppingItems: Int = 0,
    val oneStopTotal: Double? = null,
    val bestBasketTotal: Double? = null,
    val sustainableTotal: Double? = null,
    /** Clearly-labelled projections for eligible green stores; never treated as observed prices. */
    val ecoEstimatedPlans: Map<String, EcoBasketEstimate> = emptyMap(),
    val bestEcoEstimatedPlan: EcoBasketEstimate? = null,
    val storeAssessments: Map<String, StoreAssessment> = emptyMap(),
    val nearbyOffers: Int = 0,
    val expiringToday: Int = 0,
    val priceDrops: Int = 0,
    val historicalLows: Int = 0,
    val reachedTargets: Int = 0,
    val unavailableSources: Int = 0,
    val watchedQuery: Boolean = false,
    val selectedOfferIds: Set<Long> = emptySet(),
    val manualCartItems: Int = 0,
    val manualCartProductsTotal: Double = 0.0,
    val manualCartFuelCost: Double = 0.0,
    val manualCartTotal: Double = 0.0,
    val manualCartEmissionKg: Double = 0.0,
    val showSelectedOnly: Boolean = false,
    val ambiguousImageUrls: Set<String> = emptySet(),
    /** Background refresh in progress: shown as a thin, non-blocking indicator. */
    val syncProgress: SyncProgress? = null,
    val enriching: Boolean = false,
    /** Incremented when the user changes sort/filters/query: the list scrolls back to the top. */
    val orderVersion: Int = 0,
    val message: String? = null,
    /** Nearby markets and shops with no usable public price yet: never hidden from Home. */
    val localAlternatives: List<NearbyStore> = emptyList(),
)

/**
 * Home screen state.
 *
 * All heavy work (ranking thousands of offers, basket optimisation, price
 * history) runs on background dispatchers; the UI thread only receives the
 * final state. Selection is applied optimistically and persisted as a
 * snapshot, so a tap is visible immediately and survives catalogue refreshes.
 */
@OptIn(FlowPreview::class)
class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OnDeviceCatalogRepository(application)
    private val preferencesRepository = UserPreferencesRepository(application)
    private val priceWatch = PriceWatchRepository(application)
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var summaryJob: Job? = null
    private var enrichJob: Job? = null
    private var lastRefreshLocation: Pair<Double, Double>? = null
    private var lastRefreshAt: Long = 0L
    private var lastCatalogVersion: Long? = null
    @Volatile private var lastUnfiltered: List<Offer> = emptyList()
    @Volatile private var offersByStoreKey: Map<String, List<Offer>> = emptyMap()

    /** Cached, ranked catalogue: re-read only when the database changes or filters change. */
    private var rankedCache: List<Offer> = emptyList()
    private var rankedKey: Any? = null
    private var catalogPricesCache: List<CatalogPrice>? = null
    private var storesCache: List<NearbyStore> = emptyList()

    private val cacheMutex = Mutex()

    init {
        viewModelScope.launch {
            val preferences = runCatching { preferencesRepository.preferences.first() }.getOrDefault(UserPreferences())
            _uiState.update { it.copy(filters = it.filters.copy(includeLoyaltyOffers = preferences.includeLoyaltyOffers)) }
            search()
        }
        // Silent refresh: when the background worker writes new prices, recompute
        // the list without spinners and without touching the query or scroll.
        viewModelScope.launch {
            repository.observeCatalogVersion()
                .distinctUntilChanged()
                .drop(1)
                // Empty list: show the first prices almost at once; afterwards batch updates.
                .debounce { if (_uiState.value.offers.isEmpty()) 500L else 3_000L }
                .catch { }
                .collect {
                    if (searchJob?.isActive == true) {
                        pendingSilentRefresh = true
                    } else {
                        invalidateCatalog()
                        search(silent = true, fromBackground = true)
                    }
                }
        }
        // Selection comes only from the saved cart: background searches that started before
        // a tap can no longer overwrite it with an older snapshot (taps "lost").
        viewModelScope.launch {
            preferencesRepository.manualCartOfferIds.catch { }.collect { ids ->
                _uiState.update { it.copy(selectedOfferIds = ids) }
                refreshCartSummary()
            }
        }
        viewModelScope.launch {
            CatalogSyncWorker.observeProgress(application)
                .catch { }
                .collect { progress -> _uiState.update { it.copy(syncProgress = progress) } }
        }
    }

    fun updateQuery(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun submitSearch() {
        val query = _uiState.value.query.trim()
        userChangePending = true
        search()
        enrichJob?.cancel()
        if (query.length < 2) return
        // Look up ordinary shelf prices in the background; results appear when ready.
        enrichJob = viewModelScope.launch {
            _uiState.update { it.copy(enriching = true) }
            val imported = runCatching { repository.enrichRegularPrices(query) }.getOrDefault(0)
            _uiState.update { it.copy(enriching = false) }
            if (imported > 0) {
                invalidateCatalog()
                search(silent = true)
            }
        }
    }

    fun toggleCurrentPriceTarget() {
        val query = _uiState.value.query.trim()
        val price = _uiState.value.offers.minOfOrNull(Offer::price) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            priceWatch.toggleTarget(query, price)
            val watched = priceWatch.isWatched(query)
            _uiState.update { it.copy(watchedQuery = watched) }
        }
    }

    fun toggleOfferSelection(offer: Offer) {
        // Immediate feedback on the UI thread; persistence and totals follow.
        _uiState.update { state ->
            val ids = state.selectedOfferIds
            state.copy(selectedOfferIds = if (offer.id in ids) ids - offer.id else ids + offer.id)
        }
        viewModelScope.launch {
            runCatching {
                preferencesRepository.toggleManualCartOffer(
                    CartEntry(offer.id, offer.productName, offer.storeName, offer.price, offer.promotional),
                )
            }.onFailure { error ->
                _uiState.update { it.copy(message = "Selezione non salvata: ${error.localizedMessage ?: "errore"}") }
            }
            refreshCartSummary()
            if (_uiState.value.showSelectedOnly) search(silent = true)
        }
    }

    fun toggleSelectedOnly() {
        userChangePending = true
        _uiState.update { it.copy(showSelectedOnly = !it.showSelectedOnly) }
        search(silent = true)
    }

    fun clearManualCart() {
        viewModelScope.launch {
            preferencesRepository.clearManualCart()
            _uiState.update { it.copy(showSelectedOnly = false, selectedOfferIds = emptySet()) }
            refreshCartSummary()
            search(silent = true)
        }
    }

    fun consumeMessage() = _uiState.update { it.copy(message = null) }

    private val _reportStores = MutableStateFlow<List<NearbyStore>>(emptyList())
    /** Shops and markets where the user can report a price seen on the shelf. */
    val reportStores: StateFlow<List<NearbyStore>> = _reportStores.asStateFlow()

    fun loadReportStores() {
        viewModelScope.launch {
            _reportStores.value = runCatching { repository.priceReportStores() }.getOrDefault(emptyList())
        }
    }

    /**
     * Saves a price seen in a shop or at a market (they rarely publish prices online):
     * it appears in results, in the basket calculation and in the Quality sort.
     */
    fun addUserPrice(storeId: String, productName: String, priceText: String, quality: Int?) {
        val price = priceText.trim().replace("€", "").replace(',', '.').trim().toDoubleOrNull()
        if (price == null || price <= 0.0 || productName.isBlank()) {
            _uiState.update { it.copy(message = "Indica prodotto e prezzo (es. 2,50)") }
            return
        }
        viewModelScope.launch {
            val saved = runCatching { repository.addUserPrice(storeId, productName, price, quality) }.getOrDefault(false)
            if (saved) {
                invalidateCatalog()
                userChangePending = true
                search(silent = true)
            }
            _uiState.update { it.copy(message = if (saved) "Prezzo salvato" else "Prezzo non salvato") }
        }
    }

    fun selectSortMode(sortMode: SortMode) = updateFilters(_uiState.value.filters.copy(sortMode = sortMode))

    private var userChangePending = false

    fun setSustainableOnly(enabled: Boolean) = updateFilters(_uiState.value.filters.copy(sustainableOnly = enabled))

    fun setIncludeLoyaltyOffers(enabled: Boolean) =
        updateFilters(_uiState.value.filters.copy(includeLoyaltyOffers = enabled))

    fun selectStore(storeName: String?) {
        // Filter/restore instantly from the unfiltered list, then refresh in background.
        val base = lastUnfiltered
        userChangePending = true
        _uiState.update { state ->
            val storeKey = storeName?.let(StoreDeduplicator::brandKey)
            state.copy(
                selectedStore = storeName,
                // Pre-indexed during the background search: filtering is O(1) on the UI thread.
                offers = if (storeKey == null) base else offersByStoreKey[storeKey].orEmpty(),
            )
        }
        search(silent = true)
    }

    /** Clears the active store / "selected only" filter; returns true if something was cleared (Back). */
    fun clearFilters(): Boolean {
        val state = _uiState.value
        if (state.selectedStore == null && !state.showSelectedOnly) return false
        _uiState.update { it.copy(selectedStore = null, showSelectedOnly = false, offers = lastUnfiltered) }
        search(silent = true)
        return true
    }

    /** Stores the position and asks the background worker to refresh; never blocks the UI. */
    fun refreshForLocation(latitude: Double, longitude: Double) {
        val now = System.currentTimeMillis()
        val previous = lastRefreshLocation
        val movedMeters = previous?.let { distanceMeters(it.first, it.second, latitude, longitude) }
        if (previous != null && movedMeters != null && movedMeters < 500 && now - lastRefreshAt < 15 * 60_000L) return
        lastRefreshLocation = latitude to longitude
        lastRefreshAt = now
        viewModelScope.launch {
            runCatching { preferencesRepository.setLastLocation(latitude, longitude) }
            CatalogSyncWorker.requestNow(getApplication(), latitude, longitude)
        }
    }

    private fun updateFilters(filters: SearchFilters) {
        _uiState.update { it.copy(filters = filters) }
        userChangePending = true
        search(silent = true)
    }

    private suspend fun invalidateCatalog() = cacheMutex.withLock {
        rankedKey = null
        catalogPricesCache = null
    }

    private var pendingSilentRefresh = false

    /**
     * User-driven searches restart immediately. Background refreshes (new prices written
     * by the sync worker) never cancel a search in progress: they are merged and run once
     * after it, otherwise continuous database writes could keep the list from ever loading.
     */
    private fun search(silent: Boolean = false, fromBackground: Boolean = false) {
        if (fromBackground && searchJob?.isActive == true) {
            pendingSilentRefresh = true
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!silent && _uiState.value.offers.isEmpty()) _uiState.update { it.copy(isLoading = true) }
            val query = _uiState.value.query.trim()
            val preferences = runCatching { preferencesRepository.preferences.first() }.getOrDefault(UserPreferences())
            val filters = _uiState.value.filters.copy(
                userAge = preferences.age,
                maximumDistanceMeters = preferences.radiusKm * 1_000,
                loyaltyCards = preferences.loyaltyCards,
            )
            val selectedIds = runCatching { preferencesRepository.manualCartOfferIds.first() }.getOrDefault(emptySet())
            val showSelectedOnly = _uiState.value.showSelectedOnly
            val requestedStore = _uiState.value.selectedStore

            val result = withContext(Dispatchers.Default) {
                val allRanked = ranked(filters)
                val results = if (query.isBlank()) allRanked else allRanked.filter { offer ->
                    ProductMatcher.matches(offer.productName, query) || offer.storeName.contains(query, ignoreCase = true)
                }
                val displayable = results.filter { !showSelectedOnly || it.id in selectedIds }
                lastUnfiltered = displayable
                offersByStoreKey = displayable.groupBy { StoreDeduplicator.brandKey(it.storeName) }
                // Only brands that actually have products in the current results appear in the filter.
                val stores = offersByStoreKey
                    .filterValues { it.isNotEmpty() }
                    .values.map { offers -> StoreDeduplicator.brandDisplayName(offers.first().storeName) }
                    .distinct()
                    .sortedBy { it.lowercase(Locale.ROOT) }
                val selectedStore = requestedStore?.let { selected ->
                    stores.firstOrNull { StoreDeduplicator.belongsToBrand(it, selected) }
                }
                val visible = displayable.filter { offer ->
                    selectedStore == null || StoreDeduplicator.belongsToBrand(offer.storeName, selectedStore)
                }
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                SearchSnapshot(
                    offers = visible,
                    stores = stores,
                    selectedStore = selectedStore,
                    websites = stores.associateWith { name ->
                        allRanked.firstOrNull { StoreDeduplicator.belongsToBrand(it.storeName, name) }?.storeWebsite
                            ?: storesCache.firstOrNull { StoreDeduplicator.belongsToBrand(it.name, name) }?.website
                    },
                    nearby = allRanked.count { it.distanceMeters <= 2_000 },
                    expiringToday = allRanked.count { it.validUntil == today },
                    ambiguousImages = allRanked.asSequence().filter { !it.productImageUrl.isNullOrBlank() }
                        .groupBy { it.productImageUrl!! }
                        .filterValues { offers -> offers.map { it.productName.lowercase(Locale.ROOT) }.distinct().size > 1 }
                        .keys,
                    localAlternatives = storesCache.asSequence()
                        .filter { store ->
                            allRanked.none { offer -> offer.storeName.equals(store.name, ignoreCase = true) }
                        }
                        .filter { store ->
                            !filters.sustainableOnly ||
                                store.sustainabilityScore >= StoreSustainabilityResult.LEAF_THRESHOLD
                        }
                        .sortedWith(
                            compareByDescending<NearbyStore> { it.sustainabilityScore }
                                .thenBy { if (it.category == "marketplace" || it.category == "farm") 0 else 1 }
                                .thenBy(NearbyStore::distanceMeters),
                        )
                        .take(MAX_LOCAL_ALTERNATIVES)
                        .toList(),
                )
            }
            _uiState.update { state ->
                state.copy(
                    offers = result.offers,
                    filters = filters,
                    availableStores = result.stores,
                    storeWebsites = result.websites,
                    selectedStore = result.selectedStore,
                    nearbyOffers = result.nearby,
                    expiringToday = result.expiringToday,
                    ambiguousImageUrls = result.ambiguousImages,
                    localAlternatives = result.localAlternatives,
                    isLoading = false,
                    orderVersion = if (userChangePending) state.orderVersion + 1 else state.orderVersion,
                )
            }
            userChangePending = false
            refreshCartSummary()
            if (pendingSilentRefresh) {
                pendingSilentRefresh = false
                invalidateCatalog()
                search(silent = true)
            }
        }
    }

    /** Totals, basket plans and price history: computed separately so they never delay the list. */
    private var pendingSummary = false

    /**
     * Never cancels a running computation (continuous sync writes used to cancel it before
     * it could finish, so totals never appeared): a new request is merged and run after it.
     */
    private fun refreshCartSummary() {
        if (summaryJob?.isActive == true) {
            pendingSummary = true
            return
        }
        summaryJob = viewModelScope.launch {
            val preferences = runCatching { preferencesRepository.preferences.first() }.getOrDefault(UserPreferences())
            val cart = runCatching { preferencesRepository.manualCart.first() }.getOrDefault(emptyList())
            val pendingItems = runCatching {
                preferencesRepository.shoppingItems.first().filterNot { it.second }.map { it.first }
            }.getOrDefault(emptyList())
            val query = _uiState.value.query.trim()
            val selectedStore = _uiState.value.selectedStore
            val filters = _uiState.value.filters
            val transport = TransportProfile(
                vehicle = preferences.vehicleType,
                fuel = preferences.fuelType,
                consumptionPer100Km = preferences.consumptionPer100Km,
                pricePerUnit = preferences.fuelPricePerUnit,
            )
            val summary = withContext(Dispatchers.Default) {
                val allRanked = ranked(filters)
                val offersById = allRanked.associateBy(Offer::id)
                val cartOffers = cart.map { entry ->
                    val live = offersById[entry.offerId]
                    CartLine(
                        price = live?.price ?: entry.price.takeUnless(Double::isNaN) ?: 0.0,
                        storeName = live?.storeName ?: entry.storeName,
                        distance = live?.distanceMeters ?: storeDistance(entry.storeName),
                    )
                }
                val productsTotal = cartOffers.sumOf(CartLine::price)
                val travelKm = cartOffers.groupBy(CartLine::storeName).values.sumOf { lines ->
                    (lines.maxOfOrNull(CartLine::distance) ?: 0) * 2.0 / 1_000.0
                }
                val candidates = allRanked.filter {
                    selectedStore == null || StoreDeduplicator.belongsToBrand(it.storeName, selectedStore)
                }
                val matchedPrices = pendingItems.mapNotNull { requested ->
                    candidates.filter { ProductMatcher.matches(it.productName, requested) }.minOfOrNull(Offer::price)
                }
                val catalog = catalogPrices()
                val oneStop = BasketOptimizer.optimize(pendingItems, catalog, maximumStores = 1, transport = transport, goal = BasketGoal.CHEAPEST).firstOrNull()
                val best = BasketOptimizer.optimize(pendingItems, catalog, transport = transport, goal = BasketGoal.CHEAPEST).firstOrNull()
                val eco = BasketOptimizer.optimize(pendingItems, catalog, transport = transport, goal = BasketGoal.ECOLOGICAL).firstOrNull()
                val ecoEstimates = EcoBasketEstimator.estimate(
                    requestedItems = pendingItems,
                    catalog = catalog,
                    stores = storesCache.map { store ->
                        EcoStoreCandidate(
                            id = store.id,
                            name = store.name,
                            distanceMeters = store.distanceMeters,
                            greenScore = store.sustainabilityScore,
                        )
                    },
                    transport = transport,
                )
                val assessments = storesCache.associate { store ->
                    store.id to StoreAssessmentEngine.assess(store, catalog)
                }
                CartSummary(
                    items = cart.size,
                    productsTotal = productsTotal,
                    fuelCost = travelKm * transport.costPerKm(),
                    emissionKg = travelKm * transport.emissionKgPerKm(),
                    shoppingTotal = matchedPrices.sum(),
                    matched = matchedPrices.size,
                    pending = pendingItems.size,
                    oneStop = oneStop?.takeIf { it.missingItems.isEmpty() }?.monetaryTotal,
                    best = best?.takeIf { it.missingItems.isEmpty() }?.monetaryTotal,
                    // CatalogPrice does not carry the verified store green score. In strict
                    // green mode do not present that unconstrained plan as an exact eco total.
                    eco = eco?.takeIf { !filters.sustainableOnly && it.missingItems.isEmpty() }?.monetaryTotal,
                    ecoEstimates = ecoEstimates.associateBy(EcoBasketEstimate::storeId),
                    bestEcoEstimate = ecoEstimates.firstOrNull(),
                    assessments = assessments,
                )
            }
            val history = withContext(Dispatchers.IO) {
                val statuses = runCatching { repository.observeSourceStatuses().first() }.getOrDefault(emptyList())
                val version = runCatching { repository.observeCatalogVersion().first() }.getOrNull()
                val allRanked = rankedCache
                val drops = if (version != lastCatalogVersion) {
                    lastCatalogVersion = version
                    runCatching { priceWatch.recordAndFindDrops(allRanked) }.getOrNull()
                } else {
                    null
                }
                HistorySummary(
                    unavailable = statuses.count { it.state != SourceState.UPDATED },
                    drops = drops?.drops?.size,
                    lows = drops?.historicalLows,
                    targets = runCatching { priceWatch.reachedTargets(allRanked) }.getOrDefault(0),
                    watched = query.isNotBlank() && priceWatch.isWatched(query),
                )
            }
            _uiState.update { state ->
                state.copy(
                    manualCartItems = summary.items,
                    manualCartProductsTotal = summary.productsTotal,
                    manualCartFuelCost = summary.fuelCost,
                    manualCartTotal = summary.productsTotal + summary.fuelCost,
                    manualCartEmissionKg = summary.emissionKg,
                    shoppingTotal = summary.shoppingTotal,
                    matchedShoppingItems = summary.matched,
                    pendingShoppingItems = summary.pending,
                    oneStopTotal = summary.oneStop,
                    bestBasketTotal = summary.best,
                    sustainableTotal = summary.eco,
                    ecoEstimatedPlans = summary.ecoEstimates,
                    bestEcoEstimatedPlan = summary.bestEcoEstimate,
                    storeAssessments = summary.assessments,
                    unavailableSources = history.unavailable,
                    priceDrops = history.drops ?: state.priceDrops,
                    historicalLows = history.lows ?: state.historicalLows,
                    reachedTargets = history.targets,
                    watchedQuery = history.watched,
                )
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (pendingSummary) {
                    pendingSummary = false
                    viewModelScope.launch { refreshCartSummary() }
                }
            }
        }
    }

    private suspend fun ranked(filters: SearchFilters): List<Offer> = cacheMutex.withLock {
        val key = filters
        if (rankedKey == key) return@withLock rankedCache
        val all = runCatching { repository.search("").first() }.getOrDefault(emptyList())
        storesCache = runCatching { repository.storedStores() }.getOrDefault(emptyList())
        rankedCache = OfferRanking.apply(all, filters)
        rankedKey = key
        rankedCache
    }

    private suspend fun catalogPrices(): List<CatalogPrice> = cacheMutex.withLock {
        catalogPricesCache ?: runCatching { repository.catalogPrices() }.getOrDefault(emptyList())
            .also { catalogPricesCache = it }
    }

    private fun storeDistance(storeName: String): Int =
        storesCache.firstOrNull { it.name.equals(storeName, ignoreCase = true) }?.distanceMeters ?: 0

    private data class SearchSnapshot(
        val offers: List<Offer>,
        val stores: List<String>,
        val selectedStore: String?,
        val websites: Map<String, String?>,
        val nearby: Int,
        val expiringToday: Int,
        val ambiguousImages: Set<String>,
        val localAlternatives: List<NearbyStore>,
    )

    private companion object {
        const val MAX_LOCAL_ALTERNATIVES = 12
    }

    private data class CartLine(val price: Double, val storeName: String, val distance: Int)

    private data class CartSummary(
        val items: Int,
        val productsTotal: Double,
        val fuelCost: Double,
        val emissionKg: Double,
        val shoppingTotal: Double,
        val matched: Int,
        val pending: Int,
        val oneStop: Double?,
        val best: Double?,
        val eco: Double?,
        val ecoEstimates: Map<String, EcoBasketEstimate>,
        val bestEcoEstimate: EcoBasketEstimate?,
        val assessments: Map<String, StoreAssessment>,
    )

    private data class HistorySummary(
        val unavailable: Int,
        val drops: Int?,
        val lows: Int?,
        val targets: Int,
        val watched: Boolean,
    )

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) *
            cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * earthRadius * asin(sqrt(a))
    }
}
