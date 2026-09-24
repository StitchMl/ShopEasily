package it.lagioiaproductions.shopeasly.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.lagioiaproductions.shopeasly.data.model.Offer
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "ShopEasly",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "Confronta prezzi, distanza e sostenibilità",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::updateQuery,
                label = { Text("Cerca un prodotto") },
                placeholder = { Text("Latte, pasta, frutta…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submitSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = viewModel::submitSearch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("Confronta offerte")
            }

            Spacer(Modifier.height(16.dp))

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
        Column(modifier = Modifier.padding(16.dp)) {
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
            offer.qualityScore?.let { score ->
                Text("Qualità: ${"%.1f".format(Locale.ITALY, score)}/5")
            }
            if (offer.loyaltyRequired) {
                Text(
                    text = "Richiede carta fedeltà",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (offer.sustainabilityLabels.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    offer.sustainabilityLabels.take(2).forEach { label ->
                        AssistChip(onClick = {}, label = { Text(label) })
                    }
                }
            }
        }
    }
}

private fun distanceLabel(distanceMeters: Int): String = when {
    distanceMeters < 1_000 -> "$distanceMeters m"
    else -> "${"%.1f".format(Locale.ITALY, distanceMeters / 1_000.0)} km"
}
