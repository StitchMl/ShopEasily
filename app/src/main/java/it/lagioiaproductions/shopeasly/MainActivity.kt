package it.lagioiaproductions.shopeasly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import it.lagioiaproductions.shopeasly.ui.navigation.ShopEaslyApp
import it.lagioiaproductions.shopeasly.ui.theme.ShopEaslyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShopEaslyTheme {
                ShopEaslyApp()
            }
        }
    }
}
