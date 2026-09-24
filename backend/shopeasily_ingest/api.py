from pathlib import Path

from fastapi import FastAPI, HTTPException, Query

from .collector import collect
from .local_shops import find_local_food_shops
from .models import ImportedOffer, LocalShop
from .fuel_prices import italian_fuel_prices

app = FastAPI(title="ShopEasily ingestion API", version="0.1.0")
CONFIG = Path(__file__).parents[1] / "sources.yaml"


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/ingest", response_model=list[ImportedOffer])
async def ingest() -> list[ImportedOffer]:
    if not CONFIG.exists():
        raise HTTPException(503, "Crea backend/sources.yaml da sources.example.yaml")
    return await collect(CONFIG)


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
