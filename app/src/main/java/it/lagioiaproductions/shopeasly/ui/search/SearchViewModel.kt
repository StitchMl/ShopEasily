package it.lagioiaproductions.shopeasly.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import it.lagioiaproductions.shopeasly.data.model.Offer
import it.lagioiaproductions.shopeasly.data.repository.FakeOffersRepository
import it.lagioiaproductions.shopeasly.data.repository.OffersRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val offers: List<Offer> = emptyList(),
    val isLoading: Boolean = false,
)

class SearchViewModel(
    private val repository: OffersRepository = FakeOffersRepository(),
) : ViewModel() {
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

    private fun search(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            repository.search(query).collectLatest { results ->
                _uiState.value = _uiState.value.copy(
                    offers = results,
                    isLoading = false,
                )
            }
        }
    }
}
