@file:Suppress("SpellCheckingInspection")

package it.lagioiaproductions.shopeasily.ui.search

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Elderly
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Search
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import it.lagioiaproductions.shopeasily.R
import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.preferences.UserPreferencesRepository
import it.lagioiaproductions.shopeasily.data.repository.NearbyStore
import it.lagioiaproductions.shopeasily.data.model.ProductImageKey
import it.lagioiaproductions.shopeasily.domain.SortMode
import it.lagioiaproductions.shopeasily.domain.EcoBasketEstimate
import it.lagioiaproductions.shopeasily.domain.PriceTendency
import it.lagioiaproductions.shopeasily.domain.StoreAssessment
import it.lagioiaproductions.shopeasily.domain.ProductImageMatcher
import it.lagioiaproductions.shopeasily.domain.sustainabilityScore
import it.lagioiaproductions.shopeasily.domain.StoreSustainabilityResult
import it.lagioiaproductions.shopeasily.ui.common.BitmapLoader
import it.lagioiaproductions.shopeasily.ui.common.StoreLogoResolver
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val locationPreferences = remember { UserPreferencesRepository(context.applicationContext) }
    val searchArea by locationPreferences.searchArea.collectAsStateWithLifecycle(initialValue = null)
    val homeHelpSeen by locationPreferences.homeHelpSeen.collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()
    var helpTopic by remember { mutableStateOf<HomeHelpTopic?>(null) }
    var locationRefresh by remember { mutableStateOf(0) }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    val listState = rememberLazyListState()
    var showPriceDialog by remember { mutableStateOf(false) }
    var preferredReportStoreId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(homeHelpSeen) {
        if (homeHelpSeen == false && helpTopic == null) helpTopic = HomeHelpTopic.WELCOME
    }
    helpTopic?.let { topic ->
        HomeHelpDialog(
            topic = topic,
            onDismiss = {
                helpTopic = null
                if (topic == HomeHelpTopic.WELCOME) {
                    coroutineScope.launch { locationPreferences.markHomeHelpSeen() }
                }
            },
        )
    }
    if (showPriceDialog) {
        val reportStores by viewModel.reportStores.collectAsStateWithLifecycle()
        UserPriceDialog(
            stores = reportStores,
            preferredStoreId = preferredReportStoreId,
            initialProduct = state.query,
            onDismiss = { showPriceDialog = false },
            onSave = { storeId, product, price, quality ->
                viewModel.addUserPrice(storeId, product, price, quality)
                showPriceDialog = false
            },
        )
    }
    // A new sort/filter must show the new first results: LazyColumn otherwise keeps the
    // previously first product anchored on screen and the order seems unchanged.
    LaunchedEffect(state.orderVersion) {
        if (state.orderVersion > 0) listState.scrollToItem(0)
    }

    // Back first removes an active filter instead of leaving the app.
    BackHandler(enabled = state.selectedStore != null || state.showSelectedOnly) {
        viewModel.clearFilters()
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.consumeMessage()
    }

    LaunchedEffect(Unit) {
        if (!locationGranted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) locationRefresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(locationGranted, locationRefresh, searchArea?.manual) {
        if (!locationGranted || searchArea?.manual == true) return@LaunchedEffect
        val client = LocationServices.getFusedLocationProviderClient(context)
        // Start immediately from the last reliable fix; getCurrentLocation can
        // remain pending indoors and previously prevented automatic scraping.
        client.lastLocation.addOnSuccessListener { last ->
            if (last != null) viewModel.refreshForLocation(last.latitude, last.longitude)
        }
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) viewModel.refreshForLocation(location.latitude, location.longitude)
            }
    }

    LaunchedEffect(searchArea) {
        val area = searchArea?.takeIf { it.manual } ?: return@LaunchedEffect
        viewModel.refreshForLocation(area.latitude, area.longitude)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.shopeasily_logo),
                    contentDescription = "Logo ShopEasily",
                    modifier = Modifier.size(38.dp).clickable { helpTopic = HomeHelpTopic.WELCOME },
                )
                Text(
                    "ShopEasily",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                        .clickable { helpTopic = HomeHelpTopic.WELCOME }
                        .weight(1f),
                )
                HeaderMetric(
                    Icons.Rounded.ShoppingBasket,
                    compactMoney(state.activeProductsTotal),
                ) { helpTopic = HomeHelpTopic.PRODUCT_COST }
                HeaderMetric(
                    Icons.Rounded.LocalGasStation,
                    compactMoney(state.activeTravelCost),
                ) { helpTopic = HomeHelpTopic.TRAVEL_COST }
                HeaderMetric(Icons.Rounded.Eco, compactEmission(state.activeEmissionKg)) {
                    helpTopic = HomeHelpTopic.EMISSIONS
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
                    trailingIcon = {
                        if (state.query.isNotBlank() && state.offers.isNotEmpty()) {
                            IconButton(onClick = viewModel::toggleCurrentPriceTarget) {
                                Icon(
                                    if (state.watchedQuery) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Avvisami quando il prezzo scende sotto quello attuale",
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = viewModel::submitSearch,
                    modifier = Modifier.padding(start = 4.dp).size(48.dp),
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = "Cerca")
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SortMenu(state.filters.sortMode, viewModel::selectSortMode)
                CompactFilter(
                    icon = Icons.Rounded.Eco,
                    description = "Solo prodotti sostenibili",
                    selected = state.filters.sustainableOnly,
                    onClick = { viewModel.setSustainableOnly(!state.filters.sustainableOnly) },
                )
                StoreMenu(
                    stores = state.availableStores,
                    websites = state.storeWebsites,
                    selected = state.selectedStore,
                    onSelected = viewModel::selectStore,
                )
                IconButton(
                    onClick = {
                        preferredReportStoreId = null
                        viewModel.loadReportStores()
                        showPriceDialog = true
                    },
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Rounded.AddCircle, contentDescription = "Aggiungi un prezzo visto in negozio o al mercato")
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = {
                        viewModel.selectSortMode(
                            if (state.filters.sortMode == SortMode.PRICE) SortMode.SMART else SortMode.PRICE,
                        )
                    },
                    shape = MaterialTheme.shapes.small,
                    color = if (state.filters.sortMode == SortMode.PRICE) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier.semantics {
                        contentDescription =
                            if (state.filters.sortMode == SortMode.PRICE) {
                                "Disattiva ordine per costo minimo. Totale stimato per ${state.matchedShoppingItems} di ${state.pendingShoppingItems} articoli della lista"
                            } else {
                                "Ordina per costo minimo. Totale stimato per ${state.matchedShoppingItems} di ${state.pendingShoppingItems} articoli della lista"
                            }
                    },
                ) {
                    Text(
                        text = NumberFormat.getCurrencyInstance(Locale.ITALY).format(state.shoppingTotal),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }

            state.selectedStore?.let { store ->
                InputChip(
                    selected = true,
                    onClick = { viewModel.selectStore(null) },
                    label = { Text(store, maxLines = 1) },
                    avatar = { StoreFilterLogo(store, state.storeWebsites[store]) },
                    trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = "Rimuovi filtro $store", modifier = Modifier.size(18.dp)) },
                )
            }

            if (state.manualCartItems > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconToggleButton(
                        checked = state.showSelectedOnly,
                        onCheckedChange = { viewModel.toggleSelectedOnly() },
                    ) {
                        Icon(
                            Icons.Rounded.ShoppingBasket,
                            contentDescription = if (state.showSelectedOnly) "Mostra tutti i prodotti" else "Mostra solo i prodotti selezionati",
                            tint = if (state.showSelectedOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(state.manualCartItems.toString(), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = viewModel::clearManualCart) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "Svuota prodotti selezionati")
                    }
                }
            }

            if (state.syncProgress != null || state.enriching) {
                // Background refresh: thin, non-blocking; results keep working meanwhile.
                val progress = state.syncProgress
                if (progress != null && progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.completed.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth().height(2.dp)
                            .semantics { contentDescription = "Aggiornamento prezzi ${progress.completed} di ${progress.total}" },
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp)
                            .semantics { contentDescription = "Aggiornamento prezzi in corso" },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when {
                state.isLoading && state.offers.isEmpty() && state.localAlternatives.isEmpty() -> CircularProgressIndicator()
                else -> LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.filters.sustainableOnly || state.selectedLocalStoreId != null) {
                        state.bestEcoEstimatedPlan?.let { plan ->
                            item { EcoEstimateCard(plan) }
                        }
                    }
                    // Local shops without a public price must remain discoverable: placing
                    // them after thousands of offers made them effectively invisible.
                    if (state.localAlternatives.isNotEmpty() && !state.showSelectedOnly) {
                        item {
                            Text(
                                "Mercati e botteghe vicini",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        items(items = state.localAlternatives, key = { "local-${it.id}" }) { store ->
                            LocalAlternativeCard(
                                store = store,
                                estimate = state.ecoEstimatedPlans[store.id],
                                assessment = state.storeAssessments[store.id],
                                selected = state.selectedLocalStoreId == store.id,
                                onSelect = { viewModel.selectLocalStore(store.id) },
                                onNavigate = { openNavigation(context, store) },
                                onAddPrice = {
                                    preferredReportStoreId = store.id
                                    viewModel.loadReportStores()
                                    showPriceDialog = true
                                },
                            )
                        }
                    }
                    if (state.offers.isEmpty()) item { EmptyResults(hasAlternatives = state.localAlternatives.isNotEmpty()) }
                    state.offerGroups.forEach { group ->
                        item(key = "store-header-${group.key}") {
                            StoreGroupHeader(group)
                        }
                        items(items = group.offers, key = Offer::id) { offer ->
                            OfferCard(
                                offer = offer,
                                selected = offer.id in state.selectedOfferIds,
                                showSustainabilityScore = state.filters.sustainableOnly,
                                allowLegacyImage = offer.productImageUrl?.let { imageUrl ->
                                    imageUrl !in state.ambiguousImageUrls &&
                                        ProductImageMatcher.matchesLegacyImage(offer.productName, imageUrl)
                                } == true,
                                onToggle = { viewModel.toggleOfferSelection(offer) },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
    }
}

@Composable
private fun StoreGroupHeader(group: StoreOfferGroup) {
    val nearest = group.offers.minOfOrNull(Offer::distanceMeters) ?: 0
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoreFilterLogo(group.storeName, group.offers.firstOrNull()?.storeWebsite)
        Text(
            group.storeName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        Text(
            "${group.offers.size} · ${distanceLabel(nearest)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Markets (Campagna Amica, rionali) and many small shops publish no prices online:
 * the user can record the price and quality seen on the stall or shelf.
 */
@Composable
private fun UserPriceDialog(
    stores: List<NearbyStore>,
    preferredStoreId: String?,
    initialProduct: String,
    onDismiss: () -> Unit,
    onSave: (storeId: String, product: String, price: String, quality: Int?) -> Unit,
) {
    var storeId by remember(preferredStoreId) { mutableStateOf(preferredStoreId) }
    var product by remember(initialProduct) { mutableStateOf(initialProduct) }
    var price by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    val selected = stores.firstOrNull { it.id == storeId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Prezzo visto in negozio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Mercati e piccole botteghe spesso non pubblicano i prezzi online: salvali tu e " +
                        "l'app li userà nei risultati e nel calcolo della spesa.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            selected?.let { "${it.name} · ${distanceLabel(it.distanceMeters)}" }
                                ?: if (stores.isEmpty()) "Nessun negozio trovato: attendi l'aggiornamento" else "Scegli negozio o mercato",
                            maxLines = 1,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        stores.forEach { store ->
                            DropdownMenuItem(
                                text = { Text("${store.name} · ${distanceLabel(store.distanceMeters)}", maxLines = 1) },
                                onClick = {
                                    storeId = store.id
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = product,
                    onValueChange = { product = it },
                    label = { Text("Prodotto (es. Pomodori 1 kg)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { value -> price = value.filter { it.isDigit() || it == ',' || it == '.' }.take(8) },
                    label = { Text("Prezzo €") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Qualità", style = MaterialTheme.typography.labelLarge)
                Row {
                    (1..5).forEach { value ->
                        IconButton(onClick = { quality = if (quality == value) 0 else value }, modifier = Modifier.size(40.dp)) {
                            Icon(
                                if (value <= quality) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                contentDescription = "Qualità $value su 5",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = storeId != null && product.isNotBlank() && price.isNotBlank(),
                onClick = { storeId?.let { onSave(it, product, price, quality.takeIf { q -> q > 0 }) } },
            ) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun HeaderMetric(icon: ImageVector, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 3.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 5.dp)
            .widthIn(max = 76.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

private enum class HomeHelpTopic(val title: String, val body: String) {
    WELCOME(
        "La Home in breve",
        "Cerca un prodotto oppure usa la lista. Tocca il totale verde per ordinare dal prezzo più basso; la foglia privilegia sostenibilità e vicinanza. I mercati senza listino mostrano sempre una stima indicata da ~. Tocca logo e riepiloghi in alto per rileggere questi aiuti.",
    ),
    PRODUCT_COST(
        "Costo prodotti",
        "Somma i prodotti scelti. Se selezioni un mercato senza listino pubblico usa una stima prudente basata sui prezzi comparabili; il dettaglio la indica con ~.",
    ),
    TRAVEL_COST(
        "Costo dello spostamento",
        "Stima andata e ritorno usando distanza, mezzo, consumo e prezzo automatico del carburante configurati nell’app.",
    ),
    EMISSIONS(
        "Impatto CO₂",
        "Stima le emissioni dello spostamento. Sotto 1 kg usa i grammi; da 1 kg in su usa i chilogrammi per restare leggibile.",
    ),
}

@Composable
private fun HomeHelpDialog(topic: HomeHelpTopic, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
        title = { Text(topic.title) },
        text = { Text(topic.body) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ho capito") } },
    )
}

/** Keeps the compact header readable without dropping the measurement unit. */
private fun compactEmission(emissionKg: Double): String =
    if (emissionKg < 1.0) {
        "%.0f g".format(Locale.ITALY, emissionKg * 1_000)
    } else {
        "%.1f kg".format(Locale.ITALY, emissionKg)
    }

private fun compactMoney(value: Double): String = when {
    value < 1_000.0 -> NumberFormat.getCurrencyInstance(Locale.ITALY).format(value)
    value < 1_000_000.0 -> "%.1fk €".format(Locale.ITALY, value / 1_000.0)
    else -> "%.1fM €".format(Locale.ITALY, value / 1_000_000.0)
}

@Composable
private fun HomeInsights(state: SearchUiState) {
    val money = NumberFormat.getCurrencyInstance(Locale.ITALY)
    val coverage = if (state.pendingShoppingItems == 0) 0 else state.matchedShoppingItems * 100 / state.pendingShoppingItems
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Insight(Icons.Rounded.ShoppingBasket, state.activeCartTotal.let(money::format), "Selezionati")
        Insight(Icons.Rounded.LocalGasStation, state.activeTravelCost.let(money::format), "Carburante")
        val emission = if (state.activeEmissionKg < 1.0) {
            "%.0f g".format(Locale.ITALY, state.activeEmissionKg * 1_000)
        } else {
            "%.1f kg".format(Locale.ITALY, state.activeEmissionKg)
        }
        Insight(Icons.Rounded.Eco, emission, "CO₂")
        Insight(Icons.Rounded.Check, "$coverage%", "Lista")
        Insight(Icons.Rounded.ShoppingBasket, state.bestBasketTotal?.let(money::format) ?: "—", "Carrello")
        Insight(Icons.Rounded.CalendarMonth, state.expiringToday.toString(), "Scade oggi")
        Insight(Icons.AutoMirrored.Rounded.TrendingDown, state.priceDrops.toString(), "In calo")
        Insight(Icons.AutoMirrored.Rounded.ShowChart, state.historicalLows.toString(), "Minimi")
        Insight(Icons.Rounded.NearMe, state.nearbyOffers.toString(), "Vicino")
        Insight(Icons.Rounded.Storefront, state.oneStopTotal?.let(money::format) ?: "—", "1 fermata")
        Insight(
            Icons.Rounded.Eco,
            state.sustainableTotal?.let(money::format)
                ?: state.bestEcoEstimatedPlan?.let { "~${money.format(it.total)}" }
                ?: "—",
            "Eco",
        )
        Insight(Icons.Rounded.WarningAmber, state.unavailableSources.toString(), "Fonti")
        Insight(Icons.Rounded.Favorite, state.reachedTargets.toString(), "Obiettivi")
    }
}

@Composable
private fun Insight(icon: ImageVector, value: String, label: String) {
    Card(modifier = Modifier.width(104.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(19.dp))
            Text(value, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun EmptyResults(hasAlternatives: Boolean) {
    Text(
        text = if (hasAlternatives) {
            "Nessun prezzo pubblico: considera le alternative locali qui sotto."
        } else {
            "Nessuna offerta trovata. Prova un altro prodotto."
        },
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun LocalAlternativeCard(
    store: NearbyStore,
    estimate: EcoBasketEstimate?,
    assessment: StoreAssessment?,
    selected: Boolean,
    onSelect: () -> Unit,
    onNavigate: () -> Unit,
    onAddPrice: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StoreFilterLogo(store.name, store.website)
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(store.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(distanceLabel(store.distanceMeters), style = MaterialTheme.typography.bodySmall)
                    if (store.sustainabilityScore > 0) {
                        Icon(
                            Icons.Rounded.Eco,
                            contentDescription = "Foglia ${store.sustainabilityScore} su 100",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp).size(16.dp),
                        )
                        Text(
                            store.sustainabilityScore.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
                Text(
                    store.sustainabilityReasons.firstOrNull() ?: "Prezzo non ancora disponibile",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                estimate?.let {
                    Text(
                        "Carrello stimato ~${NumberFormat.getCurrencyInstance(Locale.ITALY).format(it.total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
                assessment?.takeIf { it.qualityScore != null || it.valueScore != null }?.let { score ->
                    val priceLabel = when (score.priceTendency) {
                        PriceTendency.LOW -> "prezzi bassi"
                        PriceTendency.AVERAGE -> "prezzi medi"
                        PriceTendency.HIGH -> "prezzi alti"
                        null -> null
                    }
                    Text(
                        listOfNotNull(
                            score.qualityScore?.let { "★ qualità $it" },
                            score.valueScore?.let { "€ convenienza $it" },
                            priceLabel,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                    )
                }
            }
            IconButton(onClick = onSelect, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.Eco,
                    contentDescription = if (selected) "Rimuovi ${store.name} dalla scelta eco" else "Scegli ${store.name} per la spesa eco",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onAddPrice, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.AddCircle, contentDescription = "Aggiungi prezzo per ${store.name}")
            }
            IconButton(onClick = onNavigate, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Navigation, contentDescription = "Indicazioni per ${store.name}")
            }
        }
    }
}

@Composable
private fun EcoEstimateCard(plan: EcoBasketEstimate) {
    val money = NumberFormat.getCurrencyInstance(Locale.ITALY)
    val emissions = if (plan.emissionKg < 1.0) {
        "%.0f g CO₂".format(Locale.ITALY, plan.emissionKg * 1_000)
    } else {
        "%.1f kg CO₂".format(Locale.ITALY, plan.emissionKg)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Eco, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    "Piano eco stimato · ${plan.storeName}",
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                Text("~${money.format(plan.total)}", fontWeight = FontWeight.Bold)
            }
            Text(
                "${plan.items.size} prodotti ~${money.format(plan.productsTotal)} + viaggio ${money.format(plan.travelCost)} · $emissions",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
            )
            Text(
                "Foglia ${plan.greenScore}/100 · qualità ~${plan.qualityScore}/100" +
                    (if (plan.qualityFromReviews) " da recensioni" else " stimata") +
                    if (plan.fairTrade) " · equosolidale" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1,
            )
            Text(
                "Stima da mediane locali, non è un prezzo pubblicato dal negozio" +
                    if (plan.lowConfidenceItems > 0) " · ${plan.lowConfidenceItems} voci con pochi dati" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

private fun openNavigation(context: android.content.Context, store: NearbyStore) {
    val maps = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("google.navigation:q=${store.latitude},${store.longitude}&mode=d"),
    ).apply { setPackage("com.google.android.apps.maps") }
    val fallback = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("geo:${store.latitude},${store.longitude}?q=${store.latitude},${store.longitude}(${Uri.encode(store.name)})"),
    )
    try {
        context.startActivity(if (maps.resolveActivity(context.packageManager) != null) maps else fallback)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${store.latitude},${store.longitude}"),
            ),
        )
    }
}

@Composable
private fun OfferCard(
    offer: Offer,
    selected: Boolean,
    showSustainabilityScore: Boolean,
    allowLegacyImage: Boolean,
    onToggle: () -> Unit,
) {
    val euroFormatter = NumberFormat.getCurrencyInstance(Locale.ITALY)

    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            ProductImage(offer, allowLegacyImage)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = offer.productName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            offer.brand?.let { Text(text = it) }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = euroFormatter.format(offer.price),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(distanceLabel(offer.distanceMeters))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                StoreLogo(offer)
                Text(
                    text = offer.storeName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
                )
                if (offer.storeSustainabilityScore >= StoreSustainabilityResult.LEAF_THRESHOLD) {
                    Icon(
                        Icons.Rounded.Eco,
                        contentDescription = "Negozio sostenibile, foglia ${offer.storeSustainabilityScore} su 100: " +
                            offer.storeSustainabilityReasons.joinToString(", "),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp).size(16.dp),
                    )
                }
                if (selected) {
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Nel carrello", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                if (showSustainabilityScore) SustainabilityScoreBadge(offer.sustainabilityScore())
                if (offer.promotional) ProductBadge(Icons.Rounded.LocalOffer, "Prezzo in offerta")
                offer.qualityScore?.let { score ->
                    ProductBadge(Icons.Rounded.Star, "Qualità ${"%.1f".format(Locale.ITALY, score)} su 5")
                }
                if (offer.loyaltyRequired) ProductBadge(Icons.Rounded.CreditCard, "Richiede carta fedeltà")
                offer.minimumAge?.let { ProductBadge(Icons.Rounded.Elderly, "Riservata a clienti di almeno $it anni") }
                if (offer.flashOffer) ProductBadge(Icons.Rounded.Bolt, "Offerta lampo")
                ProductBadge(Icons.Rounded.CalendarMonth, "Valida ${offer.activeDays.joinToString { it.shortLabel }}")
                offer.sustainabilityLabels.take(3).forEach { label ->
                    ProductBadge(label.badgeIcon(), label)
                }
            }
        }
    }
}

}

@Composable
private fun SustainabilityScoreBadge(score: Int) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Eco, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("$score", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 3.dp))
        }
    }
}

private fun ProductImageKey.drawableResource(): Int = when (this) {
    ProductImageKey.MILK -> R.drawable.product_milk
    ProductImageKey.YOGURT -> R.drawable.product_yogurt
    ProductImageKey.CHEESE -> R.drawable.product_cheese
    ProductImageKey.PASTA -> R.drawable.product_pasta
    ProductImageKey.RICE -> R.drawable.product_rice
    ProductImageKey.PRODUCE -> R.drawable.product_produce
    ProductImageKey.FRUIT -> R.drawable.product_fruit
    ProductImageKey.VEGETABLE -> R.drawable.product_vegetable
    ProductImageKey.LEGUMES -> R.drawable.product_legumes
    ProductImageKey.EGGS -> R.drawable.product_eggs
    ProductImageKey.MEAT -> R.drawable.product_meat
    ProductImageKey.FISH -> R.drawable.product_fish
    ProductImageKey.COFFEE -> R.drawable.product_coffee
    ProductImageKey.WATER -> R.drawable.product_water
    ProductImageKey.OIL -> R.drawable.product_oil
    ProductImageKey.BAKERY -> R.drawable.product_bakery
    ProductImageKey.HOUSEHOLD -> R.drawable.product_household
    ProductImageKey.OTHER -> R.drawable.product_generic
}

@Composable
private fun StoreMenu(
    stores: List<String>,
    websites: Map<String, String?>,
    selected: String?,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Rounded.Storefront,
                contentDescription = selected?.let { "Filtra per $it" } ?: "Filtra per punto vendita",
                tint = if (selected != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 190.dp, max = 300.dp).heightIn(max = 300.dp),
        ) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Storefront, contentDescription = null, modifier = Modifier.size(24.dp))
                        Text("Tutti", modifier = Modifier.padding(start = 10.dp))
                    }
                },
                onClick = { onSelected(null); expanded = false },
            )
            stores.forEach { store ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StoreFilterLogo(store, websites[store])
                            Text(store, modifier = Modifier.padding(start = 10.dp), maxLines = 1)
                        }
                    },
                    onClick = { onSelected(store); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun StoreFilterLogo(storeName: String, website: String?) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, website, storeName) {
        value = withContext(Dispatchers.IO) { StoreLogoResolver.load(storeName, website) }
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(28.dp)) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Marchio $storeName", modifier = Modifier.padding(3.dp))
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(storeName.take(1).uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ProductImage(offer: Offer, allowLegacyImage: Boolean) {
    // Several flyer pages expose a generic/banner image as if it belonged to every
    // JSON-LD product. Prefer a truthful category illustration when the parsed title
    // is not specific enough to validate the remote image.
    val trustedRemoteUrl = offer.productImageUrl.takeIf {
        (offer.productImageVerified || offer.imageKey != ProductImageKey.OTHER || allowLegacyImage) &&
            offer.productName.length >= 3
    }
    val bitmap by networkBitmap(trustedRemoteUrl)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = offer.productName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(92.dp).clip(MaterialTheme.shapes.medium),
        )
    } else {
        Image(
            painter = painterResource(offer.imageKey.drawableResource()),
            contentDescription = offer.productName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(92.dp).clip(MaterialTheme.shapes.medium),
        )
    }
}

@Composable
private fun StoreLogo(offer: Offer) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, offer.storeWebsite, offer.storeName) {
        value = withContext(Dispatchers.IO) {
            StoreLogoResolver.load(offer.storeName, offer.storeWebsite)
        }
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(24.dp)) {
        if (bitmap != null) {
            Image(bitmap = bitmap!!.asImageBitmap(), contentDescription = "Marchio ${offer.storeName}", modifier = Modifier.padding(3.dp))
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(offer.storeName.take(1).uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun networkBitmap(url: String?): androidx.compose.runtime.State<android.graphics.Bitmap?> {
    val targetPx = with(androidx.compose.ui.platform.LocalDensity.current) { 92.dp.roundToPx() }
    return produceState(initialValue = url?.let { BitmapLoader.cached(it, targetPx) }, url, targetPx) {
        if (url != null && value == null) value = BitmapLoader.load(url, targetPx)
    }
}

private fun SortMode.icon(): ImageVector = when (this) {
    SortMode.SMART -> Icons.Rounded.AutoAwesome
    SortMode.PRICE -> Icons.Rounded.Euro
    SortMode.DISTANCE -> Icons.Rounded.NearMe
    SortMode.QUALITY -> Icons.Rounded.Star
}

@Composable
private fun SortMenu(selected: SortMode, onSelected: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(selected.icon(), contentDescription = "Ordina per ${selected.label}", modifier = Modifier.size(20.dp))
                Text(selected.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp), maxLines = 1)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    leadingIcon = { Icon(mode.icon(), contentDescription = null) },
                    onClick = {
                        onSelected(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CompactFilter(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = CircleShape,
    ) {
        IconToggleButton(
            checked = selected,
            onCheckedChange = { onClick() },
            modifier = Modifier.size(44.dp),
        ) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ProductBadge(icon: ImageVector, description: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = CircleShape,
        modifier = Modifier
            .size(30.dp)
            .semantics { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

private fun String.badgeIcon(): ImageVector = when {
    contains("animale", ignoreCase = true) || contains("aperto", ignoreCase = true) -> Icons.Rounded.Pets
    contains("bio", ignoreCase = true) || contains("locale", ignoreCase = true) ||
        contains("km 0", ignoreCase = true) -> Icons.Rounded.Eco
    contains("ital", ignoreCase = true) -> Icons.Rounded.Public
    contains("stagion", ignoreCase = true) -> Icons.Rounded.WbSunny
    contains("artigian", ignoreCase = true) -> Icons.Rounded.Handyman
    else -> Icons.Rounded.Check
}

private fun distanceLabel(distanceMeters: Int): String = when {
    distanceMeters < 1_000 -> "$distanceMeters m"
    else -> "${"%.1f".format(Locale.ITALY, distanceMeters / 1_000.0)} km"
}
