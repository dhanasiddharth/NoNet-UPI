package com.offlineupi.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.offlineupi.app.R
import com.offlineupi.app.databinding.FragmentPortfolioBinding
import com.offlineupi.app.portfolio.PortfolioAnalytics
import com.offlineupi.app.portfolio.PortfolioAnalytics.Bucket
import com.offlineupi.app.portfolio.PortfolioAnalytics.Snapshot
import com.offlineupi.app.portfolio.PortfolioDb
import com.offlineupi.app.portfolio.PortfolioSync
import com.offlineupi.app.ui.charts.LineChartView
import com.offlineupi.app.ui.charts.SparkView
import com.offlineupi.app.worker.PriceSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Money tab — portfolio overview. Design source: docs/design/money-redesign.html (v3). */
class PortfolioFragment : Fragment() {

    private var _binding: FragmentPortfolioBinding? = null
    private val binding get() = _binding!!

    private val db by lazy { PortfolioDb(requireContext()) }
    private val sync by lazy { PortfolioSync(requireContext(), db) }

    private var snapshot: Snapshot? = null
    private var range = "1M"
    /** Focused buckets — empty or all = whole portfolio; any subset combines.
     *  Defaults to the liquid markets (Gold is the odd one out). */
    private val selection = linkedSetOf(Bucket.India, Bucket.US, Bucket.Crypto)
    private var compare = false
    private var masked = false
    private var chartMode = "Value"
    private val chartModes = listOf("Value", "Perf")

    companion object {
        // Survives tab swipes: ViewPager2 destroys the fragment view two tabs
        // away, which would cancel a viewLifecycle-scoped sync mid-backfill.
        @Volatile private var syncing = false
        @Volatile private var autoSyncTried = false
        @Volatile private var syncProgress = ""
        @Volatile private var syncError: String? = null
    }

