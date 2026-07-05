#!/usr/bin/env python3
"""Fetch daily close history from Yahoo Finance for every portfolio symbol,
plus USDINR and the two benchmarks. Emits docs/design/market_data.json for
the design mockup; the app will run the same fetch on-device.

Uses curl (system python has no SSL roots). Yahoo v8 chart API needs no auth.
"""
import json
import subprocess
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"

BENCHMARKS = ["^NSEI", "^GSPC", "USDINR=X"]

# Sector / category per instrument — Yahoo's assetProfile needs cookies+crumb
# these days, and for 37 known instruments a curated map is more reliable.
SECTORS = {
    "INFY.NS": "Information Technology", "TCS.NS": "Information Technology",
    "OLAELEC.NS": "Automobile", "ASHOKLEY.NS": "Automobile",
    "DELHIVERY.NS": "Logistics", "SOUTHBANK.NS": "Banks",
    "HDFCBANK.NS": "Banks", "HDBFS.NS": "Financial Services",
    "RAILTEL.NS": "Telecom Infra", "SWIGGY.NS": "Consumer Internet",
    "ITC.NS": "FMCG",
    "AAPL": "Technology", "MSFT": "Technology", "GOOG": "Communication Services",
    "GOOGL": "Communication Services", "AMZN": "Consumer Discretionary",
    "NFLX": "Communication Services", "UBER": "Industrials",
    "SHOP": "Technology", "ASML": "Semiconductors", "SONY": "Consumer Electronics",
    "VOO": "US Large-Cap Index", "CSPX.L": "US Large-Cap Index",
    "QQQ": "Nasdaq-100 Index", "MON100.NS": "Nasdaq-100 Index",
    "0P0000XVE4.BO": "Nifty 50 Index", "0P0001RQX5.BO": "LargeMidcap 250 Index",
    "0P0000YWL1.BO": "Flexi Cap Fund", "0P0000XV5R.BO": "Midcap Fund",
    "0P0000XVG6.BO": "Large Cap Fund", "0P0000XVL5.BO": "Large & Midcap Fund",
    "0P0000XWAI.BO": "Multi Asset Fund", "0P0000XW8D.BO": "Corporate Bond Fund",
    "GOLDBEES.NS": "Gold", "GC=F": "Gold",
    "BTC-USD": "Crypto", "ETH-USD": "Crypto",
}


def fetch_chart(symbol: str):
    url = (
        "https://query1.finance.yahoo.com/v8/finance/chart/"
        f"{subprocess.list2cmdline([symbol]) and symbol}"
        "?range=10y&interval=1d&events=div%7Csplit"
    )
    raw = subprocess.check_output(["curl", "-sf", "-A", UA, url], text=True)
    result = json.loads(raw)["chart"]["result"][0]
    ts = result.get("timestamp") or []
    closes = result["indicators"]["quote"][0].get("close") or []
    days, vals = [], []
    for t, c in zip(ts, closes):
        if c is None:
            continue
        days.append(time.strftime("%Y-%m-%d", time.gmtime(t)))
        vals.append(round(c, 4))
    return {"d": days, "c": vals}


def main():
    seed = json.loads((REPO / "docs/design/portfolio_seed.json").read_text())
    symbols = sorted({i["yahoo"] for i in seed["instruments"]}) + BENCHMARKS
    out, failed = {}, []
    for s in symbols:
        try:
            out[s] = fetch_chart(s)
            print(f"{s:>16} {len(out[s]['d'])} bars  {out[s]['d'][0]} -> {out[s]['d'][-1]}")
        except Exception as e:
            failed.append(s)
            print(f"{s:>16} FAILED: {e}")
        time.sleep(0.4)  # be polite; Yahoo rate-limits bursts
    data = {"fetchedAt": time.strftime("%Y-%m-%d %H:%M"), "sectors": SECTORS, "series": out}
    path = REPO / "docs/design/market_data.json"
    path.write_text(json.dumps(data, separators=(",", ":")))
    print(f"\nwrote {path} ({path.stat().st_size // 1024} KB), failed: {failed or 'none'}")


if __name__ == "__main__":
    main()
