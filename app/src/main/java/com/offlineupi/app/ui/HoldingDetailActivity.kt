package com.offlineupi.app.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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
    private var range = "1Y"
    private val ranges = listOf("1M", "3M", "6M", "1Y", "3Y", "5Y", "All")
    private val chartModes = listOf("Performance", "Value")
    private var chartMode = "Performance"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHoldingDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        val isin = intent.getStringExtra("isin") ?: run { finish(); return }
        lifecycleScope.launch {
            val d = withContext(Dispatchers.IO) { PortfolioAnalytics.holdingDetail(db, isin) }
            if (d == null) { finish(); return@launch }
            detail = d
            render(d)
        }
    }

    private val color get() = PortfolioUi.bucketColors.getValue(detail!!.bucket)
    private val ccy get() = detail!!.instrument.currency

    @SuppressLint("SetTextI18n")
    private fun render(d: HoldingDetail) {
        binding.tvName.text = d.instrument.name
        binding.tvSub.text = "${d.sector} · ${d.bucket.label} · vs ${d.bucket.benchName}"
        // hero is the position's worth (mockup layout); unit price lives in the qty chip
        binding.tvPrice.text = MoneyFmt.money(d.valueNow, ccy)
        chip(binding.tvDayChip, if (abs(d.dayPct) < 0.005) null else d.dayPct > 0,
            "${MoneyFmt.signedPct(d.dayPct)} today")
        val qtyTxt = if (d.qty == Math.floor(d.qty) && d.qty < 1e6) "%.0f".format(d.qty)
            else "%.4f".format(d.qty).trimEnd('0').trimEnd('.')
        val unit = if (d.instrument.isin == "GOLD") "g" else "u"
        binding.tvQtyChip.text = "$qtyTxt $unit · ${MoneyFmt.price(d.priceNow, ccy)}"

        renderRanges()
        renderChartModes()
        renderChart(d)
        renderReturns(d)
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
            "1M" -> 30L; "3M" -> 91L; "6M" -> 182L
            "1Y" -> 365L; "3Y" -> 1095L; "5Y" -> 1826L
            else -> return 0
        }
        val i = d.days.indexOfFirst { it >= d.days.last() - back }
        return if (i < 0) 0 else i
    }

    private fun renderChartModes() {
        val wrap = binding.layoutChartModes
        wrap.removeAllViews()
        for (m in chartModes) {
            val tv = TextView(this).apply {
                text = m
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(5))
                setBackgroundResource(if (m == chartMode) R.drawable.bg_pill_selected else R.drawable.bg_input_pill)
                setTextColor(getColor(if (m == chartMode) R.color.accent_emerald_light else R.color.text_secondary))
                setOnClickListener {
                    if (chartMode == m) return@setOnClickListener
                    chartMode = m
                    detail?.let { d -> renderChartModes(); renderChart(d) }
                }
            }
            wrap.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (m != chartModes.last()) marginEnd = dp(6) })
        }
    }

    /** One chart, two lenses: performance strips contributions; value shows them. */
    @SuppressLint("SetTextI18n")
    private fun renderChart(d: HoldingDetail) {
        val i0 = startIndex(d)
        val n = d.days.size
        val fmt = DateTimeFormatter.ofPattern(if (range == "All" || range == "3Y") "MMM yy" else "MMM")
        val labels = listOf(
            LocalDate.ofEpochDay(d.days[i0]).format(fmt),
            LocalDate.ofEpochDay(d.days[(i0 + n - 1) / 2]).format(fmt),
            LocalDate.ofEpochDay(d.days.last()).format(fmt),
        )

        if (chartMode == "Performance") {
            // pure price performance, indexed from the range start — buys and
            // sells never move these lines, only market moves do
            fun indexed(series: DoubleArray): DoubleArray {
                var base = Double.NaN
                var k = i0
                while (k < n && (series[k].isNaN() || series[k] <= 0)) k++
                if (k < n) base = series[k]
                return DoubleArray(n - i0) { j ->
                    val v = series[i0 + j]
                    if (v.isNaN() || base.isNaN() || base <= 0) Double.NaN else (v / base - 1) * 100
                }
            }
            val you = indexed(d.price)
            val bench = indexed(d.bench)
            // trade markers ride the price line: entry/exit timing vs the market
            val dots = d.tradeDots.filter { it.idx >= i0 }
                .map { (it.idx - i0) to you[it.idx - i0] }
                .filter { !it.second.isNaN() }
            binding.chart.allowNegative = true
            binding.chart.yFormatter = { "%.0f%%".format(it) }
            binding.chart.set(listOf(
                LineChartView.Series(you, color, area = true),
                LineChartView.Series(bench, PortfolioUi.MUTED_LINE, widthDp = 1.8f),
            ), labels, dots)
            binding.tvChartLegend.text = "— price   — ${d.bucket.benchName}   ● trades"
            val gap = (you.lastOrNull { !it.isNaN() } ?: 0.0) - (bench.lastOrNull { !it.isNaN() } ?: 0.0)
            binding.tvChartStat.text = "${if (gap >= 0) "+" else ""}%.1f pts".format(gap)
            binding.tvChartStat.setTextColor(getColor(if (gap >= 0) R.color.accent_green else R.color.accent_red))
        } else {
            val valueSlice = DoubleArray(n - i0) { j -> d.value[i0 + j] }
            val investedSlice = DoubleArray(n - i0) { j -> d.invested[i0 + j] }
            val dots = d.tradeDots.filter { it.idx >= i0 && !d.value[it.idx].isNaN() }
                .map { (it.idx - i0) to d.value[it.idx] }
            binding.chart.allowNegative = false
            binding.chart.yFormatter = { MoneyFmt.axis(it, ccy) }
            binding.chart.set(listOf(
                LineChartView.Series(valueSlice, color, area = true),
                LineChartView.Series(investedSlice, PortfolioUi.MUTED_LINE, widthDp = 1.4f, dashed = true),
            ), labels, dots)
            binding.tvChartLegend.text = "— value   ┈ invested   ● trades"
            binding.tvChartStat.text = MoneyFmt.money(d.valueNow, ccy)
            binding.tvChartStat.setTextColor(getColor(R.color.text_primary))
        }
    }

    /** Rolling price-return chips (3M / 1Y / 3Y annualised) — mockup layout. */
    @SuppressLint("SetTextI18n")
    private fun renderReturns(d: HoldingDetail) {
        val wrap = binding.layoutReturns
        wrap.removeAllViews()
        val wanted = listOf("3M", "1Y", "3Y")
        for (label in wanted) {
            val t = d.trailing.firstOrNull { it.first == label } ?: continue
            val you = t.second
            val annualised = if (label == "3Y" && you != null)
                Math.pow(1 + you, 1.0 / 3) - 1 else you
            val txt = "$label ${MoneyFmt.pct(annualised)}" + if (label == "3Y" && you != null) "/yr" else ""
            wrap.addView(TextView(this).apply {
                text = txt
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setTextColor(getColor(when {
                    annualised == null -> R.color.text_secondary
                    annualised >= 0 -> R.color.accent_green
                    else -> R.color.accent_red
                }))
                setBackgroundResource(R.drawable.bg_input_pill)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
    }

    /** 3-column stat tiles (mockup's statgrid): headline numbers + the alert rule. */
    @SuppressLint("SetTextI18n")
    private fun renderStats(d: HoldingDetail) {
        val wrap = binding.layoutStats
        wrap.removeAllViews()
        val dIdx = if (d.xirr != null && d.benchXirr != null) (d.xirr - d.benchXirr) * 100 else null
        val dInf = if (d.xirr != null && d.inflXirr != null) (d.xirr - d.inflXirr) * 100 else null
        val rules = db.alertRules()
        val own = rules["isin:${d.instrument.isin}"]
        val alertPct = own ?: rules["bucket:${d.bucket.name}"] ?: rules["default"]
            ?: PriceSyncWorker.DEFAULT_THRESHOLD

        data class Cell(val k: String, val v: String, val color: Int?, val onTap: (() -> Unit)?)
        fun pp(v: Double?) = v?.let { "${if (it >= 0) "+" else ""}%.1f pp".format(it) } ?: "—"
        val cells = listOf(
            Cell("Invested", MoneyFmt.money(d.investedNow, ccy), null, null),
            Cell("Value", MoneyFmt.money(d.valueNow, ccy), null, null),
            Cell("XIRR", MoneyFmt.pct(d.xirr), null, null),
            Cell("vs ${d.bucket.benchName}", pp(dIdx),
                dIdx?.let { getColor(if (it >= 0) R.color.accent_green else R.color.accent_red) }, null),
            Cell("vs ${d.bucket.cpi} CPI", pp(dInf),
                dInf?.let { getColor(if (it >= 0) R.color.accent_green else R.color.accent_red) }, null),
            Cell("Alert at", "±${trim(alertPct)}%", null) { promptAlertRule(d) },
        )
        cells.chunked(3).forEach { rowCells ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 3f
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
            binding.layoutStats.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8) })
        }
        // keep column widths when the last row is short
        wrap.requestLayout()
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
        for (t in d.trades.sortedByDescending { it.day }.take(10)) {
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
                text = "+ ${d.trades.size - 10} earlier"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11.5f
                setPadding(0, dp(4), 0, 0)
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
