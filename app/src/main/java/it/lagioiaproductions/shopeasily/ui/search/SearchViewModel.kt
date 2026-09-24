package it.lagioiaproductions.shopeasily.ui.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.repository.FakeOffersRepository
import it.lagioiaproductions.shopeasily.data.repository.OffersRepository
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.domain.OfferRanking
import it.lagioiaproductions.shopeasily.domain.SearchFilters
import it.lagioiaproductions.shopeasily.domain.SortMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val offers: List<Offer> = emptyList(),
    val isLoading: Boolean = false,
    val filters: SearchFilters = SearchFilters(),
)

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: OffersRepository = FakeOffersRepository()
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
            repository.search(query).collectLatest { results ->
                _uiState.value = _uiState.value.copy(
                    offers = OfferRanking.apply(results, filters),
                    filters = filters,
                    isLoading = false,
                )
            }
        }
    }
}
