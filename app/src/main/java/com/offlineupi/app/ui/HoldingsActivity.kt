package com.offlineupi.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.offlineupi.app.R
import com.offlineupi.app.databinding.ActivityHoldingsBinding
import com.offlineupi.app.portfolio.MoneyFmt
import com.offlineupi.app.portfolio.PortfolioAnalytics
import com.offlineupi.app.portfolio.PortfolioAnalytics.MoveRow
import com.offlineupi.app.portfolio.PortfolioDb
import com.offlineupi.app.ui.charts.SparkView
import com.offlineupi.app.util.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holdings by movement: what's trending up or down over a chosen period,
 * re-sortable by move, value, invested, or XIRR.
 */
class HoldingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHoldingsBinding
    private val db by lazy { PortfolioDb(this) }

    private val periods = listOf("1D" to 1, "1W" to 7, "1M" to 30, "3M" to 91, "6M" to 182, "1Y" to 365)
    private val sorts = listOf("Move", "Value", "Invested", "XIRR")

    private var period = "1M"
    private var sort = "Move"
    private var descending = true
    private var rows: List<MoveRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHoldingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        period = intent.getStringExtra("period") ?: "1M"
        sort = intent.getStringExtra("sort") ?: "Move"
        binding.btnBack.setOnClickListener { finish() }
        renderPeriodChips()
        renderSortChips()
        load()
    }

    private fun load() {
        binding.tvSummary.text = "…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val snap = PortfolioUi.snapshot(db) ?: return@withContext null
                val back = periods.first { it.first == period }.second
                PortfolioAnalytics.movement(db, snap, back)
            }
            if (result == null) { finish(); return@launch }
            rows = result
            render()
        }
    }

    private fun pill(text: String, active: Boolean, onTap: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(5), dp(10), dp(5))
        setBackgroundResource(if (active) R.drawable.bg_pill_selected else R.drawable.bg_input_pill)
        setTextColor(getColor(if (active) R.color.accent_emerald_light else R.color.text_secondary))
        setOnClickListener { onTap() }
    }

    private fun renderPeriodChips() {
        val wrap = binding.layoutPeriods
        wrap.removeAllViews()
        for ((label, _) in periods) {
            wrap.addView(
                pill(label, label == period) { period = label; renderPeriodChips(); load() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (label != periods.last().first) marginEnd = dp(6) }
            )
        }
    }

    private fun renderSortChips() {
        val wrap = binding.layoutSorts
        wrap.removeAllViews()
        for (s in sorts) {
            val label = if (s == sort) "$s ${if (descending) "↓" else "↑"}" else s
            wrap.addView(
                pill(label, s == sort) {
                    if (sort == s) descending = !descending else { sort = s; descending = true }
                    renderSortChips(); render()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        val sorted = rows.sortedByDescending { r ->
            when (sort) {
                "Value" -> r.holding.valueInr
                // cross-currency compare in INR via the holding's implied FX
                "Invested" -> if (r.holding.value > 0)
                    r.holding.invested * (r.holding.valueInr / r.holding.value)
                else r.holding.invested
                "XIRR" -> r.holding.xirr ?: -99.0
                else -> r.movePct
            }
        }.let { if (descending) it else it.reversed() }

        val up = rows.count { it.movePct > 0.05 }
        val down = rows.count { it.movePct < -0.05 }
        binding.tvSummary.text = "$period · $up up · $down down"

        val wrap = binding.layoutList
        wrap.removeAllViews()
        for (r in sorted) {
            val h = r.holding
            val row = LayoutInflater.from(this).inflate(R.layout.item_holding_row, wrap, false)
            row.setBackgroundResource(R.drawable.ripple_group)
            row.findViewById<View>(R.id.hDot).background.mutate().setTint(
                PortfolioUi.bucketColors.getValue(h.bucket))
            row.findViewById<TextView>(R.id.hName).text = h.instrument.name
            row.findViewById<TextView>(R.id.hSub).text =
                "${h.bucket.label} · in ${MoneyFmt.money(h.invested, h.instrument.currency)}" +
                    " · XIRR ${h.xirr?.let { "%.0f%%".format(it * 100) } ?: "—"}"
            row.findViewById<SparkView>(R.id.hSpark).set(
                r.spark, if (r.movePct >= 0) 0xFF2E9B6E.toInt() else 0xFFC25B4E.toInt())
            row.findViewById<TextView>(R.id.hValue).text =
                MoneyFmt.money(h.value, h.instrument.currency)
            val meta = row.findViewById<TextView>(R.id.hMeta)
            meta.text = MoneyFmt.signedPct(r.movePct)
            meta.setTextColor(getColor(
                when {
                    r.movePct > 0.05 -> R.color.accent_green
                    r.movePct < -0.05 -> R.color.accent_red
                    else -> R.color.text_secondary
                }
            ))
            meta.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            row.setOnClickListener {
                startActivity(Intent(this, HoldingDetailActivity::class.java)
                    .putExtra("isin", h.instrument.isin))
            }
            wrap.addView(row)
            wrap.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
