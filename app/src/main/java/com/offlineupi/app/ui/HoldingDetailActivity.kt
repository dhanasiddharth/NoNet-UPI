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
    private val ranges = listOf("3M", "6M", "1Y", "3Y", "All")

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
        binding.tvPrice.text = MoneyFmt.price(d.priceNow, ccy)
        chip(binding.tvDayChip, if (abs(d.dayPct) < 0.005) null else d.dayPct > 0,
            "${MoneyFmt.signedPct(d.dayPct)} today")

        val qtyTxt = if (d.qty == Math.floor(d.qty) && d.qty < 1e6) "%.0f".format(d.qty)
            else "%.4f".format(d.qty).trimEnd('0').trimEnd('.')
        val avg = if (d.qty > 0) d.investedNow / d.qty else 0.0
        binding.tvPosition.text =
            "$qtyTxt units · avg ${MoneyFmt.price(avg, ccy)} · in ${MoneyFmt.money(d.investedNow, ccy)}"

        renderRanges()
        renderCharts(d)
        renderReturns(d)
        renderStats(d)
        renderAlertRule(d)
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
                setBackgroundResource(if (r == range) R.drawable.bg_pill_active else R.drawable.bg_input_pill)
                setTextColor(getColor(if (r == range) R.color.accent_emerald_light else R.color.text_secondary))
                setOnClickListener {
                    range = r
                    detail?.let { d -> renderRanges(); renderCharts(d) }
                }
            }
            wrap.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (r != ranges.last()) marginEnd = dp(6) })
        }
    }

    private fun startIndex(d: HoldingDetail): Int {
        val back = when (range) {
            "3M" -> 91L; "6M" -> 182L; "1Y" -> 365L; "3Y" -> 1095L
            else -> return 0
        }
        val i = d.days.indexOfFirst { it >= d.days.last() - back }
        return if (i < 0) 0 else i
    }

    @SuppressLint("SetTextI18n")
    private fun renderCharts(d: HoldingDetail) {
        val i0 = startIndex(d)
        val n = d.days.size

        // ---- performance: value ÷ invested vs bench-sim ÷ invested, % ----
        fun perfOf(values: DoubleArray) = DoubleArray(n - i0) { j ->
            val k = i0 + j
            val inv = d.invested[k]
            if (inv > 0 && !values[k].isNaN()) (values[k] / inv - 1) * 100 else Double.NaN
        }
        val you = perfOf(d.value)
        val bench = perfOf(d.benchValue)
        binding.chartPerf.allowNegative = true
        binding.chartPerf.yFormatter = { "%.0f%%".format(it) }
        val fmt = DateTimeFormatter.ofPattern(if (range == "All" || range == "3Y") "MMM yy" else "MMM")
        val labels = listOf(
            LocalDate.ofEpochDay(d.days[i0]).format(fmt),
            LocalDate.ofEpochDay(d.days[(i0 + n - 1) / 2]).format(fmt),
            LocalDate.ofEpochDay(d.days.last()).format(fmt),
        )
        binding.chartPerf.set(listOf(
            LineChartView.Series(you, color, area = true),
            LineChartView.Series(bench, PortfolioUi.MUTED_LINE, widthDp = 1.8f),
        ), labels)
        binding.tvPerfLegend.text = "— ${d.instrument.name.take(18)}   — same cashflows in ${d.bucket.benchName}"

        val gap = (you.lastOrNull { !it.isNaN() } ?: 0.0) - (bench.lastOrNull { !it.isNaN() } ?: 0.0)
        binding.tvPerfGap.text = "${if (gap >= 0) "+" else ""}%.1f pts".format(gap)
        binding.tvPerfGap.setTextColor(getColor(if (gap >= 0) R.color.accent_green else R.color.accent_red))

        // ---- value chart with trade dots ----
        val valueSlice = DoubleArray(n - i0) { j -> d.value[i0 + j] }
        val investedSlice = DoubleArray(n - i0) { j -> d.invested[i0 + j] }
        val dots = d.tradeDots.filter { it.idx >= i0 && !d.value[it.idx].isNaN() }
            .map { (it.idx - i0) to d.value[it.idx] }
        binding.chartValue.allowNegative = false
        binding.chartValue.yFormatter = {
            MoneyFmt.money(it, ccy).removePrefix("₹").removePrefix("$")
        }
        binding.chartValue.set(listOf(
            LineChartView.Series(valueSlice, color, area = true),
            LineChartView.Series(investedSlice, PortfolioUi.MUTED_LINE, widthDp = 1.4f, dashed = true),
        ), labels, dots)
        binding.tvValueLegend.text = "— value   ┈ invested   ● trades"
        binding.tvValueNow.text = MoneyFmt.money(d.valueNow, ccy)
    }

    @SuppressLint("SetTextI18n")
    private fun renderReturns(d: HoldingDetail) {
        val wrap = binding.layoutReturns
        wrap.removeAllViews()
        for ((label, you, bench) in d.trailing) {
            if (you == null && bench == null) continue
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = MoneyFmt.pct(you)
                setTextColor(getColor(when {
                    you == null -> R.color.text_secondary
                    you >= 0 -> R.color.accent_green
                    else -> R.color.accent_red
                }))
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "${d.bucket.benchName} ${MoneyFmt.pct(bench)}"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
            val delta = if (you != null && bench != null) you - bench else null
            row.addView(TextView(this).apply {
                text = delta?.let { "${if (it >= 0) "+" else ""}%.1f".format(it * 100) } ?: "—"
                textSize = 11.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setTextColor(getColor(when {
                    delta == null -> R.color.text_secondary
                    delta >= 0 -> R.color.accent_green
                    else -> R.color.accent_red
                }))
                setBackgroundResource(when {
                    delta == null -> R.drawable.bg_input_pill
                    delta >= 0 -> R.drawable.bg_chip_verified
                    else -> R.drawable.bg_chip_failed
                })
            })
            wrap.addView(row)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderStats(d: HoldingDetail) {
        val wrap = binding.layoutStats
        wrap.removeAllViews()
        val gain = d.valueNow - d.investedNow
        val gainPct = if (d.investedNow > 0) gain / d.investedNow else 0.0
        val items = listOf(
            "Value" to MoneyFmt.money(d.valueNow, ccy),
            "Invested" to MoneyFmt.money(d.investedNow, ccy),
            "Gain" to "${MoneyFmt.money(gain, ccy)} (${MoneyFmt.pct(gainPct)})",
            "XIRR" to MoneyFmt.pct(d.xirr),
            "${d.bucket.benchName} XIRR" to MoneyFmt.pct(d.benchXirr),
            "Inflation (${d.bucket.cpi} CPI)" to MoneyFmt.pct(d.inflXirr),
        )
        for ((label, value) in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12.5f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = value
                setTextColor(getColor(R.color.text_primary))
                textSize = 12.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.END
            })
            wrap.addView(row)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderAlertRule(d: HoldingDetail) {
        val rules = db.alertRules()
        val own = rules["isin:${d.instrument.isin}"]
        val bucketRule = rules["bucket:${d.bucket.name}"]
        val def = rules["default"] ?: PriceSyncWorker.DEFAULT_THRESHOLD
        val (pct, source) = when {
            own != null -> own to "this holding"
            bucketRule != null -> bucketRule to d.bucket.label
            else -> def to "default"
        }
        binding.tvAlertRule.text = "±${trim(pct)}% · $source"
        binding.rowAlert.setOnClickListener {
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
                        renderAlertRule(d)
                    }
                }
                .setNeutralButton("Use ${d.bucket.label}/default") { _, _ ->
                    db.setAlertRule("isin:${d.instrument.isin}", null)
                    renderAlertRule(d)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
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
