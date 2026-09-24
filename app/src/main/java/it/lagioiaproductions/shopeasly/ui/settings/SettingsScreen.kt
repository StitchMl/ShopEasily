package it.lagioiaproductions.shopeasly.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    var radiusKm by remember { mutableFloatStateOf(10f) }
    var preferSustainable by remember { mutableStateOf(true) }
    var includeLoyalty by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Preferenze", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Raggio massimo: ${radiusKm.roundToInt()} km")
        Slider(
            value = radiusKm,
            onValueChange = { radiusKm = it },
            valueRange = 1f..30f,
            steps = 28,
        )
        SettingSwitch(
            title = "Preferisci prodotti sostenibili",
            checked = preferSustainable,
            onCheckedChange = { preferSustainable = it },
        )
        SettingSwitch(
            title = "Includi offerte con carta fedeltà",
            checked = includeLoyalty,
            onCheckedChange = { includeLoyalty = it },
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
