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
  notification with the ₹ small icon. Per-market/sector/holding rule cascade is UI-only
  in the spec for now; engine applies the default.

## Later builds
- Allocation screen (zoomable treemap), Holdings movement screen, Holding detail
  (performance + value charts), alert rule editor, scrub interaction on charts.
- CPI from MOSPI/BLS instead of the built-in table; gold premium factor setting.
- Broker CSV importers, then broker APIs; backend + proper auth when one exists.
