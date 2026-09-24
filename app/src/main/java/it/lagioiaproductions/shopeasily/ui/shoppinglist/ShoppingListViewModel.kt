package it.lagioiaproductions.shopeasily.ui.shoppinglist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.repository.FakeCatalogRepository
import it.lagioiaproductions.shopeasily.domain.BasketOptimizer
import it.lagioiaproductions.shopeasily.domain.BasketPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShoppingListItem(val name: String, val checked: Boolean)

data class ShoppingListUiState(
    val items: List<ShoppingListItem> = emptyList(),
    val plans: List<BasketPlan> = emptyList(),
)

class ShoppingListViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = UserPreferencesRepository(application)
    private val catalog = FakeCatalogRepository()
    private val plans = MutableStateFlow<List<BasketPlan>>(emptyList())

    val uiState: StateFlow<ShoppingListUiState> = combine(
        preferences.shoppingItems,
        plans,
    ) { items, currentPlans ->
        ShoppingListUiState(
            items = items.map { ShoppingListItem(it.first, it.second) },
            plans = currentPlans,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingListUiState())

    fun addItem(name: String) = viewModelScope.launch { preferences.addShoppingItem(name.trim()) }
    fun removeItem(name: String) = viewModelScope.launch { preferences.removeShoppingItem(name) }
    fun setChecked(name: String, checked: Boolean) = viewModelScope.launch {
        preferences.setShoppingItemChecked(name, checked)
    }

    fun optimize() {
        plans.value = BasketOptimizer.optimize(
            requestedItems = uiState.value.items.map(ShoppingListItem::name),
            catalog = catalog.catalog,
        )
    }
}
