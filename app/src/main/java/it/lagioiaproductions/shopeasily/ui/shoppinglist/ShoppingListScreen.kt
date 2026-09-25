package it.lagioiaproductions.shopeasily.ui.shoppinglist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.lagioiaproductions.shopeasily.data.model.StoreChannel
import it.lagioiaproductions.shopeasily.domain.BasketPlan
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ShoppingListScreen(
    modifier: Modifier = Modifier,
    viewModel: ShoppingListViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var newItem by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Lista della spesa", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${state.preferences.vehicleType.label} · ${state.preferences.fuelType.label}")
        OutlinedTextField(
            value = newItem,
            onValueChange = { newItem = it },
            label = { Text("Nuovo articolo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                viewModel.addItem(newItem)
                newItem = ""
            },
            enabled = newItem.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Aggiungi") }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.selectedItems.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Home, contentDescription = null)
                        Text(
                            "Selezionati dalla Home",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp).weight(1f),
                        )
                        IconButton(onClick = viewModel::clearSelectedItems) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Rimuovi tutti i prodotti selezionati dalla Home")
                        }
                    }
                }
                items(state.selectedItems, key = SelectedShoppingItem::offerId) { item ->
                    SelectedItemCard(item, onRemove = { viewModel.removeSelectedItem(item.offerId) })
                }
            }
            if (state.items.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.EditNote, contentDescription = null)
                        Text(
                            "Aggiunti a mano",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
            items(state.items, key = ShoppingListItem::name) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = item.checked,
                        onCheckedChange = { viewModel.setChecked(item.name, it) },
                    )
                    Text(item.name, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.removeItem(item.name) }) { Text("×") }
                }
            }
            if (state.plans.isNotEmpty()) {
                item {
                    Text("Migliori combinazioni", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                items(state.plans) { plan -> BasketPlanCard(plan) }
            }
        }
        Button(
            onClick = viewModel::optimize,
            enabled = state.items.isNotEmpty() || state.selectedItems.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Trova il carrello migliore") }
    }
}

@Composable
private fun SelectedItemCard(item: SelectedShoppingItem, onRemove: () -> Unit) {
    val currency = NumberFormat.getCurrencyInstance(Locale.ITALY)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (item.promotional) Icons.Rounded.LocalOffer else Icons.Rounded.Home,
                contentDescription = if (item.promotional) "In offerta" else "Selezionato dalla Home",
            )
            Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold)
                Text(item.storeName, style = MaterialTheme.typography.bodySmall)
            }
            Text(currency.format(item.price), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Rimuovi ${item.name}")
            }
        }
    }
}

@Composable
private fun BasketPlanCard(plan: BasketPlan) {
    val currency = NumberFormat.getCurrencyInstance(Locale.ITALY)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                plan.stores.joinToString(" + ") { it.name },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            plan.assignments.forEach { assignment ->
                val promo = if (assignment.catalogItem.promotional) " · offerta" else ""
                Text("${assignment.requestedItem}: ${currency.format(assignment.catalogItem.price)}$promo")
            }
            Text("Totale stimato: ${currency.format(plan.monetaryTotal)}", fontWeight = FontWeight.Bold)
            if (plan.serviceCosts > 0) Text("Consegna: ${currency.format(plan.serviceCosts)}")
            if (plan.estimatedTravelCost > 0) Text("Carburante stimato: ${currency.format(plan.estimatedTravelCost)}")
            Text("Impatto trasporto stimato: ${"%.2f".format(plan.estimatedEmissionKgCo2)} kg CO₂")
            plan.stores.filter { it.channel == StoreChannel.ONLINE }.forEach { store ->
                val laborLabel = store.laborScore?.let { "${(it * 100).toInt()}/100" } ?: "non disponibile"
                Text("Tutela lavoro ${store.name}: $laborLabel", style = MaterialTheme.typography.bodySmall)
                store.laborScoreSource?.let { Text("Fonte: $it", style = MaterialTheme.typography.bodySmall) }
            }
            if (plan.stores.any { it.channel == StoreChannel.ONLINE && it.laborScore == null }) {
                Text("Dati sul lavoro del corriere non verificati", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
