package it.lagioiaproductions.shopeasly.ui.shoppinglist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class ShoppingItem(val name: String, val checked: Boolean = false)

@Composable
fun ShoppingListScreen(modifier: Modifier = Modifier) {
    var newItem by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf(ShoppingItem("Latte"), ShoppingItem("Pasta")) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Lista della spesa", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Aggiungi gli articoli che il motore dovrà ottimizzare.")
        OutlinedTextField(
            value = newItem,
            onValueChange = { newItem = it },
            label = { Text("Nuovo articolo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val value = newItem.trim()
                if (value.isNotEmpty()) {
                    items.add(ShoppingItem(value))
                    newItem = ""
                }
            },
            enabled = newItem.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Aggiungi")
        }
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = item.checked,
                    onCheckedChange = { checked -> items[index] = item.copy(checked = checked) },
                )
                Text(item.name)
            }
        }
        Button(
            onClick = {},
            enabled = items.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Trova il carrello migliore")
        }
    }
}
