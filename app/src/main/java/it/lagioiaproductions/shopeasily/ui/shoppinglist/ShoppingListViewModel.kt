package it.lagioiaproductions.shopeasily.ui.shoppinglist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.repository.FakeCatalogRepository
import it.lagioiaproductions.shopeasily.data.repository.OnDeviceCatalogRepository
import it.lagioiaproductions.shopeasily.data.repository.RoutePoint
import it.lagioiaproductions.shopeasily.data.repository.RoutingRepository
import it.lagioiaproductions.shopeasily.domain.BasketOptimizer
import it.lagioiaproductions.shopeasily.domain.BasketPlan
import it.lagioiaproductions.shopeasily.domain.TransportProfile
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

data class ShoppingListItem(val name: String, val checked: Boolean)

data class ShoppingListUiState(
    val items: List<ShoppingListItem> = emptyList(),
    val plans: List<BasketPlan> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
)

class ShoppingListViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = UserPreferencesRepository(application)
    private val catalog = FakeCatalogRepository()
    private val liveCatalog = OnDeviceCatalogRepository(application)
    private val routing = RoutingRepository()
    private val plans = MutableStateFlow<List<BasketPlan>>(emptyList())

    val uiState: StateFlow<ShoppingListUiState> = combine(
        preferences.shoppingItems,
        preferences.preferences,
        plans,
    ) { items, userPreferences, currentPlans ->
        ShoppingListUiState(
            items = items.map { ShoppingListItem(it.first, it.second) },
            plans = currentPlans,
            preferences = userPreferences,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingListUiState())

    fun addItem(name: String) = viewModelScope.launch { preferences.addShoppingItem(name.trim()) }
    fun removeItem(name: String) = viewModelScope.launch { preferences.removeShoppingItem(name) }
    fun setChecked(name: String, checked: Boolean) = viewModelScope.launch {
        preferences.setShoppingItemChecked(name, checked)
    }

    fun optimize() = viewModelScope.launch(Dispatchers.IO) {
        val catalogPrices = liveCatalog.catalogPrices().ifEmpty { catalog.catalog }
        val transport = TransportProfile(
            vehicle = uiState.value.preferences.vehicleType,
            fuel = uiState.value.preferences.fuelType,
            consumptionPer100Km = uiState.value.preferences.consumptionPer100Km,
            pricePerUnit = uiState.value.preferences.fuelPricePerUnit,
        )
        val basePlans = BasketOptimizer.optimize(
            requestedItems = uiState.value.items.map(ShoppingListItem::name),
            catalog = catalogPrices,
            transport = transport,
        )
        plans.value = basePlans.map { plan ->
            val points = plan.stores.mapNotNull { store ->
                val lat = store.latitude ?: return@mapNotNull null
                val lon = store.longitude ?: return@mapNotNull null
                RoutePoint(lat, lon)
            }
            val route = routing.route(points, roundTrip = true)
            if (route == null || points.size < 2) plan else {
                val km = route.distanceMeters / 1_000.0
                plan.copy(
                    estimatedTravelCost = km * transport.costPerKm(),
                    estimatedEmissionKgCo2 = km * transport.emissionKgPerKm() +
                        plan.stores.sumOf { it.deliveryEmissionKgCo2 },
                )
            }
        }
    }
}
