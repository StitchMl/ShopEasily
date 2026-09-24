package it.lagioiaproductions.shopeasily.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.repository.FakeOffersRepository
import it.lagioiaproductions.shopeasily.data.repository.OffersRepository
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.repository.OnDeviceCatalogRepository
import it.lagioiaproductions.shopeasily.domain.OfferRanking
import it.lagioiaproductions.shopeasily.domain.SearchFilters
import it.lagioiaproductions.shopeasily.domain.SortMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: OffersRepository = OnDeviceCatalogRepository(application)
    private val preferencesRepository = UserPreferencesRepository(application)
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    init {
        search("")
    }

    fun updateQuery(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
    }

    fun submitSearch() {
        search(_uiState.value.query)
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

    private fun updateFilters(filters: SearchFilters) {
        _uiState.value = _uiState.value.copy(filters = filters)
        search(_uiState.value.query)
    }

    private fun search(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val preferences = preferencesRepository.preferences.first()
            val filters = _uiState.value.filters.copy(
                userAge = preferences.age,
                maximumDistanceMeters = preferences.radiusKm * 1_000,
                includeLoyaltyOffers = preferences.includeLoyaltyOffers,
                loyaltyCards = preferences.loyaltyCards,
            )
            val allRanked = OfferRanking.apply(repository.search("").first(), filters)
            val stores = allRanked.map(Offer::storeName).distinct().sorted()
            val selectedStore = _uiState.value.selectedStore?.takeIf(stores::contains)
            val results = if (query.isBlank()) allRanked else OfferRanking.apply(repository.search(query).first(), filters)
            val visibleOffers = results.filter { selectedStore == null || it.storeName == selectedStore }
            val pendingItems = preferencesRepository.shoppingItems.first().filterNot { it.second }.map { it.first }
            val totalCandidates = allRanked.filter { selectedStore == null || it.storeName == selectedStore }
            val matchedPrices = pendingItems.mapNotNull { requested ->
                totalCandidates.filter { offer ->
                    offer.productName.contains(requested, ignoreCase = true) ||
                        requested.contains(offer.productName.substringBefore(' '), ignoreCase = true)
                }.minOfOrNull(Offer::price)
            }
            _uiState.value = _uiState.value.copy(
                offers = visibleOffers,
                filters = filters,
                availableStores = stores,
                selectedStore = selectedStore,
                shoppingTotal = matchedPrices.sum(),
                matchedShoppingItems = matchedPrices.size,
                pendingShoppingItems = pendingItems.size,
                isLoading = false,
            )
        }
    }
}
