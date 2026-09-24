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

Endpoint: `GET /health`, `POST /ingest`, `GET /local-shops?lat=45.46&lon=9.19`, `GET /fuel-prices`.

`/fuel-prices` calcola la mediana nazionale giornaliera di benzina self, diesel self e GPL dal CSV open data MIMIT (IODL 2.0). Nell'app il prezzo non è modificabile manualmente: viene aggiornato online e l'ultimo dato valido resta disponibile offline.

Ogni sito richiede selettori propri. Non aggirare login, CAPTCHA o protezioni; rispettare robots.txt, termini d'uso, copyright e frequenza di accesso. Le app mobili vanno integrate tramite API ufficiali o accordi commerciali, non decompilate. I PDF scansionati richiederanno in seguito un modulo OCR.
