package it.lagioiaproductions.shopeasily.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferences
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.preferences.VehicleType
import it.lagioiaproductions.shopeasily.data.preferences.FuelType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UserPreferencesRepository(application)

    val preferences: StateFlow<UserPreferences> = repository.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences(),
    )

    fun setAge(age: Int?) = viewModelScope.launch { repository.setAge(age) }
    fun setRadiusKm(value: Int) = viewModelScope.launch { repository.setRadiusKm(value) }
    fun setPreferSustainable(value: Boolean) = viewModelScope.launch { repository.setPreferSustainable(value) }
    fun setIncludeLoyalty(value: Boolean) = viewModelScope.launch { repository.setIncludeLoyalty(value) }
    fun setFlashNotifications(value: Boolean) = viewModelScope.launch { repository.setFlashNotifications(value) }
    fun setLoyaltyCard(shopName: String, owned: Boolean) = viewModelScope.launch {
        repository.setLoyaltyCard(shopName, owned)
    }
    fun setTransport(vehicle: VehicleType, fuel: FuelType) = viewModelScope.launch {
        repository.setTransport(vehicle, fuel)
    }
    fun setConsumption(value: Double) = viewModelScope.launch { repository.setConsumption(value) }
    fun setFuelPrice(value: Double) = viewModelScope.launch { repository.setFuelPrice(value) }
}
