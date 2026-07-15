package com.offlineupi.app.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.offlineupi.app.R
import com.offlineupi.app.databinding.ActivityHoldingDetailBinding
import com.offlineupi.app.portfolio.MoneyFmt
import com.offlineupi.app.portfolio.PortfolioAnalytics
import com.offlineupi.app.portfolio.PortfolioAnalytics.HoldingDetail
import com.offlineupi.app.portfolio.PortfolioDb
import com.offlineupi.app.ui.charts.LineChartView
import com.offlineupi.app.worker.PriceSyncWorker
import com.offlineupi.app.util.TimeFmt
import com.offlineupi.app.util.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Single-holding view, native currency. Two charts by design: performance
 * (you vs the same cashflows in the bucket's index, both as % of invested)
 * and value (with invested line and buy/sell dots).
 */
class HoldingDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHoldingDetailBinding
    private val db by lazy { PortfolioDb(this) }

    private var detail: HoldingDetail? = null
    private var fund: com.offlineupi.app.portfolio.Fundamentals? = null
    /** Freshest unit price from the intraday pull; falls back to the daily close. */
    private var livePrice: Double? = null
    private var range = "1Y"
    private val ranges = listOf("1D", "1W", "1M", "3M", "6M", "1Y", "3Y", "5Y", "All")
    private val chartModes = listOf("Performance", "Value")
    private var chartMode = "Performance"
    private var tradesExpanded = false
    /** 15-minute bars (epochSec → native price) for the 1D view; null = daily fallback. */
    private var intraday: List<Pair<Long, Double>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHoldingDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        binding.chart.onViewportChange = { s, e ->
            detail?.let { updateWindowStats(it, s, e) }
        }

        val isin = intent.getStringExtra("isin") ?: run { finish(); return }
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { PortfolioAnalytics.holdingDetail(db, isin) }
            if (d == null) { finish(); return@launch }
            detail = d
            // cached fundamentals paint with the first frame; refresh comes later
            val cachedF = withContext(Dispatchers.IO) { db.fundamentals(d.instrument.yahoo) }
            fund = cachedF?.let { com.offlineupi.app.portfolio.Fundamentals.parse(it.first, it.second) }
            render(d)
            // daily bars can't draw a real 1D — pull ~48h of 15m bars in the
            // background and re-render if the user is (or lands) on 1D
            val bars = withContext(Dispatchers.IO) {
                runCatching { fetchIntradayNative(d) }.getOrNull()
            }
            if (bars != null && bars.size >= 2) {
                intraday = bars
                // freshest price flows into the value hero, the qty chip and
                // the 52W marker (the hero stays the position's total worth)
                livePrice = bars.last().second
                // persist it as today's close so the overview & position value
                // reflect the current price too. GOLD's intraday is converted
                // (₹/g) while the price table holds raw Yahoo ($/oz) — skip it.
                if (d.instrument.isin != "GOLD") withContext(Dispatchers.IO) {
                    db.upsertPrices(d.instrument.yahoo, listOf(bars.last().first / 86_400L to bars.last().second))
                }
                renderHeader(d)
                renderFundamentals(d)
                if (range == "1D") renderChart(d)
            }
            // fundamentals older than a day refresh quietly (GOLD has none —
            // its Yahoo quote is $/oz while the page runs on ₹/g). Age is the
            // only gate: sniffing the JSON for a module doesn't work, funds
            // legitimately never have financialData.
            if (d.instrument.isin != "GOLD" &&
                (cachedF == null || System.currentTimeMillis() - cachedF.second > 24 * 3_600_000L)
            ) {
                val fresh = withContext(Dispatchers.IO) {
                    runCatching {
                        com.offlineupi.app.portfolio.PortfolioSync(this@HoldingDetailActivity, db)
                            .fetchFundamentals(d.instrument.yahoo)
                            ?.also { db.setFundamentals(d.instrument.yahoo, it) }
                    }.getOrNull()
                }
                if (fresh != null) {
                    fund = com.offlineupi.app.portfolio.Fundamentals.parse(fresh, System.currentTimeMillis())
                    renderFundamentals(d)
                }
            }
        }
    }

    private fun fetchIntradayNative(d: HoldingDetail): List<Pair<Long, Double>> {
        val raw = com.offlineupi.app.portfolio.PortfolioSync(this, db)
            .fetchIntraday(d.instrument.yahoo)
        if (d.instrument.isin != "GOLD") return raw
        val fx = db.priceSeries("USDINR=X").lastOrNull()?.second ?: return emptyList()
        return raw.map { it.first to it.second * fx / PortfolioAnalytics.OZ_TO_GRAM }
    }

    private val color get() = PortfolioUi.bucketColors.getValue(detail!!.bucket)
    private val ccy get() = detail!!.instrument.currency

    private fun curPrice(d: HoldingDetail) = livePrice ?: d.priceNow

    /** Position worth: valueNow covers every share class (GOOG+GOOGL merge);
     *  a live tick scales it by the primary class's move — exact for single-
     *  class holdings, proportional for merged ones. */
    private fun curWorth(d: HoldingDetail): Double =
        if (livePrice != null && d.priceNow > 0) d.valueNow * (livePrice!! / d.priceNow)
        else d.valueNow

    /** Hero = position's total worth, unit price + qty quiet on its right;
     *  XIRR leads the chip row — the page's headline return is money-weighted,
     *  not the raw price move. */
    @SuppressLint("SetTextI18n")
    private fun renderHeader(d: HoldingDetail) {
        val worth = curWorth(d)
        binding.tvPrice.text = MoneyFmt.money(worth, ccy)
        binding.tvAfterTax.text = "≈ ${MoneyFmt.money(
            PortfolioAnalytics.afterTax(worth, d.investedNow), ccy)} after tax"
        chip(binding.tvXirrChip, d.xirr?.let { it >= 0 },
            d.xirr?.let { "XIRR ${MoneyFmt.pct(it)}/yr" } ?: "XIRR —")
        binding.tvUnitPrice.text = MoneyFmt.price(curPrice(d), ccy)
        val qtyTxt = if (d.qty == Math.floor(d.qty) && d.qty < 1e6) "%.0f".format(d.qty)
            else "%.4f".format(d.qty).trimEnd('0').trimEnd('.')
        binding.tvQty.text = "$qtyTxt " + if (d.instrument.isin == "GOLD") "grams" else "units"
    }

    @SuppressLint("SetTextI18n")
    private fun render(d: HoldingDetail) {
        binding.tvName.text = d.instrument.name
        binding.tvSub.text = "${d.sector} · ${d.bucket.label} · vs ${d.bucket.benchName}"
        binding.tvPerBenchCap.text = "VS ${d.bucket.benchName.uppercase()}"
        renderHeader(d)

        renderRanges()
        renderChartModes()
        renderChart(d)
        renderReturns(d)
        renderFundamentals(d)
        renderStats(d)
        renderTrades(d)
    }

    private fun renderRanges() {
        val wrap = binding.layoutRanges
        wrap.removeAllViews()
        for (r in ranges) {
            val tv = TextView(this).apply {
                text = r
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(5))
                setBackgroundResource(if (r == range) R.drawable.bg_pill_selected else R.drawable.bg_input_pill)
                setTextColor(getColor(if (r == range) R.color.accent_emerald_light else R.color.text_secondary))
                setOnClickListener {
                    range = r
                    detail?.let { d -> renderRanges(); renderChart(d) }
                }
            }
            wrap.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (r != ranges.last()) marginEnd = dp(6) })
        }
    }

    private fun startIndex(d: HoldingDetail): Int {
        val back = when (range) {
            "1D" -> 1L; "1W" -> 7L; "1M" -> 30L; "3M" -> 91L; "6M" -> 182L
            "1Y" -> 365L; "3Y" -> 1095L; "5Y" -> 1826L
            else -> return 0
        }
        val i = d.days.indexOfFirst { it >= d.days.last() - back }
        return if (i < 0) 0 else i
    }

    /**
     * Hero delta chip + chart stat follow whatever window is on screen — the
     * selected range at first, then the panned window as the user drags.
     */
    @SuppressLint("SetTextI18n")
    private fun updateWindowStats(d: HoldingDetail, s: Int, e: Int) {
        val n = d.days.size
        fun spanPct(arr: DoubleArray): Double? {
            val a = (s..e).firstOrNull { !arr[it].isNaN() && arr[it] > 0 }?.let { arr[it] } ?: return null
            val b = (e downTo s).firstOrNull { !arr[it].isNaN() && arr[it] > 0 }?.let { arr[it] } ?: return null
            return (b / a - 1) * 100
        }
        val move = spanPct(d.price)
        val atLatest = e >= n - 1
        val fmtD = DateTimeFormatter.ofPattern("d MMM")
        val label = if (atLatest) range else
            "${LocalDate.ofEpochDay(d.days[s]).format(fmtD)}–${LocalDate.ofEpochDay(d.days[e]).format(fmtD)}"
        chip(binding.tvDayChip,
            move?.let { if (abs(it) < 0.05) null else it > 0 },
            "${move?.let { MoneyFmt.signedPct(it) } ?: "—"} · $label")

        // period breakdown: money added, then the true gain — worth now minus
        // (worth at the window start + money put in during it), so deposits
        // don't masquerade as gains — and the window's money-weighted XIRR.
        // The raw price move already lives in the chip above.
        val invested = d.invested[e] - d.invested[s]
        // the gain's invested-delta anchors at the first OWNED day, not the
        // window start — the opening value already contains that day's buy,
        // so counting it again would double-subtract (windows can now begin
        // before the position existed)
        val sIdx = (s..e).firstOrNull { !d.value[it].isNaN() && d.value[it] > 0 }
        val vS = sIdx?.let { d.value[it] }
        val vE = (e downTo s).firstOrNull { !d.value[it].isNaN() && d.value[it] > 0 }?.let { d.value[it] }
        val gain = if (sIdx != null && vS != null && vE != null)
            (vE - vS) - (d.invested[e] - d.invested[sIdx]) else null
        val bench = spanPct(d.bench)
        val gap = if (move != null && bench != null) move - bench else null
        setPeriod(invested, gain, PortfolioAnalytics.windowXirr(d, s, e), gap)
    }

    /** The four window stats in the strip under the chart. null → "—";
     *  gain/XIRR/gap get a green/red tone, invested stays neutral (adding
     *  money isn't good or bad). [xirr] is a fraction/yr — null for windows
     *  too short to annualise; [benchGap] is percentage points vs the index. */
    @SuppressLint("SetTextI18n")
    private fun setPeriod(investedDelta: Double?, gain: Double?, xirr: Double?, benchGap: Double?) {
        fun signedMoney(v: Double) = (if (v >= 0) "+" else "−") + MoneyFmt.money(abs(v), ccy)
        fun tone(tv: TextView, v: Double?, dead: Double) = tv.setTextColor(getColor(when {
            v == null || abs(v) < dead -> R.color.text_primary
            v > 0 -> R.color.accent_green
            else -> R.color.accent_red
        }))
        binding.tvPerInvested.text = investedDelta?.let {
            if (abs(it) < 1.0) "—" else signedMoney(it)
        } ?: "—"
        binding.tvPerChange.text = gain?.let { signedMoney(it) } ?: "—"
        tone(binding.tvPerChange, gain, 1e-6)
        binding.tvPerReturn.text = xirr?.let { MoneyFmt.pct(it) } ?: "—"
        tone(binding.tvPerReturn, xirr, 0.0005)
        binding.tvPerBench.text = benchGap?.let {
            "${if (it >= 0) "+" else ""}%.1f pts".format(it) } ?: "—"
        tone(binding.tvPerBench, benchGap, 0.05)
    }

    /** Compact Perf/Value lens toggle, living in the legend row it controls. */
    private fun renderChartModes() {
        val wrap = binding.layoutChartModes
        wrap.removeAllViews()
        for (m in chartModes) {
            val tv = TextView(this).apply {
                text = if (m == "Performance") "Perf" else m
                textSize = 10.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(3), dp(10), dp(3))
                setBackgroundResource(if (m == chartMode) R.drawable.bg_pill_selected else R.drawable.bg_input_pill)
                setTextColor(getColor(if (m == chartMode) R.color.accent_emerald_light else R.color.text_secondary))
                setOnClickListener {
                    if (chartMode == m) return@setOnClickListener
                    chartMode = m
                    detail?.let { d -> renderChartModes(); renderChart(d) }
                }
            }
            wrap.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (m != chartModes.last()) marginEnd = dp(4) })
        }
    }

    /** One chart, two lenses: performance strips contributions; value shows them. */
    @SuppressLint("SetTextI18n")
    private fun renderChart(d: HoldingDetail) {
        val bars = intraday
        if (range == "1D" && bars != null && bars.size >= 2) {
            renderIntraday(d, bars)
            return
        }
        binding.chart.xLabelFormatter = null

        val i0 = startIndex(d)
        val n = d.days.size
        val win = n - i0
        val dateFmt = DateTimeFormatter.ofPattern("d MMM yy")
        val dots = d.tradeDots.map { td ->
            val buy = td.trade.side == "buy"
            val cash = td.trade.qty * td.trade.price + td.trade.fee
            LineChartView.Dot(td.idx, value = if (buy) cash else -cash,
                label = (if (buy) "+" else "−") + MoneyFmt.money(cash, ccy), up = buy)
        }
        binding.chart.dotLabelFormatter =
            { v -> (if (v >= 0) "+" else "−") + MoneyFmt.money(abs(v), ccy) }
        // the performance line gets busy — hide trade amounts until scrubbed onto
        binding.chart.scrubDotLabels = chartMode == "Performance"

        if (chartMode == "Performance") {
            // cumulative price % over full history; the chart re-anchors 0% to
            // the visible window, so both ranges and panning stay pure price
            // moves — buys/sells appear only as markers
            fun indexedFull(series: DoubleArray): DoubleArray {
                var base = Double.NaN
                var k = 0
                while (k < n && (series[k].isNaN() || series[k] <= 0)) k++
                if (k < n) base = series[k]
                return DoubleArray(n) { j ->
                    val v = series[j]
                    if (v.isNaN() || v <= 0 || base.isNaN()) Double.NaN else (v / base - 1) * 100
                }
            }
            val you = indexedFull(d.price)
            val bench = indexedFull(d.bench)
            binding.chart.allowNegative = true
            binding.chart.yFormatter = { "%.0f%%".format(it) }
            binding.chart.scrubFormatter = { idx, day, v ->
                val px = d.price.getOrNull(idx)?.takeIf { !it.isNaN() }
                LocalDate.ofEpochDay(day).format(dateFmt) +
                    (px?.let { " · ${MoneyFmt.price(it, ccy)}" } ?: "") +
                    " · ${if (v >= 0) "+" else ""}${"%.1f".format(v)}%"
            }
            binding.chart.set(listOf(
                LineChartView.Series(you, color, area = true),
                LineChartView.Series(bench, getColor(R.color.chart_muted), widthDp = 1.8f),
            ), d.days, dots, win, rebase = true)
            binding.tvChartLegend.text = "— price   — ${d.bucket.benchName}   ● trades"
        } else {
            binding.chart.allowNegative = false
            binding.chart.yFormatter = { MoneyFmt.axis(it, ccy) }
            binding.chart.scrubFormatter = { idx, day, v ->
                val px = d.price.getOrNull(idx)?.takeIf { !it.isNaN() }
                LocalDate.ofEpochDay(day).format(dateFmt) +
                    (px?.let { " · ${MoneyFmt.price(it, ccy)}" } ?: "") +
                    " · ${MoneyFmt.money(v, ccy)}"
            }
            binding.chart.set(listOf(
                LineChartView.Series(d.value, color, area = true),
                LineChartView.Series(d.invested, getColor(R.color.chart_muted), widthDp = 1.4f, dashed = true),
            ), d.days, dots, win, rebase = false)
            binding.tvChartLegend.text = "— value   ┈ invested   ● trades"
        }

        // stats follow the chart's actual viewport (kept across lens switches)
        val (vs, ve) = binding.chart.viewport()
        updateWindowStats(d, vs, ve)
    }

    /** 1D from live 15-minute bars (~48h). No benchmark line: index bars keep
     *  different market hours, so intraday shows the security alone. */
    @SuppressLint("SetTextI18n")
    private fun renderIntraday(d: HoldingDetail, bars: List<Pair<Long, Double>>) {
        val secs = LongArray(bars.size) { bars[it].first }
        val px = DoubleArray(bars.size) { bars[it].second }
        fun t(s: Long): String = TimeFmt.intraday(s)   // IST, am/pm
        binding.chart.xLabelFormatter = { s -> t(s) }

        val first = px.firstOrNull { it > 0 } ?: return
        val last = px.lastOrNull { it > 0 } ?: return
        val movePct = (last / first - 1) * 100

        if (chartMode == "Performance") {
            val pct = DoubleArray(px.size) { if (px[it] > 0) (px[it] / first - 1) * 100 else Double.NaN }
            binding.chart.allowNegative = true
            binding.chart.yFormatter = { "%.1f%%".format(it) }
            binding.chart.scrubFormatter = { idx, s, v ->
                "${t(s)} · ${MoneyFmt.price(px[idx], ccy)} · ${if (v >= 0) "+" else ""}${"%.1f".format(v)}%"
            }
            binding.chart.set(listOf(LineChartView.Series(pct, color, area = true)), secs)
            binding.tvChartLegend.text = "— price · 15m bars · last 48h"
        } else {
            val vals = DoubleArray(px.size) { if (px[it] > 0) d.qty * px[it] else Double.NaN }
            binding.chart.allowNegative = false
            binding.chart.yFormatter = { MoneyFmt.axis(it, ccy) }
            binding.chart.scrubFormatter = { idx, s, v ->
                "${t(s)} · ${MoneyFmt.price(px[idx], ccy)} · ${MoneyFmt.money(v, ccy)}"
            }
            binding.chart.set(listOf(LineChartView.Series(vals, color, area = true)), secs)
            binding.tvChartLegend.text = "— value · 15m bars · last 48h"
        }
        chip(binding.tvDayChip, if (abs(movePct) < 0.05) null else movePct > 0,
            "${MoneyFmt.signedPct(movePct)} · 48h")
        // no trade data or benchmark in the intraday feed, and annualising 48h
        // is noise — only the gain stat carries a number here
        setPeriod(null, d.qty * (last - first), null, null)
    }

    /** RETURNS owns every %/yr number: the trailing money-weighted line
     *  (what YOUR cash in this position earned per year over each window,
     *  contribution-aware, ending with since-inception) as plain text — the
     *  pill look read as buttons — plus the lifetime edge vs the bucket's
     *  index and inflation. */
    @SuppressLint("SetTextI18n")
    private fun renderReturns(d: HoldingDetail) {
        val wrap = binding.layoutXirrLine
        wrap.removeAllViews()
        val n = d.days.size
        val firstTradeDay = d.trades.first().day
        val entries = listOf("1Y" to 365, "3Y" to 1095, "5Y" to 1826)
            .mapNotNull { (label, back) ->
                val s = n - 1 - back
                // a window that opens before the first buy is just "All" again —
                // only show windows the position fully spans
                if (s < 0 || d.days[s] < firstTradeDay) null
                else label to PortfolioAnalytics.windowXirr(d, s, n - 1)
            } + ("All" to d.xirr)
        for ((label, r) in entries) {
            wrap.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(5) })
            wrap.addView(TextView(this).apply {
                text = MoneyFmt.pct(r)
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(getColor(when {
                    r == null -> R.color.text_secondary
                    r >= 0 -> R.color.accent_green
                    else -> R.color.accent_red
                }))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { if (label != "All") marginEnd = dp(14) })
        }

        val dIdx = if (d.xirr != null && d.benchXirr != null) (d.xirr - d.benchXirr) * 100 else null
        val dInf = if (d.xirr != null && d.inflXirr != null) (d.xirr - d.inflXirr) * 100 else null
        fun pp(v: Double?) = v?.let { "${if (it >= 0) "+" else ""}%.1f pp".format(it) } ?: "—"
        fun tint(v: Double?) = v?.let {
            getColor(if (it >= 0) R.color.accent_green else R.color.accent_red) }
        binding.layoutReturnsTiles.removeAllViews()
        statGrid(binding.layoutReturnsTiles, listOf(
            Cell("vs ${d.bucket.benchName} (lifetime)", pp(dIdx), tint(dIdx)),
            Cell("vs ${d.bucket.cpi} CPI (lifetime)", pp(dInf), tint(dInf)),
        ), fullWidth = true)
    }

    private data class Cell(val k: String, val v: String, val color: Int? = null,
                            val onTap: (() -> Unit)? = null)

    /** 3-column stat tiles (mockup's statgrid) — shared by POSITION, RETURNS &
     *  the facts grid. [fullWidth] lets a short row stretch to fill the line
     *  instead of leaving ghost columns. */
    private fun statGrid(wrap: LinearLayout, cells: List<Cell>, fullWidth: Boolean = false) {
        cells.chunked(3).forEach { rowCells ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = if (fullWidth) rowCells.size.toFloat() else 3f
            }
            rowCells.forEachIndexed { i, c ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_detail_group)
                    setPadding(dp(10), dp(9), dp(10), dp(9))
                    c.onTap?.let { tap -> setOnClickListener { tap() } }
                }
                tile.addView(TextView(this).apply {
                    text = c.k.uppercase()
                    textSize = 9.5f
                    letterSpacing = 0.06f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(getColor(R.color.text_secondary))
                    maxLines = 1
                })
                tile.addView(TextView(this).apply {
                    text = c.v
                    textSize = 13.5f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(c.color ?: getColor(R.color.text_primary))
                    maxLines = 1
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
                row.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (i < rowCells.size - 1) marginEnd = dp(8) })
            }
            wrap.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) })
        }
    }

    /**
     * The value-investing basics (Screener/Morningstar-style, not trading
     * noise): where today's price sits in the 52-week range, then the core
     * ratios. 52W comes from Yahoo when cached (true intraday extremes),
     * else from the stored daily closes — so it renders even offline.
     */
    @SuppressLint("SetTextI18n")
    private fun renderFundamentals(d: HoldingDetail) {
        val f = fund
        var lo = Double.MAX_VALUE; var hi = 0.0
        val from = d.days.last() - 365
        for (k in d.days.indices) {
            if (d.days[k] < from) continue
            val v = d.price[k]
            if (!v.isNaN() && v > 0) { if (v < lo) lo = v; if (v > hi) hi = v }
        }
        val yahooOk = d.instrument.isin != "GOLD" && f != null &&
            f.low52 != null && f.high52 != null && f.high52 > f.low52
        val low = if (yahooOk) f!!.low52 else lo.takeIf { it != Double.MAX_VALUE }
        val high = if (yahooOk) f!!.high52 else hi.takeIf { it > 0 }

        val cur = curPrice(d)
        if (low != null && high != null && high > low) {
            binding.rangeBar.visibility = View.VISIBLE
            binding.tvFundNote.visibility = View.VISIBLE
            binding.rangeBar.set(low, high, cur, color) { MoneyFmt.price(it, ccy) }
            binding.tvFundNote.text = when {
                cur >= high * 0.995 -> "trading at its 52-week high"
                cur <= low * 1.005 -> "trading at its 52-week low"
                else -> "%.1f%% below 52W high · %.1f%% above 52W low"
                    .format((1 - cur / high) * 100, (cur / low - 1) * 100)
            }
        } else {
            binding.rangeBar.visibility = View.GONE
            binding.tvFundNote.visibility = View.GONE
        }

        val cells = buildList {
            f?.marketCap?.takeIf { it > 0 }?.let { add(Cell("Mkt cap", MoneyFmt.cap(it, ccy))) }
            f?.pe?.takeIf { it > 0 }?.let { add(Cell("P/E (TTM)", "%.1f".format(it))) }
            f?.pb?.takeIf { it > 0 }?.let { add(Cell("P/B", "%.2f".format(it))) }
            f?.eps?.let { add(Cell("EPS (TTM)", MoneyFmt.price(it, ccy))) }
            f?.bookValue?.takeIf { it > 0 }?.let { add(Cell("Book value", MoneyFmt.price(it, ccy))) }
            f?.divYield?.takeIf { it > 0 }?.let { add(Cell("Div yield", "%.2f%%".format(it * 100))) }
            f?.roe?.let { add(Cell("ROE", "%.1f%%".format(it * 100))) }
            f?.debtToEquity?.let { add(Cell("Debt/equity", "%.2f".format(it))) }
        }
        binding.layoutFund.removeAllViews()
        binding.layoutFund.visibility = if (cells.isEmpty()) View.GONE else View.VISIBLE
        if (cells.isNotEmpty()) statGrid(binding.layoutFund, cells)

        binding.tvFundMeta.text = when {
            f != null -> "Yahoo · ${age(f.fetchedAt)}"
            else -> "52W from price history"
        }
        renderValuation(d)
    }

    /**
     * VALUATION — the classic value-investing checks, each an independent lens
     * on today's price rather than one fake-precise "fair value":
     *  · Graham number √(22.5·EPS·book): the defensive-investor ceiling
     *    (P/E ≤ 15 and P/B ≤ 1.5 combined). Conservative for asset-light
     *    businesses, so it's one lens, not the verdict.
     *  · Earnings yield (EPS ÷ price) against the 10Y government bond — is
     *    the business out-earning the risk-free alternative?
     *  · PEG (P/E ÷ expected growth): Lynch's growth-adjusted price tag.
     *  · Street 12-month mean target — opinion, shown last as a reference.
     * Only renders for things with earnings (stocks); funds/ETFs/gold hide it.
     */
    @SuppressLint("SetTextI18n")
    private fun renderValuation(d: HoldingDetail) {
        val f = fund
        val cur = curPrice(d)
        data class Check(val title: String, val sub: String, val value: String,
                         val chip: String, val up: Boolean?, val isOpinion: Boolean = false)
        val rows = buildList {
            if (f == null || d.instrument.isin == "GOLD" || cur <= 0) return@buildList
            f.grahamNumber?.let { g ->
                val gap = (cur / g - 1) * 100
                add(Check("Graham number", "√(22.5 × EPS × book) — defensive ceiling",
                    MoneyFmt.price(g, ccy),
                    if (gap >= 0) "price %.0f%% above".format(gap)
                    else "price %.0f%% below".format(-gap),
                    gap < 0))
            }
            f.earningsYield(cur)?.let { ey ->
                val bond = com.offlineupi.app.portfolio.Fundamentals.tenYearYield(ccy)
                add(Check("Earnings yield", "EPS ÷ price, vs the 10-year govt bond",
                    "%.1f%%".format(ey * 100),
                    "10Y ≈ %.1f%%".format(bond * 100),
                    when { ey >= bond -> true; ey >= bond * 0.6 -> null; else -> false }))
            }
            f.peg?.let { peg ->
                add(Check("PEG ratio", "P/E ÷ expected earnings growth",
                    "%.2f".format(peg),
                    when { peg < 1 -> "cheap for its growth"
                        peg <= 2 -> "fair for its growth"
                        else -> "rich for its growth" },
                    when { peg < 1 -> true; peg <= 2 -> null; else -> false }))
            }
            f.targetMean?.takeIf { it > 0 }?.let { t ->
                val up = (t / cur - 1) * 100
                add(Check("Street target",
                    "12-month mean" + (f.analystCount?.let { " · $it analysts" } ?: ""),
                    MoneyFmt.price(t, ccy),
                    "${MoneyFmt.signedPct(up)} vs price", up >= 0, isOpinion = true))
            }
        }
        // the merged section is titled by what it can show: judgment when the
        // checks exist (stocks), plain facts otherwise (funds/ETFs/gold)
        binding.tvFundTitle.text = if (rows.isEmpty()) "FUNDAMENTALS" else "VALUATION"
        binding.layoutVal.removeAllViews()
        binding.tvValNote.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        if (rows.isEmpty()) return

        // headline reads the value checks only — analyst targets are opinion.
        // Tinted banner: the verdict is the payoff, it shouldn't whisper.
        val checks = rows.filter { !it.isOpinion }
        val cheap = checks.count { it.up == true }
        val rich = checks.count { it.up == false }
        val verdict: Boolean? = when {
            checks.isEmpty() -> null
            rich > cheap -> false
            cheap > rich -> true
            else -> null
        }
        binding.tvValNote.text = when {
            checks.isEmpty() -> "not enough data for the value checks"
            rich > cheap -> "${rich} of ${checks.size} value checks read the price as expensive"
            cheap > rich -> "${cheap} of ${checks.size} value checks read the price as cheap"
            else -> "the value checks are mixed at today's price"
        }
        binding.tvValNote.setBackgroundResource(R.drawable.bg_banner)
        binding.tvValNote.background.mutate().setTint(getColor(when (verdict) {
            true -> R.color.banner_good
            false -> R.color.banner_bad
            null -> R.color.card_raised
        }))
        binding.tvValNote.setTextColor(getColor(when (verdict) {
            true -> R.color.accent_green
            false -> R.color.accent_red
            null -> R.color.text_secondary
        }))

        val wrap = binding.layoutVal
        for (r in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_detail_group)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(TextView(this).apply {
                text = r.title
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(getColor(R.color.text_primary))
            })
            left.addView(TextView(this).apply {
                text = r.sub
                textSize = 10.5f
                setTextColor(getColor(R.color.text_secondary))
            })
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val right = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }
            right.addView(TextView(this).apply {
                text = r.value
                textSize = 13.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(getColor(R.color.text_primary))
            })
            right.addView(TextView(this).apply {
                text = r.chip
                textSize = 10.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(getColor(when (r.up) {
                    true -> R.color.accent_green
                    false -> R.color.accent_red
                    null -> R.color.text_secondary
                }))
            })
            row.addView(right)
            wrap.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) })
        }
    }

    private fun age(ts: Long): String {
        val mins = (System.currentTimeMillis() - ts) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 48 * 60 -> "${mins / 60}h ago"
            else -> "${mins / 1440}d ago"
        }
    }

    /** POSITION: the money facts the header doesn't already show — net
     *  invested, lifetime P&L (incl. realised, since invested nets out sells),
     *  and the alert rule (the "›" marks the one tappable tile). */
    @SuppressLint("SetTextI18n")
    private fun renderStats(d: HoldingDetail) {
        val wrap = binding.layoutStats
        wrap.removeAllViews()
        val rules = db.alertRules()
        val own = rules["isin:${d.instrument.isin}"]
        val alertPct = own ?: rules["bucket:${d.bucket.name}"] ?: rules["default"]
            ?: PriceSyncWorker.DEFAULT_THRESHOLD

        val pnl = d.valueNow - d.investedNow
        statGrid(wrap, listOf(
            Cell("Invested", MoneyFmt.money(d.investedNow, ccy)),
            Cell("P&L (lifetime)",
                (if (pnl >= 0) "+" else "−") + MoneyFmt.money(abs(pnl), ccy),
                getColor(if (pnl >= 0) R.color.accent_green else R.color.accent_red)),
            Cell("Alert at", "±${trim(alertPct)}% ›", null) { promptAlertRule(d) },
        ))
    }

    private fun promptAlertRule(d: HoldingDetail) {
        val own = db.alertRules()["isin:${d.instrument.isin}"]
        val input = EditText(this).apply {
            hint = "e.g. 3.5"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (own != null) trim(own) else "")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Alert when ${d.instrument.name} moves beyond ±%")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                input.text.toString().toDoubleOrNull()?.let { v ->
                    db.setAlertRule("isin:${d.instrument.isin}", v)
                    detail?.let { renderStats(it) }
                }
            }
            .setNeutralButton("Use ${d.bucket.label}/default") { _, _ ->
                db.setAlertRule("isin:${d.instrument.isin}", null)
                detail?.let { renderStats(it) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun renderTrades(d: HoldingDetail) {
        binding.tvTradesCount.text = "${d.trades.size} total"
        val wrap = binding.layoutTrades
        wrap.removeAllViews()
        val fmt = DateTimeFormatter.ofPattern("d MMM yy")
        val shown = d.trades.sortedByDescending { it.day }
            .let { if (tradesExpanded) it else it.take(10) }
        for (t in shown) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = LocalDate.ofEpochDay(t.day).format(fmt)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11.5f
            }, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = t.side
                textSize = 11.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(getColor(if (t.side == "buy") R.color.accent_green else R.color.accent_red))
            }, LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                val q = if (t.qty == Math.floor(t.qty)) "%.0f".format(t.qty)
                    else "%.4f".format(t.qty).trimEnd('0').trimEnd('.')
                text = "$q @ ${MoneyFmt.price(t.price, ccy)}"
                setTextColor(getColor(R.color.text_primary))
                textSize = 12.5f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "${t.owner} · ${t.broker}"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11.5f
            })
            wrap.addView(row)
        }
        if (d.trades.size > 10) {
            wrap.addView(TextView(this).apply {
                text = if (tradesExpanded) "Show less ‹"
                    else "View all ${d.trades.size} ›"
                setTextColor(getColor(R.color.accent_emerald_light))
                textSize = 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setBackgroundResource(R.drawable.ripple_group)
                setPadding(0, dp(10), 0, dp(6))
                setOnClickListener {
                    tradesExpanded = !tradesExpanded
                    detail?.let { renderTrades(it) }
                }
            })
        }
    }

    private fun trim(v: Double) =
        if (v == Math.floor(v)) "%.0f".format(v) else "%.1f".format(v)

    private fun chip(tv: TextView, up: Boolean?, text: String) {
        tv.text = text
        when (up) {
            true -> { tv.setTextColor(getColor(R.color.accent_green))
                tv.setBackgroundResource(R.drawable.bg_chip_verified) }
            false -> { tv.setTextColor(getColor(R.color.accent_red))
                tv.setBackgroundResource(R.drawable.bg_chip_failed) }
            null -> { tv.setTextColor(getColor(R.color.text_secondary))
                tv.setBackgroundResource(R.drawable.bg_input_pill) }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
