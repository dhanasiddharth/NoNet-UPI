#!/usr/bin/env python3
"""Compute the portfolio analytics that power the Money tab design mockup.

Everything derives from trades x daily closes:
  - daily value + invested series (INR), overall and per bucket (India/US/Gold/Crypto)
  - same-cashflow benchmark simulation (India+Gold -> ^NSEI, US+Crypto -> ^GSPC)
  - XIRR per bucket / instrument, portfolio vs benchmark
  - current allocation by bucket, sector and instrument
  - recent significant single-day movers (the alerts feed)

Reads  docs/design/portfolio_seed.json + market_data.json
Writes docs/design/design_data.json and prints a validation summary to
compare against the sheet's own Summary tab.
"""
import json
from bisect import bisect_right
from datetime import date, timedelta
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OZ_TO_GRAM = 31.1035

seed = json.loads((REPO / "docs/design/portfolio_seed.json").read_text())
mkt = json.loads((REPO / "docs/design/market_data.json").read_text())
series = mkt["series"]
sectors = mkt["sectors"]

inst = {i["isin"]: i for i in seed["instruments"]}
trades = seed["trades"]


def bucket_of(isin):
    i = inst[isin]
    if i["isin"] == "GOLD":
        return "Gold"
    if i["type"] == "Crypto":
        return "Crypto"
    return "US" if i["currency"] == "USD" else "India"


# ---- master calendar with forward-filled closes ----
start = date.fromisoformat(min(t["date"] for t in trades))
end = date.fromisoformat(max(max(s["d"]) for s in series.values()))
CAL = []
d = start
while d <= end:
    CAL.append(d.isoformat())
    d += timedelta(days=1)
N = len(CAL)


def ffill(sym):
    s = series[sym]
    out, j, last = [None] * N, 0, None
    lookup = dict(zip(s["d"], s["c"]))
    for k, day in enumerate(CAL):
        if day in lookup:
            last = lookup[day]
        out[k] = last
    return out


PX = {sym: ffill(sym) for sym in series}
FX = PX["USDINR=X"]

# ---- CPI (approximate annual rates for the design spec; the app should pull
# MOSPI / BLS series). Compounded into a daily index so cashflows can be
# "invested in inflation" exactly like the market benchmarks. ----
CPI_RATES = {
    "IN": {2018: 3.9, 2019: 3.7, 2020: 6.6, 2021: 5.1, 2022: 6.7,
           2023: 5.7, 2024: 5.0, 2025: 4.0, 2026: 4.2},
    "US": {2018: 2.4, 2019: 1.8, 2020: 1.2, 2021: 4.7, 2022: 8.0,
           2023: 4.1, 2024: 2.9, 2025: 2.8, 2026: 2.6},
}


def cpi_index(country):
    idx, level = [0.0] * N, 1.0
    for k, day in enumerate(CAL):
        y = int(day[:4])
        r = CPI_RATES[country].get(y, list(CPI_RATES[country].values())[-1])
        level *= (1 + r / 100) ** (1 / 365.25)
        idx[k] = level
    return idx


CPI = {"IN": cpi_index("IN"), "US": cpi_index("US")}


def inflation_sim(flows, country):
    """Terminal value of the same cashflows compounded at CPI."""
    idx = CPI[country]
    return sum(c * idx[N - 1] / idx[k] for k, c in flows)


def px_inr(isin, k):
    """Close of instrument on calendar day k, converted to INR."""
    i = inst[isin]
    p = PX[i["yahoo"]][k]
    if p is None:
        return None
    if isin == "GOLD":                      # GC=F is USD/oz -> INR/gram
        return p * (FX[k] or 0) / OZ_TO_GRAM
    if i["currency"] == "USD":
        return p * (FX[k] or 0)
    return p


def trade_cash_inr(t, k):
    """Net cashflow of a trade in INR (buy positive = money in)."""
    gross = t["qty"] * t["price"] + t.get("fee", 0)
    if inst[t["isin"]]["currency"] == "USD":
        gross *= FX[k] or 0
    return gross if t["side"] == "buy" else -gross


day_idx = {day: k for k, day in enumerate(CAL)}

