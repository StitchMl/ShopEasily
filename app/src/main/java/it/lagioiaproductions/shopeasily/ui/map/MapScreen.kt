package it.lagioiaproductions.shopeasily.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Geocoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.repository.NearbyStore
import it.lagioiaproductions.shopeasily.data.repository.OnDeviceCatalogRepository
import it.lagioiaproductions.shopeasily.ui.common.StoreLogoResolver
import it.lagioiaproductions.shopeasily.domain.StoreSustainabilityResult
import it.lagioiaproductions.shopeasily.sync.CatalogSyncWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.util.Locale

@SuppressLint("MissingPermission")
@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { OnDeviceCatalogRepository(context.applicationContext) }
    val preferences = remember { UserPreferencesRepository(context.applicationContext) }
    val searchArea by preferences.searchArea.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var userLocation by remember { mutableStateOf<LatLng?>(null) }
    var stores by remember { mutableStateOf(emptyList<NearbyStore>()) }
    var isLoading by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var mapInstance by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var showLocationDialog by remember { mutableStateOf(false) }
    var locationQuery by remember { mutableStateOf("") }
    var locationError by remember { mutableStateOf<String?>(null) }
    var resolvingLocation by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                mapInstance = map
                map.setStyle(Style.Builder().fromUri("asset://osm_style.json")) { style ->
                    style.addSource(GeoJsonSource(STORES_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>())))
                    style.addLayer(
                        SymbolLayer("store-marks", STORES_SOURCE).withProperties(
                            iconImage("{logo}"), iconSize(0.55f), iconAllowOverlap(true),
                        ),
                    )
                    style.addLayer(
                        SymbolLayer("store-labels", STORES_SOURCE).withProperties(
                            textField("{name}"), textSize(11f), textOffset(arrayOf(0f, 1.7f)),
                            textColor("#173F34"),
                        ),
                    )
                    style.addSource(GeoJsonSource(USER_SOURCE, FeatureCollection.fromFeatures(emptyArray<Feature>())))
                    style.addLayer(
                        CircleLayer("user-dot", USER_SOURCE).withProperties(
                            circleColor("#2F80ED"), circleRadius(9f),
                            circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
                        ),
                    )
                    styleReady = true
                }
            }
        }
    }

    // Stores already known are shown immediately, even offline.
    LaunchedEffect(Unit) {
        val known = runCatching { repository.storedStores() }.getOrDefault(emptyList())
        if (stores.isEmpty() && known.isNotEmpty()) stores = known
    }

    LaunchedEffect(searchArea) {
        searchArea?.let { area ->
            userLocation = LatLng(area.latitude, area.longitude)
        }
    }

    LaunchedEffect(locationGranted, refreshKey, searchArea?.manual) {
        if (!locationGranted) return@LaunchedEffect
        // Opening the map must be instant and must not wake the GPS every time.
        // Reuse the persisted device fix; acquire a new one only on first use or
        // after the user explicitly taps Refresh.
        if (refreshKey == 0) {
            val cached = runCatching { preferences.searchArea.first() }.getOrNull()
            if (cached != null) {
                userLocation = LatLng(cached.latitude, cached.longitude)
                isLoading = false
                return@LaunchedEffect
            }
        }
        if (searchArea?.manual == true) return@LaunchedEffect
        isLoading = true
        runCatching {
            val client = LocationServices.getFusedLocationProviderClient(context)
            // A cached fix makes the map useful immediately indoors. The fresh fix below
            // replaces it as soon as Android can provide one.
            client.lastLocation.addOnSuccessListener { location ->
                if (location != null && userLocation == null) {
                    userLocation = LatLng(location.latitude, location.longitude)
                }
            }
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) userLocation = LatLng(location.latitude, location.longitude)
                    else isLoading = false
                }
                .addOnFailureListener { isLoading = false }
        }.onFailure { isLoading = false }
    }

    LaunchedEffect(userLocation, refreshKey) {
        val position = userLocation ?: return@LaunchedEffect
        val radius = runCatching { preferences.preferences.first().radiusKm }.getOrDefault(10)
        val cached = runCatching {
            repository.cachedStoresNear(position.latitude, position.longitude, radius)
        }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) stores = cached
        isLoading = false
        // Discovery and offers are refreshed by one background worker: the map itself
        // never waits for Overpass, Places, PDF parsing or OCR.
        if (searchArea?.manual != true) {
            runCatching { preferences.setLastLocation(position.latitude, position.longitude) }
        }
        CatalogSyncWorker.requestNow(context.applicationContext, position.latitude, position.longitude, force = refreshKey > 0)
    }

    // Room is the map cache. Each completed background batch is published without a
    // spinner and recomputed against the current device position.
    LaunchedEffect(userLocation) {
        val position = userLocation ?: return@LaunchedEffect
        val radius = runCatching { preferences.preferences.first().radiusKm }.getOrDefault(10)
        repository.observeCatalogVersion().collect {
            val cached = runCatching {
                repository.cachedStoresNear(position.latitude, position.longitude, radius)
            }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) stores = cached
        }
    }

    LaunchedEffect(userLocation, styleReady) {
        if (!styleReady) return@LaunchedEffect
        userLocation?.let { position ->
            runCatching {
                mapInstance?.style?.getSourceAs<GeoJsonSource>(USER_SOURCE)
                    ?.setGeoJson(Point.fromLngLat(position.longitude, position.latitude))
                mapInstance?.cameraPosition = CameraPosition.Builder().target(position).zoom(13.5).build()
            }
        }
    }

    LaunchedEffect(stores, styleReady) {
        if (!styleReady) return@LaunchedEffect
        val visibleStores = stores.take(MAX_MAP_MARKERS)
        fun publish(logos: List<Bitmap?>) {
            val style = mapInstance?.style?.takeIf { it.isFullyLoaded } ?: return
            val features = visibleStores.mapIndexed { index, store ->
                val logoKey = "store-logo-$index"
                runCatching { style.addImage(logoKey, logos.getOrNull(index) ?: StoreLogoResolver.initialMarker(store.name)) }
                Feature.fromGeometry(Point.fromLngLat(store.longitude, store.latitude)).apply {
                    addStringProperty("storeId", store.id)
                    addStringProperty("name", store.name)
                    addStringProperty("logo", logoKey)
                }
            }
            runCatching {
                style.getSourceAs<GeoJsonSource>(STORES_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
            }
        }
        // Markers appear at once with initials; official logos replace them when downloaded.
        publish(emptyList())
        val permits = Semaphore(6)
        val logos = withContext(Dispatchers.IO) {
            visibleStores.map { store ->
                async {
                    permits.withPermit { runCatching { StoreLogoResolver.load(store.name, store.website) }.getOrNull() }
                }
            }.awaitAll()
        }
        publish(logos)
    }

    // Follow the real lifecycle: the map used to stay "resumed" in background.
    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
        }
    }

    DisposableEffect(mapInstance, stores, styleReady) {
        val map = mapInstance
        if (map == null || !styleReady) return@DisposableEffect onDispose { }
        val listener = MapLibreMap.OnMapClickListener { coordinate ->
            val screenPoint = map.projection.toScreenLocation(coordinate)
            // Labels and icons of nearby shops can overlap. MapLibre does not guarantee
            // feature order, so "first" could open DOC while the user tapped the adjacent
            // Conad. Resolve every rendered ID and choose the geographically nearest pin.
            val renderedIds = map.queryRenderedFeatures(screenPoint, "store-marks", "store-labels")
                .mapNotNull { feature -> feature.getStringProperty("storeId")?.takeIf(String::isNotBlank) }
                .distinct()
                .toSet()
            val store = closestStoreToTap(stores, renderedIds, coordinate.latitude, coordinate.longitude)
            if (store != null) {
                runCatching { openNavigation(context, store) }
                true
            } else {
                false
            }
        }
        map.addOnMapClickListener(listener)
        onDispose { map.removeOnMapClickListener(listener) }
    }

    if (showLocationDialog) {
        AlertDialog(
            onDismissRequest = { if (!resolvingLocation) showLocationDialog = false },
            title = { Text("Zona di ricerca") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Inserisci città, CAP o indirizzo")
                    OutlinedTextField(
                        value = locationQuery,
                        onValueChange = { locationQuery = it; locationError = null },
                        singleLine = true,
                        placeholder = { Text("Es. Roma EUR") },
                    )
                    locationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (searchArea?.manual == true) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    preferences.useDeviceLocation()
                                    userLocation = null
                                    showLocationDialog = false
                                    refreshKey++
                                }
                            },
                        ) { Text("Usa posizione dispositivo") }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = locationQuery.isNotBlank() && !resolvingLocation,
                    onClick = {
                        resolvingLocation = true
                        locationError = null
                        scope.launch {
                            val result = geocode(context, locationQuery)
                            resolvingLocation = false
                            if (result == null) {
                                locationError = "Località non trovata"
                            } else {
                                preferences.setManualSearchArea(result.first, result.second, locationQuery)
                                userLocation = LatLng(result.first, result.second)
                                showLocationDialog = false
                                refreshKey++
                            }
                        }
                    },
                ) { Text(if (resolvingLocation) "Cerco…" else "Usa questa zona") }
            },
            dismissButton = { TextButton(onClick = { showLocationDialog = false }) { Text("Annulla") } },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    searchArea?.takeIf { it.manual }?.label ?: "Vicino a te",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (stores.isEmpty()) "Negozi e mercati nel tuo raggio" else "${stores.size} punti vendita trovati",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { showLocationDialog = true }) {
                Icon(Icons.Rounded.Search, contentDescription = "Scegli zona di ricerca")
            }
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                } else {
                    IconButton(onClick = { refreshKey++ }, enabled = locationGranted || searchArea?.manual == true) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna negozi e offerte")
                    }
                }
            }
        }

        if (!locationGranted && searchArea?.manual != true) {
            LocationPermissionCard {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
                FilledIconButton(
                    onClick = {
                        userLocation?.let {
                            mapInstance?.cameraPosition = CameraPosition.Builder().target(it).zoom(14.5).build()
                        }
                    },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) { Icon(Icons.Rounded.MyLocation, contentDescription = "Centra sulla mia posizione") }

                Row(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .horizontalScroll(rememberScrollState()).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    stores.take(MAX_VISIBLE_STORES).forEach { store ->
                        StoreCard(
                            store = store,
                            onNavigate = {
                                runCatching { openNavigation(context, store) }
                            },
                        )
                    }
                }
            }
        }
    }
}

