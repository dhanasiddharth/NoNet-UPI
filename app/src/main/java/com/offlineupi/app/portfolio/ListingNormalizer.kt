package com.offlineupi.app.portfolio

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Turns raw sheet tabs (from the Apps Script endpoint) or the bundled seed
 * JSON into instruments + trades. Mirrors tools/export_portfolio.py — the
 * sheet's rollup tabs are derived data, so only tradebooks are read.
 */
object ListingNormalizer {

    data class Listing(val instruments: List<Instrument>, val trades: List<Trade>)

    private val TRADE_TABS = mapOf(
        "Sid Kite EQ" to Triple("Sid", "Kite", "kite"),
        "Sid Kite MF" to Triple("Sid", "Kite", "kite"),
        "Vino Kite EQ" to Triple("Vino", "Kite", "kite"),
        "Vino Kite MF" to Triple("Vino", "Kite", "kite"),
        "Ilan Kite MF" to Triple("Ilan", "Kite", "kite"),
        "Grow" to Triple("Sid", "Groww", "groww"),
        "Sid IBKR" to Triple("Sid", "IBKR", "ibkr"),
        "Vino IBKR" to Triple("Vino", "IBKR", "ibkr"),
    )

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("MM-dd-yy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy hh:mm a"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss"),
    )

    private fun day(raw: String, fallback: String? = null): Long {
        val s = raw.trim()
        for (f in DATE_FORMATS) {
            try { return LocalDate.parse(s, f).toEpochDay() } catch (_: Exception) {}
            try { return LocalDate.from(f.parse(s)).toEpochDay() } catch (_: Exception) {}
        }
        if (fallback != null) return day(fallback)
        throw IllegalArgumentException("unparseable date: $raw")
    }

    private fun num(s: String?): Double =
        s?.replace(",", "")?.replace("₹", "")?.replace("$", "")?.trim()
            ?.toDoubleOrNull() ?: 0.0

    /** Bundled seed (tools/export_portfolio.py output). */
    fun fromSeed(json: String): Listing {
        val root = JSONObject(json)
        val instruments = mutableListOf<Instrument>()
        val ins = root.getJSONArray("instruments")
        for (i in 0 until ins.length()) {
            val o = ins.getJSONObject(i)
            instruments.add(Instrument(
                o.getString("isin"), o.getString("yahoo"),
                o.getString("currency"), o.getString("type"), o.getString("name")
            ))
        }
        val trades = mutableListOf<Trade>()
        val ts = root.getJSONArray("trades")
        for (i in 0 until ts.length()) {
            val o = ts.getJSONObject(i)
            trades.add(Trade(
                key = "${o.getString("broker")}:${o.getString("tradeId")}:${o.getString("isin")}:$i",
                isin = o.getString("isin"),
                day = PortfolioDb.epochDay(o.getString("date")),
                side = o.getString("side"),
                qty = o.getDouble("qty"), price = o.getDouble("price"),
                fee = o.optDouble("fee", 0.0),
                owner = o.getString("owner"), broker = o.getString("broker"),
            ))
        }
        return Listing(instruments, dedupe(trades))
    }

    /** Raw tabs from the Apps Script endpoint: {"tabs": {name: [[row],[row]...]}} */
    fun fromSheetTabs(json: String): Listing {
        val tabs = JSONObject(json).getJSONObject("tabs")
        val instruments = parseIsinTab(tabs.optJSONArray("ISIN Ticker"))
        val trades = mutableListOf<Trade>()

        for ((tab, meta) in TRADE_TABS) {
            val rows = rowsAsMaps(tabs.optJSONArray(tab) ?: continue)
            val (owner, broker, fmt) = meta
            for ((n, r) in rows.withIndex()) {
                try {
                    trades.add(when (fmt) {
                        "kite" -> parseKite(r, owner, broker)
                        "groww" -> parseGroww(r, owner, broker) ?: continue
                        else -> parseIbkr(r, owner, broker)
                    })
                } catch (_: Exception) { /* skip malformed row $n */ }
            }
        }
        trades += parseGold(tabs.optJSONArray("Gold"))
        trades += parseCrypto(tabs.optJSONArray("Crypto"))

        // synthetic ids for anything traded but missing from the ISIN tab
        val have = instruments.map { it.isin }.toMutableSet()
        val synthetic = listOf(
            Instrument("BTC", "BTC-USD", "USD", "Crypto", "Bitcoin"),
            Instrument("ETH", "ETH-USD", "USD", "Crypto", "Ethereum"),
            Instrument("GOLD", "GC=F", "INR", "Gold", "Physical Gold"),
        ).filter { have.add(it.isin) }
        return Listing(instruments + synthetic, dedupe(trades.sortedBy { it.day }))
    }

    private fun dedupe(trades: List<Trade>): List<Trade> {
        val seen = HashSet<String>()
        return trades.filter { seen.add(it.key) }
    }

