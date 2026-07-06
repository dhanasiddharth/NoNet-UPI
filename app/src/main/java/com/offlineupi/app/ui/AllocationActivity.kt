package com.offlineupi.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.offlineupi.app.R
import com.offlineupi.app.databinding.ActivityAllocationBinding
import com.offlineupi.app.portfolio.MoneyFmt
import com.offlineupi.app.portfolio.PortfolioAnalytics.Bucket
import com.offlineupi.app.portfolio.PortfolioAnalytics.Snapshot
import com.offlineupi.app.portfolio.PortfolioDb
import com.offlineupi.app.ui.charts.TreemapView
import com.offlineupi.app.util.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Zoomable allocation: buckets → sectors → holdings. Area = INR weight, labels stay native. */
class AllocationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAllocationBinding
    private val db by lazy { PortfolioDb(this) }
    private var snapshot: Snapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAllocationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.btnBack.setOnClickListener { if (!binding.treemap.up()) finish() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!binding.treemap.up()) { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        binding.treemap.onLevelChange = { path -> renderLevel(path) }
        binding.treemap.onLeafTap = { node ->
            if (!node.id.contains(':')) startActivity(
                Intent(this, HoldingDetailActivity::class.java).putExtra("isin", node.id)
            )
        }

        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { PortfolioUi.snapshot(db) }
            snapshot = snap
            if (snap == null) { finish(); return@launch }
            binding.treemap.set(buildNodes(snap))
        }
    }

    private fun buildNodes(s: Snapshot): List<TreemapView.Node> =
        Bucket.entries.mapNotNull { b ->
            val st = s.buckets.getValue(b)
            if (st.valueInr <= 0) return@mapNotNull null
            val base = PortfolioUi.bucketColors.getValue(b)
            val holdings = s.holdings.filter { it.bucket == b }

            fun holdingNodes(list: List<com.offlineupi.app.portfolio.PortfolioAnalytics.Holding>): List<TreemapView.Node> {
                val sorted = list.sortedByDescending { it.valueInr }
                return sorted.mapIndexed { i, h ->
                    TreemapView.Node(
                        id = h.instrument.isin, label = h.instrument.name,
                        amount = MoneyFmt.money(h.value, h.instrument.currency),
                        value = h.valueInr,
                        color = TreemapView.shade(base, i, sorted.size),
                    )
                }
            }

            val children = if (b == Bucket.India || b == Bucket.US) {
                val groups = holdings.groupBy { it.sector }.entries
                    .sortedByDescending { g -> g.value.sumOf { it.valueInr } }
                groups.mapIndexed { i, (sector, list) ->
                    TreemapView.Node(
                        id = "sector:${b.name}:$sector", label = sector,
                        amount = MoneyFmt.money(list.sumOf { it.value }, b.currency),
                        value = list.sumOf { it.valueInr },
                        color = TreemapView.shade(base, i, groups.size),
                        children = holdingNodes(list),
                    )
                }
            } else holdingNodes(holdings)

            TreemapView.Node(
                id = "bucket:${b.name}", label = b.label,
                amount = MoneyFmt.money(st.value, b.currency),
                value = st.valueInr, color = base, children = children,
            )
        }

    @SuppressLint("SetTextI18n")
    private fun renderLevel(path: List<TreemapView.Node>) {
        val s = snapshot ?: return
        binding.tvCrumb.text =
            if (path.isEmpty()) "All assets"
            else path.joinToString(" ▸ ") { it.label }
        binding.tvTotal.text = path.lastOrNull()?.amount ?: MoneyFmt.inr(s.value)

        val nodes = (path.lastOrNull()?.children ?: buildNodes(s))
            .sortedByDescending { it.value }
        val total = nodes.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
        val wrap = binding.layoutKey
        wrap.removeAllViews()
        for (n in nodes) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                setBackgroundResource(R.drawable.ripple_group)
                setOnClickListener { binding.treemap.drillInto(n) }
            }
            row.addView(View(this).apply {
                setBackgroundResource(R.drawable.bg_pill_active)
                background.setTint(n.color)
            }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(10) })
            row.addView(TextView(this).apply {
                text = n.label
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "%.1f%%".format(n.value / total * 100)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12.5f
            }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = n.amount
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
            wrap.addView(row)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
