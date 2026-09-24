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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
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
import it.lagioiaproductions.shopeasily.data.preferences.FuelType
import it.lagioiaproductions.shopeasily.data.preferences.VehicleType

private val supportedLoyaltyPrograms = listOf(
    "Esselunga",
    "Coop",
    "Conad",
    "Carrefour",
    "Lidl",
    "Eurospin",
    "NaturaSì",
    "Cortilia",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    var ageText by remember(preferences.age) { mutableStateOf(preferences.age?.toString().orEmpty()) }
    var loyaltyMenuOpen by remember { mutableStateOf(false) }
    var selectedShop by remember { mutableStateOf("") }
    var vehicleMenuOpen by remember { mutableStateOf(false) }
    var fuelMenuOpen by remember { mutableStateOf(false) }
    var consumptionText by remember(preferences.consumptionPer100Km) {
        mutableStateOf(preferences.consumptionPer100Km.toString())
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.setFlashNotifications(granted) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Profilo", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = ageText,
            onValueChange = { value ->
                val filtered = value.filter(Char::isDigit).take(3)
                ageText = filtered
                viewModel.setAge(filtered.toIntOrNull()?.takeIf { it in 1..120 })
            },
            label = { Text("Età (facoltativa)") },
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
        Text("Trasporto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        ExposedDropdownMenuBox(
            expanded = vehicleMenuOpen,
            onExpandedChange = { vehicleMenuOpen = !vehicleMenuOpen },
        ) {
            OutlinedTextField(
                value = preferences.vehicleType.label,
                onValueChange = {}, readOnly = true, label = { Text("Mezzo") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(vehicleMenuOpen) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(vehicleMenuOpen, { vehicleMenuOpen = false }) {
                VehicleType.entries.forEach { vehicle ->
                    DropdownMenuItem(text = { Text(vehicle.label) }, onClick = {
                        val fuel = if (vehicle == VehicleType.WALK || vehicle == VehicleType.BICYCLE) FuelType.NONE
                        else preferences.fuelType.takeUnless { it == FuelType.NONE } ?: FuelType.GASOLINE
                        viewModel.setTransport(vehicle, fuel)
                        vehicleMenuOpen = false
                    })
                }
            }
        }
        ExposedDropdownMenuBox(
            expanded = fuelMenuOpen,
            onExpandedChange = { fuelMenuOpen = !fuelMenuOpen },
        ) {
            OutlinedTextField(
                value = preferences.fuelType.label,
                onValueChange = {}, readOnly = true, label = { Text("Alimentazione") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(fuelMenuOpen) },
                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(fuelMenuOpen, { fuelMenuOpen = false }) {
                FuelType.entries.filter { fuel ->
                    preferences.vehicleType in listOf(VehicleType.WALK, VehicleType.BICYCLE) || fuel != FuelType.NONE
                }.forEach { fuel ->
                    DropdownMenuItem(text = { Text(fuel.label) }, onClick = {
                        viewModel.setTransport(preferences.vehicleType, fuel)
                        fuelMenuOpen = false
                    })
                }
            }
        }
        if (preferences.fuelType != FuelType.NONE) {
            OutlinedTextField(
                value = consumptionText,
                onValueChange = { value ->
                    consumptionText = value.replace(',', '.')
                    consumptionText.toDoubleOrNull()?.let(viewModel::setConsumption)
                },
                label = { Text(if (preferences.fuelType == FuelType.ELECTRIC) "kWh/100 km" else "L/100 km") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    val unit = if (preferences.fuelType == FuelType.ELECTRIC) "€/kWh" else "€/L"
                    Text("Media online: %.3f %s".format(preferences.fuelPricePerUnit, unit))
                },
            )
        }
        Text("Carte fedeltà", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ExposedDropdownMenuBox(
                expanded = loyaltyMenuOpen,
                onExpandedChange = { loyaltyMenuOpen = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = selectedShop,
                    onValueChange = {
                        selectedShop = it
                        loyaltyMenuOpen = true
                    },
                    singleLine = true,
                    label = { Text("Negozio") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(loyaltyMenuOpen) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = loyaltyMenuOpen,
                    onDismissRequest = { loyaltyMenuOpen = false },
                ) {
                    supportedLoyaltyPrograms.filter { it.contains(selectedShop, ignoreCase = true) }.forEach { shop ->
                        DropdownMenuItem(text = { Text(shop) }, onClick = {
                            selectedShop = shop
                            loyaltyMenuOpen = false
                        })
                    }
                }
            }
            Button(
                onClick = {
                    viewModel.setLoyaltyCard(selectedShop.trim(), true)
                    selectedShop = ""
                },
                enabled = selectedShop.isNotBlank(),
            ) { Text("+") }
        }
        preferences.loyaltyCards.sorted().forEach { shop ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(shop, modifier = Modifier.weight(1f))
                IconButton(onClick = { viewModel.setLoyaltyCard(shop, false) }) { Text("×") }
            }
        }
        SettingSwitch(
            title = "Preferisci prodotti sostenibili",
            checked = preferences.preferSustainable,
            onCheckedChange = viewModel::setPreferSustainable,
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
