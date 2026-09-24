# ShopEasily

<p align="center">
  <img src="app/src/main/res/drawable-nodpi/shopeasily_logo.png" alt="Logo ShopEasily: lettera E integrata con una foglia" width="220" />
</p>

<p align="center">
  <strong>Risparmio intelligente, qualità e sostenibilità in un'unica spesa.</strong>
</p>

Prototipo Android nativo in Kotlin e Jetpack Compose per confrontare la spesa per prezzo, distanza, qualità e sostenibilità.

## Identità visiva

Il simbolo combina una **E** geometrica con una foglia. La E richiama il nome ShopEasily, mentre la foglia rappresenta prodotti freschi, attenzione ambientale e scelte di consumo responsabili. Il verde scuro comunica affidabilità e il verde brillante evidenzia la componente sostenibile.

### Immagini prodotto

Gli asset sono generici e privi di marchi commerciali, così possono essere usati nelle card dimostrative senza suggerire affiliazioni con produttori reali.

<table>
  <tr>
    <th>Latte</th>
    <th>Pasta</th>
    <th>Ortofrutta</th>
  </tr>
  <tr>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_milk.png" alt="Confezione generica di latte" width="220" /></td>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_pasta.png" alt="Confezione generica di pasta" width="220" /></td>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_produce.png" alt="Cesto di frutta e verdura" width="220" /></td>
  </tr>
</table>

## Avvio

1. Apri il progetto con Android Studio.
2. Esegui **Sync Project with Gradle Files**.
3. Avvia l'app su un dispositivo o emulatore con accesso a Internet.

Per collegare il catalogo raccolto automaticamente, avvia il servizio in `backend/` e imposta in `local.properties`:

```properties
BACKEND_URL=http://10.0.2.2:8000
```

`10.0.2.2` vale per l'emulatore Android. Su un telefono fisico usa l'indirizzo LAN/HTTPS del server. Quando la mappa ottiene la posizione, l'app avvia automaticamente la scoperta nel raggio scelto; la ricerca legge fino a 500 offerte normalizzate e usa il catalogo locale solo se il server non è configurato o non risponde.

La mappa usa **MapLibre e OpenStreetMap** e non richiede chiavi API. La configurazione attuale dei tile pubblici è adatta allo sviluppo; prima della pubblicazione va scelto un provider con capacità e condizioni adeguate oppure un servizio ospitato direttamente.

## Backend dati

La cartella `backend/` contiene un servizio FastAPI eseguibile che:

- importa PDF testuali e pagine HTML configurate da `sources.yaml`;
- verifica `robots.txt`, identifica l'app e limita la frequenza;
- espone piccoli negozi (macellerie, fruttivendoli, panifici, aziende agricole e mercati) da OpenStreetMap/Overpass;
- calcola i prezzi mediani giornalieri di benzina, diesel e GPL dal CSV open data MIMIT;
- conserva automaticamente l'ultimo prezzo medio disponibile per funzionare anche offline.

Le app dei retailer e le recensioni di Google Maps/Tripadvisor vanno acquisite solo tramite API ufficiali e relative licenze: non vengono aggirati login, CAPTCHA o divieti di scraping. Tripadvisor richiede inoltre attribuzione per i dati della Content API.

## Funzioni presenti

- ricerca e ranking di offerte attive nel giorno corrente;
- scoperta automatica geografica di cataloghi pubblici, sitemap, JSON-LD e volantini PDF;
- immagini prodotto e indicatori di qualità/sostenibilità;
- badge prodotto solo-icon con descrizioni accessibili a TalkBack;
- illustrazioni minimali per categoria; immagini ufficiali del negozio solo quando il feed ne concede esplicitamente il riuso;
- filtro per distanza, carta fedeltà ed età minima;
- profilo con carte fedeltà possedute: un'offerta riservata viene mostrata solo per la catena registrata;
- mappa con posizione utente e marker dei punti vendita;
- lista della spesa persistente;
- confronto del carrello, compresi prezzi non promozionali;
- confronto tra punti vendita fisici e servizi online;
- stima di carburante, consegna ed emissioni;
- calcolo del viaggio per mezzo (a piedi, bici, scooter, auto, furgone o camion), alimentazione, consumo e prezzo corrente configurabile;
- indicatori sul lavoro del corriere solo se accompagnati da una fonte;
- notifiche locali deduplicate per offerte lampo.

I cataloghi e le valutazioni presenti sono dati dimostrativi. Prima della produzione devono essere sostituiti da API/backend e fonti verificate.
