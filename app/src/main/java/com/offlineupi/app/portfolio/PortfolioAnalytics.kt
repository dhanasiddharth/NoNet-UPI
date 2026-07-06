package com.offlineupi.app.portfolio

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Everything derives from trades × daily closes (port of
 * tools/build_design_data.py). Currency policy: INR exists only at the
 * whole-portfolio level; buckets and securities stay in their own currency,
 * and their benchmark/inflation comparisons run in that currency too.
 */
object PortfolioAnalytics {

    const val OZ_TO_GRAM = 31.1035

    enum class Bucket(val label: String, val currency: String, val benchSymbol: String,
                      val benchName: String, val cpi: String) {
        India("India", "INR", "^NSEI", "Nifty 50", "IN"),
        US("US", "USD", "^GSPC", "S&P 500", "US"),
        Gold("Gold", "INR", "^NSEI", "Nifty 50", "IN"),
        Crypto("Crypto", "USD", "^GSPC", "S&P 500", "US"),
    }

    // Approximate CPI (annual %) — replace with MOSPI/BLS series later
    private val CPI_RATES = mapOf(
        "IN" to mapOf(2018 to 3.9, 2019 to 3.7, 2020 to 6.6, 2021 to 5.1, 2022 to 6.7,
            2023 to 5.7, 2024 to 5.0, 2025 to 4.0, 2026 to 4.2),
        "US" to mapOf(2018 to 2.4, 2019 to 1.8, 2020 to 1.2, 2021 to 4.7, 2022 to 8.0,
            2023 to 4.1, 2024 to 2.9, 2025 to 2.8, 2026 to 2.6),
    )

    // Sector map for allocation; anything unknown falls back to its type
    val SECTORS = mapOf(
        "INFY.NS" to "IT", "TCS.NS" to "IT", "OLAELEC.NS" to "Automobile",
        "ASHOKLEY.NS" to "Automobile", "DELHIVERY.NS" to "Logistics",
        "SOUTHBANK.NS" to "Banks", "HDFCBANK.NS" to "Banks", "HDBFS.NS" to "Financials",
        "RAILTEL.NS" to "Telecom Infra", "SWIGGY.NS" to "Consumer Internet", "ITC.NS" to "FMCG",
        "AAPL" to "Technology", "MSFT" to "Technology", "GOOG" to "Communication",
        "GOOGL" to "Communication", "AMZN" to "Consumer Disc.", "NFLX" to "Communication",
        "UBER" to "Industrials", "SHOP" to "Technology", "ASML" to "Semiconductors",
        "SONY" to "Consumer Elec.", "VOO" to "US Large-Cap Index", "CSPX.L" to "US Large-Cap Index",
        "QQQ" to "Nasdaq-100 Index", "MON100.NS" to "Nasdaq-100 Index",
        "0P0000XVE4.BO" to "Nifty 50 Index", "0P0001RQX5.BO" to "LargeMidcap Index",
        "0P0000YWL1.BO" to "Flexi Cap Fund", "0P0000XV5R.BO" to "Midcap Fund",
        "0P0000XVG6.BO" to "Large Cap Fund", "0P0000XVL5.BO" to "Large&Mid Fund",
        "0P0000XWAI.BO" to "Multi Asset Fund", "0P0000XW8D.BO" to "Corporate Bond",
        "GOLDBEES.NS" to "Gold", "GC=F" to "Gold", "BTC-USD" to "Crypto", "ETH-USD" to "Crypto",
    )

    data class BucketStat(
        val bucket: Bucket,
        val seriesNative: DoubleArray,   // daily value in the bucket's currency
        val value: Double, val invested: Double, val valueInr: Double,
        val xirr: Double?, val benchXirr: Double?, val inflXirr: Double?,
        val dayPct: Double,
    )

    data class Holding(
        val instrument: Instrument, val bucket: Bucket, val sector: String,
        val qty: Double, val price: Double,
        val value: Double, val invested: Double,      // native
        val valueInr: Double, val dayPct: Double, val xirr: Double?,
        val spark: DoubleArray,                       // ~30 native closes
    )

