package it.lagioiaproductions.shopeasily.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import it.lagioiaproductions.shopeasily.ui.map.MapScreen
import it.lagioiaproductions.shopeasily.ui.search.SearchScreen
import it.lagioiaproductions.shopeasily.ui.search.SearchViewModel
import it.lagioiaproductions.shopeasily.ui.settings.SettingsScreen
import it.lagioiaproductions.shopeasily.ui.shoppinglist.ShoppingListScreen

private enum class Destination(
    val label: String,
    val icon: ImageVector,
    val description: String,
) {
    SEARCH("Home", Icons.Rounded.Home, "Home e ricerca"),
    MAP("Mappa", Icons.Rounded.Map, "Mappa dei negozi"),
    LIST("Lista", Icons.Rounded.Checklist, "Lista della spesa"),
    SETTINGS("Altro", Icons.Rounded.Settings, "Impostazioni"),
}

@Composable
fun ShopEasilyApp() {
    var destination by rememberSaveable { mutableStateOf(Destination.SEARCH) }
    val searchViewModel: SearchViewModel = viewModel()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(item.icon, contentDescription = item.description) },
                        label = { Text(item.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
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
