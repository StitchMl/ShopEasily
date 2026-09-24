package it.lagioiaproductions.shopeasly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import it.lagioiaproductions.shopeasly.ui.search.SearchScreen
import it.lagioiaproductions.shopeasly.ui.search.SearchViewModel
import it.lagioiaproductions.shopeasly.ui.theme.ShopEaslyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShopEaslyTheme {
                val searchViewModel: SearchViewModel = viewModel()
                SearchScreen(viewModel = searchViewModel)
            }
        }
    }
}
