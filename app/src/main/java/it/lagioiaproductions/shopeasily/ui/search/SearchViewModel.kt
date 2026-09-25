package it.lagioiaproductions.shopeasily.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.repository.FakeOffersRepository
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.repository.OnDeviceCatalogRepository
import it.lagioiaproductions.shopeasily.data.repository.PriceWatchRepository
import it.lagioiaproductions.shopeasily.domain.BasketGoal
import it.lagioiaproductions.shopeasily.domain.BasketOptimizer
import it.lagioiaproductions.shopeasily.domain.TransportProfile
import it.lagioiaproductions.shopeasily.data.local.SourceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import it.lagioiaproductions.shopeasily.domain.OfferRanking
import it.lagioiaproductions.shopeasily.domain.SearchFilters
import it.lagioiaproductions.shopeasily.domain.SortMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class SearchUiState(
    val query: String = "",
    val offers: List<Offer> = emptyList(),
    val isLoading: Boolean = false,
    val filters: SearchFilters = SearchFilters(),
    val availableStores: List<String> = emptyList(),
    val selectedStore: String? = null,
    val shoppingTotal: Double = 0.0,
    val matchedShoppingItems: Int = 0,
    val pendingShoppingItems: Int = 0,
    val oneStopTotal: Double? = null,
    val bestBasketTotal: Double? = null,
    val sustainableTotal: Double? = null,
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
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OnDeviceCatalogRepository(application)
    private val preferencesRepository = UserPreferencesRepository(application)
    private val priceWatch = PriceWatchRepository(application)
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()
    private var searchJob: Job? = null
    private var locationRefreshJob: Job? = null
    private var lastRefreshLocation: Pair<Double, Double>? = null
    private var lastRefreshAt: Long = 0L

    init {
        search("")
    }

    fun updateQuery(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
    }

    fun submitSearch() {
        search(_uiState.value.query)
    }

    fun toggleCurrentPriceTarget() {
        val query = _uiState.value.query.trim()
        val price = _uiState.value.offers.minOfOrNull(Offer::price) ?: return
        priceWatch.toggleTarget(query, price)
        _uiState.value = _uiState.value.copy(watchedQuery = priceWatch.isWatched(query))
    }

    fun toggleOfferSelection(offer: Offer) {
        viewModelScope.launch {
            preferencesRepository.toggleManualCartOffer(offer.id)
            search(_uiState.value.query)
        }
    }

    fun toggleSelectedOnly() {
        _uiState.value = _uiState.value.copy(showSelectedOnly = !_uiState.value.showSelectedOnly)
        search(_uiState.value.query)
    }

    fun clearManualCart() {
        viewModelScope.launch {
            preferencesRepository.clearManualCart()
            _uiState.value = _uiState.value.copy(showSelectedOnly = false)
            search(_uiState.value.query)
        }
    }

    fun selectSortMode(sortMode: SortMode) {
        updateFilters(_uiState.value.filters.copy(sortMode = sortMode))
    }

    fun setSustainableOnly(enabled: Boolean) {
        updateFilters(_uiState.value.filters.copy(sustainableOnly = enabled))
    }

    fun setIncludeLoyaltyOffers(enabled: Boolean) {
        updateFilters(_uiState.value.filters.copy(includeLoyaltyOffers = enabled))
    }

    fun selectStore(storeName: String?) {
        _uiState.value = _uiState.value.copy(selectedStore = storeName)
        search(_uiState.value.query)
    }

    fun refreshForLocation(latitude: Double, longitude: Double) {
        val now = System.currentTimeMillis()
        val previous = lastRefreshLocation
        val movedMeters = previous?.let { distanceMeters(it.first, it.second, latitude, longitude) }
        if (previous != null && movedMeters != null && movedMeters < 500 && now - lastRefreshAt < 15 * 60_000L) return
        if (locationRefreshJob?.isActive == true) return
        lastRefreshLocation = latitude to longitude
        lastRefreshAt = now
        locationRefreshJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.offers.isEmpty())
            val radius = preferencesRepository.preferences.first().radiusKm
            runCatching {
                repository.synchronize(latitude, longitude, radius) { _, _ -> }
            }
            search(_uiState.value.query)
        }
    }

    private fun updateFilters(filters: SearchFilters) {
        _uiState.value = _uiState.value.copy(filters = filters)
        search(_uiState.value.query)
    }

    private fun search(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = _uiState.value.offers.isEmpty())
            val preferences = preferencesRepository.preferences.first()
            val filters = _uiState.value.filters.copy(
                userAge = preferences.age,
                maximumDistanceMeters = preferences.radiusKm * 1_000,
                includeLoyaltyOffers = preferences.includeLoyaltyOffers,
                loyaltyCards = preferences.loyaltyCards,
            )
            val allRanked = OfferRanking.apply(repository.search("").first(), filters)
            val discoveredStores = repository.observeSourceStatuses().first().map { it.storeName }
            val stores = (allRanked.map(Offer::storeName) + discoveredStores).distinct().sorted()
            val selectedStore = _uiState.value.selectedStore?.takeIf(stores::contains)
            val results = if (query.isBlank()) allRanked else OfferRanking.apply(repository.search(query).first(), filters)
            val selectedIds = preferencesRepository.manualCartOfferIds.first()
            val visibleOffers = results.filter { offer ->
                (selectedStore == null || offer.storeName == selectedStore) &&
                    (!_uiState.value.showSelectedOnly || offer.id in selectedIds)
            }
            val pendingItems = preferencesRepository.shoppingItems.first().filterNot { it.second }.map { it.first }
            val totalCandidates = allRanked.filter { selectedStore == null || it.storeName == selectedStore }
            val matchedPrices = pendingItems.mapNotNull { requested ->
                totalCandidates.filter { offer ->
                    offer.productName.contains(requested, ignoreCase = true) ||
                        requested.contains(offer.productName.substringBefore(' '), ignoreCase = true)
                }.minOfOrNull(Offer::price)
            }
            val catalog = repository.catalogPrices()
            val transport = TransportProfile(
                vehicle = preferences.vehicleType,
                fuel = preferences.fuelType,
                consumptionPer100Km = preferences.consumptionPer100Km,
                pricePerUnit = preferences.fuelPricePerUnit,
            )
            val selectedOffers = allRanked.filter { it.id in selectedIds }
            val productsTotal = selectedOffers.sumOf(Offer::price)
            val travelKm = selectedOffers.groupBy(Offer::storeName).values.sumOf { storeOffers ->
                (storeOffers.maxOfOrNull(Offer::distanceMeters) ?: 0) * 2.0 / 1_000.0
            }
            val fuelCost = travelKm * transport.costPerKm()
            val oneStop = BasketOptimizer.optimize(pendingItems, catalog, maximumStores = 1, transport = transport, goal = BasketGoal.CHEAPEST).firstOrNull()
            val bestBasket = BasketOptimizer.optimize(pendingItems, catalog, transport = transport, goal = BasketGoal.CHEAPEST).firstOrNull()
            val sustainable = BasketOptimizer.optimize(pendingItems, catalog, transport = transport, goal = BasketGoal.ECOLOGICAL).firstOrNull()
            val statuses = repository.observeSourceStatuses().first()
            val drops = priceWatch.recordAndFindDrops(allRanked)
            _uiState.value = _uiState.value.copy(
                offers = visibleOffers,
                filters = filters,
                availableStores = stores,
                selectedStore = selectedStore,
                shoppingTotal = matchedPrices.sum(),
                matchedShoppingItems = matchedPrices.size,
                pendingShoppingItems = pendingItems.size,
                oneStopTotal = oneStop?.monetaryTotal,
                bestBasketTotal = bestBasket?.monetaryTotal,
                sustainableTotal = sustainable?.monetaryTotal,
                nearbyOffers = allRanked.count { it.distanceMeters <= 2_000 },
                expiringToday = allRanked.count { it.validUntil == SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) },
                priceDrops = drops.drops.size,
                historicalLows = drops.historicalLows,
                reachedTargets = priceWatch.reachedTargets(allRanked),
                unavailableSources = statuses.count { it.state != SourceState.UPDATED },
                watchedQuery = query.isNotBlank() && priceWatch.isWatched(query),
                selectedOfferIds = selectedIds,
                manualCartItems = selectedOffers.size,
                manualCartProductsTotal = productsTotal,
                manualCartFuelCost = fuelCost,
                manualCartTotal = productsTotal + fuelCost,
                manualCartEmissionKg = travelKm * transport.emissionKgPerKm(),
                isLoading = false,
            )
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) *
            cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * earthRadius * asin(sqrt(a))
    }
}
