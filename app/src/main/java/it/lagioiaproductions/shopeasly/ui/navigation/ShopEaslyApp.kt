package it.lagioiaproductions.shopeasly.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import it.lagioiaproductions.shopeasly.ui.map.MapScreen
import it.lagioiaproductions.shopeasly.ui.search.SearchScreen
import it.lagioiaproductions.shopeasly.ui.search.SearchViewModel
import it.lagioiaproductions.shopeasly.ui.settings.SettingsScreen
import it.lagioiaproductions.shopeasly.ui.shoppinglist.ShoppingListScreen

private enum class Destination(
    val label: String,
    val symbol: String,
) {
    SEARCH("Cerca", "⌕"),
    MAP("Mappa", "⌖"),
    LIST("Lista", "✓"),
    SETTINGS("Impostazioni", "⚙"),
}

@Composable
fun ShopEaslyApp() {
    var destination by rememberSaveable { mutableStateOf(Destination.SEARCH) }
    val searchViewModel: SearchViewModel = viewModel()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {
                            Text(
                                text = item.symbol,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (destination) {
            Destination.SEARCH -> SearchScreen(
                viewModel = searchViewModel,
                modifier = Modifier.padding(innerPadding),
            )
            Destination.MAP -> MapScreen(modifier = Modifier.padding(innerPadding))
            Destination.LIST -> ShoppingListScreen(modifier = Modifier.padding(innerPadding))
            Destination.SETTINGS -> SettingsScreen(modifier = Modifier.padding(innerPadding))
        }
    }
}
