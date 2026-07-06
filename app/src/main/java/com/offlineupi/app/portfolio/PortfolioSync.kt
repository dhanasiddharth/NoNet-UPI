package com.offlineupi.app.portfolio

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Listing + price sync. Listing comes from the tokened Apps Script endpoint
 * (see tools/sheet_webapp.gs) — the simplest single-user auth that keeps the
 * sheet private; revisit when a backend exists. Prices come straight from
 * Yahoo's public chart API and live only on-device.
 */
class PortfolioSync(private val context: Context, private val db: PortfolioDb) {

    // ---- endpoint config (entered once, stored encrypted) ----
    private val prefs by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "portfolio_sync", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var endpointUrl: String?
        get() = prefs.getString("url", null)
        set(v) { prefs.edit().putString("url", v).apply() }

    var endpointToken: String?
        get() = prefs.getString("token", null)
        set(v) { prefs.edit().putString("token", v).apply() }

    val isConfigured get() = !endpointUrl.isNullOrBlank() && !endpointToken.isNullOrBlank()

    // ---- bootstrap from the bundled seed (first launch, offline) ----
    fun importSeedIfEmpty(): Boolean {
        if (db.tradeCount() > 0) return false
        val json = try {
            context.assets.open("portfolio_seed.json").bufferedReader().use(BufferedReader::readText)
        } catch (_: Exception) { return false }
        val listing = ListingNormalizer.fromSeed(json)
        db.replaceListing(listing.instruments, listing.trades)
        db.setMeta("listingSource", "seed")
        return true
    }

    // ---- listing from the sheet ----
    fun syncListing(): Int {
        val url = endpointUrl ?: error("Sheet endpoint not configured")
        val token = endpointToken ?: error("Sheet endpoint not configured")
        val full = url + (if (url.contains('?')) "&" else "?") +
            "token=" + URLEncoder.encode(token, "UTF-8")
        val body = httpGet(full)
        if (body.contains("\"error\"")) error("Sheet endpoint refused the token")
        val listing = ListingNormalizer.fromSheetTabs(body)
        require(listing.trades.isNotEmpty()) { "Endpoint returned no trades" }
        db.replaceListing(listing.instruments, listing.trades)
        db.setMeta("listingSource", "sheet")
        db.setMeta("listingSyncedAt", LocalDate.now().toString())
        return listing.trades.size
    }

    // ---- prices from Yahoo ----
    val benchmarkSymbols = listOf("^NSEI", "^GSPC", "USDINR=X")

    fun symbolsToSync(): List<String> =
        (db.instruments().map { it.yahoo }.filter { it.isNotBlank() } + benchmarkSymbols).distinct()

    /** Returns bars fetched. First sync per symbol backfills 10y, then 3mo merges. */
    fun syncPrices(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val symbols = symbolsToSync()
        var bars = 0
        var lastError: Exception? = null
        symbols.forEachIndexed { i, sym ->
            try {
                val range = if (db.lastPriceDay(sym) == null) "10y" else "3mo"
                bars += fetchChart(sym, range)
            } catch (e: Exception) {
                // one symbol failing shouldn't kill the sync, but keep the evidence
                android.util.Log.w("PortfolioSync", "fetch failed for $sym", e)
                lastError = e
            }
            onProgress(i + 1, symbols.size)
            Thread.sleep(150)   // stay polite with Yahoo
        }
        if (bars == 0 && lastError != null) {
            throw IllegalStateException("Price fetch failed: ${lastError!!.message}", lastError)
        }
        db.setMeta("priceSyncedAt", System.currentTimeMillis().toString())
        return bars
    }

    private fun fetchChart(symbol: String, range: String): Int {
        val enc = URLEncoder.encode(symbol, "UTF-8")
        val body = httpGet(
            "https://query1.finance.yahoo.com/v8/finance/chart/$enc?range=$range&interval=1d"
        )
        val result = org.json.JSONObject(body)
            .getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val ts = result.optJSONArray("timestamp") ?: return 0
        val closes = result.getJSONObject("indicators")
            .getJSONArray("quote").getJSONObject(0).optJSONArray("close") ?: return 0
        val bars = buildList {
            for (i in 0 until ts.length()) {
                if (closes.isNull(i)) continue
                add(ts.getLong(i) / 86_400L to closes.getDouble(i))
            }
        }
        db.upsertPrices(symbol, bars)
        return bars.size
    }

    private fun httpGet(url: String): String {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            // handle redirects manually: HttpURLConnection won't follow
            // cross-host ones (Apps Script) and silently breaks on them
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Finance/2.0")
            val code = conn.responseCode
            if (code in 300..399) {
                current = conn.getHeaderField("Location") ?: error("redirect without location")
                conn.disconnect()
                return@repeat
            }
            require(code in 200..299) { "HTTP $code for $current" }
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        }
        error("too many redirects")
    }
}
