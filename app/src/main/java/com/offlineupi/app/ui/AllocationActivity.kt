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
import com.offlineupi.app.portfolio.PortfolioAnalytics.Holding
import com.offlineupi.app.portfolio.PortfolioAnalytics.Snapshot
import com.offlineupi.app.portfolio.PortfolioDb
import com.offlineupi.app.ui.charts.TreemapView
import com.offlineupi.app.util.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Zoomable allocation. Three cuts of the same map (design v3 segmented
 * control): Market (bucket → sector → holding), Sector (all sectors as the
 * top level), Holdings (flat). Area = INR weight so everything shares one
 * scale; labels stay native. Small tails fold into an "Other · n" block that
 * zooms into its own map.
 */
class AllocationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAllocationBinding
    private val db by lazy { PortfolioDb(this) }
    private var snapshot: Snapshot? = null

    private val modes = listOf("Market", "Sector", "Holdings")
    private var mode = "Market"

    private val otherColor = 0xFF3A4540.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAllocationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        mode = savedInstanceState?.getString("mode") ?: "Market"
        val restorePath = savedInstanceState?.getStringArrayList("path").orEmpty()

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
        renderModePills()

        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { PortfolioUi.snapshot(db) }
            snapshot = snap
            if (snap == null) { finish(); return@launch }
            binding.treemap.set(rootsFor(snap))
            // re-drill to where the user was before rotation
            for (id in restorePath) {
                val level = binding.treemap.currentPath().lastOrNull()?.children ?: rootsFor(snap)
                val node = level.firstOrNull { it.id == id } ?: break
                binding.treemap.drillInto(node)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("mode", mode)
        outState.putStringArrayList("path",
            ArrayList(binding.treemap.currentPath().map { it.id }))
    }

    private fun renderModePills() {
        val wrap = binding.layoutModes
        wrap.removeAllViews()
        for (m in modes) {
            val tv = TextView(this).apply {
                text = m
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(5))
                setBackgroundResource(if (m == mode) R.drawable.bg_pill_selected else R.drawable.bg_input_pill)
                setTextColor(getColor(if (m == mode) R.color.accent_emerald_light else R.color.text_secondary))
                setOnClickListener {
                    if (mode == m) return@setOnClickListener
                    mode = m
                    renderModePills()
                    snapshot?.let { binding.treemap.set(rootsFor(it)) }   // resets the zoom stack
                }
            }
            wrap.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (m != modes.last()) marginEnd = dp(6) })
        }
    }

    // ---------- node building ----------

    private fun rootsFor(s: Snapshot): List<TreemapView.Node> = when (mode) {
        "Sector" -> sectorNodes(s.holdings)
        "Holdings" -> holdingNodes(s.holdings.sortedByDescending { it.valueInr }, base = null)
        else -> marketNodes(s)
    }

    /** Same currency → native sum; mixed (cross-market groups) → INR. */
    private fun amountFor(list: List<Holding>): String {
        val ccy = list.first().instrument.currency
        return if (list.all { it.instrument.currency == ccy })
            MoneyFmt.money(list.sumOf { it.value }, ccy)
        else MoneyFmt.inr(list.sumOf { it.valueInr })
    }

    /**
     * Top 9 readable blocks; the tail is an explicit "Other · n" that zooms
     * into its own map (recursively). With [base] set, blocks shade from the
     * parent color by rank; without, each holding keeps its bucket color.
     */
    private fun holdingNodes(sorted: List<Holding>, base: Int?): List<TreemapView.Node> {
        val top = sorted.take(9)
        val rest = sorted.drop(9)
        val count = top.size + if (rest.isEmpty()) 0 else 1
        val nodes = top.mapIndexed { i, h ->
            TreemapView.Node(
                id = h.instrument.isin, label = h.instrument.name,
                amount = MoneyFmt.money(h.value, h.instrument.currency),
                value = h.valueInr,
                color = base?.let { TreemapView.shade(it, i, count) }
                    ?: PortfolioUi.bucketColors.getValue(h.bucket),
            )
        }
        if (rest.isEmpty()) return nodes
        return nodes + TreemapView.Node(
            id = "group:other", label = "Other · ${rest.size}",
            amount = amountFor(rest),
            value = rest.sumOf { it.valueInr },
            color = otherColor,
            children = holdingNodes(rest, base),
        )
    }

    /** All sectors as the top level, colored by their market, shaded by rank within it. */
    private fun sectorNodes(holdings: List<Holding>): List<TreemapView.Node> {
        val groups = holdings.groupBy { it.sector }.entries
            .sortedByDescending { g -> g.value.sumOf { it.valueInr } }
        val rankInBucket = mutableMapOf<Bucket, Int>()
        val countInBucket = groups.groupingBy { it.value.first().bucket }.eachCount()
        return groups.map { (sector, list) ->
            val b = list.first().bucket
            val rank = rankInBucket.getOrDefault(b, 0)
            rankInBucket[b] = rank + 1
            val color = TreemapView.shade(
                PortfolioUi.bucketColors.getValue(b), rank, countInBucket.getValue(b))
            TreemapView.Node(
                id = "sector:$sector", label = sector,
                amount = amountFor(list),
                value = list.sumOf { it.valueInr },
                color = color,
                children = holdingNodes(list.sortedByDescending { it.valueInr }, color),
            )
        }
    }

    private fun marketNodes(s: Snapshot): List<TreemapView.Node> =
        Bucket.entries.mapNotNull { b ->
            val st = s.buckets.getValue(b)
            if (st.valueInr <= 0) return@mapNotNull null
            val base = PortfolioUi.bucketColors.getValue(b)
            val holdings = s.holdings.filter { it.bucket == b }

            val children = if (b == Bucket.India || b == Bucket.US) {
                val groups = holdings.groupBy { it.sector }.entries
                    .sortedByDescending { g -> g.value.sumOf { it.valueInr } }
                groups.mapIndexed { i, (sector, list) ->
                    val color = TreemapView.shade(base, i, groups.size)
                    TreemapView.Node(
                        id = "sector:${b.name}:$sector", label = sector,
                        amount = MoneyFmt.money(list.sumOf { it.value }, b.currency),
                        value = list.sumOf { it.valueInr },
                        color = color,
                        children = holdingNodes(list.sortedByDescending { it.valueInr }, color),
                    )
                }
            } else holdingNodes(holdings.sortedByDescending { it.valueInr }, base)

            TreemapView.Node(
                id = "bucket:${b.name}", label = b.label,
                amount = MoneyFmt.money(st.value, b.currency),
                value = st.valueInr, color = base, children = children,
            )
        }

    // ---------- level rendering ----------

    @SuppressLint("SetTextI18n")
    private fun renderLevel(path: List<TreemapView.Node>) {
        val s = snapshot ?: return
        binding.tvCrumb.text =
            if (path.isEmpty()) "All ${mode.lowercase().trimEnd('s')}s"
            else path.joinToString(" ▸ ") { it.label }
        binding.tvTotal.text = path.lastOrNull()?.amount ?: MoneyFmt.inr(s.value)

        val nodes = (path.lastOrNull()?.children ?: rootsFor(s))
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
