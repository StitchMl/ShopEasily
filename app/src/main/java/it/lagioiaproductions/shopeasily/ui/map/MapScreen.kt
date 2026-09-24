package it.lagioiaproductions.shopeasily.ui.map

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import it.lagioiaproductions.shopeasily.BuildConfig
import it.lagioiaproductions.shopeasily.data.model.StoreChannel
import it.lagioiaproductions.shopeasily.data.repository.FakeCatalogRepository

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val stores = remember { FakeCatalogRepository().stores }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(45.4642, 9.1900), 12f)
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Negozi vicini", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        if (!locationGranted) {
            Button(
                onClick = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        ),
                    )
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text("Usa la mia posizione")
            }
        }
        if (BuildConfig.MAPS_CONFIGURED) {
            GoogleMap(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = locationGranted),
            ) {
                stores.filter { it.channel == StoreChannel.PHYSICAL }.forEach { store ->
                    val latitude = store.latitude ?: return@forEach
                    val longitude = store.longitude ?: return@forEach
                    Marker(
                        state = rememberUpdatedMarkerState(position = LatLng(latitude, longitude)),
                        title = store.name,
                        snippet = "${store.distanceMeters} m da te",
                    )
                }
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Configura la mappa", fontWeight = FontWeight.Bold)
                    Text("Aggiungi MAPS_API_KEY a local.properties")
                    stores.filter { it.channel == StoreChannel.PHYSICAL }.forEach { store ->
                        Text("• ${store.name}: ${store.distanceMeters} m")
                    }
                }
            }
        }
        stores.filter { it.channel == StoreChannel.ONLINE }.forEach { store ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(store.name, fontWeight = FontWeight.Bold)
                    Text("Consegna € ${"%.2f".format(store.deliveryFee)} · CO₂ stimata ${store.deliveryEmissionKgCo2} kg")
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