# ---- daily holdings, value, invested per bucket ----
# Currency policy: INR exists ONLY at the whole-portfolio level. Buckets and
# securities stay in their own currency (US/Crypto: USD; India/Gold: INR).
BUCKETS = ["India", "US", "Gold", "Crypto"]
BUCKET_CCY = {"India": "INR", "US": "USD", "Gold": "INR", "Crypto": "USD"}
qty = {isin: [0.0] * N for isin in {t["isin"] for t in trades}}
invested = {b: [0.0] * N for b in BUCKETS}          # INR (portfolio overlay)
invested_native = {b: [0.0] * N for b in BUCKETS}   # bucket currency
bench_units = {"^NSEI": [0.0] * N, "^GSPC": [0.0] * N}


def trade_cash_native(t):
    gross = t["qty"] * t["price"] + t.get("fee", 0)
    return gross if t["side"] == "buy" else -gross


for t in trades:
    k = day_idx[t["date"]]
    q = t["qty"] if t["side"] == "buy" else -t["qty"]
    qty[t["isin"]][k] += q
    b = bucket_of(t["isin"])
    cash = trade_cash_inr(t, k)
    invested[b][k] += cash
    invested_native[b][k] += trade_cash_native(t)
    # benchmark sim in the bucket's own market and currency: INR flows buy
    # ^NSEI at INR, USD flows buy ^GSPC at USD — no FX in the comparison
    if BUCKET_CCY[b] == "INR":
        ref = PX["^NSEI"][k]
        if ref:
            bench_units["^NSEI"][k] += cash / ref
    else:
        ref = PX["^GSPC"][k]
        if ref:
            bench_units["^GSPC"][k] += trade_cash_native(t) / ref

for arr in (list(qty.values()) + list(invested.values())
            + list(invested_native.values()) + list(bench_units.values())):
    for k in range(1, N):
        arr[k] += arr[k - 1]


def px_native(isin, k):
    """Close in the instrument's own display currency (gold: INR/gram)."""
    i = inst[isin]
    p = PX[i["yahoo"]][k]
    if p is None:
        return None
    if isin == "GOLD":
        return p * (FX[k] or 0) / OZ_TO_GRAM
    return p


value = {b: [0.0] * N for b in BUCKETS}          # INR (portfolio total)
value_native = {b: [0.0] * N for b in BUCKETS}   # bucket currency (tiles/charts)
for isin, qarr in qty.items():
    b = bucket_of(isin)
    for k in range(N):
        if qarr[k] > 1e-9:
            p = px_inr(isin, k)
            if p:
                value[b][k] += qarr[k] * p
            pn = px_native(isin, k)
            if pn:
                value_native[b][k] += qarr[k] * pn

total = [sum(value[b][k] for b in BUCKETS) for k in range(N)]
total_inv = [sum(invested[b][k] for b in BUCKETS) for k in range(N)]
# whole-portfolio benchmark line is the one place FX conversion applies
bench_val = [
    bench_units["^NSEI"][k] * (PX["^NSEI"][k] or 0)
    + bench_units["^GSPC"][k] * (PX["^GSPC"][k] or 0) * (FX[k] or 0)
    for k in range(N)
]


# ---- XIRR ----
def xirr(flows, terminal_value, terminal_day):
    """flows: [(day_index, +cash_in)] ; solves NPV=0 with outflow at terminal."""
    if terminal_value <= 0 or not flows:
        return None
    t0 = flows[0][0]

    def npv(rate):
        acc = sum(c / (1 + rate) ** ((k - t0) / 365.25) for k, c in flows)
        return acc - terminal_value / (1 + rate) ** ((terminal_day - t0) / 365.25)

    lo, hi = -0.9999, 20.0
    if npv(lo) * npv(hi) > 0:
        return None
    for _ in range(120):
        mid = (lo + hi) / 2
        if npv(lo) * npv(mid) <= 0:
            hi = mid
        else:
            lo = mid
    return (lo + hi) / 2


last = N - 1
flows_all = []                                   # INR — whole-portfolio only
flows_b = {b: [] for b in BUCKETS}               # bucket currency
for t in trades:
    k = day_idx[t["date"]]
    b = bucket_of(t["isin"])
    flows_all.append((k, trade_cash_inr(t, k)))
    flows_b[b].append((k, trade_cash_native(t)))

# Per-bucket benchmark sim, in the bucket's own currency — same cashflows
# buying its market index (^NSEI in INR, ^GSPC in USD)
bench_bucket_val = {}
for b in BUCKETS:
    bsym = "^NSEI" if BUCKET_CCY[b] == "INR" else "^GSPC"
    units = 0.0
    vals = [0.0] * N
    fl = sorted(flows_b[b])
    fi = 0
    for k in range(N):
        while fi < len(fl) and fl[fi][0] == k:
            ref = PX[bsym][k]
            if ref:
                units += fl[fi][1] / ref
            fi += 1
        vals[k] = units * (PX[bsym][k] or 0)
    bench_bucket_val[b] = vals

