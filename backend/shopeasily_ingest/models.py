from datetime import date
from pydantic import BaseModel, HttpUrl


class ImportedOffer(BaseModel):
    source_id: str
    source_url: HttpUrl
    store: str
    product_name: str
    price: float
    valid_from: date | None = None
    valid_until: date | None = None
    loyalty_required: bool = False
    raw_text: str


class LocalShop(BaseModel):
    osm_id: int
    name: str
    category: str
    latitude: float
    longitude: float
    website: str | None = None
