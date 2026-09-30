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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val vehicleModelLabel: String? = null,
)

data class CartEntry(
    val offerId: Long,
    val name: String,
    val storeName: String,
    val price: Double,
    val promotional: Boolean,
    /** Snapshot used for instant fuel/CO2 totals even while Room is still loading. */
    val distanceMeters: Int = 0,
) {
    val isResolved: Boolean get() = name.isNotBlank()

    fun encode(): String = listOf(
        offerId.toString(), name.clean(), storeName.clean(), price.toString(), promotional.toString(), distanceMeters.toString(),
    ).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "\u001F"
        private fun String.clean() = replace(SEPARATOR, " ")

        fun decode(value: String): CartEntry? {
            val parts = value.split(SEPARATOR)
            if (parts.size !in 5..6) return null
            return CartEntry(
                offerId = parts[0].toLongOrNull() ?: return null,
                name = parts[1],
                storeName = parts[2],
                price = parts[3].toDoubleOrNull() ?: return null,
                promotional = parts[4].toBoolean(),
                distanceMeters = parts.getOrNull(5)?.toIntOrNull() ?: 0,
            )
        }
    }
}

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
        val manualCartOfferIds = stringSetPreferencesKey("manual_cart_offer_ids")
        val vehicleModelLabel = stringPreferencesKey("vehicle_model_label")
        val manualCartEntries = stringSetPreferencesKey("manual_cart_entries")
        val lastLatitude = stringPreferencesKey("last_latitude")
        val lastLongitude = stringPreferencesKey("last_longitude")
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
            vehicleModelLabel = values[Keys.vehicleModelLabel],
        )
    }

    val shoppingItems: Flow<List<Pair<String, Boolean>>> = context.shopEasilyDataStore.data.map { values ->
        val checked = values[Keys.checkedShoppingItems].orEmpty()
        values[Keys.shoppingItems].orEmpty().sortedBy { it.lowercase() }.map { it to (it in checked) }
    }.distinctUntilChanged()

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

    /**
     * Products selected on Home. Each entry is a snapshot (name, store, price)
     * so the selection survives catalogue refreshes, expired offers and price
     * changes, and the shopping list can show it without scanning the catalogue.
     */
    val manualCart: Flow<List<CartEntry>> = context.shopEasilyDataStore.data.map { values ->
        val snapshots = values[Keys.manualCartEntries].orEmpty().mapNotNull { CartEntry.decode(it) }
        val known = snapshots.map(CartEntry::offerId).toSet()
        // Legacy selections stored only the id: keep them until resolved.
        val legacy = values[Keys.manualCartOfferIds].orEmpty().mapNotNull(String::toLongOrNull)
            .filterNot(known::contains)
            .map { CartEntry(it, "", "", Double.NaN, false) }
        (snapshots + legacy).sortedBy { it.name.lowercase() }
    }.distinctUntilChanged()

    val manualCartOfferIds: Flow<Set<Long>> = manualCart.map { entries -> entries.map(CartEntry::offerId).toSet() }
        .distinctUntilChanged()

    /** Adds or removes an offer from the Home selection. Returns true when it is now selected. */
    suspend fun toggleManualCartOffer(entry: CartEntry): Boolean {
        var selected = false
        context.shopEasilyDataStore.edit { values ->
            val entries = values[Keys.manualCartEntries].orEmpty()
            val ids = values[Keys.manualCartOfferIds].orEmpty()
            val existing = entries.filter { CartEntry.decode(it)?.offerId == entry.offerId }.toSet()
            val idText = entry.offerId.toString()
            if (existing.isNotEmpty() || idText in ids) {
                values[Keys.manualCartEntries] = entries - existing
                values[Keys.manualCartOfferIds] = ids - idText
            } else {
                values[Keys.manualCartEntries] = entries + entry.encode()
                selected = true
            }
        }
        return selected
    }

    suspend fun removeManualCartOffer(id: Long) = context.shopEasilyDataStore.edit { values ->
        values[Keys.manualCartEntries] = values[Keys.manualCartEntries].orEmpty()
            .filterNot { CartEntry.decode(it)?.offerId == id }.toSet()
        values[Keys.manualCartOfferIds] = values[Keys.manualCartOfferIds].orEmpty() - id.toString()
    }

    /** Replaces legacy id-only selections with full snapshots once resolved from the catalogue. */
    suspend fun upgradeLegacyCartEntries(resolved: List<CartEntry>) {
        if (resolved.isEmpty()) return
        context.shopEasilyDataStore.edit { values ->
            values[Keys.manualCartEntries] = values[Keys.manualCartEntries].orEmpty() + resolved.map(CartEntry::encode)
            values[Keys.manualCartOfferIds] = values[Keys.manualCartOfferIds].orEmpty() -
                resolved.map { it.offerId.toString() }.toSet()
        }
    }

    suspend fun clearManualCart() = context.shopEasilyDataStore.edit { values ->
        values.remove(Keys.manualCartOfferIds)
        values.remove(Keys.manualCartEntries)
    }

    val lastLocation: Flow<Pair<Double, Double>?> = context.shopEasilyDataStore.data.map { values ->
        val lat = values[Keys.lastLatitude]?.toDoubleOrNull()
        val lon = values[Keys.lastLongitude]?.toDoubleOrNull()
        if (lat != null && lon != null) lat to lon else null
    }.distinctUntilChanged()

    suspend fun setLastLocation(latitude: Double, longitude: Double) = context.shopEasilyDataStore.edit {
        it[Keys.lastLatitude] = latitude.toString()
        it[Keys.lastLongitude] = longitude.toString()
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

    suspend fun setVehicleEfficiency(label: String, litersPer100Km: Double) = context.shopEasilyDataStore.edit {
        it[Keys.vehicleModelLabel] = label
        it[Keys.consumption] = litersPer100Km.coerceIn(0.1, 100.0).toString()
    }

    suspend fun setAutomaticFuelPrice(value: Double) = context.shopEasilyDataStore.edit {
        it[Keys.fuelPrice] = value.coerceIn(0.0, 10.0).toString()
    }

    /** Adds an item, ignoring blanks and case-insensitive duplicates. Returns false if nothing was added. */
    suspend fun addShoppingItem(name: String): Boolean {
        val cleaned = name.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return false
        var added = false
        context.shopEasilyDataStore.edit { values ->
            val current = values[Keys.shoppingItems].orEmpty()
            if (current.none { it.equals(cleaned, ignoreCase = true) }) {
                values[Keys.shoppingItems] = current + cleaned
                added = true
            }
        }
        return added
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
