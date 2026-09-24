package it.lagioiaproductions.shopeasily.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    var ageText by remember(preferences.age) { mutableStateOf(preferences.age?.toString().orEmpty()) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.setFlashNotifications(granted) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Preferenze", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("I dati restano sul dispositivo e servono per personalizzare le offerte.")
        OutlinedTextField(
            value = ageText,
            onValueChange = { value ->
                val filtered = value.filter(Char::isDigit).take(3)
                ageText = filtered
                viewModel.setAge(filtered.toIntOrNull()?.takeIf { it in 1..120 })
            },
            label = { Text("Età (facoltativa)") },
            supportingText = { Text("Serve per promozioni 65+ o altre fasce dichiarate") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Raggio massimo: ${preferences.radiusKm} km")
        Slider(
            value = preferences.radiusKm.toFloat(),
            onValueChange = { viewModel.setRadiusKm(it.toInt()) },
            valueRange = 1f..30f,
            steps = 28,
        )
        SettingSwitch(
            title = "Preferisci prodotti sostenibili",
            checked = preferences.preferSustainable,
            onCheckedChange = viewModel::setPreferSustainable,
        )
        SettingSwitch(
            title = "Includi offerte con carta fedeltà",
            checked = preferences.includeLoyaltyOffers,
            onCheckedChange = viewModel::setIncludeLoyalty,
        )
        SettingSwitch(
            title = "Notifiche per offerte lampo",
            checked = preferences.flashNotificationsEnabled,
            onCheckedChange = { enabled ->
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.setFlashNotifications(enabled)
                }
            },
        )
        Text(
            "Le valutazioni etiche dei servizi online vengono mostrate solo quando hanno una fonte verificabile.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
