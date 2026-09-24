package it.lagioiaproductions.shopeasily.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.shopEasilyDataStore by preferencesDataStore(name = "shopeasily_preferences")

enum class VehicleType(val label: String, val emissionMultiplier: Double) {
    WALK("A piedi", 0.0), BICYCLE("Bici", 0.0), SCOOTER("Scooter", 0.55),
    CAR("Auto", 1.0), VAN("Furgone", 1.45), TRUCK("Camion", 3.2),
}

enum class FuelType(val label: String, val defaultPrice: Double, val kgCo2PerUnit: Double) {
    NONE("Nessuno", 0.0, 0.0), GASOLINE("Benzina", 1.85, 2.31),
    DIESEL("Diesel", 1.75, 2.68), LPG("GPL", 0.75, 1.51),
    ELECTRIC("Elettrico", 0.30, 0.23),
}

data class UserPreferences(
    val age: Int? = null,
    val radiusKm: Int = 10,
    val preferSustainable: Boolean = true,
    val includeLoyaltyOffers: Boolean = true,
    val flashNotificationsEnabled: Boolean = true,
    val lastNotifiedOfferId: Long? = null,
    val loyaltyCards: Set<String> = emptySet(),
    val vehicleType: VehicleType = VehicleType.CAR,
    val fuelType: FuelType = FuelType.GASOLINE,
    val consumptionPer100Km: Double = 6.5,
    val fuelPricePerUnit: Double = FuelType.GASOLINE.defaultPrice,
)

class UserPreferencesRepository(private val context: Context) {
    private object Keys {
        val age = intPreferencesKey("age")
        val radiusKm = intPreferencesKey("radius_km")
        val preferSustainable = booleanPreferencesKey("prefer_sustainable")
        val includeLoyalty = booleanPreferencesKey("include_loyalty")
        val flashNotifications = booleanPreferencesKey("flash_notifications")
        val shoppingItems = stringSetPreferencesKey("shopping_items")
        val checkedShoppingItems = stringSetPreferencesKey("checked_shopping_items")
        val lastNotifiedOfferId = longPreferencesKey("last_notified_offer_id")
        val loyaltyCards = stringSetPreferencesKey("loyalty_cards")
        val vehicleType = stringPreferencesKey("vehicle_type")
        val fuelType = stringPreferencesKey("fuel_type")
        val consumption = stringPreferencesKey("consumption_per_100_km")
        val fuelPrice = stringPreferencesKey("fuel_price_per_unit")
    }

    val preferences: Flow<UserPreferences> = context.shopEasilyDataStore.data.map { values ->
        UserPreferences(
            age = values[Keys.age],
            radiusKm = values[Keys.radiusKm] ?: 10,
            preferSustainable = values[Keys.preferSustainable] ?: true,
            includeLoyaltyOffers = values[Keys.includeLoyalty] ?: true,
            flashNotificationsEnabled = values[Keys.flashNotifications] ?: true,
            lastNotifiedOfferId = values[Keys.lastNotifiedOfferId],
            loyaltyCards = values[Keys.loyaltyCards].orEmpty(),
            vehicleType = values[Keys.vehicleType]?.let { runCatching { VehicleType.valueOf(it) }.getOrNull() }
                ?: VehicleType.CAR,
            fuelType = values[Keys.fuelType]?.let { runCatching { FuelType.valueOf(it) }.getOrNull() }
                ?: FuelType.GASOLINE,
            consumptionPer100Km = values[Keys.consumption]?.toDoubleOrNull() ?: 6.5,
            fuelPricePerUnit = values[Keys.fuelPrice]?.toDoubleOrNull() ?: FuelType.GASOLINE.defaultPrice,
        )
    }

    val shoppingItems: Flow<List<Pair<String, Boolean>>> = context.shopEasilyDataStore.data.map { values ->
        val checked = values[Keys.checkedShoppingItems].orEmpty()
        values[Keys.shoppingItems].orEmpty().sorted().map { it to (it in checked) }
    }

    suspend fun setAge(age: Int?) = context.shopEasilyDataStore.edit { values ->
        if (age == null) values.remove(Keys.age) else values[Keys.age] = age
    }

    suspend fun setRadiusKm(radiusKm: Int) = context.shopEasilyDataStore.edit {
        it[Keys.radiusKm] = radiusKm
    }

    suspend fun setPreferSustainable(enabled: Boolean) = context.shopEasilyDataStore.edit {
        it[Keys.preferSustainable] = enabled
    }

    suspend fun setIncludeLoyalty(enabled: Boolean) = context.shopEasilyDataStore.edit {
        it[Keys.includeLoyalty] = enabled
    }

    suspend fun setFlashNotifications(enabled: Boolean) = context.shopEasilyDataStore.edit {
        it[Keys.flashNotifications] = enabled
    }

    suspend fun setLastNotifiedOfferId(id: Long) = context.shopEasilyDataStore.edit {
        it[Keys.lastNotifiedOfferId] = id
    }

    suspend fun setLoyaltyCard(shopName: String, owned: Boolean) = context.shopEasilyDataStore.edit { values ->
        val cards = values[Keys.loyaltyCards].orEmpty()
        values[Keys.loyaltyCards] = if (owned) cards + shopName else cards - shopName
    }

    suspend fun setTransport(vehicle: VehicleType, fuel: FuelType) = context.shopEasilyDataStore.edit {
        it[Keys.vehicleType] = vehicle.name
        it[Keys.fuelType] = fuel.name
    }

    suspend fun setConsumption(value: Double) = context.shopEasilyDataStore.edit {
        it[Keys.consumption] = value.coerceIn(0.0, 100.0).toString()
    }

    suspend fun setAutomaticFuelPrice(value: Double) = context.shopEasilyDataStore.edit {
        it[Keys.fuelPrice] = value.coerceIn(0.0, 10.0).toString()
    }

    suspend fun addShoppingItem(name: String) = context.shopEasilyDataStore.edit { values ->
        values[Keys.shoppingItems] = values[Keys.shoppingItems].orEmpty() + name
    }

    suspend fun removeShoppingItem(name: String) = context.shopEasilyDataStore.edit { values ->
        values[Keys.shoppingItems] = values[Keys.shoppingItems].orEmpty() - name
        values[Keys.checkedShoppingItems] = values[Keys.checkedShoppingItems].orEmpty() - name
    }

    suspend fun setShoppingItemChecked(name: String, checked: Boolean) =
        context.shopEasilyDataStore.edit { values ->
            val current = values[Keys.checkedShoppingItems].orEmpty()
            values[Keys.checkedShoppingItems] = if (checked) current + name else current - name
        }
}
