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
import it.lagioiaproductions.shopeasily.domain.GeneralProductName
import it.lagioiaproductions.shopeasily.domain.OfferRanking
import it.lagioiaproductions.shopeasily.domain.ProductMatcher
import it.lagioiaproductions.shopeasily.domain.SearchFilters
import it.lagioiaproductions.shopeasily.domain.SortMode
import it.lagioiaproductions.shopeasily.domain.TransportProfile
import it.lagioiaproductions.shopeasily.domain.StoreSustainabilityResult
import it.lagioiaproductions.shopeasily.domain.StoreAssessment
import it.lagioiaproductions.shopeasily.domain.StoreAssessmentEngine
import it.lagioiaproductions.shopeasily.domain.effectiveQualityScore
import it.lagioiaproductions.shopeasily.domain.sustainabilityScore
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
import kotlin.math.roundToInt
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
    val offerGroups: List<StoreOfferGroup> = emptyList(),
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
    val selectedLocalStoreId: String? = null,
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
    val minimumBasketProductsTotal: Double = 0.0,
    val minimumBasketTravelCost: Double = 0.0,
    val minimumBasketEmissionKg: Double = 0.0,
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
) {
    /**
     * The compact Home totals follow the explicitly selected local shop.  The
     * persisted/manual-cart metrics remain the fallback when no shop is active.
     */
    val activeProductsTotal: Double
        get() = minimumBasketMetrics?.productsTotal
            ?: selectedEcoPlan?.productsTotal
            ?: manualCartProductsTotal
    val activeTravelCost: Double
        get() = minimumBasketMetrics?.fuelCost
            ?: selectedEcoPlan?.travelCost
            ?: manualCartFuelCost
    val activeCartTotal: Double
        get() = minimumBasketMetrics?.let { it.productsTotal + it.fuelCost }
            ?: selectedEcoPlan?.total
            ?: manualCartTotal
    val activeEmissionKg: Double
        get() = minimumBasketMetrics?.emissionKg
            ?: selectedEcoPlan?.emissionKg
            ?: manualCartEmissionKg
    val activeItemCount: Int
        get() = selectedEcoPlan?.items?.size ?: manualCartItems

    private val selectedEcoPlan: EcoBasketEstimate?
        get() = selectedLocalStoreId?.let(ecoEstimatedPlans::get)
    private val minimumBasketMetrics: ActiveBasketMetrics?
        get() = if (filters.sortMode == SortMode.PRICE) {
            ActiveBasketMetrics(minimumBasketProductsTotal, minimumBasketTravelCost, minimumBasketEmissionKg)
        } else null
}

private data class ActiveBasketMetrics(val productsTotal: Double, val fuelCost: Double, val emissionKg: Double)

data class StoreOfferGroup(
    val key: String,
    val storeName: String,
    val offers: List<Offer>,
)