stats = {}
for b in BUCKETS:
    cur, inv = value_native[b][last], invested_native[b][last]
    country = "IN" if BUCKET_CCY[b] == "INR" else "US"
    infl_terminal = inflation_sim(flows_b[b], country)
    stats[b] = {
        "value": round(cur, 2), "invested": round(inv, 2),
        "valueInr": round(value[b][last]),
        "currency": BUCKET_CCY[b],
        "xirr": xirr(sorted(flows_b[b]), cur, last),
        "benchXirr": xirr(sorted(flows_b[b]), bench_bucket_val[b][last], last),
        "benchValue": round(bench_bucket_val[b][last], 2),
        "benchmark": "Nifty 50" if BUCKET_CCY[b] == "INR" else "S&P 500",
        # same cashflows merely keeping up with that market's CPI
        "inflXirr": xirr(sorted(flows_b[b]), infl_terminal, last),
        "inflValue": round(infl_terminal, 2),
        "inflation": "India CPI" if country == "IN" else "US CPI",
    }
overall_infl = inflation_sim(flows_all, "IN")   # everything vs India CPI
overall = {
    "value": round(total[last]), "invested": round(total_inv[last]),
    "xirr": xirr(flows_all, total[last], last),
    "benchXirr": xirr(flows_all, bench_val[last], last),
    "benchValue": round(bench_val[last]),
    "inflXirr": xirr(flows_all, overall_infl, last),
    "inflValue": round(overall_infl),
    "inflation": "India CPI",
}

# ---- per-instrument current stats + allocation ----
holdings = []
for isin, qarr in qty.items():
    q = qarr[last]
    if q <= 1e-9:
        continue
    p = px_inr(isin, last)
    cur = q * (p or 0)
    fl = [(day_idx[t["date"]], trade_cash_inr(t, day_idx[t["date"]]))
          for t in trades if t["isin"] == isin]
    i = inst[isin]
    # native-currency view: securities read in their own currency, and XIRR
    # on native cashflows shows the position's performance without FX drift
    pn = px_native(isin, last)
    prev_n = px_native(isin, last - 1)
    cur_native = q * (pn or 0)
    fl_native = []
    for t in trades:
        if t["isin"] != isin:
            continue
        gross = t["qty"] * t["price"] + t.get("fee", 0)
        fl_native.append((day_idx[t["date"]], gross if t["side"] == "buy" else -gross))
    fl_native.sort()
    # slim history for the holding-detail chart: weekly points, daily for the
    # last 400 days, native currency, starting a month before first trade.
    # Three aligned series: position value, same-cashflows-in-benchmark, and
    # invested (step) — the detail chart is value-vs-benchmark, not raw price.
    first_k = min(k for k, _ in fl)
    hk = [k for k in range(max(0, first_k - 30), N)
          if k >= N - 400 or k % 7 == 0]
    bsym = "^NSEI" if BUCKET_CCY[bucket_of(isin)] == "INR" else "^GSPC"
    bunits, binv, fi = 0.0, 0.0, 0
    bval_at, inv_at = {}, {}
    fln = sorted(fl_native)
    for k in range(N):
        while fi < len(fln) and fln[fi][0] == k:
            ref = PX[bsym][k]
            if ref:
                bunits += fln[fi][1] / ref
            binv += fln[fi][1]
            fi += 1
        bval_at[k] = bunits * (PX[bsym][k] or 0)
        inv_at[k] = binv
    hseries = {
        "d": [CAL[k] for k in hk],
        "c": [round(px_native(isin, k) or 0, 2) for k in hk],
        "v": [round(qarr[k] * (px_native(isin, k) or 0), 2) for k in hk],
        "b": [round(bval_at[k], 2) for k in hk],
        "i": [round(inv_at[k], 2) for k in hk],
    }
    # price-based rolling returns (3M simple, 1Y simple, 3Y CAGR)
    def roll(days, annualize=False):
        k1, k0 = last, last - days
        if k0 < 0:
            return None
        p1, p0 = px_native(isin, k1), px_native(isin, k0)
        if not p1 or not p0:
            return None
        r = p1 / p0 - 1
        return round(((1 + r) ** (365.0 / days) - 1) if annualize else r, 4)
    country = "IN" if BUCKET_CCY[bucket_of(isin)] == "INR" else "US"
    infl_term = inflation_sim(fln, country)
    tmarks = [{"date": t["date"], "side": t["side"], "qty": t["qty"],
               "price": round(t["price"], 2)}
              for t in trades if t["isin"] == isin]
    holdings.append({
        "isin": isin, "name": i["name"], "yahoo": i["yahoo"], "type": i["type"],
        "currency": "INR" if isin == "GOLD" else i["currency"],
        "bucket": bucket_of(isin), "sector": sectors.get(i["yahoo"], "Other"),
        "qty": round(q, 4), "valueInr": round(cur),
        "value": round(cur_native, 2), "invested": round(sum(c for _, c in fl_native), 2),
        "price": round(pn or 0, 2),
        "dayPct": round((pn / prev_n - 1) * 100, 2) if pn and prev_n else 0,
        "investedInr": round(sum(c for _, c in fl)),
        "xirr": xirr(fl_native, cur_native, last),
        "benchXirr": xirr(fl_native, bval_at[last], last),
        "benchValue": round(bval_at[last], 2),
        "benchmark": "Nifty 50" if bsym == "^NSEI" else "S&P 500",
        "inflXirr": xirr(fln, infl_term, last),
        "roll3m": roll(91), "roll1y": roll(365), "roll3y": roll(365 * 3, annualize=True),
        "series": hseries, "trades": tmarks,
    })
