# Backend di importazione

Servizio iniziale eseguibile per importare PDF testuali e pagine HTML da fonti autorizzate e trovare piccoli negozi alimentari tramite OpenStreetMap/Overpass.

```powershell
cd backend
py -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
Copy-Item sources.example.yaml sources.yaml
uvicorn shopeasily_ingest.api:app --reload
```

Endpoint: `GET /health`, `POST /ingest`, `POST /discover-and-ingest?lat=45.46&lon=9.19&radius_m=5000`, `GET /offers?q=latte`, `GET /local-shops?lat=45.46&lon=9.19`, `GET /fuel-prices`.

La scansione geografica scopre automaticamente tramite OpenStreetMap supermercati, discount, minimarket, mercati e piccoli negozi nel raggio scelto. Segue i siti ufficiali dichiarati in OSM, cerca collegamenti a offerte, volantini, cataloghi e PDF e salva un catalogo deduplicato. Sono applicati limiti di concorrenza, dimensione, dominio e rete per evitare abusi e richieste verso indirizzi privati.

Per le fonti trovate solo parzialmente vengono inoltre consultati `sitemap.xml` e dati strutturati standard `Product/Offer`. Un volantino pubblico non scoperto automaticamente può essere importato con `POST /import-public-url?store=...&url=...`, sempre nel rispetto di robots.txt.

`/fuel-prices` calcola la mediana nazionale giornaliera di benzina self, diesel self e GPL dal CSV open data MIMIT (IODL 2.0). Nell'app il prezzo non è modificabile manualmente: viene aggiornato online e l'ultimo dato valido resta disponibile offline.

Ogni sito richiede selettori propri. Non aggirare login, CAPTCHA o protezioni; rispettare robots.txt, termini d'uso, copyright e frequenza di accesso. Le app mobili vanno integrate tramite API ufficiali o accordi commerciali, non decompilate. I PDF scansionati richiederanno in seguito un modulo OCR.
