import json
from pathlib import Path

from .models import ImportedOffer


class CatalogStore:
    def __init__(self, path: Path):
        self.path = path

    def load(self) -> list[ImportedOffer]:
        if not self.path.exists():
            return []
        return [ImportedOffer.model_validate(item) for item in json.loads(self.path.read_text(encoding="utf-8"))]

    def merge(self, offers: list[ImportedOffer]) -> list[ImportedOffer]:
        current = self.load()
        merged = {
            (item.source_id, item.store.casefold(), item.product_name.casefold(), item.price): item
            for item in current + offers
        }
        result = list(merged.values())
        temporary = self.path.with_suffix(".tmp")
        temporary.write_text(
            json.dumps([item.model_dump(mode="json") for item in result], ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        temporary.replace(self.path)
        return result
