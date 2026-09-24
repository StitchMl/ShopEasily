import asyncio
import ipaddress
import math
import socket
from urllib.parse import urljoin, urlsplit

import httpx
from bs4 import BeautifulSoup
from xml.etree import ElementTree

from .collector import USER_AGENT, robots_allows
from .local_shops import find_local_food_shops
from .models import ImportedOffer, LocalShop
from .parsers import parse_pdf, parse_structured_products

DISCOVERY_WORDS = ("offert", "volantin", "catalog", "promozion", "promo")
MAX_DOCUMENT_BYTES = 20 * 1024 * 1024
MAX_LINKS_PER_STORE = 8


async def _public_url(url: str) -> bool:
    parts = urlsplit(url)
    if parts.scheme not in {"http", "https"} or not parts.hostname:
        return False
    try:
        addresses = await asyncio.to_thread(socket.getaddrinfo, parts.hostname, None)
    except socket.gaierror:
        return False
    return all(ipaddress.ip_address(item[4][0]).is_global for item in addresses)


def _candidate_links(base_url: str, html: str) -> list[str]:
    soup = BeautifulSoup(html, "html.parser")
    links: list[str] = []
    base_host = urlsplit(base_url).hostname
    for anchor in soup.select("a[href]"):
        href = urljoin(base_url, anchor.get("href", ""))
        label = f"{anchor.get_text(' ', strip=True)} {href}".casefold()
        if urlsplit(href).hostname != base_host:
            continue
        if href.lower().endswith(".pdf") or any(word in label for word in DISCOVERY_WORDS):
            if href not in links:
                links.append(href)
    return links[:MAX_LINKS_PER_STORE]


async def _sitemap_candidates(client: httpx.AsyncClient, homepage: str) -> list[str]:
    parts = urlsplit(homepage)
    sitemap = f"{parts.scheme}://{parts.netloc}/sitemap.xml"
    if not await robots_allows(client, sitemap):
        return []
    try:
        response = await client.get(sitemap)
        response.raise_for_status()
        if len(response.content) > 5 * 1024 * 1024:
            return []
        root = ElementTree.fromstring(response.content)
    except (httpx.HTTPError, ElementTree.ParseError):
        return []
    urls = []
    for node in root.iter():
        if not node.tag.endswith("loc") or not node.text:
            continue
        url = node.text.strip()
        if any(word in url.casefold() for word in DISCOVERY_WORDS):
            urls.append(url)
    return urls[:MAX_LINKS_PER_STORE]


async def _scan_store(client: httpx.AsyncClient, shop: LocalShop) -> list[ImportedOffer]:
    if not shop.website or not await _public_url(shop.website):
        return []
    homepage = shop.website
    if not await robots_allows(client, homepage):
        return []
    try:
        response = await client.get(homepage)
        response.raise_for_status()
    except httpx.HTTPError:
        return []
    candidates = _candidate_links(str(response.url), response.text)
    candidates.extend(url for url in await _sitemap_candidates(client, str(response.url)) if url not in candidates)
    candidates = candidates[:MAX_LINKS_PER_STORE]
    offers: list[ImportedOffer] = []
    for index, url in enumerate(candidates):
        if not await _public_url(url) or not await robots_allows(client, url):
            continue
        try:
            document = await client.get(url)
            document.raise_for_status()
            if len(document.content) > MAX_DOCUMENT_BYTES:
                continue
            content_type = document.headers.get("content-type", "").lower()
            if "pdf" in content_type or url.lower().endswith(".pdf"):
                offers.extend(parse_pdf(f"osm-{shop.osm_id}-{index}", url, shop.name, document.content))
            elif "html" in content_type:
                offers.extend(parse_structured_products(
                    f"osm-{shop.osm_id}-{index}", url, shop.name, document.text,
                ))
                for child in _candidate_links(str(document.url), document.text):
                    if child.lower().endswith(".pdf") and await _public_url(child) and await robots_allows(client, child):
                        pdf = await client.get(child)
                        if pdf.is_success and len(pdf.content) <= MAX_DOCUMENT_BYTES:
                            offers.extend(parse_pdf(f"osm-{shop.osm_id}-{index}", child, shop.name, pdf.content))
            await asyncio.sleep(1.5)
        except (httpx.HTTPError, ValueError):
            continue
    return offers


async def discover_and_collect(latitude: float, longitude: float, radius_m: int) -> tuple[list[LocalShop], list[ImportedOffer]]:
    shops = await find_local_food_shops(latitude, longitude, radius_m)
    semaphore = asyncio.Semaphore(3)
    async with httpx.AsyncClient(
        headers={"User-Agent": USER_AGENT}, timeout=30, follow_redirects=True,
    ) as client:
        async def limited(shop: LocalShop) -> list[ImportedOffer]:
            async with semaphore:
                return await _scan_store(client, shop)
        batches = await asyncio.gather(*(limited(shop) for shop in shops))
    enriched: list[ImportedOffer] = []
    for shop, batch in zip(shops, batches, strict=True):
        distance = _distance_meters(latitude, longitude, shop.latitude, shop.longitude)
        enriched.extend(offer.model_copy(update={"distance_meters": distance}) for offer in batch)
    return shops, enriched


def _distance_meters(lat1: float, lon1: float, lat2: float, lon2: float) -> int:
    radius = 6_371_000
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    delta_phi = math.radians(lat2 - lat1)
    delta_lambda = math.radians(lon2 - lon1)
    a = math.sin(delta_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda / 2) ** 2
    return round(radius * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a)))


async def collect_public_url(url: str, store: str) -> list[ImportedOffer]:
    if not await _public_url(url):
        return []
    async with httpx.AsyncClient(headers={"User-Agent": USER_AGENT}, timeout=30, follow_redirects=True) as client:
        if not await robots_allows(client, url):
            return []
        response = await client.get(url)
        response.raise_for_status()
        if len(response.content) > MAX_DOCUMENT_BYTES:
            return []
        content_type = response.headers.get("content-type", "").lower()
        if "pdf" in content_type or url.lower().endswith(".pdf"):
            return parse_pdf("manual-public-url", str(response.url), store, response.content)
        return parse_structured_products("manual-public-url", str(response.url), store, response.text)