/** Process-local snapshot: reopening the Activity never presents an empty Home first. */
private object SearchSessionCache {
    @Volatile var state: SearchUiState? = null
    @Volatile var lastRefreshLocation: Pair<Double, Double>? = null
    @Volatile var lastRefreshAt: Long = 0L
}

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
    private val restoredState = SearchSessionCache.state
    private val _uiState = MutableStateFlow(
        restoredState?.copy(isLoading = false, syncProgress = null, enriching = false, message = null)
            ?: SearchUiState(),
    )
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var summaryJob: Job? = null
    private var manualMetricsJob: Job? = null
    private var shoppingSnapshotJob: Job? = null
    private var instantFilterJob: Job? = null
    private var enrichJob: Job? = null
    private var lastCatalogVersion: Long? = null
    @Volatile private var lastUnfiltered: List<Offer> = emptyList()
    @Volatile private var offersByStoreKey: Map<String, List<Offer>> = emptyMap()
    @Volatile private var latestManualCart: List<CartEntry> = emptyList()
    @Volatile private var latestShoppingItems: List<Pair<String, Boolean>> = emptyList()
    @Volatile private var latestPreferences: UserPreferences = UserPreferences()
    @Volatile private var latestSelectedOffers: List<Offer> = emptyList()

    /** Cached, ranked catalogue: re-read only when the database changes or filters change. */
    private var rankedCache: List<Offer> = emptyList()
    private var rankedKey: Any? = null
    private var catalogOffersCache: List<Offer>? = null
    private val rankingsCache = LinkedHashMap<SearchFilters, List<Offer>>()
    private var catalogPricesCache: List<CatalogPrice>? = null
    private var storesCache: List<NearbyStore> = emptyList()

    private val cacheMutex = Mutex()

    init {
        viewModelScope.launch {
            uiState.collect { state -> SearchSessionCache.state = state }
        }
        viewModelScope.launch {
            val preferences = runCatching { preferencesRepository.preferences.first() }.getOrDefault(UserPreferences())
            latestPreferences = preferences
            _uiState.update { it.copy(filters = it.filters.copy(includeLoyaltyOffers = preferences.includeLoyaltyOffers)) }
            search(silent = restoredState != null)
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
            preferencesRepository.manualCart.catch { }.collect { cart ->
                latestManualCart = cart
                _uiState.update { it.copy(selectedOfferIds = cart.map(CartEntry::offerId).toSet()) }
                refreshManualCartMetrics()
                val shoppingItems = runCatching { preferencesRepository.shoppingItems.first() }.getOrDefault(emptyList())
                publishShoppingListSnapshot(shoppingItems, cart)
                if (_uiState.value.filters.sortMode == SortMode.PRICE) {
                    applyCachedFilters(_uiState.value.filters)
                    search(silent = true)
                } else {
                    refreshCartSummary(restart = true)
                }
            }
        }
        // The shopping list can be edited on another bottom-bar screen while this
        // ViewModel stays alive. Recalculate estimates as soon as it changes.
        viewModelScope.launch {
            preferencesRepository.shoppingItems.catch { }.collect { items ->
                latestShoppingItems = items
                publishShoppingListSnapshot(items, latestManualCart)
                if (_uiState.value.filters.sortMode == SortMode.PRICE) {
                    applyCachedFilters(_uiState.value.filters)
                    search(silent = true)
                } else {
                    refreshCartSummary(restart = true)
                }
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
                    CartEntry(
                        offer.id,
                        GeneralProductName.from(offer.productName, offer.storeName, offer.brand),
                        offer.storeName,
                        offer.price,
                        offer.promotional,
                        offer.distanceMeters,
                    ),
                )
            }.onFailure { error ->
                _uiState.update { it.copy(message = "Selezione non salvata: ${error.localizedMessage ?: "errore"}") }
            }
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
            refreshManualCartMetrics()
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

    fun selectStore(storeName: String?) {
        // Filter/restore instantly from the unfiltered list, then refresh in background.
        val base = lastUnfiltered
        _uiState.update { state ->
            val storeKey = storeName?.let(StoreDeduplicator::brandKey)
            state.copy(
                selectedStore = storeName,
                // Pre-indexed during the background search: filtering is O(1) on the UI thread.
                offers = if (storeKey == null) base else offersByStoreKey[storeKey].orEmpty(),
                offerGroups = groupOffers(
                    if (storeKey == null) base else offersByStoreKey[storeKey].orEmpty(),
                    state.filters,
                ),
                orderVersion = state.orderVersion + 1,
            )
        }
        // Store filtering is entirely local and indexed. Only the basket totals need
        // a background refresh; do not restart catalogue ranking for a simple tap.
        refreshCartSummary()
    }

    fun selectLocalStore(storeId: String) {
        _uiState.update { state ->
            val selectedId = storeId.takeUnless { it == state.selectedLocalStoreId }
            state.copy(
                selectedLocalStoreId = selectedId,
                bestEcoEstimatedPlan = selectedId?.let(state.ecoEstimatedPlans::get)
                    ?: state.ecoEstimatedPlans.values.firstOrNull(),
            )
        }
    }

    /** Clears the active store / "selected only" filter; returns true if something was cleared (Back). */
    fun clearFilters(): Boolean {
        val state = _uiState.value
        if (state.selectedStore == null && !state.showSelectedOnly) return false
        _uiState.update {
            it.copy(
                selectedStore = null,
                showSelectedOnly = false,
                offers = lastUnfiltered,
                offerGroups = groupOffers(lastUnfiltered, it.filters),
            )
        }
        search(silent = true)
        return true
    }

    /** Stores the position and asks the background worker to refresh; never blocks the UI. */
    fun refreshForLocation(latitude: Double, longitude: Double, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val previous = SearchSessionCache.lastRefreshLocation
        val movedMeters = previous?.let { distanceMeters(it.first, it.second, latitude, longitude) }
        if (!force && previous != null && movedMeters != null && movedMeters < 500 &&
            now - SearchSessionCache.lastRefreshAt < 15 * 60_000L
        ) return
        SearchSessionCache.lastRefreshLocation = latitude to longitude
        SearchSessionCache.lastRefreshAt = now
        viewModelScope.launch {
            runCatching { preferencesRepository.setLastLocation(latitude, longitude) }
            // lastLocation is delivered before the fresh GPS fix. If they differ,
            // replace the stale WorkManager request instead of KEEP-ing the wrong area.
            CatalogSyncWorker.requestNow(
                getApplication(),
                latitude,
                longitude,
                force = force || (movedMeters != null && movedMeters >= 500),
            )
        }
    }

    private fun updateFilters(filters: SearchFilters) {
        _uiState.update { it.copy(filters = filters) }
        userChangePending = true
        applyCachedFilters(filters)
        search(silent = true)
    }

    /** Immediate UI projection; the regular search then validates it against Room. */
    private fun applyCachedFilters(filters: SearchFilters) {
        instantFilterJob?.cancel()
        val source = catalogOffersCache ?: rankedCache
        if (source.isEmpty()) return
        val query = _uiState.value.query.trim()
        val selectedStore = _uiState.value.selectedStore
        val showSelectedOnly = _uiState.value.showSelectedOnly
        val selectedIds = latestManualCart.map(CartEntry::offerId).toSet()
        val requested = (
            latestShoppingItems.filterNot { it.second }.map { it.first } +
                latestManualCart.map { GeneralProductName.from(it.name, it.storeName) }
            ).filter(String::isNotBlank).distinctBy { ProductMatcher.key(it) }
        instantFilterJob = viewModelScope.launch(Dispatchers.Default) {
            val ranked = OfferRanking.apply(source, filters)
            val queried = if (query.isBlank() || (filters.sortMode == SortMode.PRICE && requested.isNotEmpty())) {
                ranked
            } else {
                ranked.filter { ProductMatcher.matches(it.productName, query) || it.storeName.contains(query, true) }
            }
            val displayable = queried.filter { !showSelectedOnly || it.id in selectedIds }
            val storesIndex = displayable.groupBy { StoreDeduplicator.brandKey(it.storeName) }
            val filteredByStore = selectedStore?.let { store ->
                storesIndex[StoreDeduplicator.brandKey(store)].orEmpty()
            } ?: displayable
            val assignments = if (filters.sortMode == SortMode.PRICE && requested.isNotEmpty()) {
                cheapestAssignments(requested, filteredByStore, latestSelectedOffers)
            } else emptyList()
            val visible = if (assignments.isNotEmpty() ||
                (filters.sortMode == SortMode.PRICE && requested.isNotEmpty())
            ) assignments.map { it.second }.distinctBy(Offer::id) else filteredByStore
            val metrics = if (filters.sortMode == SortMode.PRICE && requested.isNotEmpty()) {
                calculateOfferBasketMetrics(assignments.map { it.second }, latestPreferences)
            } else ManualCartMetrics(0, 0.0, 0.0, 0.0)
            _uiState.update { state ->
                if (state.filters != filters) state else state.copy(
                    offers = visible,
                    offerGroups = groupOffers(visible, filters),
                    availableStores = storesIndex.values.map { offers ->
                        StoreDeduplicator.brandDisplayName(offers.first().storeName)
                    }.distinct().sortedBy { it.lowercase(Locale.ROOT) },
                    minimumBasketProductsTotal = metrics.productsTotal,
                    minimumBasketTravelCost = metrics.fuelCost,
                    minimumBasketEmissionKg = metrics.emissionKg,
                    orderVersion = state.orderVersion + 1,
                )
            }
        }
    }

    private suspend fun invalidateCatalog() = cacheMutex.withLock {
        rankedKey = null
        catalogOffersCache = null
        rankingsCache.clear()
        catalogPricesCache = null
        storesCache = emptyList()
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
            latestPreferences = preferences
            val filters = _uiState.value.filters.copy(
                userAge = preferences.age,
                maximumDistanceMeters = preferences.radiusKm * 1_000,
                loyaltyCards = preferences.loyaltyCards,
            )
            val cartEntries = runCatching { preferencesRepository.manualCart.first() }.getOrDefault(emptyList())
            val selectedIds = cartEntries.map(CartEntry::offerId).toSet()
            val selectedOffers = runCatching { repository.offersByIds(selectedIds) }.getOrDefault(emptyList())
            latestSelectedOffers = selectedOffers
            val listItems = runCatching { preferencesRepository.shoppingItems.first() }.getOrDefault(emptyList())
            val requestedProducts = (
                listItems.filterNot { it.second }.map { it.first } +
                    cartEntries.map { GeneralProductName.from(it.name, it.storeName) }
                ).filter(String::isNotBlank).distinctBy { ProductMatcher.key(it) }
            val showSelectedOnly = _uiState.value.showSelectedOnly
            val requestedStore = _uiState.value.selectedStore

            val result = withContext(Dispatchers.Default) {
                val allRanked = ranked(filters)
                val minimumMode = filters.sortMode == SortMode.PRICE && requestedProducts.isNotEmpty()
                val results = if (query.isBlank() || minimumMode) allRanked else allRanked.filter { offer ->
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
                val storeVisible = displayable.filter { offer ->
                    selectedStore == null || StoreDeduplicator.belongsToBrand(offer.storeName, selectedStore)
                }
                val minimumAssignments = if (minimumMode) {
                    cheapestAssignments(requestedProducts, storeVisible, selectedOffers)
                } else {
                    emptyList()
                }
                val visible = if (minimumMode) {
                    minimumAssignments.map { it.second }.distinctBy(Offer::id)
                } else {
                    storeVisible
                }
                val minimumMetrics = if (minimumMode) {
                    calculateOfferBasketMetrics(minimumAssignments.map { it.second }, preferences)
                } else {
                    ManualCartMetrics(0, 0.0, 0.0, 0.0)
                }
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                SearchSnapshot(
                    offers = visible,
                    offerGroups = groupOffers(visible, filters),
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
                                .thenBy(NearbyStore::distanceMeters)
                                .thenByDescending { store ->
                                    store.sustainabilityReasons.any { reason ->
                                        reason.contains("equo", true) ||
                                            reason.contains("fair", true) ||
                                            reason.contains("solidale", true)
                                    }
                                }
                                .thenBy { if (it.category == "marketplace" || it.category == "farm") 0 else 1 },
                        )
                        .take(MAX_LOCAL_ALTERNATIVES)
                        .toList(),
                    minimumMetrics = minimumMetrics,
                )
            }
            _uiState.update { state ->
                state.copy(
                    offers = result.offers,
                    offerGroups = result.offerGroups,
                    filters = filters,
                    availableStores = result.stores,
                    storeWebsites = result.websites,
                    selectedStore = result.selectedStore,
                    nearbyOffers = result.nearby,
                    expiringToday = result.expiringToday,
                    ambiguousImageUrls = result.ambiguousImages,
                    localAlternatives = result.localAlternatives,
                    minimumBasketProductsTotal = result.minimumMetrics.productsTotal,
                    minimumBasketTravelCost = result.minimumMetrics.fuelCost,
                    minimumBasketEmissionKg = result.minimumMetrics.emissionKg,
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
     * User edits must be reflected before the full optimiser finishes. This path only
     * reads the in-memory ranked index, so deleting the final item immediately resets
     * the Home total instead of waiting for database/history/route calculations.
     */
    private fun publishShoppingListSnapshot(
        items: List<Pair<String, Boolean>>,
        cart: List<CartEntry> = latestManualCart,
    ) {
        val listRequested = items.asSequence()
            .filterNot { it.second }
            .map { it.first.trim() }
            .filter(String::isNotBlank)
            .toList()
        val requested = (listRequested + cart.mapNotNull { entry ->
            entry.name.trim().takeIf(String::isNotBlank)
        })
            .distinctBy { it.lowercase(Locale.ROOT) }
        _uiState.update { state ->
            state.copy(
                pendingShoppingItems = requested.size,
                matchedShoppingItems = if (requested.isEmpty()) 0 else state.matchedShoppingItems.coerceAtMost(requested.size),
                shoppingTotal = if (requested.isEmpty()) 0.0 else state.shoppingTotal,
            )
        }
        shoppingSnapshotJob?.cancel()
        shoppingSnapshotJob = viewModelScope.launch(Dispatchers.Default) {
            val state = _uiState.value
            val candidates = rankedCache.filter { offer ->
                state.selectedStore == null || StoreDeduplicator.belongsToBrand(offer.storeName, state.selectedStore)
            }
            val savedPrices = cart.filter { it.price.isFinite() && it.price > 0.0 }
                .associateBy({ it.name.trim().lowercase(Locale.ROOT) }, CartEntry::price)
            val prices = requested.mapNotNull { product ->
                candidates.asSequence()
                    .filter { ProductMatcher.matches(it.productName, product) }
                    .minOfOrNull(Offer::price)
                    ?: savedPrices[product.lowercase(Locale.ROOT)]
            }
            _uiState.update {
                it.copy(
                    shoppingTotal = prices.sum(),
                    matchedShoppingItems = prices.size,
                    pendingShoppingItems = requested.size,
                )
            }
            // Keep a selected local market in sync as well. This estimator scans the
            // already cached catalogue once per item and is intentionally independent
            // from the slower multi-store basket optimiser.
            val preferences = runCatching { preferencesRepository.preferences.first() }
                .getOrDefault(UserPreferences())
            val estimates = EcoBasketEstimator.estimate(
                requestedItems = requested,
                catalog = catalogPricesCache.orEmpty(),
                stores = storesCache.map(::ecoStoreCandidate),
                transport = preferences.transportProfile(),
            )
            val estimatesByStore = estimates.associateBy(EcoBasketEstimate::storeId)
            _uiState.update { current ->
                current.copy(
                    ecoEstimatedPlans = estimatesByStore,
                    bestEcoEstimatedPlan = current.selectedLocalStoreId?.let(estimatesByStore::get)
                        ?: estimates.firstOrNull(),
                )
            }
        }
    }

    /** Updates the three compact Home metrics without running basket optimisation. */
    private fun refreshManualCartMetrics() {
        manualMetricsJob?.cancel()
        manualMetricsJob = viewModelScope.launch {
            val preferences = runCatching { preferencesRepository.preferences.first() }.getOrDefault(UserPreferences())
            val cart = runCatching { preferencesRepository.manualCart.first() }.getOrDefault(emptyList())
            val metrics = withContext(Dispatchers.Default) { calculateManualCartMetrics(cart, preferences) }
            _uiState.update { state ->
                state.copy(
                    manualCartItems = metrics.items,
                    manualCartProductsTotal = metrics.productsTotal,
                    manualCartFuelCost = metrics.fuelCost,
                    manualCartTotal = metrics.productsTotal + metrics.fuelCost,
                    manualCartEmissionKg = metrics.emissionKg,
                )
            }
        }
    }

    /**
     * Never cancels a running computation (continuous sync writes used to cancel it before
     * it could finish, so totals never appeared): a new request is merged and run after it.
     */
    private fun refreshCartSummary(restart: Boolean = false) {
        if (restart) {
            pendingSummary = false
            summaryJob?.cancel()
        }
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
            val requestedItems = (pendingItems + cart.mapNotNull { entry ->
                entry.name.trim().takeIf(String::isNotBlank)
            }).distinctBy { it.lowercase(Locale.ROOT) }
            val query = _uiState.value.query.trim()
            val selectedStore = _uiState.value.selectedStore
            val filters = _uiState.value.filters
            val transport = preferences.transportProfile()
            val manualMetrics = withContext(Dispatchers.Default) { calculateManualCartMetrics(cart, preferences) }
            // These values are the ones visible in the compact header: publish them
            // before the expensive multi-store optimisers and history calculations.
            _uiState.update { state ->
                state.copy(
                    manualCartItems = manualMetrics.items,
                    manualCartProductsTotal = manualMetrics.productsTotal,
                    manualCartFuelCost = manualMetrics.fuelCost,
                    manualCartTotal = manualMetrics.productsTotal + manualMetrics.fuelCost,
                    manualCartEmissionKg = manualMetrics.emissionKg,
                )
            }
            val catalog = withContext(Dispatchers.Default) { catalogPrices() }
            val requestedForEcoEstimate = requestedItems
            val ecoEstimates = withContext(Dispatchers.Default) {
                EcoBasketEstimator.estimate(
                    requestedItems = requestedForEcoEstimate,
                    catalog = catalog,
                    stores = storesCache.map(::ecoStoreCandidate),
                    transport = transport,
                )
            }
            val ecoEstimatesByStore = ecoEstimates.associateBy(EcoBasketEstimate::storeId)
            // Publish market estimates before the slower exact basket optimisation.
            _uiState.update { state ->
                state.copy(
                    ecoEstimatedPlans = ecoEstimatesByStore,
                    bestEcoEstimatedPlan = state.selectedLocalStoreId?.let(ecoEstimatesByStore::get)
                        ?: ecoEstimates.firstOrNull(),
                )
            }
            val summary = withContext(Dispatchers.Default) {
                val allRanked = ranked(filters)
                val candidates = allRanked.filter {
                    selectedStore == null || StoreDeduplicator.belongsToBrand(it.storeName, selectedStore)
                }
                val savedPrices = cart.filter { it.price.isFinite() && it.price > 0.0 }
                    .associateBy({ it.name.trim().lowercase(Locale.ROOT) }, CartEntry::price)
                val matchedPrices = requestedItems.mapNotNull { requested ->
                    candidates.asSequence()
                        .filter { ProductMatcher.matches(it.productName, requested) }
                        .minOfOrNull(Offer::price)
                        ?: savedPrices[requested.trim().lowercase(Locale.ROOT)]
                }
                // Detailed price/quality assessment is quadratic in catalogue size;
                // calculate it only for the local cards actually visible to the user.
                val assessments = _uiState.value.localAlternatives.associate { store ->
                    store.id to StoreAssessmentEngine.assess(store, catalog)
                }
                val oneStop = BasketOptimizer.optimize(requestedItems, catalog, maximumStores = 1, transport = transport, goal = BasketGoal.CHEAPEST).firstOrNull()
                val best = BasketOptimizer.optimize(requestedItems, catalog, transport = transport, goal = BasketGoal.CHEAPEST).firstOrNull()
                val eco = BasketOptimizer.optimize(requestedItems, catalog, transport = transport, goal = BasketGoal.ECOLOGICAL).firstOrNull()
                CartSummary(
                    items = manualMetrics.items,
                    productsTotal = manualMetrics.productsTotal,
                    fuelCost = manualMetrics.fuelCost,
                    emissionKg = manualMetrics.emissionKg,
                    shoppingTotal = matchedPrices.sum(),
                    matched = matchedPrices.size,
                    pending = requestedItems.size,
                    oneStop = oneStop?.takeIf { it.missingItems.isEmpty() }?.monetaryTotal,
                    best = best?.takeIf { it.missingItems.isEmpty() }?.monetaryTotal,
                    // CatalogPrice does not carry the verified store green score. In strict
                    // green mode do not present that unconstrained plan as an exact eco total.
                    eco = eco?.takeIf { !filters.sustainableOnly && it.missingItems.isEmpty() }?.monetaryTotal,
                    ecoEstimates = ecoEstimatesByStore,
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
                    bestEcoEstimatedPlan = state.selectedLocalStoreId?.let(summary.ecoEstimates::get)
                        ?: summary.bestEcoEstimate,
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
        rankingsCache[filters]?.let { cached ->
            rankedCache = cached
            rankedKey = filters
            return@withLock cached
        }
        val all = catalogOffersCache ?: runCatching { repository.search("").first() }.getOrDefault(emptyList())
            .also { catalogOffersCache = it }
        if (storesCache.isEmpty()) {
            storesCache = runCatching { repository.storedStores() }.getOrDefault(emptyList())
        }
        rankedCache = OfferRanking.apply(all, filters)
        rankedKey = filters
        rankingsCache[filters] = rankedCache
        while (rankingsCache.size > MAX_RANKING_VARIANTS) {
            rankingsCache.remove(rankingsCache.keys.first())
        }
        rankedCache
    }

    private suspend fun catalogPrices(): List<CatalogPrice> = cacheMutex.withLock {
        catalogPricesCache ?: runCatching { repository.catalogPrices() }.getOrDefault(emptyList())
            .also { catalogPricesCache = it }
    }

    private fun UserPreferences.transportProfile() = TransportProfile(
        vehicle = vehicleType,
        fuel = fuelType,
        consumptionPer100Km = consumptionPer100Km,
        pricePerUnit = fuelPricePerUnit,
    )

    private fun ecoStoreCandidate(store: NearbyStore) = EcoStoreCandidate(
        id = store.id,
        name = store.name,
        distanceMeters = store.distanceMeters,
        greenScore = store.sustainabilityScore,
        observedQualityScore = store.reviewRating?.let { rating ->
            (rating / 5.0 * 100).roundToInt().coerceIn(0, 100)
        },
        fairTrade = store.sustainabilityReasons.any { reason ->
            reason.contains("equo", ignoreCase = true) ||
                reason.contains("fair", ignoreCase = true) ||
                reason.contains("solidale", ignoreCase = true)
        },
    )

    private fun calculateManualCartMetrics(cart: List<CartEntry>, preferences: UserPreferences): ManualCartMetrics {
        val offersById = (catalogOffersCache ?: rankedCache).associateBy(Offer::id)
        val lines = cart.map { entry ->
            val live = offersById[entry.offerId]
            CartLine(
                price = live?.price ?: entry.price.takeUnless(Double::isNaN) ?: 0.0,
                storeName = live?.storeName ?: entry.storeName,
                distance = live?.distanceMeters
                    ?: entry.distanceMeters.takeIf { it > 0 }
                    ?: storeDistance(entry.storeName),
            )
        }
        val transport = preferences.transportProfile()
        val travelKm = lines.groupBy { StoreDeduplicator.brandKey(it.storeName) }.values.sumOf { storeLines ->
            (storeLines.maxOfOrNull(CartLine::distance) ?: 0) * 2.0 / 1_000.0
        }
        return ManualCartMetrics(
            items = cart.size,
            productsTotal = lines.sumOf(CartLine::price),
            fuelCost = travelKm * transport.costPerKm(),
            emissionKg = travelKm * transport.emissionKgPerKm(),
        )
    }

    private fun storeDistance(storeName: String): Int = storesCache.asSequence()
        .filter { StoreDeduplicator.belongsToBrand(it.name, storeName) }
        .minOfOrNull(NearbyStore::distanceMeters) ?: 0

    private fun cheapestAssignments(
        requested: List<String>,
        offers: List<Offer>,
        fallbackOffers: List<Offer> = emptyList(),
    ): List<Pair<String, Offer>> =
        requested.mapNotNull { product ->
            (offers.asSequence()
                .filter { ProductMatcher.matches(it.productName, product) }
                .minWithOrNull(compareBy<Offer>(Offer::price).thenBy(Offer::distanceMeters))
                ?: fallbackOffers.asSequence()
                    .filter { ProductMatcher.matches(it.productName, product) }
                    .minWithOrNull(compareBy<Offer>(Offer::price).thenBy(Offer::distanceMeters)))
                ?.let { product to it }
        }

    private fun calculateOfferBasketMetrics(offers: List<Offer>, preferences: UserPreferences): ManualCartMetrics {
        val transport = preferences.transportProfile()
        val travelKm = offers.groupBy { StoreDeduplicator.brandKey(it.storeName) }.values.sumOf { storeOffers ->
            (storeOffers.maxOfOrNull(Offer::distanceMeters) ?: 0) * 2.0 / 1_000.0
        }
        return ManualCartMetrics(
            items = offers.size,
            productsTotal = offers.sumOf(Offer::price),
            fuelCost = travelKm * transport.costPerKm(),
            emissionKg = travelKm * transport.emissionKgPerKm(),
        )
    }

    private fun groupOffers(offers: List<Offer>, filters: SearchFilters): List<StoreOfferGroup> {
        val groups = offers.groupBy { StoreDeduplicator.brandKey(it.storeName) }
            .map { (key, entries) ->
                StoreOfferGroup(key, StoreDeduplicator.brandDisplayName(entries.first().storeName), entries)
            }
        val comparator = when {
            filters.sustainableOnly -> compareByDescending<StoreOfferGroup> { group ->
                group.offers.maxOfOrNull(Offer::sustainabilityScore) ?: 0
            }.thenBy { group -> group.offers.minOfOrNull(Offer::distanceMeters) ?: Int.MAX_VALUE }
                .thenByDescending { group ->
                    group.offers.any { offer ->
                        offer.sustainabilityLabels.any { label ->
                            label.contains("equo", true) || label.contains("fair", true) || label.contains("solidale", true)
                        }
                    }
                }
                .thenBy { group -> group.offers.minOfOrNull { it.unitPrice ?: it.price } ?: Double.MAX_VALUE }
            filters.sortMode == SortMode.PRICE -> compareBy { group: StoreOfferGroup ->
                group.offers.minOfOrNull { it.unitPrice ?: it.price } ?: Double.MAX_VALUE
            }
            filters.sortMode == SortMode.DISTANCE -> compareBy { group: StoreOfferGroup ->
                group.offers.minOfOrNull(Offer::distanceMeters) ?: Int.MAX_VALUE
            }
            filters.sortMode == SortMode.QUALITY -> compareByDescending { group: StoreOfferGroup ->
                group.offers.maxOfOrNull(Offer::effectiveQualityScore) ?: 0f
            }
            else -> compareBy { group: StoreOfferGroup -> offers.indexOf(group.offers.first()) }
        }
        return groups.sortedWith(comparator)
    }

    private data class SearchSnapshot(
        val offers: List<Offer>,
        val offerGroups: List<StoreOfferGroup>,
        val stores: List<String>,
        val selectedStore: String?,
        val websites: Map<String, String?>,
        val nearby: Int,
        val expiringToday: Int,
        val ambiguousImages: Set<String>,
        val localAlternatives: List<NearbyStore>,
        val minimumMetrics: ManualCartMetrics,
    )

    private companion object {
        const val MAX_LOCAL_ALTERNATIVES = 12
        const val MAX_RANKING_VARIANTS = 8
    }

    private data class CartLine(val price: Double, val storeName: String, val distance: Int)

    private data class ManualCartMetrics(
        val items: Int,
        val productsTotal: Double,
        val fuelCost: Double,
        val emissionKg: Double,
    )

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
