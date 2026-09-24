package it.lagioiaproductions.shopeasily

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import it.lagioiaproductions.shopeasily.ui.navigation.ShopEasilyApp
import it.lagioiaproductions.shopeasily.ui.theme.ShopEasilyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShopEasilyTheme {
                ShopEasilyApp()
            }
        }
    }
}
