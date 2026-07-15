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

    /**
     * Intraday bars (15-minute closes over the last ~48h) for the 1D view.
     * View-time data only — never persisted; daily closes remain the record.
     */
    fun fetchIntraday(symbol: String): List<Pair<Long, Double>> {
        val enc = URLEncoder.encode(symbol, "UTF-8")
        val body = httpGet(
            "https://query1.finance.yahoo.com/v8/finance/chart/$enc?range=2d&interval=15m"
        )
        val result = org.json.JSONObject(body)
            .getJSONObject("chart").getJSONArray("result").getJSONObject(0)
        val ts = result.optJSONArray("timestamp") ?: return emptyList()
        val closes = result.getJSONObject("indicators")
            .getJSONArray("quote").getJSONObject(0).optJSONArray("close") ?: return emptyList()
        return buildList {
            for (i in 0 until ts.length()) {
                if (!closes.isNull(i)) add(ts.getLong(i) to closes.getDouble(i))
            }
        }
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

    // ---- fundamentals (quoteSummary sits behind Yahoo's cookie+crumb) ----

    /**
     * Raw quoteSummary result JSON (summaryDetail, defaultKeyStatistics,
     * financialData, earningsTrend) for one symbol, or null when Yahoo balks —
     * callers treat absence as "no fundamentals", never an error. A stale
     * crumb gets one fresh retry.
     */
    fun fetchFundamentals(symbol: String): String? {
        for (attempt in 0..1) {
            val (cookie, crumb) = yahooAuth(fresh = attempt > 0) ?: return null
            try {
                val enc = URLEncoder.encode(symbol, "UTF-8")
                val body = httpGet(
                    "https://query1.finance.yahoo.com/v10/finance/quoteSummary/$enc" +
                        "?modules=summaryDetail,defaultKeyStatistics,financialData,earningsTrend&crumb=" +
                        URLEncoder.encode(crumb, "UTF-8"),
                    cookie
                )
                return org.json.JSONObject(body).getJSONObject("quoteSummary")
                    .optJSONArray("result")?.optJSONObject(0)?.toString()
            } catch (e: Exception) {
                if (attempt == 1) return null   // fresh crumb also failed — give up quietly
            }
        }
        return null
    }

    /** Cookie+crumb pair, cached ~12h: any Yahoo hit sets the cookie (the 404
     *  from fc.yahoo.com is expected), then getcrumb exchanges it for a crumb. */
    private fun yahooAuth(fresh: Boolean = false): Pair<String, String>? {
        if (!fresh) {
            val c = prefs.getString("yahooCookie", null)
            val k = prefs.getString("yahooCrumb", null)
            val at = prefs.getLong("yahooAuthAt", 0)
            if (c != null && k != null &&
                System.currentTimeMillis() - at < 12 * 3_600_000L) return c to k
        }
        return try {
            val cookie = mintCookie() ?: mintCookieDirect() ?: return null
            val crumb = httpGet("https://query1.finance.yahoo.com/v1/test/getcrumb", cookie).trim()
            if (crumb.isBlank() || crumb.startsWith("<")) return null
            prefs.edit().putString("yahooCookie", cookie).putString("yahooCrumb", crumb)
                .putLong("yahooAuthAt", System.currentTimeMillis()).apply()
            cookie to crumb
        } catch (_: Exception) { null }
    }

    /** Normal path: one GET to fc.yahoo.com (the 404 is expected) sets the cookie. */
    private fun mintCookie(): String? = try {
        val conn = URL("https://fc.yahoo.com").openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.responseCode
        val cookie = conn.headerFields.entries
            .firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
            ?.value?.mapNotNull { it?.substringBefore(';')?.trim() }
            ?.filter { it.isNotBlank() }?.joinToString("; ")
        conn.disconnect()
        cookie?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /**
     * fc.yahoo.com sits on common ad-block DNS lists (it's a tracker domain
     * that happens to mint Yahoo's auth cookie), so home resolvers often
     * sinkhole it to 127.0.0.1. Fallback: resolve it upstream over DoH and
     * speak TLS to the real edge ourselves — SNI set to the hostname and the
     * certificate verified against it, so this is the same handshake the
     * normal path would have done.
     */
    private fun mintCookieDirect(): String? {
        return try {
            val ip = dohResolve("fc.yahoo.com") ?: return null
            val sf = javax.net.ssl.SSLSocketFactory.getDefault()
            (sf.createSocket() as javax.net.ssl.SSLSocket).use { s ->
                s.connect(java.net.InetSocketAddress(ip, 443), 15_000)
                s.soTimeout = 15_000
                s.sslParameters = s.sslParameters.apply {
                    serverNames = listOf(javax.net.ssl.SNIHostName("fc.yahoo.com"))
                }
                s.startHandshake()
                if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                        .verify("fc.yahoo.com", s.session)) return null
                s.outputStream.write(("GET / HTTP/1.1\r\nHost: fc.yahoo.com\r\n" +
                    "User-Agent: $USER_AGENT\r\nConnection: close\r\n\r\n").toByteArray())
                s.outputStream.flush()
                s.inputStream.bufferedReader().lineSequence()
                    .takeWhile { it.isNotBlank() }
                    .filter { it.startsWith("set-cookie:", ignoreCase = true) }
                    .map { it.substringAfter(':').substringBefore(';').trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("; ").ifBlank { null }
            }
        } catch (_: Exception) { null }
    }

    /** First IPv4 answer for [host] from Cloudflare/Google DoH, sinkholes rejected. */
    private fun dohResolve(host: String): String? = listOf(
        "https://cloudflare-dns.com/dns-query?name=$host&type=A",
        "https://dns.google/resolve?name=$host&type=A",
    ).firstNotNullOfOrNull { u ->
        try {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/dns-json")
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val ans = org.json.JSONObject(body).optJSONArray("Answer")
            (0 until (ans?.length() ?: 0)).asSequence()
                .map { ans!!.getJSONObject(it) }
                .filter { it.optInt("type") == 1 }
                .map { it.optString("data") }
                .firstOrNull {
                    it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) &&
                        !it.startsWith("127.") && it != "0.0.0.0"
                }
        } catch (_: Exception) { null }
    }

    private fun httpGet(url: String, cookie: String? = null): String {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            // handle redirects manually: HttpURLConnection won't follow
            // cross-host ones (Apps Script) and silently breaks on them
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            cookie?.let { conn.setRequestProperty("Cookie", it) }
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

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Android) Finance/2.0"
    }
}