    private fun rowsAsMaps(tab: JSONArray?): List<Map<String, String>> {
        tab ?: return emptyList()
        if (tab.length() < 2) return emptyList()
        val header = tab.getJSONArray(0).let { h -> List(h.length()) { h.getString(it).trim() } }
        return buildList {
            for (i in 1 until tab.length()) {
                val row = tab.getJSONArray(i)
                if ((0 until row.length()).all { row.optString(it).isBlank() }) continue
                add(header.indices.associate { header[it] to (row.optString(it) ?: "") })
            }
        }
    }

    private fun parseIsinTab(tab: JSONArray?): List<Instrument> =
        rowsAsMaps(tab).mapNotNull { r ->
            val isin = r["ISIN"]?.trim().orEmpty()
            if (isin.isBlank()) null else Instrument(
                isin, r["Yahoo Symbol"].orEmpty().trim(),
                r["Holding Currency"].orEmpty().ifBlank { "INR" },
                r["Type"].orEmpty().ifBlank { "Stock" },
                r["Name"].orEmpty().ifBlank { isin },
            )
        }

    private fun parseKite(r: Map<String, String>, owner: String, broker: String): Trade {
        val d = day(r["order_execution_time"].orEmpty().ifBlank { r["trade_date"].orEmpty() },
            r["trade_date"])
        return Trade(
            key = "$broker:${r["trade_id"]}:${r["isin"]}:${r["trade_type"]}:${r["quantity"]}",
            isin = r["isin"].orEmpty(), day = d,
            side = r["trade_type"].orEmpty().lowercase(),
            qty = num(r["quantity"]), price = num(r["price"]), fee = 0.0,
            owner = owner, broker = broker,
        )
    }

    private fun parseGroww(r: Map<String, String>, owner: String, broker: String): Trade? {
        if (!r["Order status"].orEmpty().equals("executed", true)) return null
        val qty = num(r["Quantity"])
        // Groww's export puts the TOTAL order value in the Price column
        return Trade(
            key = "$broker:${r["Exchange Order Id"]}:${r["ISIN"]}",
            isin = r["ISIN"].orEmpty(), day = day(r["Execution date and time"].orEmpty()),
            side = r["Type"].orEmpty().lowercase(),
            qty = qty, price = if (qty > 0) num(r["Price"]) / qty else 0.0, fee = 0.0,
            owner = owner, broker = broker,
        )
    }

    private fun parseIbkr(r: Map<String, String>, owner: String, broker: String): Trade =
        Trade(
            key = "$broker:${r["TradeID"]}:${r["ISIN"]}",
            isin = r["ISIN"].orEmpty(), day = day(r["TradeDate"].orEmpty()),
            side = r["Buy/Sell"].orEmpty().lowercase(),
            qty = num(r["Quantity"]), price = num(r["TradePrice"]),
            fee = kotlin.math.abs(num(r["IBCommission"])),
            owner = owner, broker = broker,
        )

    /** Gold: header block sits above the table; some rows have no per-gram rate
     *  and Total Cost includes making charges — effective price is cost/weight. */
    private fun parseGold(tab: JSONArray?): List<Trade> {
        tab ?: return emptyList()
        var headerIdx = -1
        for (i in 0 until tab.length()) {
            if (tab.getJSONArray(i).optString(0).trim() == "Payment Date") { headerIdx = i; break }
        }
        if (headerIdx < 0) return emptyList()
        val sub = JSONArray().also { for (i in headerIdx until tab.length()) it.put(tab.getJSONArray(i)) }
        return rowsAsMaps(sub).mapIndexedNotNull { n, r ->
            val dateRaw = r["Payment Date"]?.trim().orEmpty()
            if (dateRaw.isBlank()) return@mapIndexedNotNull null
            val grams = num(r["Weight"]); val cost = num(r["Total Cost"])
            if (grams <= 0) return@mapIndexedNotNull null
            Trade(
                key = "gold:$n:$dateRaw", isin = "GOLD", day = day(dateRaw, null),
                side = "buy", qty = grams, price = cost / grams, fee = 0.0,
                owner = "Family", broker = "Physical",
            )
        }
    }

    private fun parseCrypto(tab: JSONArray?): List<Trade> {
        tab ?: return emptyList()
        var headerIdx = -1
        for (i in 0 until tab.length()) {
            if (tab.getJSONArray(i).optString(0).trim() == "Date") { headerIdx = i; break }
        }
        if (headerIdx < 0) return emptyList()
        val sub = JSONArray().also { for (i in headerIdx until tab.length()) it.put(tab.getJSONArray(i)) }
        return rowsAsMaps(sub).mapIndexedNotNull { n, r ->
            val dateRaw = r["Date"]?.trim().orEmpty()
            if (dateRaw.isBlank()) return@mapIndexedNotNull null
            val qty = num(r["Quantity"])
            if (qty <= 0) return@mapIndexedNotNull null
            Trade(
                key = "crypto:$n:${r["Symbol"]}:$dateRaw", isin = r["Symbol"].orEmpty(),
                day = day(dateRaw), side = r["Transaction"].orEmpty().lowercase(),
                qty = qty, price = num(r["Cost"]) / qty, fee = num(r["Fee"]),
                owner = "Sid", broker = "Crypto",
            )
        }
    }
}
