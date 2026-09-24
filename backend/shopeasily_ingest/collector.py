import asyncio
import urllib.robotparser
from pathlib import Path
from urllib.parse import urlsplit

import httpx
import yaml

from .models import ImportedOffer
from .parsers import HtmlSelectors, parse_html, parse_pdf

USER_AGENT = "ShopEasily/0.1 (+https://github.com/StitchMl/ShopEasily)"


async def robots_allows(client: httpx.AsyncClient, url: str) -> bool:
    parts = urlsplit(url)
    robots_url = f"{parts.scheme}://{parts.netloc}/robots.txt"
    response = await client.get(robots_url)
    if response.status_code >= 400:
        return False
    parser = urllib.robotparser.RobotFileParser()
    parser.set_url(robots_url)
    parser.parse(response.text.splitlines())
    return parser.can_fetch(USER_AGENT, url)


async def collect(config_path: Path) -> list[ImportedOffer]:
    config = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}
    output: list[ImportedOffer] = []
    async with httpx.AsyncClient(headers={"User-Agent": USER_AGENT}, timeout=30, follow_redirects=True) as client:
        for source in config.get("sources", []):
            if not source.get("enabled", False):
                continue
            url = source["url"]
            if not await robots_allows(client, url):
                continue
            response = await client.get(url)
            response.raise_for_status()
            if source["kind"] == "pdf":
                output.extend(parse_pdf(source["id"], url, source["store"], response.content))
            elif source["kind"] == "html":
                selectors = HtmlSelectors(source["item_selector"], source["name_selector"], source["price_selector"])
                output.extend(parse_html(source["id"], url, source["store"], response.text, selectors))
            await asyncio.sleep(float(source.get("delay_seconds", 2)))
    return output
