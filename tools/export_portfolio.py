#!/usr/bin/env python3
"""One-time portfolio seed exporter.

Reads the family portfolio Google Sheet (per-owner broker tradebooks, the
ISIN->Yahoo mapping, physical gold and crypto ledgers) and emits the app's
seed file. Broker rollup tabs (INR/USD/Transactions/Yahoo Export) are
derived data in the sheet, so they are deliberately not read — trades are
the source of truth and everything else is computed.

Usage:
  python3 tools/export_portfolio.py            # writes app seed + docs copy
  Requires: gcloud auth for dhanasiddharth@gmail.com (token fetched via CLI).
"""
import json
import subprocess
import urllib.parse
from datetime import datetime
from pathlib import Path

SHEET_ID = "1rmDk8dUZzoJqlqH4cKYOxyanawn5HABP7ljKCijiUwc"
ACCOUNT = "dhanasiddharth@gmail.com"

# tab -> (owner, broker, format)
TRADE_TABS = {
    "Sid Kite EQ": ("Sid", "Kite", "kite"),
    "Sid Kite MF": ("Sid", "Kite", "kite"),
    "Vino Kite EQ": ("Vino", "Kite", "kite"),
    "Vino Kite MF": ("Vino", "Kite", "kite"),
    "Ilan Kite MF": ("Ilan", "Kite", "kite"),
    "Grow": ("Sid", "Groww", "groww"),
    "Sid IBKR": ("Sid", "IBKR", "ibkr"),
    "Vino IBKR": ("Vino", "IBKR", "ibkr"),
}
OTHER_TABS = ["ISIN Ticker", "Gold", "Crypto"]


def token() -> str:
    return subprocess.check_output(
        ["gcloud", "auth", "print-access-token", ACCOUNT], text=True
    ).strip()


def batch_get(ranges):
    qs = "&".join("ranges=" + urllib.parse.quote(r) for r in ranges)
    url = (
        f"https://sheets.googleapis.com/v4/spreadsheets/{SHEET_ID}/values:batchGet?"
        f"{qs}&valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=FORMATTED_STRING"
    )
    # curl rather than urllib: the system python is missing SSL root certs
    raw = subprocess.check_output(
        ["curl", "-sf", "-H", f"Authorization: Bearer {token()}", url], text=True
    )
    data = json.loads(raw)
    out = {}
    for vr in data["valueRanges"]:
        name = vr["range"].split("!")[0].strip("'")
        out[name] = vr.get("values", [])
    return out


def rows_as_dicts(values):
    if not values:
        return []
    header = [str(h).strip() for h in values[0]]
    out = []
    for row in values[1:]:
        if not any(str(c).strip() for c in row):
            continue
        out.append({header[i]: row[i] for i in range(min(len(header), len(row)))})
    return out


