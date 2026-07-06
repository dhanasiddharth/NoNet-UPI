# Money tab — app scope

Design source of truth: `docs/design/money-redesign.html` (v3). Data policy: ₹ only at
whole-portfolio level; buckets and securities in their own currency. No real portfolio
data in git — the bundled seed asset is git-ignored and baked into local builds only.

## Build 1 (this build)
- **Listing source = the sheet.** Bundled `portfolio_seed.json` imports on first launch;
  the **Sync** button refreshes listings from the sheet via a tokened Apps Script
  endpoint (`tools/sheet_webapp.gs`) — single-user, no backend, sheet stays private.
  URL + token entered once, stored in EncryptedSharedPreferences.
- **Prices on-device** from Yahoo v8 chart API (10y backfill on first sync, 3-month
  merge after), stored in SQLite. INTERNET permission added — first network use.
- **Analytics engine in Kotlin** (port of `tools/build_design_data.py`): daily
  calendars with forward-fill, bucket value/invested series (native), whole-portfolio
  INR series, same-cashflow index sims (^NSEI/^GSPC), CPI sims (IN/US table),
  XIRR by bisection, per-holding stats, day movers.
- **Money overview UI** per v3: hero + masked toggle, four native-currency tiles with
  sparklines, performance chart with ranges + vs-index overlay, dot plot
  (you/index/inflation), allocation bar + key, movement feed, top-5 holdings, Sync row.
- **Alerts**: daily WorkManager price sync, then threshold check (default ±4%/day) →
  notification with the ₹ small icon.

## Build 2 (all spec'd screens)

- **Allocation screen**: squarified treemap, area = INR weight, labels native. Tap or
  pinch-out zooms bucket → sector → holding; pinch-in / back zooms out; key list
  follows the current level.
- **Holdings movement screen**: every holding with period chips (1D–1Y), native price
  move + spark per period, sortable by Move/Value/Invested/XIRR (tap again to flip).
- **Holding detail screen**: two charts by design — performance (you vs same cashflows
  in the bucket index, both % of invested) and value (invested line + trade dots);
  trailing returns vs index (1M–3Y), position stats incl. index/inflation XIRR, trade
  history, per-holding alert threshold row.
- **Alerts screen**: fired-alert history (alert_log) + threshold cascade editor —
  holding rule ▸ bucket rule ▸ default; worker resolves per holding and logs hits.
- Overview wires into all of these: bell → Alerts, Zoom › → Allocation, All › →
  Holdings, any holding/mover row → detail. DB schema v2 adds alert_rule/alert_log.

## Later builds

- Chart scrub interaction; treemap free pinch-zoom (continuous scale).
- CPI from MOSPI/BLS instead of the built-in table; gold premium factor setting.
- Broker CSV importers, then broker APIs; backend + proper auth when one exists.