internal fun closestStoreToTap(
    stores: List<NearbyStore>,
    renderedIds: Set<String>,
    latitude: Double,
    longitude: Double,
): NearbyStore? = stores.asSequence()
    .filter { it.id in renderedIds }
    .minByOrNull { store ->
        val latitudeDelta = store.latitude - latitude
        val longitudeDelta = (store.longitude - longitude) * kotlin.math.cos(Math.toRadians(latitude))
        latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta
    }

@Suppress("DEPRECATION")
private suspend fun geocode(context: android.content.Context, query: String): Pair<Double, Double>? =
    withContext(Dispatchers.IO) {
        runCatching {
            Geocoder(context, Locale.ITALY).getFromLocationName(query.trim(), 1)
                ?.firstOrNull()
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

private fun openNavigation(context: android.content.Context, store: NearbyStore) {
    val uri = Uri.parse(
        "google.navigation:q=${store.latitude},${store.longitude}&mode=d",
    )
    val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
    val fallback = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("geo:${store.latitude},${store.longitude}?q=${store.latitude},${store.longitude}(${Uri.encode(store.name)})"),
    )
    val selectedIntent = if (intent.resolveActivity(context.packageManager) != null) intent else fallback
    try {
        context.startActivity(selectedIntent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://www.google.com/maps/dir/?api=1&destination=${store.latitude},${store.longitude}",
                ),
            ),
        )
    }
}

