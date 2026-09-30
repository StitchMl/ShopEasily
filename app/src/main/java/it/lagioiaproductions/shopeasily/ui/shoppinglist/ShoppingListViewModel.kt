package it.lagioiaproductions.shopeasily.ui.shoppinglist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.lagioiaproductions.shopeasily.data.preferences.CartEntry
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferences
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.repository.FakeCatalogRepository
import it.lagioiaproductions.shopeasily.data.repository.OnDeviceCatalogRepository
import it.lagioiaproductions.shopeasily.data.repository.RoutePoint
import it.lagioiaproductions.shopeasily.data.repository.RoutingRepository
import it.lagioiaproductions.shopeasily.domain.BasketOptimizer
import it.lagioiaproductions.shopeasily.domain.GeneralProductName
import it.lagioiaproductions.shopeasily.domain.BasketPlan
import it.lagioiaproductions.shopeasily.domain.TransportProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShoppingListItem(val name: String, val checked: Boolean)

data class SelectedShoppingItem(
    val offerId: Long,
    val name: String,
    val storeName: String,
    val price: Double,
    val promotional: Boolean,
)

data class OptimizationState(
    val running: Boolean = false,
    val plans: List<BasketPlan> = emptyList(),
    val message: String? = null,
)

data class ShoppingListUiState(
    val items: List<ShoppingListItem> = emptyList(),
    val plans: List<BasketPlan> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val selectedItems: List<SelectedShoppingItem> = emptyList(),
    val optimizing: Boolean = false,
    val message: String? = null,
)

/**
 * The list used to combine the DataStore with a full scan of the on-device
 * catalogue (thousands of rows, re-run on every preference change): new items
 * appeared only seconds later, or never if the scan failed. Now every source
 * is a light DataStore flow and selected products are stored as snapshots.
 */
class ShoppingListViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = UserPreferencesRepository(application)
    private val catalog = FakeCatalogRepository()
    private val liveCatalog = OnDeviceCatalogRepository(application)
    private val routing = RoutingRepository()
    private val optimization = MutableStateFlow(OptimizationState())
    private var optimizeJob: Job? = null

    val uiState: StateFlow<ShoppingListUiState> = combine(
        preferences.shoppingItems,
        preferences.preferences,
        preferences.manualCart,
        optimization,
    ) { items, userPreferences, cart, optimizationState ->
        ShoppingListUiState(
            items = items.map { ShoppingListItem(it.first, it.second) },
            plans = optimizationState.plans,
            preferences = userPreferences,
            selectedItems = cart.map { entry ->
                SelectedShoppingItem(
                    offerId = entry.offerId,
                    name = GeneralProductName.from(entry.name, entry.storeName).ifBlank { "Prodotto selezionato" },
                    storeName = entry.storeName,
                    price = entry.price.takeUnless(Double::isNaN) ?: 0.0,
                    promotional = entry.promotional,
                )
            },
            optimizing = optimizationState.running,
            message = optimizationState.message,
        )
    }.catch { error ->
        emit(ShoppingListUiState(message = "Impossibile leggere la lista: ${error.localizedMessage ?: "errore"}"))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShoppingListUiState())

    init {
        // One-off upgrade of selections saved by older versions (id only).
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val legacyIds = preferences.manualCart.first().filterNot(CartEntry::isResolved).map(CartEntry::offerId)
                if (legacyIds.isNotEmpty()) {
                    val resolved = liveCatalog.offersByIds(legacyIds).map {
                        CartEntry(it.id, it.productName, it.storeName, it.price, it.promotional, it.distanceMeters)
                    }
                    preferences.upgradeLegacyCartEntries(resolved)
                }
            }
        }
    }

    fun addItem(name: String) = viewModelScope.launch {
        val added = runCatching { preferences.addShoppingItem(name) }.getOrDefault(false)
        if (!added && name.isNotBlank()) {
            optimization.value = optimization.value.copy(message = "\"${name.trim()}\" è già nella lista")
        }
    }

    fun removeItem(name: String) = viewModelScope.launch { runCatching { preferences.removeShoppingItem(name) } }

    fun setChecked(name: String, checked: Boolean) = viewModelScope.launch {
        runCatching { preferences.setShoppingItemChecked(name, checked) }
    }

    fun consumeMessage() {
        optimization.value = optimization.value.copy(message = null)
    }

    fun optimize() {
        optimizeJob?.cancel()
        optimizeJob = viewModelScope.launch {
            optimization.value = OptimizationState(running = true)
            val state = uiState.value
            val requested = (
                state.items.filterNot(ShoppingListItem::checked).map(ShoppingListItem::name) +
                    state.selectedItems.map(SelectedShoppingItem::name)
                ).distinctBy { it.lowercase() }
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val live = liveCatalog.catalogPrices()
                    val catalogPrices = live.ifEmpty { catalog.catalog }
                    val transport = TransportProfile(
                        vehicle = state.preferences.vehicleType,
                        fuel = state.preferences.fuelType,
                        consumptionPer100Km = state.preferences.consumptionPer100Km,
                        pricePerUnit = state.preferences.fuelPricePerUnit,
                    )
                    val basePlans = BasketOptimizer.optimize(requested, catalogPrices, transport = transport)
                    withContext(Dispatchers.IO) {
                        basePlans.map { plan ->
                            val points = plan.stores.mapNotNull { store ->
                                val lat = store.latitude ?: return@mapNotNull null
                                val lon = store.longitude ?: return@mapNotNull null
                                RoutePoint(lat, lon)
                            }
                            val route = if (points.size >= 2) routing.route(points, roundTrip = true) else null
                            if (route == null) plan else {
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
            }
            optimization.value = result.fold(
                onSuccess = { plans ->
                    OptimizationState(
                        plans = plans,
                        message = when {
                            requested.isEmpty() -> "Tutti gli articoli sono già spuntati"
                            plans.isEmpty() -> "Nessun negozio vicino vende questi articoli: prova nomi più generici (es. \"latte\")"
                            else -> null
                        },
                    )
                },
                onFailure = { OptimizationState(message = "Calcolo non riuscito, riprova") },
            )
        }
    }

    fun removeSelectedItem(id: Long) = viewModelScope.launch { runCatching { preferences.removeManualCartOffer(id) } }
    fun clearSelectedItems() = viewModelScope.launch { runCatching { preferences.clearManualCart() } }
}
