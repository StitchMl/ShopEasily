package it.lagioiaproductions.shopeasily.ui.search

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Elderly
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WbSunny
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.lagioiaproductions.shopeasily.R
import it.lagioiaproductions.shopeasily.data.model.Offer
import it.lagioiaproductions.shopeasily.data.model.ProductImageKey
import it.lagioiaproductions.shopeasily.domain.SortMode
import java.text.NumberFormat
import java.util.Locale

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.shopeasily_logo),
                    contentDescription = "Logo ShopEasily",
                    modifier = Modifier.size(48.dp),
                )
                Column(Modifier.padding(start = 10.dp)) {
                    Text("ShopEasily", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::updateQuery,
                label = { Text("Cerca un prodotto") },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )

            Button(
                onClick = viewModel::submitSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("Cerca")
            }

            Spacer(Modifier.height(16.dp))

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
                CompactFilter(
                    icon = Icons.Rounded.CreditCard,
                    description = "Offerte delle mie carte fedeltà",
                    selected = state.filters.includeLoyaltyOffers,
                    onClick = {
                        viewModel.setIncludeLoyaltyOffers(!state.filters.includeLoyaltyOffers)
                    },
                )
            }

            Spacer(Modifier.height(8.dp))

            when {
                state.isLoading -> CircularProgressIndicator()
                state.offers.isEmpty() -> EmptyResults()
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items = state.offers, key = Offer::id) { offer ->
                        OfferCard(offer)
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
    }
}

@Composable
private fun EmptyResults() {
    Text(
        text = "Nessuna offerta trovata. Prova un altro prodotto.",
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun OfferCard(offer: Offer) {
    val euroFormatter = NumberFormat.getCurrencyInstance(Locale.ITALY)

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp)) {
            Image(
                painter = painterResource(offer.imageKey.drawableResource()),
                contentDescription = offer.productName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(92.dp)
                    .clip(MaterialTheme.shapes.medium),
            )
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

            Text(
                text = offer.storeName,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
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

private fun ProductImageKey.drawableResource(): Int = when (this) {
    ProductImageKey.MILK -> R.drawable.product_milk
    ProductImageKey.PASTA -> R.drawable.product_pasta
    ProductImageKey.PRODUCE -> R.drawable.product_produce
    ProductImageKey.MEAT -> R.drawable.product_meat
    ProductImageKey.BAKERY -> R.drawable.product_bakery
    ProductImageKey.HOUSEHOLD -> R.drawable.product_household
    ProductImageKey.OTHER -> R.drawable.product_generic
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
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(44.dp)) {
            Icon(selected.icon(), contentDescription = "Ordina per ${selected.label}")
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
