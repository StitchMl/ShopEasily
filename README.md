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

Gli asset sono icone originali, minimali e prive di marchi commerciali. Forme e colori sono coerenti in tutte le categorie e restano leggibili anche nelle card più compatte.

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
  <tr>
    <th>Carne</th>
    <th>Forno</th>
    <th>Casa</th>
  </tr>
  <tr>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_meat.png" alt="Categoria carne" width="220" /></td>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_bakery.png" alt="Categoria forno" width="220" /></td>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_household.png" alt="Categoria casa" width="220" /></td>
  </tr>
  <tr><th>Pesce</th><th>Riso</th><th>Legumi</th></tr>
  <tr>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_fish.png" alt="Categoria pesce" width="220" /></td>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_rice.png" alt="Categoria riso" width="220" /></td>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_legumes.png" alt="Categoria legumi" width="220" /></td>
  </tr>
  <tr><th>Uova</th><th>Formaggio</th><th>Yogurt</th></tr>
  <tr>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_eggs.png" alt="Categoria uova" width="220" /></td>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_cheese.png" alt="Categoria formaggio" width="220" /></td>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_yogurt.png" alt="Categoria yogurt" width="220" /></td>
  </tr>
  <tr><th>Caffè</th><th>Acqua</th><th>Olio</th></tr>
  <tr>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_coffee.png" alt="Categoria caffè" width="220" /></td>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_water.png" alt="Categoria acqua" width="220" /></td>
    <td align="center"><img src="app/src/main/res/drawable-nodpi/product_oil.png" alt="Categoria olio" width="220" /></td>
  </tr>
</table>

## Avvio

1. Apri il progetto con Android Studio.
2. Esegui **Sync Project with Gradle Files**.
3. Avvia l'app su un dispositivo o emulatore con accesso a Internet.

Non serve alcun server. Quando la mappa ottiene la posizione, l'app interroga direttamente OpenStreetMap/Overpass, scopre i negozi nel raggio scelto, visita i siti pubblici consentiti, legge JSON-LD e volantini PDF testuali e conserva fino a 10.000 offerte nella memoria privata del telefono. Il catalogo dimostrativo resta disponibile durante la prima sincronizzazione o senza rete.

La mappa usa **MapLibre e OpenStreetMap** e non richiede chiavi API. La configurazione attuale dei tile pubblici è adatta allo sviluppo; prima della pubblicazione va scelto un provider con capacità e condizioni adeguate oppure un servizio ospitato direttamente.

## Raccolta dati autonoma

La raccolta usata dall'app avviene direttamente sul dispositivo:

- trova supermercati, discount, mercati e piccoli negozi da OpenStreetMap/Overpass nel raggio scelto;
- verifica `robots.txt`, identifica l'app e limita numero e dimensione dei documenti;
- importa dati prodotto JSON-LD e PDF testuali pubblicamente accessibili;
- conserva automaticamente fino a 10.000 offerte per funzionare anche offline;
- aggiorna i prezzi medi di benzina, diesel e GPL dai dati aperti MIMIT.

La cartella `backend/` rimane esclusivamente come strumento opzionale di sviluppo e non serve per installare o usare l'app.

Le app dei retailer e le recensioni di Google Maps/Tripadvisor vanno acquisite solo tramite API ufficiali e relative licenze: non vengono aggirati login, CAPTCHA o divieti di scraping. Tripadvisor richiede inoltre attribuzione per i dati della Content API.

## Funzioni presenti

- ricerca e ranking di offerte attive nel giorno corrente;
- scoperta automatica geografica di cataloghi pubblici, sitemap, JSON-LD e volantini PDF;
- immagini prodotto e indicatori di qualità/sostenibilità;
- badge prodotto solo-icon con descrizioni accessibili a TalkBack;
- illustrazioni minimali per categoria; immagini ufficiali del negozio solo quando il feed ne concede esplicitamente il riuso;
- immagine specifica del prodotto dal catalogo pubblico quando disponibile, con fallback locale per categoria;
- filtro per insegna, favicon/marchio dello store e totale stimato degli articoli ancora da acquistare;
- filtro per distanza, carta fedeltà ed età minima;
- profilo con carte fedeltà possedute: un'offerta riservata viene mostrata solo per la catena registrata;
- mappa operativa con posizione, nomi reali OpenStreetMap, distanza, selezione rapida e indicazioni stradali;
- foglia verde solo per punti vendita con tag pubblici bio, filiera locale o fair-trade;
- lista della spesa persistente;
- confronto del carrello, compresi prezzi non promozionali;
- confronto tra punti vendita fisici e servizi online;
- stima di carburante, consegna ed emissioni;
- calcolo del viaggio per mezzo (a piedi, bici, scooter, auto, furgone o camion), alimentazione, consumo e prezzo corrente configurabile;
- indicatori sul lavoro del corriere solo se accompagnati da una fonte;
- notifiche locali deduplicate per offerte lampo.

I prezzi inclusi per il primo avvio sono dati dimostrativi e non dichiarazioni commerciali delle insegne citate. La sincronizzazione sul dispositivo li integra progressivamente con fonti pubbliche verificabili.
