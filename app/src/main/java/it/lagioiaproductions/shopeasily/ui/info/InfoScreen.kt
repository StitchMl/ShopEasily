package it.lagioiaproductions.shopeasily.ui.info

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Euro
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun InfoScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") }
            Text("Come funziona", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        InfoCard(Icons.Rounded.Eco, "Punteggio foglia", "È un indice da 0 a 100, non una certificazione. Fino a 60 punti derivano da bio, filiera locale, equosolidale o benessere animale; fino a 30 dalla vicinanza; fino a 10 dalla qualità disponibile. 80–100 è ottimo, 60–79 buono, 40–59 discreto; sotto 40 indica pochi dati o impatto maggiore.")
        InfoCard(Icons.Rounded.Info, "Filtro foglia", "Mantiene la fascia dei prodotti più sostenibili trovati. Se nessuno è perfetto, mostra comunque le alternative migliori. L’ordinamento scelto resta prioritario dentro questa fascia.")
        InfoCard(Icons.Rounded.Euro, "Ordinamenti", "Prezzo usa il prezzo unitario quando disponibile, altrimenti quello della confezione. Distanza usa il tragitto stradale quando reperibile. Qualità usa dati espliciti oppure una stima prudente della completezza della scheda. Consigliati combina prezzo 45%, distanza 20%, qualità 20% e sostenibilità 15%.")
        InfoCard(Icons.Rounded.ShoppingBasket, "Offerte e prezzi normali", "Il cartellino indica una promozione. Senza cartellino è un prezzo ordinario recuperato dal catalogo pubblico: viene comunque confrontato perché può essere più conveniente di un’offerta altrove.")
        InfoCard(Icons.Rounded.Route, "Costo della spesa", "Il totale consigliato unisce prodotti e spostamento. Carburante ed emissioni dipendono da tragitto, veicolo, alimentazione e consumo selezionati. Per gli acquisti online viene considerata la consegna quando il dato è disponibile.")
        InfoCard(Icons.Rounded.Star, "Affidabilità dei dati", "ShopEasily legge cataloghi pubblici, pagine prodotto e volantini. L’OCR viene usato quando il testo non è disponibile e i risultati non plausibili vengono scartati. Prezzi e disponibilità possono cambiare: verifica sempre il negozio prima dell’acquisto.")
    }
}

@Composable
private fun InfoCard(icon: ImageVector, title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
