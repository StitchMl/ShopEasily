package it.lagioiaproductions.shopeasily.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.shopEasilyDataStore by preferencesDataStore(name = "shopeasily_preferences")

data class UserPreferences(
    val age: Int? = null,
    val radiusKm: Int = 10,
    val preferSustainable: Boolean = true,
    val includeLoyaltyOffers: Boolean = true,
    val flashNotificationsEnabled: Boolean = true,
    val lastNotifiedOfferId: Long? = null,
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
    }

    val preferences: Flow<UserPreferences> = context.shopEasilyDataStore.data.map { values ->
        UserPreferences(
            age = values[Keys.age],
            radiusKm = values[Keys.radiusKm] ?: 10,
            preferSustainable = values[Keys.preferSustainable] ?: true,
            includeLoyaltyOffers = values[Keys.includeLoyalty] ?: true,
            flashNotificationsEnabled = values[Keys.flashNotifications] ?: true,
            lastNotifiedOfferId = values[Keys.lastNotifiedOfferId],
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