def norm_date(raw, fallback=None):
    """Sheet tabs mix yyyy-mm-dd, mm-dd-yy and dd-mm-yyyy hh:mm — normalize to ISO."""
    s = str(raw).strip()
    for fmt in ("%Y-%m-%d", "%m-%d-%y", "%d-%m-%Y %I:%M %p", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(s, fmt).date().isoformat()
        except ValueError:
            pass
    if fallback:
        return norm_date(fallback)
    raise ValueError(f"unparseable date: {raw!r}")


def f(x):
    return float(str(x).replace(",", "") or 0)


def parse_kite(rows, owner, broker):
    for r in rows:
        # tradebook date column is locale-mangled for MF tabs; execution time is reliable
        date = norm_date(r.get("order_execution_time") or r["trade_date"], r["trade_date"])
        yield {
            "isin": r["isin"], "date": date,
            "side": r["trade_type"].lower(),
            "qty": f(r["quantity"]), "price": f(r["price"]), "fee": 0.0,
            "owner": owner, "broker": broker, "tradeId": str(r.get("trade_id", "")),
        }


def parse_groww(rows, owner, broker):
    for r in rows:
        if str(r.get("Order status", "")).lower() != "executed":
            continue
        qty = f(r["Quantity"])
        # Groww's export puts the TOTAL order value in the Price column
        yield {
            "isin": r["ISIN"], "date": norm_date(r["Execution date and time"]),
            "side": r["Type"].lower(),
            "qty": qty, "price": f(r["Price"]) / qty if qty else 0.0, "fee": 0.0,
            "owner": owner, "broker": broker, "tradeId": str(r.get("Exchange Order Id", "")),
        }


def parse_ibkr(rows, owner, broker):
    for r in rows:
        yield {
            "isin": r["ISIN"], "date": norm_date(r["TradeDate"]),
            "side": r["Buy/Sell"].lower(),
            "qty": f(r["Quantity"]), "price": f(r["TradePrice"]),
            "fee": abs(f(r.get("IBCommission", 0))),
            "owner": owner, "broker": broker, "tradeId": str(r.get("TradeID", "")),
        }


PARSERS = {"kite": parse_kite, "groww": parse_groww, "ibkr": parse_ibkr}


def main():
    repo = Path(__file__).resolve().parent.parent
    tabs = batch_get(list(TRADE_TABS) + OTHER_TABS)

    instruments = []
    for r in rows_as_dicts(tabs["ISIN Ticker"]):
        if not str(r.get("ISIN", "")).strip():
            continue
        instruments.append({
            "isin": r["ISIN"], "yahoo": r.get("Yahoo Symbol", ""),
            "currency": r.get("Holding Currency", "INR"),
            "type": r.get("Type", "Stock"), "name": r.get("Name", r["ISIN"]),
        })
    # Crypto/gold may not be in the ISIN tab; add synthetic ids only when absent
    have = {i["isin"] for i in instruments}
    instruments += [
        i for i in [
            {"isin": "BTC", "yahoo": "BTC-USD", "currency": "USD", "type": "Crypto", "name": "Bitcoin"},
            {"isin": "ETH", "yahoo": "ETH-USD", "currency": "USD", "type": "Crypto", "name": "Ethereum"},
            {"isin": "GOLD", "yahoo": "GC=F", "currency": "INR", "type": "Gold", "name": "Physical Gold"},
        ] if i["isin"] not in have
    ]

    trades = []
    seen = set()
    for tab, (owner, broker, fmt) in TRADE_TABS.items():
        for t in PARSERS[fmt](rows_as_dicts(tabs[tab]), owner, broker):
            key = (t["broker"], t["tradeId"], t["isin"], t["side"], t["qty"])
            if t["tradeId"] and key in seen:   # same trade appears in owner+merged tabs
                continue
            seen.add(key)
            trades.append(t)

    # Gold ledger: header block sits above the table; find the header row.
    # Some rows carry only Weight + Total Cost (no per-gram rate), and Total
    # Cost includes making charges — so the effective price is cost/weight.
    gold_rows = tabs["Gold"]
    hi = next(i for i, row in enumerate(gold_rows) if row and str(row[0]).strip() == "Payment Date")
    for n, r in enumerate(rows_as_dicts(gold_rows[hi:])):
        if not str(r.get("Payment Date", "")).strip():
            continue
        grams, cost = f(r["Weight"]), f(r["Total Cost"])
        trades.append({
            "isin": "GOLD", "date": norm_date(r["Payment Date"]), "side": "buy",
            "qty": grams, "price": cost / grams if grams else 0.0, "fee": 0.0,
            "owner": "Family", "broker": "Physical",
            # row index keeps ids unique: identical same-day repeat buys are real
            "tradeId": f"gold-{n}-{norm_date(r['Payment Date'])}",
        })

    crypto_rows = tabs["Crypto"]
    hi = next(i for i, row in enumerate(crypto_rows) if row and str(row[0]).strip() == "Date")
    for r in rows_as_dicts(crypto_rows[hi:]):
        if not str(r.get("Date", "")).strip():
            continue
        qty = f(r["Quantity"])
        trades.append({
            "isin": r["Symbol"], "date": norm_date(r["Date"]),
            "side": r["Transaction"].lower(),
            "qty": qty, "price": f(r["Cost"]) / qty if qty else 0.0,
            "fee": f(r.get("Fee", 0)),
            "owner": "Sid", "broker": "Crypto",
            "tradeId": f"crypto-{r['Symbol']}-{norm_date(r['Date'])}-{qty}",
        })

    trades.sort(key=lambda t: t["date"])
    seed = {
        "exportedAt": datetime.now().isoformat(timespec="seconds"),
        "instruments": instruments,
        "trades": trades,
    }

    out_app = repo / "app/src/main/assets/portfolio_seed.json"
    out_app.parent.mkdir(parents=True, exist_ok=True)
    out_app.write_text(json.dumps(seed, separators=(",", ":")))
    out_docs = repo / "docs/design/portfolio_seed.json"
    out_docs.write_text(json.dumps(seed, indent=1))

    by = {}
    for t in trades:
        by.setdefault((t["owner"], t["broker"]), []).append(t)
    print(f"instruments: {len(instruments)}, trades: {len(trades)}")
    for k, v in sorted(by.items()):
        print(f"  {k[0]:>6} / {k[1]:<8} {len(v)} trades")
    print(f"wrote {out_app} and {out_docs}")


if __name__ == "__main__":
    main()
