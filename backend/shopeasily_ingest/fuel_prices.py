import csv
import io
from statistics import median

import httpx

MIMIT_DAILY_PRICES = "https://www.mimit.gov.it/images/exportCSV/prezzo_alle_8.csv"
USER_AGENT = "ShopEasily/0.1 (+https://github.com/StitchMl/ShopEasily)"


async def italian_fuel_prices() -> dict[str, float | str]:
    """Mediane nazionali dal dataset quotidiano MIMIT; self per benzina/diesel."""
    async with httpx.AsyncClient(headers={"User-Agent": USER_AGENT}, timeout=30) as client:
        response = await client.get(MIMIT_DAILY_PRICES)
        response.raise_for_status()
    text = response.content.decode("utf-8-sig", errors="replace")
    lines = text.splitlines()
    extracted_at = lines[0].removeprefix("Estrazione del ").strip()
    rows = csv.DictReader(io.StringIO("\n".join(lines[1:])), delimiter="|")
    values: dict[str, list[float]] = {"gasoline": [], "diesel": [], "lpg": []}
    names = {"Benzina": "gasoline", "Gasolio": "diesel", "GPL": "lpg"}
    for row in rows:
        key = names.get(row.get("descCarburante", ""))
        if key is None:
            continue
        if key != "lpg" and row.get("isSelf") != "1":
            continue
        try:
            values[key].append(float(row["prezzo"]))
        except (TypeError, ValueError):
            continue
    return {
        "source": "MIMIT Osservaprezzi - IODL 2.0",
        "extracted_at": extracted_at,
        **{key: round(median(prices), 3) for key, prices in values.items() if prices},
    }
