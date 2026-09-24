package it.lagioiaproductions.shopeasly.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.lagioiaproductions.shopeasly.data.preferences.UserPreferences
import it.lagioiaproductions.shopeasly.data.preferences.UserPreferencesRepository
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
}
