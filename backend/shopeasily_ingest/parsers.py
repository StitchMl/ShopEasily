import io
import re
from dataclasses import dataclass

from bs4 import BeautifulSoup
from pypdf import PdfReader

from .models import ImportedOffer

PRICE = re.compile(r"(?:€\s*)?(\d{1,4}[,.]\d{2})(?:\s*€)?")


@dataclass(frozen=True)
class HtmlSelectors:
    item: str
    name: str
    price: str


def parse_price(text: str) -> float | None:
    match = PRICE.search(text.replace("\xa0", " "))
    return float(match.group(1).replace(",", ".")) if match else None


def parse_html(source_id: str, url: str, store: str, html: str, selectors: HtmlSelectors) -> list[ImportedOffer]:
    soup = BeautifulSoup(html, "html.parser")
    offers: list[ImportedOffer] = []
    for item in soup.select(selectors.item):
        name_node = item.select_one(selectors.name)
        price_node = item.select_one(selectors.price)
        if not name_node or not price_node:
            continue
        price = parse_price(price_node.get_text(" ", strip=True))
        name = name_node.get_text(" ", strip=True)
        if not name or price is None:
            continue
        offers.append(ImportedOffer(
            source_id=source_id, source_url=url, store=store,
            product_name=name, price=price,
            raw_text=item.get_text(" ", strip=True)[:1000],
        ))
    return offers


def parse_pdf(source_id: str, url: str, store: str, content: bytes) -> list[ImportedOffer]:
    """Parser conservativo: associa un prezzo alla riga di testo precedente."""
    reader = PdfReader(io.BytesIO(content))
    lines = [line.strip() for page in reader.pages for line in (page.extract_text() or "").splitlines() if line.strip()]
    offers: list[ImportedOffer] = []
    for index, line in enumerate(lines):
        price = parse_price(line)
        if price is None:
            continue
        name = PRICE.sub("", line).strip(" -–:") or (lines[index - 1] if index else "")
        if len(name) < 2:
            continue
        offers.append(ImportedOffer(
            source_id=source_id, source_url=url, store=store,
            product_name=name[:200], price=price, raw_text=line[:1000],
        ))
    return offers
