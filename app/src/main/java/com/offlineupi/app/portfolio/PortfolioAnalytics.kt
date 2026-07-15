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

    /** Assumed India slab for the "after tax" line — a planning number, not a
     *  filing computation. */
    const val TAX_RATE = 0.30

    /** What the money is worth after paying tax on gains: the principal was
     *  bought with post-tax money, so only growth above net contributions is
     *  taxed; a losing position offsets other income, so it adds back. */
    fun afterTax(value: Double, invested: Double) = value - TAX_RATE * (value - invested)

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
        val seriesNative: DoubleArray,     // daily value in the bucket's currency
        val investedNative: DoubleArray,   // cumulative net contributions
        val benchNative: DoubleArray,      // same cashflows in the bucket's index
        val seriesInr: DoubleArray,        // INR mirrors, for cross-bucket blends
        val investedInr: DoubleArray,
        val benchInr: DoubleArray,
        val flowsInr: List<Pair<Int, Double>>,   // for XIRR on arbitrary bucket sets
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

    /** Last computed snapshot — sub-screens reuse it instead of recomputing. */
    @Volatile var cached: Snapshot? = null

    /**
     * Share classes of one company report as a single position. Generic rule:
     * instruments whose names collapse to the same key after stripping share-
     * class designators ("Class A", "Cl C", trailing "-A") — same currency —
     * are one company. GOOG/GOOGL both arrive as "Google", so they fold.
     */
    fun classKey(i: Instrument): String {
        val base = i.name.lowercase()
            .replace(Regex("\\b(class|cl)\\s*[a-c]\\b"), " ")
            .replace(Regex("[-·]\\s*[a-c]$"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return "${i.currency}:$base"
    }

    fun classGroup(inst: Instrument, all: Collection<Instrument>): List<Instrument> {
        val key = classKey(inst)
        return listOf(inst) + all.filter { it.isin != inst.isin && classKey(it) == key }
    }

    /** "Google (GOOG+GOOGL)" — only when there really are multiple classes. */
    fun mergedName(group: List<Instrument>): String =
        if (group.size < 2) group.first().name
        else "${group.first().name} (${group.map { it.yahoo }.sorted().joinToString("+")})"

    /** XIRR by bisection over (dayIndex, cashflow) pairs — for ad-hoc bucket blends. */
    fun xirrOf(flows: List<Pair<Int, Double>>, terminal: Double, lastIdx: Int): Double? {
        if (terminal <= 0 || flows.isEmpty()) return null
        val t0 = flows.minOf { it.first }
        fun npv(rate: Double): Double {
            var acc = 0.0
            for ((k, c) in flows) acc += c / (1 + rate).pow((k - t0) / 365.25)
            return acc - terminal / (1 + rate).pow((lastIdx - t0) / 365.25)
        }
        var lo = -0.9999; var hi = 20.0
        if (npv(lo) * npv(hi) > 0) return null
        repeat(120) {
            val mid = (lo + hi) / 2
            if (npv(lo) * npv(mid) <= 0) hi = mid else lo = mid
        }
        return (lo + hi) / 2
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
        val flowsInrB = Bucket.entries.associateWith { mutableListOf<Pair<Int, Double>>() }
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
            flowsInrB.getValue(b).add(k to cashInr)
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
            val benchNative = DoubleArray(n)
            for (k in 0 until n) {
                while (fi < flows.size && flows[fi].first == k) {
                    val ref = px[b.benchSymbol]?.get(k) ?: Double.NaN
                    if (!ref.isNaN() && ref > 0) units += flows[fi].second / ref
                    fi++
                }
                benchNative[k] = units * (px[b.benchSymbol]?.get(k) ?: 0.0).orZero()
            }
            val cur = valueNatB.getValue(b)[last]
            val benchInr = if (b.currency == "USD")
                DoubleArray(n) { k -> benchNative[k] * fx[k].orZero() } else benchNative
            BucketStat(
                bucket = b, seriesNative = valueNatB.getValue(b),
                investedNative = investedNatB.getValue(b),
                benchNative = benchNative,
                seriesInr = valueInrB.getValue(b),
                investedInr = investedInrB.getValue(b),
                benchInr = benchInr,
                flowsInr = flowsInrB.getValue(b).sortedBy { it.first },
                value = cur, invested = investedNatB.getValue(b)[last],
                valueInr = valueInrB.getValue(b)[last],
                xirr = xirr(flows, cur),
                benchXirr = xirr(flows, benchNative[last]),
                inflXirr = xirr(flows, inflationTerminal(flows, b.cpi)),
                dayPct = dayChange(valueNatB.getValue(b)),
            )
        }

        // ---- holdings ----
        val holdingsRaw = qty.mapNotNull { (isin, qarr) ->
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

        // ---- fold share classes of one company into a single position ----
        val holdings = run {
            val byKey = holdingsRaw.groupBy { classKey(it.instrument) }
            holdingsRaw.mapNotNull { h ->
                val g = byKey.getValue(classKey(h.instrument))
                if (g.size == 1) return@mapNotNull h
                val primary = g.maxBy { it.valueInr }
                if (h !== primary) return@mapNotNull null
                val flows = g.flatMap { flowsNatByIsin[it.instrument.isin].orEmpty() }
                    .sortedBy { it.first }
                val value = g.sumOf { it.value }
                val qtySum = g.sumOf { it.qty }
                primary.copy(
                    instrument = primary.instrument.copy(
                        name = mergedName(g.sortedByDescending { it.valueInr }.map { it.instrument })
                    ),
                    qty = qtySum,
                    price = if (qtySum > 0) value / qtySum else primary.price,
                    value = value,
                    invested = g.sumOf { it.invested },
                    valueInr = g.sumOf { it.valueInr },
                    dayPct = if (value > 0) g.sumOf { it.dayPct * it.value } / value else primary.dayPct,
                    xirr = xirr(flows, value),
                )
            }.sortedByDescending { it.valueInr }
        }

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
        ).also { cached = it }
    }

    // ---------- single-holding detail (native currency throughout) ----------

    data class TradeDot(val idx: Int, val trade: Trade)

    data class HoldingDetail(
        val instrument: Instrument, val bucket: Bucket, val sector: String,
        val days: LongArray,
        val price: DoubleArray,        // native close, NaN where unknown
        val bench: DoubleArray,        // raw benchmark closes (for price-indexed perf)
        val value: DoubleArray,        // qty × close
        val invested: DoubleArray,     // net contributions, step series
        val benchValue: DoubleArray,   // same cashflows into the bucket's index
        val trades: List<Trade>,
        val tradeDots: List<TradeDot>,
        val qty: Double, val priceNow: Double, val valueNow: Double, val investedNow: Double,
        val dayPct: Double,
        val xirr: Double?, val benchXirr: Double?, val inflXirr: Double?,
    )

    fun holdingDetail(db: PortfolioDb, isin: String): HoldingDetail? {
        val all = db.instruments()
        val inst = all.firstOrNull { it.isin == isin } ?: return null
        // all share classes of the company fold into this one view
        val group = classGroup(inst, all)
        val isins = group.map { it.isin }.toSet()
        val trades = db.trades().filter { it.isin in isins }.sortedBy { it.day }
        if (trades.isEmpty()) return null
        val b = bucketOf(inst)

        val rawBy = group.associate { it.isin to db.priceSeries(it.yahoo) }
        val bench = db.priceSeries(b.benchSymbol)
        val fxRaw = db.priceSeries("USDINR=X")
        // the calendar starts at the earliest stored close, not the first buy —
        // Performance should show the security's history before you owned it
        val firstTradeDay = trades.first().day
        val start = minOf(firstTradeDay,
            rawBy.values.mapNotNull { it.firstOrNull()?.first }.minOrNull() ?: firstTradeDay)
        val end = rawBy.values.mapNotNull { it.lastOrNull()?.first }.maxOrNull() ?: return null
        if (end <= start) return null
        val n = (end - start + 1).toInt()
        val days = LongArray(n) { start + it }

        fun ffill(series: List<Pair<Long, Double>>): DoubleArray {
            val out = DoubleArray(n) { Double.NaN }
            var lastV = Double.NaN
            var idx = 0
            // seed with the latest value on/before the window start
            while (idx < series.size && series[idx].first <= start) { lastV = series[idx].second; idx++ }
            out[0] = lastV
            for (k in 1 until n) {
                val day = days[k]
                while (idx < series.size && series[idx].first <= day) { lastV = series[idx].second; idx++ }
                out[k] = lastV
            }
            return out
        }
        val fx = ffill(fxRaw)
        val pxBy = group.associate { g ->
            g.isin to ffill(rawBy.getValue(g.isin)).let { arr ->
                if (g.isin == "GOLD") DoubleArray(n) { k -> arr[k] * fx[k] / OZ_TO_GRAM } else arr
            }
        }
        val px = pxBy.getValue(inst.isin)   // primary class prices the header & trailing returns
        val bx = ffill(bench)

        val qtyBy = group.associate { it.isin to DoubleArray(n) }
        val invested = DoubleArray(n)
        val benchUnits = DoubleArray(n)
        val flows = mutableListOf<Pair<Int, Double>>()
        val dots = mutableListOf<TradeDot>()
        for (t in trades) {
            val k = (t.day - start).toInt().coerceIn(0, n - 1)
            val sign = if (t.side == "buy") 1.0 else -1.0
            qtyBy.getValue(t.isin)[k] += sign * t.qty
            val cash = sign * (t.qty * t.price + t.fee)
            invested[k] += cash
            flows.add(k to cash)
            if (!bx[k].isNaN() && bx[k] > 0) benchUnits[k] += cash / bx[k]
            dots.add(TradeDot(k, t))
        }
        for (arr in qtyBy.values + listOf(invested, benchUnits))
            for (k in 1 until n) arr[k] += arr[k - 1]

        // before the first trade the position doesn't exist — NaN, not 0, so
        // the Value chart starts where ownership does (every consumer already
        // filters NaN/<=0)
        val firstK = (firstTradeDay - start).toInt()
        val value = DoubleArray(n) { k ->
            if (k < firstK) return@DoubleArray Double.NaN
            var v = 0.0
            var missing = false
            for (g in group) {
                val q = qtyBy.getValue(g.isin)[k]
                if (q > 1e-9) {
                    val p = pxBy.getValue(g.isin)[k]
                    if (p.isNaN()) missing = true else v += q * p
                }
            }
            if (missing) Double.NaN else v
        }
        val benchValue = DoubleArray(n) { k -> if (bx[k].isNaN()) Double.NaN else benchUnits[k] * bx[k] }

        val last = n - 1
        val valueNow = value[last].orZero()
        val qtyNow = group.sumOf { qtyBy.getValue(it.isin)[last] }

        fun xirr(terminal: Double): Double? {
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
        val cpiIdx = run {
            val rates = CPI_RATES.getValue(b.cpi)
            val lastRate = rates.values.last()
            var level = 1.0
            DoubleArray(n) { k ->
                val y = LocalDate.ofEpochDay(days[k]).year
                level *= (1 + (rates[y] ?: lastRate) / 100).pow(1 / 365.25)
                level
            }
        }
        val inflTerminal = flows.sumOf { (k, c) -> c * cpiIdx[last] / cpiIdx[k] }

        var prevIdx = last
        while (prevIdx > 0 && px[prevIdx] == px[prevIdx - 1]) prevIdx--
        val prev = if (prevIdx > 0) px[prevIdx - 1] else Double.NaN
        val dayPct = if (!px[last].isNaN() && !prev.isNaN() && prev > 0) (px[last] / prev - 1) * 100 else 0.0

        return HoldingDetail(
            instrument = inst.copy(name = mergedName(group)),
            bucket = b, sector = SECTORS[inst.yahoo] ?: inst.type,
            days = days, price = px, bench = bx,
            value = value, invested = invested, benchValue = benchValue,
            trades = trades, tradeDots = dots,
            qty = qtyNow, priceNow = px[last].orZero(), valueNow = valueNow,
            investedNow = invested[last],
            dayPct = dayPct,
            xirr = xirr(valueNow), benchXirr = xirr(benchValue[last].orZero()),
            inflXirr = xirr(inflTerminal),
        )
    }

    /**
     * Money-weighted (XIRR) return of a visible window [s, e]: the position's
     * worth at the window start counts as the opening stake, trades inside move
     * cash, and the worth at the end closes it out. Annualised, so windows
     * shorter than ~4 weeks return null — extrapolating days is noise.
     */
    fun windowXirr(d: HoldingDetail, s: Int, e: Int): Double? {
        val sIdx = (s..e).firstOrNull { !d.value[it].isNaN() && d.value[it] > 0 } ?: return null
        val eIdx = (e downTo sIdx).firstOrNull { !d.value[it].isNaN() && d.value[it] > 0 } ?: return null
        if (eIdx - sIdx < 28) return null
        // the opening stake already contains any trade dated sIdx (qty applies
        // that same day), so only strictly-later trades are separate flows
        val flows = mutableListOf(sIdx to d.value[sIdx])
        for (t in d.trades) {
            // same coercion holdingDetail applies — a trade dated after the
            // last stored close (weekend buy) still lands on the final day
            val k = (t.day - d.days[0]).toInt().coerceIn(0, d.days.size - 1)
            if (k in (sIdx + 1)..eIdx) {
                val sign = if (t.side == "buy") 1.0 else -1.0
                flows.add(k to sign * (t.qty * t.price + t.fee))
            }
        }
        return xirrOf(flows, d.value[eIdx], eIdx)
    }

    // ---------- price movement over an arbitrary window ----------

    data class MoveRow(val holding: Holding, val movePct: Double, val spark: DoubleArray)

    /** Native-currency price move per holding over the last [daysBack] days (1 = today's move). */
    fun movement(db: PortfolioDb, snap: Snapshot, daysBack: Int): List<MoveRow> {
        val n = snap.days.size
        val last = n - 1
        val fxRaw = db.priceSeries("USDINR=X")
        fun ffill(series: List<Pair<Long, Double>>): DoubleArray {
            val out = DoubleArray(n) { Double.NaN }
            var lastV = Double.NaN
            var idx = 0
            for (k in 0 until n) {
                while (idx < series.size && series[idx].first <= snap.days[k]) { lastV = series[idx].second; idx++ }
                out[k] = lastV
            }
            return out
        }
        val fx = ffill(fxRaw)
        return snap.holdings.map { h ->
            val arr = ffill(db.priceSeries(h.instrument.yahoo)).let { raw ->
                if (h.instrument.isin == "GOLD") DoubleArray(n) { k -> raw[k] * fx[k] / OZ_TO_GRAM } else raw
            }
            val window = max(daysBack, 7)          // a 1-day spark is a dot; show a week
            var k0 = max(0, last - window)
            while (k0 < last && (arr[k0].isNaN() || arr[k0] <= 0)) k0++
            val spark = DoubleArray(last - k0 + 1) { i -> arr[k0 + i].orZero() }
            val pct = if (daysBack <= 1) h.dayPct else run {
                val s = max(0, last - daysBack)
                var i = s
                while (i < last && (arr[i].isNaN() || arr[i] <= 0)) i++
                if (i < last && arr[i] > 0 && !arr[last].isNaN()) (arr[last] / arr[i] - 1) * 100 else 0.0
            }
            MoveRow(h, pct, spark)
        }
    }

    private fun Double.orZero() = if (isNaN()) 0.0 else this
}