    data class Mover(val holding: Holding, val day: Long, val pct: Double)

    data class Snapshot(
        val asOfDay: Long,
        val days: LongArray,               // calendar, epoch days
        val totalInr: DoubleArray, val investedInr: DoubleArray, val benchInr: DoubleArray,
        val buckets: Map<Bucket, BucketStat>,
        val value: Double, val invested: Double,
        val xirr: Double?, val benchXirr: Double?, val inflXirr: Double?,
        val benchValue: Double, val inflValue: Double,
        val holdings: List<Holding>,
        val movers: List<Mover>,           // |1d| >= threshold, last 90d, newest first
    )

    fun bucketOf(i: Instrument): Bucket = when {
        i.isin == "GOLD" -> Bucket.Gold
        i.type.equals("Crypto", true) -> Bucket.Crypto
        i.currency == "USD" -> Bucket.US
        else -> Bucket.India
    }

    fun compute(db: PortfolioDb, moverThresholdPct: Double = 4.0): Snapshot? {
        val instruments = db.instruments().associateBy { it.isin }
        val trades = db.trades()
        if (trades.isEmpty() || instruments.isEmpty()) return null

        // ---- calendar + forward-filled closes ----
        val start = trades.minOf { it.day }
        val symbols = instruments.values.map { it.yahoo }.toSet() + setOf("^NSEI", "^GSPC", "USDINR=X")
        val raw = symbols.associateWith { db.priceSeries(it) }
        val end = raw.values.mapNotNull { it.lastOrNull()?.first }.maxOrNull() ?: return null
        if (end <= start) return null
        val n = (end - start + 1).toInt()
        val days = LongArray(n) { start + it }

        fun ffill(series: List<Pair<Long, Double>>): DoubleArray {
            val out = DoubleArray(n) { Double.NaN }
            var last = Double.NaN
            var idx = 0
            for (k in 0 until n) {
                val day = days[k]
                while (idx < series.size && series[idx].first <= day) { last = series[idx].second; idx++ }
                out[k] = last
            }
            return out
        }
        val px = raw.mapValues { ffill(it.value) }
        val fx = px["USDINR=X"] ?: return null

        fun pxNative(isin: String, k: Int): Double {
            val i = instruments[isin] ?: return Double.NaN
            val p = px[i.yahoo]?.get(k) ?: Double.NaN
            if (p.isNaN()) return Double.NaN
            return if (isin == "GOLD") p * fx[k] / OZ_TO_GRAM else p
        }
        fun pxInr(isin: String, k: Int): Double {
            val i = instruments[isin] ?: return Double.NaN
            val p = pxNative(isin, k)
            if (p.isNaN()) return Double.NaN
            return if (i.currency == "USD" && isin != "GOLD") p * fx[k] else p
        }

        // ---- CPI daily index ----
        fun cpiIndex(country: String): DoubleArray {
            val rates = CPI_RATES.getValue(country)
            val lastRate = rates.values.last()
            var level = 1.0
            return DoubleArray(n) { k ->
                val y = LocalDate.ofEpochDay(days[k]).year
                val r = rates[y] ?: lastRate
                level *= (1 + r / 100).pow(1 / 365.25)
                level
            }
        }
        val cpi = mapOf("IN" to cpiIndex("IN"), "US" to cpiIndex("US"))

        // ---- accumulate qty / invested / benchmark units ----
        val dayIdx = HashMap<Long, Int>(n).also { m -> for (k in 0 until n) m[days[k]] = k }
        val qty = HashMap<String, DoubleArray>()
        val investedInrB = Bucket.entries.associateWith { DoubleArray(n) }
        val investedNatB = Bucket.entries.associateWith { DoubleArray(n) }
        val benchUnits = mutableMapOf("^NSEI" to DoubleArray(n), "^GSPC" to DoubleArray(n))
        val flowsAllInr = mutableListOf<Pair<Int, Double>>()
        val flowsNatB = Bucket.entries.associateWith { mutableListOf<Pair<Int, Double>>() }
        val flowsNatByIsin = HashMap<String, MutableList<Pair<Int, Double>>>()

        for (t in trades) {
            val k = dayIdx[t.day] ?: continue
            val inst = instruments[t.isin] ?: continue
            val b = bucketOf(inst)
            val sign = if (t.side == "buy") 1.0 else -1.0
            qty.getOrPut(t.isin) { DoubleArray(n) }[k] += sign * t.qty
            val cashNat = sign * (t.qty * t.price + t.fee)
            val cashInr = if (inst.currency == "USD" && t.isin != "GOLD") cashNat * fx[k] else cashNat
            investedNatB.getValue(b)[k] += cashNat
            investedInrB.getValue(b)[k] += cashInr
            flowsAllInr.add(k to cashInr)
            flowsNatB.getValue(b).add(k to cashNat)
            flowsNatByIsin.getOrPut(t.isin) { mutableListOf() }.add(k to cashNat)
            val ref = px[b.benchSymbol]?.get(k) ?: Double.NaN
            if (!ref.isNaN() && ref > 0) benchUnits.getValue(b.benchSymbol)[k] += cashNat / ref
        }
        (qty.values + investedInrB.values + investedNatB.values + benchUnits.values).forEach { arr ->
            for (k in 1 until n) arr[k] += arr[k - 1]
        }

        // ---- value series ----
        val valueInrB = Bucket.entries.associateWith { DoubleArray(n) }
        val valueNatB = Bucket.entries.associateWith { DoubleArray(n) }
        for ((isin, qarr) in qty) {
            val inst = instruments[isin] ?: continue
            val b = bucketOf(inst)
            for (k in 0 until n) {
                if (qarr[k] > 1e-9) {
                    val pi = pxInr(isin, k); val pn = pxNative(isin, k)
                    if (!pi.isNaN()) valueInrB.getValue(b)[k] += qarr[k] * pi
                    if (!pn.isNaN()) valueNatB.getValue(b)[k] += qarr[k] * pn
                }
            }
        }
        val totalInr = DoubleArray(n) { k -> Bucket.entries.sumOf { valueInrB.getValue(it)[k] } }
        val totalInvInr = DoubleArray(n) { k -> Bucket.entries.sumOf { investedInrB.getValue(it)[k] } }
        val benchInr = DoubleArray(n) { k ->
            benchUnits.getValue("^NSEI")[k] * (px["^NSEI"]?.get(k) ?: 0.0).orZero() +
                benchUnits.getValue("^GSPC")[k] * (px["^GSPC"]?.get(k) ?: 0.0).orZero() * fx[k].orZero()
        }

        val last = n - 1

        // ---- XIRR ----
        fun xirr(flows: List<Pair<Int, Double>>, terminal: Double): Double? {
            if (terminal <= 0 || flows.isEmpty()) return null
            val t0 = flows.minOf { it.first }
            fun npv(rate: Double): Double {
                var acc = 0.0
                for ((k, c) in flows) acc += c / (1 + rate).pow((k - t0) / 365.25)
                return acc - terminal / (1 + rate).pow((last - t0) / 365.25)
            }
            var lo = -0.9999; var hi = 20.0
            if (npv(lo) * npv(hi) > 0) return null
            repeat(120) {
                val mid = (lo + hi) / 2
                if (npv(lo) * npv(mid) <= 0) hi = mid else lo = mid
            }
            return (lo + hi) / 2
        }

        fun inflationTerminal(flows: List<Pair<Int, Double>>, country: String): Double {
            val idx = cpi.getValue(country)
            return flows.sumOf { (k, c) -> c * idx[last] / idx[k] }
        }

        fun dayChange(arr: DoubleArray): Double {
            var i = last
            while (i > 0 && arr[i] == arr[i - 1]) i--
            val prev = if (i > 0) arr[i - 1] else 0.0
            return if (prev > 0) (arr[last] / prev - 1) * 100 else 0.0
        }

        // ---- per-bucket stats (native currency, native benchmark sim) ----
        val bucketStats = Bucket.entries.associateWith { b ->
            val flows = flowsNatB.getValue(b).sortedBy { it.first }
            var units = 0.0; var fi = 0
            var benchTerminal = 0.0
            val sortedFlows = flows
            for (k in 0 until n) {
                while (fi < sortedFlows.size && sortedFlows[fi].first == k) {
                    val ref = px[b.benchSymbol]?.get(k) ?: Double.NaN
                    if (!ref.isNaN() && ref > 0) units += sortedFlows[fi].second / ref
                    fi++
                }
                if (k == last) benchTerminal = units * (px[b.benchSymbol]?.get(k) ?: 0.0).orZero()
            }
            val cur = valueNatB.getValue(b)[last]
            BucketStat(
                bucket = b, seriesNative = valueNatB.getValue(b),
                value = cur, invested = investedNatB.getValue(b)[last],
                valueInr = valueInrB.getValue(b)[last],
                xirr = xirr(flows, cur),
                benchXirr = xirr(flows, benchTerminal),
                inflXirr = xirr(flows, inflationTerminal(flows, b.cpi)),
                dayPct = dayChange(valueNatB.getValue(b)),
            )
        }

        // ---- holdings ----
        val holdings = qty.mapNotNull { (isin, qarr) ->
            val q = qarr[last]
            if (q <= 1e-9) return@mapNotNull null
            val inst = instruments[isin] ?: return@mapNotNull null
            val b = bucketOf(inst)
            val pn = pxNative(isin, last)
            val flows = flowsNatByIsin[isin].orEmpty().sortedBy { it.first }
            var prevIdx = last
            while (prevIdx > 0 && pxNative(isin, prevIdx) == pxNative(isin, prevIdx - 1)) prevIdx--
            val prev = if (prevIdx > 0) pxNative(isin, prevIdx - 1) else Double.NaN
            val spark = DoubleArray(30) { i ->
                val k = max(0, last - 29 + i); pxNative(isin, k).orZero()
            }
            Holding(
                instrument = inst, bucket = b,
                sector = SECTORS[inst.yahoo] ?: inst.type,
                qty = q, price = pn.orZero(),
                value = q * pn.orZero(),
                invested = flows.sumOf { it.second },
                valueInr = q * pxInr(isin, last).orZero(),
                dayPct = if (!pn.isNaN() && !prev.isNaN() && prev > 0) (pn / prev - 1) * 100 else 0.0,
                xirr = xirr(flows, q * pn.orZero()),
                spark = spark,
            )
        }.sortedByDescending { it.valueInr }

        // ---- recent movers ----
        val movers = mutableListOf<Mover>()
        for (h in holdings) {
            val series = px[h.instrument.yahoo] ?: continue
            for (k in max(1, n - 90) until n) {
                val p0 = series[k - 1]; val p1 = series[k]
                if (!p0.isNaN() && !p1.isNaN() && p0 > 0 && p0 != p1) {
                    val pctMove = (p1 / p0 - 1) * 100
                    if (abs(pctMove) >= moverThresholdPct) movers.add(Mover(h, days[k], pctMove))
                }
            }
        }
        movers.sortByDescending { it.day }

        val flowsAll = flowsAllInr.sortedBy { it.first }
        val inflTerm = inflationTerminal(flowsAll, "IN")   // everything vs India CPI
        return Snapshot(
            asOfDay = days[last], days = days,
            totalInr = totalInr, investedInr = totalInvInr, benchInr = benchInr,
            buckets = bucketStats,
            value = totalInr[last], invested = totalInvInr[last],
            xirr = xirr(flowsAll, totalInr[last]),
            benchXirr = xirr(flowsAll, benchInr[last]),
            inflXirr = xirr(flowsAll, inflTerm),
            benchValue = benchInr[last], inflValue = inflTerm,
            holdings = holdings,
            movers = movers,
        )
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this
}