    private val bucketColors by lazy {
        mapOf(
            Bucket.India to 0xFF16A56A.toInt(), Bucket.US to 0xFF5B8DEF.toInt(),
            Bucket.Gold to 0xFFC8842E.toInt(), Bucket.Crypto to 0xFFA17AE0.toInt(),
        )
    }
    // resolved per call so they follow the active light/dark palette
    private val brand get() = requireContext().getColor(R.color.accent_green)
    private val mutedLine get() = requireContext().getColor(R.color.chart_muted)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPortfolioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnSync.setOnClickListener { startSync(listingToo = false) }
        binding.btnSync.setOnLongClickListener { configureOrSyncListing(); true }
        binding.btnAlerts.setOnClickListener {
            startActivity(android.content.Intent(requireContext(), AlertsActivity::class.java))
        }
        binding.tvAllocZoom.setOnClickListener { openAllocation() }
        binding.allocBar.setOnClickListener { openAllocation() }
        binding.tvMoversAll.setOnClickListener { openHoldings("1D", "Move") }
        binding.tvHoldingsAll.setOnClickListener { openHoldings("1M", "Value") }
        binding.btnEye.setOnClickListener {
            masked = !masked
            binding.btnEye.setImageResource(
                if (masked) R.drawable.ic_visibility_off else R.drawable.ic_visibility
            )
            snapshot?.let { render(it) }
        }
        binding.btnCompare.setOnClickListener {
            compare = !compare
            binding.btnCompare.setBackgroundResource(
                if (compare) R.drawable.bg_pill_selected else R.drawable.bg_input_pill
            )
            binding.btnCompare.setTextColor(
                requireContext().getColor(if (compare) R.color.accent_emerald_light else R.color.text_secondary)
            )
            snapshot?.let { renderChart(it) }
        }
        // Panning the chart re-frames the whole top of the page (hero growth,
        // flow, range row) to the visible window — not just the graph.
        binding.chart.onViewportChange = { st, en -> snapshot?.let { applyWindow(it, st, en) } }
        renderChartModePills()
    }

    /** Value ↔ Performance lens on the overview chart (same pattern as detail). */
    private fun renderChartModePills() {
        val wrap = binding.layoutChartMode
        wrap.removeAllViews()
        for (m in chartModes) {
            val tv = TextView(requireContext()).apply {
                text = m
                textSize = 11.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                setBackgroundResource(if (m == chartMode) R.drawable.bg_pill_selected else R.drawable.bg_input_pill)
                setTextColor(requireContext().getColor(
                    if (m == chartMode) R.color.accent_emerald_light else R.color.text_secondary))
                setOnClickListener {
                    if (chartMode == m) return@setOnClickListener
                    chartMode = m
                    renderChartModePills()
                    binding.btnCompare.visibility = if (m == "Perf") View.GONE else View.VISIBLE
                    snapshot?.let { renderChart(it) }
                }
            }
            wrap.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (m != chartModes.last()) marginEnd = dp(6) })
        }
        binding.btnCompare.visibility = if (chartMode == "Perf") View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---------- data ----------
    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) {
                sync.importSeedIfEmpty()
                PortfolioAnalytics.compute(db)
            }
            if (_binding == null) return@launch
            snapshot = snap
            when {
                snap == null && db.tradeCount() == 0 -> showEmpty(
                    "No listing yet. Long-press sync to connect the sheet, or bundle a seed."
                )
                snap == null -> {
                    val err = syncError
                    showEmpty(
                        if (syncing) "Listing loaded (${db.tradeCount()} trades). Fetching prices…"
                        else if (err != null) "Price sync failed: $err\n\nTap sync to retry."
                        else "Listing loaded (${db.tradeCount()} trades). Tap sync to fetch prices."
                    )
                    // fetch prices once automatically; failures wait for a manual tap
                    if (!syncing && !autoSyncTried) { autoSyncTried = true; startSync(listingToo = false) }
                    if (syncing) watchSync()
                }
                else -> {
                    binding.tvEmpty.visibility = View.GONE
                    binding.layoutContent.visibility = View.VISIBLE
                    render(snap)
                }
            }
        }
    }

    private fun showEmpty(msg: String) {
        binding.tvEmpty.text = msg
        binding.tvEmpty.visibility = View.VISIBLE
        binding.layoutContent.visibility = View.GONE
    }

    /** Sync runs on an app-scoped thread; the fragment just watches it. */
    private fun startSync(listingToo: Boolean) {
        if (syncing) { watchSync(); return }
        syncing = true
        syncError = null
        syncProgress = "Syncing…"
        requestNotifPermission()
        val appCtx = requireContext().applicationContext
        Thread {
            val appDb = PortfolioDb(appCtx)
            val appSync = PortfolioSync(appCtx, appDb)
            try {
                if (listingToo && appSync.isConfigured) appSync.syncListing()
                appSync.syncPrices { done, total -> syncProgress = "Prices $done/$total" }
                PriceSyncWorker.checkMovements(appCtx, appDb)
            } catch (e: Exception) {
                syncError = e.message ?: "Sync failed"
            } finally {
                syncing = false
            }
        }.start()
        watchSync()
    }

    /** Polls sync state onto whatever view is currently alive. */
    private fun watchSync() {
        binding.tvSyncStatus.text = syncProgress
        viewLifecycleOwner.lifecycleScope.launch {
            while (syncing && _binding != null) {
                binding.tvSyncStatus.text = syncProgress
                kotlinx.coroutines.delay(300)
            }
            if (_binding == null) return@launch
            syncError?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
            load()
        }
    }

    private fun configureOrSyncListing() {
        if (sync.isConfigured) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Sync listing from sheet")
                .setMessage("Pull the latest trades from the sheet endpoint?")
                .setPositiveButton("Sync") { _, _ -> startSync(listingToo = true) }
                .setNeutralButton("Change endpoint") { _, _ -> showEndpointDialog() }
                .setNegativeButton("Cancel", null)
                .show()
        } else showEndpointDialog()
    }

    private fun showEndpointDialog() {
        val ctx = requireContext()
        val pad = (20 * resources.displayMetrics.density).toInt()
        val urlIn = EditText(ctx).apply {
            hint = "Apps Script web-app URL"
            setText(sync.endpointUrl.orEmpty())
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val tokenIn = EditText(ctx).apply {
            hint = "Token"
            setText(sync.endpointToken.orEmpty())
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(urlIn); addView(tokenIn)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Sheet listing endpoint")
            .setMessage("Deploy tools/sheet_webapp.gs on the portfolio sheet, then paste its URL and token. Stored encrypted on this device.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                sync.endpointUrl = urlIn.text.toString().trim()
                sync.endpointToken = tokenIn.text.toString().trim()
                if (sync.isConfigured) startSync(listingToo = true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- navigation ----------
    private fun openAllocation() {
        startActivity(android.content.Intent(requireContext(), AllocationActivity::class.java))
    }

    private fun openHoldings(period: String, sort: String) {
        startActivity(
            android.content.Intent(requireContext(), HoldingsActivity::class.java)
                .putExtra("period", period).putExtra("sort", sort)
        )
    }

    private fun openDetail(isin: String) {
        startActivity(
            android.content.Intent(requireContext(), HoldingDetailActivity::class.java)
                .putExtra("isin", isin)
        )
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 51)
    }

    // ---------- formatting ----------
    private fun mask(s: String) = if (masked) "•••••" else s

    private fun inr(v: Double): String {
        val a = abs(v)
        return when {
            a >= 1e7 -> "₹%.2f Cr".format(v / 1e7)
            a >= 1e5 -> "₹%.1f L".format(v / 1e5)
            else -> "₹" + String.format(Locale("en", "IN"), "%,.0f", v)
        }
    }

    private fun money(v: Double, currency: String): String {
        if (currency != "USD") return inr(v)
        val a = abs(v)
        return when {
            a >= 1e6 -> "$%.2fM".format(v / 1e6)
            a >= 1e4 -> "$%.1fk".format(v / 1e3)
            else -> "$" + String.format(Locale.US, "%,.0f", v)
        }
    }

    private fun pct(v: Double?) = v?.let { (if (it >= 0) "+" else "") + "%.1f%%".format(it * 100) } ?: "—"

    private fun chip(tv: TextView, up: Boolean?, text: String) {
        tv.text = text
        when (up) {
            true -> { tv.setTextColor(requireContext().getColor(R.color.accent_green))
                tv.setBackgroundResource(R.drawable.bg_chip_verified) }
            false -> { tv.setTextColor(requireContext().getColor(R.color.accent_red))
                tv.setBackgroundResource(R.drawable.bg_chip_failed) }
            null -> { tv.setTextColor(requireContext().getColor(R.color.text_secondary))
                tv.setBackgroundResource(R.drawable.bg_input_pill) }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- render ----------
    @SuppressLint("SetTextI18n")
    private fun render(s: Snapshot) {
        val syncedAt = db.getMeta("priceSyncedAt")?.toLongOrNull()
        binding.tvSyncStatus.text = if (syncedAt == null) "Not synced" else {
            val mins = (System.currentTimeMillis() - syncedAt) / 60000
            when {
                mins < 60 -> "Synced ${mins}m ago"
                mins < 48 * 60 -> "Synced ${mins / 60}h ago"
                else -> "Synced ${mins / 1440}d ago"
            }
        }
        renderHero(s)
        renderTiles(s)
        renderRanges()
        renderChart(s)
        renderDotPlot(s)
        renderAllocation(s)
        renderMovers(s)
        renderHoldings(s)
    }

    /**
     * Value/invested/bench sources for the current selection. One bucket (or a
     * same-currency set like US+Crypto) stays native per the currency policy;
     * a cross-currency blend like India+US falls back to INR.
     */
    private data class Src(
        val values: DoubleArray, val invested: DoubleArray, val bench: DoubleArray,
        val ccy: String, val color: Int, val label: String, val benchName: String,
        val flowsInr: List<Pair<Int, Double>>?, val xirr: Double?,
    )

    private fun src(s: Snapshot): Src {
        val sel = selection.toList()
        if (sel.isEmpty() || sel.size == Bucket.entries.size) return Src(
            s.totalInr, s.investedInr, s.benchInr, "INR", brand, "Portfolio", "indexes",
            null, s.xirr,
        )
        val stats = sel.map { s.buckets.getValue(it) }
        val uniformCcy = sel.first().currency.takeIf { c -> sel.all { it.currency == c } }
        val label = sel.joinToString(" + ") { it.label }
        val benchName = sel.map { it.benchName }.distinct().joinToString(" + ")
        val color = if (sel.size == 1) bucketColors.getValue(sel[0]) else brand
        val flows = stats.flatMap { it.flowsInr }.sortedBy { it.first }
        val xirr = if (sel.size == 1) stats[0].xirr else PortfolioAnalytics.xirrOf(
            flows, stats.sumOf { it.valueInr }, s.days.size - 1)
        fun sum(pick: (PortfolioAnalytics.BucketStat) -> DoubleArray): DoubleArray {
            val arrs = stats.map(pick)
            return DoubleArray(arrs[0].size) { k -> arrs.sumOf { it[k] } }
        }
        return if (uniformCcy != null) Src(
            sum { it.seriesNative }, sum { it.investedNative }, sum { it.benchNative },
            uniformCcy, color, label, benchName, flows, xirr,
        ) else Src(
            sum { it.seriesInr }, sum { it.investedInr }, sum { it.benchInr },
            "INR", color, label, benchName, flows, xirr,
        )
    }

    @SuppressLint("SetTextI18n")
    private fun renderHero(s: Snapshot) {
        val sc = src(s)
        // The headline is current net worth; the growth chip + flow sub-line are
        // window-dependent and set by applyWindow (which renderChart calls, and
        // which fires again on every pan — so the top tracks the visible window).
        binding.tvHeroValue.text = mask(money(sc.values.last(), sc.ccy))
        binding.tvAfterTax.text = mask("≈ " + money(
            PortfolioAnalytics.afterTax(sc.values.last(), sc.invested.last()), sc.ccy)
            + " after tax")
        binding.tvXirrChip.text = "XIRR " + pct(sc.xirr)
    }

    /**
     * Recomputes every window-dependent number — the hero growth chip & flow
     * sub-line and the range row under the chart — for an arbitrary visible
     * span [startIdx, endIdx]. Called once per render with the chart's viewport
     * and again from onViewportChange, so panning the chart re-frames the whole
     * top of the page, not just the graph.
     */
    @SuppressLint("SetTextI18n")
    private fun applyWindow(s: Snapshot, startIdx: Int, endIdx: Int) {
        val sc = src(s)
        val n = sc.values.size
        val i0 = startIdx.coerceIn(0, n - 1)
        val iE = endIdx.coerceIn(i0, n - 1)

        // Split the change into two parts the user asked to see apart:
        //  · growth — what the holdings gained/lost on the market
        //  · added/removed — money put in or taken out over the window
        val vEnd = (iE downTo i0).firstOrNull { sc.values[it] > 0 }?.let { sc.values[it] } ?: 0.0
        val i0v = (i0..iE).firstOrNull { sc.values[it] > 0 }
        val v0 = i0v?.let { sc.values[it] } ?: vEnd
        val added = sc.invested[iE] - sc.invested[i0]
        // growth anchors its invested-delta at the first OWNED day: v0 already
        // contains that day's buy, so counting it again would double-subtract
        // (windows can start before the selection's first trade)
        val addedSinceOwned = i0v?.let { sc.invested[iE] - sc.invested[it] } ?: added
        val growth = (vEnd - v0) - addedSinceOwned
        val base = v0 + addedSinceOwned.coerceAtLeast(0.0)
        val gp = if (base > 0) growth / base else 0.0

        val fmtD = DateTimeFormatter.ofPattern("d MMM")
        val atLatest = iE >= n - 1
        val winLabel = if (atLatest) range else
            "${LocalDate.ofEpochDay(s.days[i0]).format(fmtD)}–${LocalDate.ofEpochDay(s.days[iE]).format(fmtD)}"

        chip(binding.tvDayChip, if (abs(gp) < 5e-4) null else growth > 0,
            mask("${if (growth >= 0) "▲" else "▼"} ${money(abs(growth), sc.ccy)} · ${"%.1f".format(abs(gp) * 100)}% · $winLabel"))
        val flow = when {
            added > 1.0 -> "added ${mask(money(added, sc.ccy))}"
            added < -1.0 -> "withdrew ${mask(money(-added, sc.ccy))}"
            else -> null
        }
        binding.tvHeroSub.text = buildString {
            append(sc.label)
            if (flow != null) append(" · $flow")
            append(if (atLatest) " · as of ${LocalDate.ofEpochDay(s.days[iE]).format(fmtD)}"
                   else " · to ${LocalDate.ofEpochDay(s.days[iE]).format(fmtD)}")
        }

        // range row under the chart follows the active lens
        if (chartMode == "Perf") {
            fun rel(arr: DoubleArray): Double? {
                val b = (i0..iE).firstOrNull { !arr[it].isNaN() }?.let { arr[it] } ?: return null
                val e = (iE downTo i0).firstOrNull { !arr[it].isNaN() }?.let { arr[it] } ?: return null
                return ((1 + e / 100) / (1 + b / 100) - 1) * 100
            }
            val yNow = rel(twrSeries(sc.values, sc.invested)) ?: 0.0
            val gap = yNow - (rel(twrSeries(sc.bench, sc.invested)) ?: 0.0)
            binding.tvRangeValue.text = "${if (yNow >= 0) "+" else ""}${"%.1f".format(yNow)}%"
            chip(binding.tvRangeChange, if (abs(gap) < 0.05) null else gap > 0,
                "${if (gap >= 0) "+" else ""}${"%.1f".format(gap)} pts vs index")
            binding.tvRangeMeta.text = winLabel
        } else {
            binding.tvRangeValue.text = mask(money(vEnd, sc.ccy))
            chip(binding.tvRangeChange, if (abs(gp) < 1e-4) null else growth > 0,
                mask("${if (growth >= 0) "+" else "−"}${money(abs(growth), sc.ccy)} · ${"%.1f".format(abs(gp) * 100)}%"))
            binding.tvRangeMeta.text =
                if (added > 1000) "$winLabel · added ${mask(money(added, sc.ccy))}" else winLabel
        }
    }

    /** Time-weighted return %: each day's growth measured after backing out that
     *  day's contribution, so deposits and sells never move the line. */
    private fun twrSeries(values: DoubleArray, invested: DoubleArray): DoubleArray {
        val n = values.size
        val out = DoubleArray(n) { Double.NaN }
        var level = 1.0
        var prev = Double.NaN
        for (k in 0 until n) {
            val v = values[k]
            if (v.isNaN() || v <= 0) continue
            if (!prev.isNaN() && k > 0) {
                val b = prev + (invested[k] - invested[k - 1])
                if (b > 0) level *= v / b
            }
            prev = v
            out[k] = (level - 1) * 100
        }
        return out
    }

    private fun renderTiles(s: Snapshot) {
        val wrap = binding.layoutTiles
        wrap.removeAllViews()
        val i0 = startIndex(s)
        for (b in Bucket.entries) {
            val st = s.buckets.getValue(b)
            val tile = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_bucket_tile, wrap, false)
            val dot = tile.findViewById<View>(R.id.tileDot)
            dot.background.mutate().setTint(bucketColors.getValue(b))
            tile.findViewById<TextView>(R.id.tileName).text = b.label
            tile.findViewById<TextView>(R.id.tileValue).text = mask(money(st.value, b.currency))
            // spark + move follow the selected range, like the rest of the page
            tile.findViewById<SparkView>(R.id.tileSpark)
                .set(st.seriesNative.window(i0), bucketColors.getValue(b))
            val d = tile.findViewById<TextView>(R.id.tileDay)
            val w0 = (i0 until st.seriesNative.size)
                .firstOrNull { st.seriesNative[it] > 0 }?.let { st.seriesNative[it] } ?: 0.0
            val movePct = if (w0 > 0) (st.value / w0 - 1) * 100 else 0.0
            d.text = "${if (movePct >= 0) "+" else ""}${"%.1f".format(movePct)}%"
            d.setTextColor(requireContext().getColor(
                when { movePct > 0.05 -> R.color.accent_green
                    movePct < -0.05 -> R.color.accent_red
                    else -> R.color.text_secondary }
            ))
            val chosen = b in selection
            tile.isSelected = chosen
            tile.setBackgroundResource(
                if (chosen) R.drawable.bg_tile_selected else R.drawable.bg_detail_group
            )
            tile.setOnClickListener {
                if (!selection.add(b)) selection.remove(b)
                if (selection.size == Bucket.entries.size) selection.clear()
                snapshot?.let { render(it) }
            }
            wrap.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (b != Bucket.entries.last()) marginEnd = dp(8) })
        }
    }

    /** Windowed, downsampled slice for the tile sparklines. */
    private fun DoubleArray.window(from: Int): DoubleArray {
        val n = size - from
        if (n <= 64) return copyOfRange(from, size)
        val stride = n / 64f
        return DoubleArray(64) { i -> this[from + (i * stride).toInt().coerceAtMost(n - 1)] }
    }

    private val ranges = listOf("1W", "1M", "3M", "6M", "1Y", "All")

    private fun renderRanges() {
        val wrap = binding.layoutRanges
        wrap.removeAllViews()
        for (r in ranges) {
            val tv = TextView(requireContext()).apply {
                text = r
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, dp(5))
                if (r == range) {
                    setBackgroundResource(R.drawable.bg_pill_selected)
                    setTextColor(requireContext().getColor(R.color.accent_emerald_light))
                } else {
                    setBackgroundResource(R.drawable.bg_input_pill)
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                }
                setOnClickListener {
                    range = r
                    // the range re-frames the whole page (hero, tiles, chart)
                    snapshot?.let { render(it) }
                }
            }
            wrap.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (r != ranges.last()) marginEnd = dp(6) })
        }
    }

    private fun startIndex(s: Snapshot): Int {
        val endDay = s.asOfDay
        val fromDay = when (range) {
            // All still falls through the zero-skip below: with a bucket subset
            // selected, the calendar starts at the FIRST trade overall (e.g. a
            // 2018 crypto buy), and years of flat zero would crush the graph
            "All" -> Long.MIN_VALUE
            else -> endDay - mapOf("1W" to 7L, "1M" to 30L, "3M" to 91L, "6M" to 182L, "1Y" to 365L).getValue(range)
        }
        var i = s.days.indexOfFirst { it >= fromDay }
        if (i < 0) i = 0
        val series = src(s).values
        while (i < series.size - 1 && series[i] <= 0) i++
        return i
    }

    @SuppressLint("SetTextI18n")
    private fun renderChart(s: Snapshot) {
        val sc = src(s)
        val i0 = startIndex(s)
        val n = sc.values.size
        val win = n - i0
        val dateFmt = DateTimeFormatter.ofPattern("d MMM yy")

        // trade points = days the net contribution changed; label the amount
        // (hidden when masked). Nearby markers merge into one combined number.
        val tradeDots = buildList {
            for (k in 1 until n) {
                val flow = sc.invested[k] - sc.invested[k - 1]
                if (abs(flow) > 1.0) add(LineChartView.Dot(
                    k, value = flow,
                    label = if (masked) null else (if (flow >= 0) "+" else "−") + money(abs(flow), sc.ccy),
                    up = flow >= 0
                ))
            }
        }
        binding.chart.dotLabelFormatter =
            { v -> (if (v >= 0) "+" else "−") + money(abs(v), sc.ccy) }
        // performance line is busy — reveal trade amounts only on scrub
        binding.chart.scrubDotLabels = chartMode == "Perf"

        if (chartMode == "Perf") {
            // time-weighted return over full history; the chart re-anchors 0% to
            // the visible window (rebase) so panning stays meaningful.
            binding.chart.allowNegative = true
            binding.chart.yFormatter = { "%.0f%%".format(it) }
            binding.chart.scrubFormatter = { _, day, v ->
                LocalDate.ofEpochDay(day).format(dateFmt) +
                    " · ${if (v >= 0) "+" else ""}${"%.1f".format(v)}%"
            }
            binding.chart.set(listOf(
                LineChartView.Series(twrSeries(sc.values, sc.invested), sc.color, area = true),
                LineChartView.Series(twrSeries(sc.bench, sc.invested), mutedLine, widthDp = 1.8f),
            ), s.days, tradeDots, win, rebase = true)
            binding.tvLegend.text = "— return %   — ${sc.benchName}   ● trades"
        } else {
            val lines = mutableListOf(LineChartView.Series(sc.values, sc.color, area = true))
            var legend = "— ${sc.label} value"
            if (compare) {
                lines += LineChartView.Series(sc.bench, mutedLine, widthDp = 1.8f)
                lines += LineChartView.Series(sc.invested, mutedLine, widthDp = 1.4f, dashed = true)
                legend = "— ${sc.label}   — Same money in ${sc.benchName}   ┈ Invested"
            }
            binding.chart.allowNegative = false
            binding.chart.yFormatter = { com.offlineupi.app.portfolio.MoneyFmt.axis(it, sc.ccy) }
            binding.chart.scrubFormatter = { _, day, v ->
                LocalDate.ofEpochDay(day).format(dateFmt) + " · " + mask(money(v, sc.ccy))
            }
            binding.chart.set(lines, s.days, tradeDots, win, rebase = false)
            binding.tvLegend.text = "$legend   ● trades"
        }

        // hero + range row track the chart's actual viewport (kept across lens
        // switches and pans); onViewportChange re-runs this as the user pans.
        val (vs, ve) = binding.chart.viewport()
        applyWindow(s, vs, ve)
    }

    private fun renderDotPlot(s: Snapshot) {
        val rows = mutableListOf(
            com.offlineupi.app.ui.charts.DotPlotView.Row(
                "All", "blend · IN CPI", s.xirr, s.benchXirr, s.inflXirr, brand)
        )
        for (b in Bucket.entries) {
            val st = s.buckets.getValue(b)
            rows += com.offlineupi.app.ui.charts.DotPlotView.Row(
                b.label,
                "${b.benchName.replace(" 500", "").replace(" 50", "")} · ${b.cpi} CPI",
                st.xirr, st.benchXirr, st.inflXirr, bucketColors.getValue(b)
            )
        }
        binding.dotPlot.set(rows)
        val gain = s.value - s.benchValue
        val real = s.value - s.inflValue
        binding.tvBeatFooter.text =
            "Same cashflows: indexes → ${mask(inr(s.benchValue))} " +
            "(${if (gain >= 0) "ahead" else "behind"} ${mask(inr(abs(gain)))}) · " +
            "India CPI → ${mask(inr(s.inflValue))} (real gain ${mask(inr(real))})"
    }

    private fun renderAllocation(s: Snapshot) {
        binding.tvAllocTotal.text = mask(inr(s.value)) + " total"
        val bar = binding.allocBar
        bar.removeAllViews()
        for (b in Bucket.entries) {
            val st = s.buckets.getValue(b)
            val w = (st.valueInr / s.value).toFloat().coerceAtLeast(0.004f)
            val seg = View(requireContext())
            seg.setBackgroundColor(bucketColors.getValue(b))
            bar.addView(seg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)
                .apply { if (b != Bucket.entries.last()) marginEnd = dp(2) })
        }
        val key = binding.allocKey
        key.removeAllViews()
        for (b in Bucket.entries.sortedByDescending { s.buckets.getValue(it).valueInr }) {
            val st = s.buckets.getValue(b)
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            row.addView(View(requireContext()).apply {
                setBackgroundResource(R.drawable.bg_dot)
                background.mutate().setTint(bucketColors.getValue(b))
            }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(10) })
            row.addView(TextView(requireContext()).apply {
                text = b.label
                setTextColor(requireContext().getColor(R.color.text_primary))
                textSize = 13f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(requireContext()).apply {
                text = "%.1f%%".format(st.valueInr / s.value * 100)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                textSize = 12.5f
            }, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(requireContext()).apply {
                text = mask(money(st.value, b.currency))
                setTextColor(requireContext().getColor(R.color.text_primary))
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(dp(84), ViewGroup.LayoutParams.WRAP_CONTENT))
            key.addView(row)
        }
    }

    private fun renderMovers(s: Snapshot) {
        val wrap = binding.layoutMovers
        wrap.removeAllViews()
        val fmt = DateTimeFormatter.ofPattern("d MMM")
        val items = s.movers.take(4)
        if (items.isEmpty()) {
            wrap.addView(TextView(requireContext()).apply {
                text = "No moves beyond ±4% in the last 90 days"
                setTextColor(requireContext().getColor(R.color.text_secondary))
                textSize = 12.5f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        for (m in items) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                setBackgroundResource(R.drawable.ripple_group)
                setOnClickListener { openDetail(m.holding.instrument.isin) }
            }
            row.addView(TextView(requireContext()).apply {
                text = LocalDate.ofEpochDay(m.day).format(fmt)
                setTextColor(requireContext().getColor(R.color.text_secondary))
                textSize = 11.5f
            }, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(requireContext()).apply {
                text = m.holding.instrument.name
                setTextColor(requireContext().getColor(R.color.text_primary))
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(requireContext()).apply {
                text = mask(money(m.holding.value, m.holding.instrument.currency))
                setTextColor(requireContext().getColor(R.color.text_secondary))
                textSize = 11.5f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            row.addView(TextView(requireContext()).apply {
                val up = m.pct > 0
                text = "${if (up) "▲" else "▼"} ${"%.1f".format(abs(m.pct))}%"
                textSize = 11.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setTextColor(requireContext().getColor(if (up) R.color.accent_green else R.color.accent_red))
                setBackgroundResource(if (up) R.drawable.bg_chip_verified else R.drawable.bg_chip_failed)
            })
            wrap.addView(row)
            wrap.addView(View(requireContext()).apply {
                setBackgroundColor(requireContext().getColor(R.color.divider))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
    }

    private fun renderHoldings(s: Snapshot) {
        binding.tvHoldingsCount.text = "top 5 of ${s.holdings.size} by value"
        val wrap = binding.layoutHoldings
        wrap.removeAllViews()
        for (h in s.holdings.take(5)) {
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_holding_row, wrap, false)
            row.setBackgroundResource(R.drawable.ripple_group)
            row.setOnClickListener { openDetail(h.instrument.isin) }
            row.findViewById<View>(R.id.hDot).background.mutate().setTint(bucketColors.getValue(h.bucket))
            row.findViewById<TextView>(R.id.hName).text = h.instrument.name
            row.findViewById<TextView>(R.id.hSub).text =
                mask(money(h.invested, h.instrument.currency)) + " in · " +
                    "%.1f%%".format(h.valueInr / s.value * 100)
            row.findViewById<SparkView>(R.id.hSpark).set(
                h.spark,
                if (h.dayPct >= 0) 0xFF2E9B6E.toInt() else 0xFFC25B4E.toInt()
            )
            row.findViewById<TextView>(R.id.hValue).text = mask(money(h.value, h.instrument.currency))
            val meta = row.findViewById<TextView>(R.id.hMeta)
            val dayTxt = "${if (h.dayPct > 0) "+" else ""}${"%.1f".format(h.dayPct)}%"
            meta.text = "$dayTxt · XIRR ${h.xirr?.let { "%.0f%%".format(it * 100) } ?: "—"}"
            meta.setTextColor(requireContext().getColor(
                when { h.dayPct > 0.05 -> R.color.accent_green
                    h.dayPct < -0.05 -> R.color.accent_red
                    else -> R.color.text_secondary }
            ))
            wrap.addView(row)
        }
    }
}
