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
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Sync
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
        InfoCard(Icons.Rounded.Eco, "Foglia del negozio", "Ogni punto vendita riceve un indice da 0 a 100 calcolato dai dati pubblici di OpenStreetMap e dall’insegna: solo biologico (50), bio in parte (15–30), commercio equo (10–45), vendita diretta del produttore (45), mercato rionale (25), prodotti locali o filiera corta (25), sfuso o rifiuti zero (20–40), contenitori riutilizzabili (10), bottega indipendente di vicinato (10). La foglia verde compare da 40 punti; toccandola TalkBack legge il motivo.")
        InfoCard(Icons.Rounded.Eco, "Punteggio foglia del prodotto", "È un indice da 0 a 100, non una certificazione. Fino a 45 punti dalle etichette del prodotto (bio, equosolidale, benessere animale, filiera locale), fino a 35 dalla foglia del negozio, fino a 15 dalla vicinanza e fino a 5 dalla qualità dei dati. 80–100 è ottimo, 60–79 buono, 40–59 discreto; sotto 40 indica pochi dati o impatto maggiore.")
        InfoCard(Icons.Rounded.Storefront, "Piccole realtà", "Oltre a volantini e cataloghi delle catene, ShopEasily legge i prezzi condivisi su Open Prices (Open Food Facts) per il singolo negozio OpenStreetMap e i cataloghi pubblici WooCommerce e Shopify usati da molte botteghe, aziende agricole e negozi sfusi. Più persone fotografano cartellini su Open Prices, più negozi di quartiere compaiono.")
        InfoCard(Icons.Rounded.Sync, "Aggiornamenti in background", "La raccolta dei prezzi avviene in background con rete disponibile e batteria non scarica, circa ogni 6 ore e quando cambi zona. La Home si aggiorna da sola senza interromperti; la barra sottile in alto indica l’avanzamento.")
        InfoCard(Icons.Rounded.Info, "Filtro foglia", "Esclude i punti vendita con indice green inferiore a 40. Non ripiega su negozi sotto soglia: se mancano prezzi pubblici propone mercati e botteghe idonei presenti nella zona.")
        InfoCard(Icons.Rounded.Euro, "Piano eco stimato", "Il simbolo ~ indica una stima, non un prezzo del negozio. Per ogni articolo usa la mediana dei prezzi comparabili osservati e un margine prudente; se i dati mancano usa una base di categoria e lo segnala. Al totale aggiunge andata e ritorno, costo del mezzo ed emissioni. Le stime non vengono mai salvate come offerte reali.")
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
