from pathlib import Path

from fastapi import FastAPI, HTTPException, Query

from .collector import collect
from .local_shops import find_local_food_shops
from .models import ImportedOffer, LocalShop
from .fuel_prices import italian_fuel_prices
from .catalog_store import CatalogStore
from .discovery import collect_public_url, discover_and_collect

app = FastAPI(title="ShopEasily ingestion API", version="0.1.0")
CONFIG = Path(__file__).parents[1] / "sources.yaml"
CATALOG = CatalogStore(Path(__file__).parents[1] / "catalog.json")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/ingest", response_model=list[ImportedOffer])
async def ingest() -> list[ImportedOffer]:
    if not CONFIG.exists():
        raise HTTPException(503, "Crea backend/sources.yaml da sources.example.yaml")
    return CATALOG.merge(await collect(CONFIG))


@app.post("/discover-and-ingest")
async def discover_ingest(
    lat: float = Query(ge=-90, le=90),
    lon: float = Query(ge=-180, le=180),
    radius_m: int = Query(5000, ge=100, le=20000),
) -> dict[str, object]:
    shops, offers = await discover_and_collect(lat, lon, radius_m)
    catalog = CATALOG.merge(offers)
    return {"shops_found": len(shops), "offers_imported": len(offers), "catalog_size": len(catalog)}


@app.get("/offers", response_model=list[ImportedOffer])
def offers(q: str = "", offset: int = 0, limit: int = Query(100, ge=1, le=500)) -> list[ImportedOffer]:
    normalized = q.strip().casefold()
    catalog = CATALOG.load()
    if normalized:
        catalog = [item for item in catalog if normalized in item.product_name.casefold() or normalized in item.store.casefold()]
    return catalog[offset:offset + limit]


@app.post("/import-public-url", response_model=list[ImportedOffer])
async def import_public_url(url: str, store: str) -> list[ImportedOffer]:
    imported = await collect_public_url(url, store.strip()[:120])
    CATALOG.merge(imported)
    return imported


@app.get("/local-shops", response_model=list[LocalShop])
async def local_shops(
    lat: float = Query(ge=-90, le=90),
    lon: float = Query(ge=-180, le=180),
    radius_m: int = Query(5000, ge=100, le=20000),
) -> list[LocalShop]:
    return await find_local_food_shops(lat, lon, radius_m)


@app.get("/fuel-prices")
async def fuel_prices() -> dict[str, float | str]:
    return await italian_fuel_prices()
