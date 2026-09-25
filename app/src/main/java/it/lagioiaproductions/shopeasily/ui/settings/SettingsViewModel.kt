package it.lagioiaproductions.shopeasily.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferences
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.preferences.VehicleType
import it.lagioiaproductions.shopeasily.data.preferences.FuelType
import it.lagioiaproductions.shopeasily.data.repository.FuelPriceRepository
import it.lagioiaproductions.shopeasily.data.repository.VehicleEfficiencyRepository
import it.lagioiaproductions.shopeasily.data.repository.VehicleOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UserPreferencesRepository(application)
    private val fuelPrices = FuelPriceRepository()
    private val vehicleEfficiency = VehicleEfficiencyRepository()
    private val _vehicleLookup = MutableStateFlow(VehicleLookupState())
    val vehicleLookup = _vehicleLookup.asStateFlow()

    val preferences: StateFlow<UserPreferences> = repository.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = UserPreferences(),
    )

    init {
        viewModelScope.launch {
            val current = repository.preferences.first()
            refreshFuelPrice(current.fuelType)
        }
        viewModelScope.launch {
            val year = _vehicleLookup.value.year
            _vehicleLookup.value = _vehicleLookup.value.copy(loading = true)
            _vehicleLookup.value = _vehicleLookup.value.copy(
                makes = vehicleEfficiency.makes(year),
                loading = false,
            )
        }
    }

    fun setAge(age: Int?) = viewModelScope.launch { repository.setAge(age) }
    fun setRadiusKm(value: Int) = viewModelScope.launch { repository.setRadiusKm(value) }
    fun setPreferSustainable(value: Boolean) = viewModelScope.launch { repository.setPreferSustainable(value) }
    fun setFlashNotifications(value: Boolean) = viewModelScope.launch { repository.setFlashNotifications(value) }
    fun setLoyaltyCard(shopName: String, owned: Boolean) = viewModelScope.launch {
        repository.setLoyaltyCard(shopName, owned)
    }
    fun setTransport(vehicle: VehicleType, fuel: FuelType) = viewModelScope.launch {
        repository.setTransport(vehicle, fuel)
        refreshFuelPrice(fuel)
    }
    fun setConsumption(value: Double) = viewModelScope.launch { repository.setConsumption(value) }

    fun selectVehicleYear(year: Int) = viewModelScope.launch {
        _vehicleLookup.value = VehicleLookupState(year = year, loading = true)
        _vehicleLookup.value = _vehicleLookup.value.copy(makes = vehicleEfficiency.makes(year), loading = false)
    }

    fun selectVehicleMake(make: String) = viewModelScope.launch {
        val state = _vehicleLookup.value
        _vehicleLookup.value = state.copy(make = make, model = null, option = null, models = emptyList(), options = emptyList(), loading = true)
        _vehicleLookup.value = _vehicleLookup.value.copy(models = vehicleEfficiency.models(state.year, make), loading = false)
    }

    fun selectVehicleModel(model: String) = viewModelScope.launch {
        val state = _vehicleLookup.value
        val make = state.make ?: return@launch
        _vehicleLookup.value = state.copy(model = model, option = null, options = emptyList(), loading = true)
        _vehicleLookup.value = _vehicleLookup.value.copy(options = vehicleEfficiency.options(state.year, make, model), loading = false)
    }

    fun selectVehicleOption(option: VehicleOption) = viewModelScope.launch {
        _vehicleLookup.value = _vehicleLookup.value.copy(option = option, loading = true)
        vehicleEfficiency.efficiency(option)?.let { result ->
            repository.setVehicleEfficiency(result.label, result.litersPer100Km)
        }
        _vehicleLookup.value = _vehicleLookup.value.copy(loading = false)
    }

    private suspend fun refreshFuelPrice(fuel: FuelType) {
        fuelPrices.nationalMedian(fuel)?.let { repository.setAutomaticFuelPrice(it) }
    }
}

data class VehicleLookupState(
    val year: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
    val make: String? = null,
    val model: String? = null,
    val option: VehicleOption? = null,
    val makes: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val options: List<VehicleOption> = emptyList(),
    val loading: Boolean = false,
)
