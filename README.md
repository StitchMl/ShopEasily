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
2. Aggiungi al file locale e non versionato `local.properties`:

   ```properties
   MAPS_API_KEY=la_tua_chiave_maps_android
   ```

3. Nella Google Cloud Console abilita **Maps SDK for Android** e limita la chiave al package `it.lagioiaproductions.shopeasily` e al certificato SHA-1 usato per la build.
4. Esegui **Sync Project with Gradle Files** e avvia l'app su un emulatore con Google Play Services.

Senza una chiave valida le altre sezioni funzionano, ma Google Maps non può caricare le tessere. Il Maps SDK nativo ha attualmente una soglia gratuita, ma Google richiede comunque un progetto Cloud con fatturazione e una chiave limitata. In alternativa si può migrare la schermata a **MapLibre** con dati OpenStreetMap; per una pubblicazione reale bisogna scegliere un provider di tile con capacità adeguata o ospitarlo, perché i server pubblici OSM non sono un CDN gratuito per traffico elevato.

## Backend dati

La cartella `backend/` contiene un servizio FastAPI eseguibile che:

- importa PDF testuali e pagine HTML configurate da `sources.yaml`;
- verifica `robots.txt`, identifica l'app e limita la frequenza;
- espone piccoli negozi (macellerie, fruttivendoli, panifici, aziende agricole e mercati) da OpenStreetMap/Overpass;
- calcola i prezzi mediani giornalieri di benzina, diesel e GPL dal CSV open data MIMIT;
- lascia configurabile il prezzo dell'elettricità.

Le app dei retailer e le recensioni di Google Maps/Tripadvisor vanno acquisite solo tramite API ufficiali e relative licenze: non vengono aggirati login, CAPTCHA o divieti di scraping. Tripadvisor richiede inoltre attribuzione per i dati della Content API.

## Funzioni presenti

- ricerca e ranking di offerte attive nel giorno corrente;
- immagini prodotto e indicatori di qualità/sostenibilità;
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
