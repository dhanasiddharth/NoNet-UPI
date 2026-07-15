package com.offlineupi.app.portfolio

import org.json.JSONObject
import kotlin.math.sqrt

/**
 * The value-investing basics for the security page — 52W range, P/E, P/B,
 * EPS, book value, market cap, dividend yield, plus the inputs the VALUATION
 * checks need (growth, ROE, leverage, street target) — parsed from a cached
 * Yahoo quoteSummary result. Whatever Yahoo doesn't report for an asset class
 * (crypto has no P/E, funds no book value) stays null and its tile simply
 * doesn't render.
 */
data class Fundamentals(
    val high52: Double?, val low52: Double?,
    val pe: Double?, val pb: Double?, val eps: Double?, val bookValue: Double?,
    val marketCap: Double?,
    val divYield: Double?,   // fraction, e.g. 0.0046 = 0.46%
    val roe: Double?,        // fraction
    val debtToEquity: Double?,   // ratio, e.g. 0.42
    val pegRatio: Double?,       // Yahoo's own, when present
    val growthNextY: Double?,    // consensus +1y EPS growth, fraction
    val targetMean: Double?,     // mean 12-month analyst target, native ccy
    val analystCount: Int?,
    val fetchedAt: Long,
) {
    /** Graham's defensive ceiling √(22.5 × EPS × book value) — only meaningful
     *  with real positive earnings and book. */
    val grahamNumber: Double?
        get() = if ((eps ?: 0.0) > 0 && (bookValue ?: 0.0) > 0)
            sqrt(22.5 * eps!! * bookValue!!) else null

    /** EPS ÷ price — the yield the business earns on today's price. */
    fun earningsYield(price: Double): Double? =
        eps?.takeIf { it > 0 && price > 0 }?.let { it / price }

    /** Lynch's PEG: Yahoo's when reported, else trailing P/E ÷ consensus growth. */
    val peg: Double?
        get() = pegRatio?.takeIf { it > 0 }
            ?: pe?.takeIf { it > 0 }?.let { p ->
                growthNextY?.takeIf { it > 0.005 }?.let { g -> p / (g * 100) }
            }

    companion object {
        /** Rough 10Y sovereign-yield anchors for the earnings-yield check.
         *  Precision doesn't matter — the comparison does. Nudge occasionally. */
        fun tenYearYield(currency: String) = if (currency == "USD") 0.043 else 0.065

        fun parse(json: String, fetchedAt: Long): Fundamentals? = try {
            val o = JSONObject(json)
            fun raw(module: String, field: String): Double? =
                o.optJSONObject(module)?.optJSONObject(field)
                    ?.takeIf { it.has("raw") }?.optDouble("raw")
                    ?.takeIf { !it.isNaN() }
            // consensus EPS growth for the coming year, from the trend table
            val growth = o.optJSONObject("earningsTrend")?.optJSONArray("trend")?.let { arr ->
                (0 until arr.length()).asSequence()
                    .mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { it.optString("period") == "+1y" }
                    ?.optJSONObject("growth")?.takeIf { it.has("raw") }
                    ?.optDouble("raw")?.takeIf { !it.isNaN() }
            }
            Fundamentals(
                high52 = raw("summaryDetail", "fiftyTwoWeekHigh"),
                low52 = raw("summaryDetail", "fiftyTwoWeekLow"),
                pe = raw("summaryDetail", "trailingPE")
                    ?: raw("defaultKeyStatistics", "trailingPE"),
                pb = raw("defaultKeyStatistics", "priceToBook"),
                eps = raw("defaultKeyStatistics", "trailingEps"),
                bookValue = raw("defaultKeyStatistics", "bookValue"),
                marketCap = raw("summaryDetail", "marketCap"),
                // guard the odd percent-not-fraction variant
                divYield = raw("summaryDetail", "dividendYield")
                    ?.let { if (it > 1) it / 100 else it },
                roe = raw("financialData", "returnOnEquity"),
                // Yahoo reports D/E as a percent (41.5 = 0.415×)
                debtToEquity = raw("financialData", "debtToEquity")?.let { it / 100 },
                pegRatio = raw("defaultKeyStatistics", "pegRatio"),
                growthNextY = growth,
                targetMean = raw("financialData", "targetMeanPrice"),
                analystCount = raw("financialData", "numberOfAnalystOpinions")?.toInt(),
                fetchedAt = fetchedAt,
            )
        } catch (_: Exception) { null }
    }
}