holdings.sort(key=lambda h: -h["valueInr"])

# ---- recent significant movers (alert feed examples) ----
movers = []
for h in holdings:
    sym = h["yahoo"]
    for k in range(max(1, N - 90), N):
        p0, p1 = PX[sym][k - 1], PX[sym][k]
        if p0 and p1 and p0 > 0:
            pct = (p1 / p0 - 1) * 100
            if abs(pct) >= 4.0:
                movers.append({"date": CAL[k], "isin": h["isin"], "name": h["name"],
                               "pct": round(pct, 2),
                               "value": h["value"], "currency": h["currency"]})
movers.sort(key=lambda m: m["date"], reverse=True)

# ---- emit (weekly points before the last 400 days to keep size sane) ----
def slim(arr):
    pts = []
    for k in range(N):
        if k >= N - 400 or k % 7 == 0 or k == 0:
            pts.append(k)
    return pts

keep = slim(total)
out = {
    "asOf": CAL[last], "calendar": [CAL[k] for k in keep],
    "total": [round(total[k]) for k in keep],
    "invested": [round(total_inv[k]) for k in keep],
    "bench": [round(bench_val[k]) for k in keep],
    # bucket series in their own currency; INR appears only in `total`
    "buckets": {b: [round(value_native[b][k], 2) for k in keep] for b in BUCKETS},
    "bucketCcy": BUCKET_CCY,
    "stats": {"overall": overall, **stats},
    "holdings": holdings,
    "movers": movers[:40],
    "moversCount": len(movers),
}
path = REPO / "docs/design/design_data.json"
path.write_text(json.dumps(out, separators=(",", ":")))
# .js twin so the mockup works over file:// (fetch of local json is CORS-blocked)
(REPO / "docs/design/design_data.js").write_text(
    "window.PORTFOLIO_DATA=" + json.dumps(out, separators=(",", ":")) + ";"
)

print(f"as of {CAL[last]}   ({path.stat().st_size//1024} KB)")
print(f"TOTAL   ₹{overall['value']:,.0f}  invested ₹{overall['invested']:,.0f}  "
      f"XIRR {overall['xirr']*100:.1f}%  benchXIRR {overall['benchXirr']*100:.1f}%")
for b in BUCKETS:
    s = stats[b]
    sym = "₹" if s["currency"] == "INR" else "$"
    x = f"{s['xirr']*100:.1f}%" if s["xirr"] is not None else "n/a"
    bx = f"{s['benchXirr']*100:.1f}%" if s["benchXirr"] is not None else "n/a"
    print(f"{b:>6}  {sym}{s['value']:,.0f}  invested {sym}{s['invested']:,.0f}  XIRR {x}  vs {s['benchmark']} ({s['currency']}) {bx}")
print(f"holdings: {len(holdings)}, movers(90d,>=4%): {len(movers)}")
