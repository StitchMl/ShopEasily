import httpx

from .models import LocalShop

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
USER_AGENT = "ShopEasily/0.1 (+https://github.com/StitchMl/ShopEasily)"


async def find_local_food_shops(latitude: float, longitude: float, radius_m: int = 5000) -> list[LocalShop]:
    """Trova mercati, macellerie, panifici e fruttivendoli dai dati OpenStreetMap."""
    query = f"""[out:json][timeout:25];(
      nwr(around:{radius_m},{latitude},{longitude})[shop~"^(supermarket|convenience|discount|deli|butcher|greengrocer|bakery|farm)$"];
      nwr(around:{radius_m},{latitude},{longitude})[amenity="marketplace"];
    );out center tags;"""
    async with httpx.AsyncClient(headers={"User-Agent": USER_AGENT}, timeout=30) as client:
        response = await client.post(OVERPASS_URL, content=query)
        response.raise_for_status()
    shops: list[LocalShop] = []
    for item in response.json().get("elements", []):
        tags = item.get("tags", {})
        name = tags.get("name")
        point = item.get("center", item)
        if not name or "lat" not in point or "lon" not in point:
            continue
        shops.append(LocalShop(
            osm_id=item["id"], name=name,
            category=tags.get("shop", tags.get("amenity", "food")),
            latitude=point["lat"], longitude=point["lon"],
            website=tags.get("website") or tags.get("contact:website"),
        ))
    return shops
