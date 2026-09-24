package it.lagioiaproductions.shopeasily.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import it.lagioiaproductions.shopeasily.data.model.StoreChannel
import it.lagioiaproductions.shopeasily.data.repository.FakeCatalogRepository
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val stores = remember {
        FakeCatalogRepository().stores.filter { it.channel == StoreChannel.PHYSICAL }
    }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            onStart()
            onResume()
            getMapAsync { map ->
                mapInstance = map
                map.setStyle(Style.Builder().fromUri("asset://osm_style.json")) {
                    val features = stores.mapNotNull { store ->
                        val latitude = store.latitude ?: return@mapNotNull null
                        val longitude = store.longitude ?: return@mapNotNull null
                        Feature.fromGeometry(Point.fromLngLat(longitude, latitude)).apply {
                            addStringProperty("name", store.name)
                        }
                    }
                    it.addSource(GeoJsonSource("stores", FeatureCollection.fromFeatures(features)))
                    it.addLayer(
                        CircleLayer("store-dots", "stores").withProperties(
                            circleColor("#2E6B57"), circleRadius(8f),
                            circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                        ),
                    )
                    it.addSource(GeoJsonSource("user-position", FeatureCollection.fromFeatures(emptyArray<Feature>())))
                    it.addLayer(
                        CircleLayer("user-dot", "user-position").withProperties(
                            circleColor("#2F80ED"), circleRadius(9f),
                            circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
                        ),
                    )
                    styleReady = true
                    it.addLayer(
                        SymbolLayer("store-labels", "stores").withProperties(
                            textField("{name}"), textSize(12f), textOffset(arrayOf(0f, 1.5f)),
                            textColor("#173F34"),
                        ),
                    )
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(45.4642, 9.1900))
                        .zoom(12.0)
                        .build()
                }
            }
        }
    }

    LaunchedEffect(locationGranted) {
        if (locationGranted) {
            LocationServices.getFusedLocationProviderClient(context)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) userLocation = LatLng(location.latitude, location.longitude)
                }
        }
    }

    LaunchedEffect(userLocation, styleReady) {
        val position = userLocation ?: return@LaunchedEffect
        val map = mapInstance ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        map.style?.getSourceAs<GeoJsonSource>("user-position")
            ?.setGeoJson(Point.fromLngLat(position.longitude, position.latitude))
        map.cameraPosition = CameraPosition.Builder().target(position).zoom(14.0).build()
    }

    DisposableEffect(mapView) {
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            "Negozi vicini",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp),
        )
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
            ) { Text("Usa la mia posizione") }
        }
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