@Composable
private fun LocationPermissionCard(onEnable: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("Attiva la posizione per vedere negozi, mercati e offerte realmente vicini.")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onEnable) { Text("Attiva posizione") }
        }
    }
}

@Composable
private fun StoreCard(store: NearbyStore, onNavigate: () -> Unit) {
    Card(onClick = onNavigate, modifier = Modifier.width(230.dp)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StoreLogo(store)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        store.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (store.sustainabilityScore >= StoreSustainabilityResult.LEAF_THRESHOLD) {
                        Icon(
                            Icons.Rounded.Eco,
                            contentDescription = "Foglia ${store.sustainabilityScore} su 100: " +
                                store.sustainabilityReasons.joinToString(", "),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp).size(18.dp),
                        )
                    }
                }
                if (store.sustainabilityReasons.isNotEmpty()) {
                    Text(
                        store.sustainabilityReasons.first(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                store.reviewRating?.let { rating ->
                    Text(
                        "★ %.1f · %d recensioni".format(rating, store.reviewCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                    )
                }
                Text(
                    distanceLabel(store.distanceMeters),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onNavigate) {
                Icon(Icons.Rounded.Navigation, contentDescription = "Indicazioni per ${store.name}")
            }
        }
    }
}

@Composable
private fun StoreLogo(store: NearbyStore) {
    val bitmap by produceState<Bitmap?>(initialValue = null, store.website, store.name) {
        value = withContext(Dispatchers.IO) { StoreLogoResolver.load(store.name, store.website) }
    }
    Image(
        bitmap = (bitmap ?: StoreLogoResolver.initialMarker(store.name)).asImageBitmap(),
        contentDescription = "Marchio ${store.name}",
        modifier = Modifier.size(34.dp),
    )
}

private fun distanceLabel(meters: Int) = if (meters < 1_000) "$meters m" else "%.1f km".format(meters / 1_000.0)

private const val STORES_SOURCE = "stores"
private const val USER_SOURCE = "user-position"
private const val MAX_VISIBLE_STORES = 30
private const val MAX_MAP_MARKERS = 120
