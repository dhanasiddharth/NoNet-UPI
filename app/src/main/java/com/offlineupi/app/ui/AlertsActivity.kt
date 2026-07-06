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
import com.offlineupi.app.databinding.ActivityAlertsBinding
import com.offlineupi.app.portfolio.MoneyFmt
import com.offlineupi.app.portfolio.PortfolioAnalytics.Bucket
import com.offlineupi.app.portfolio.PortfolioAnalytics.Snapshot
import com.offlineupi.app.portfolio.PortfolioDb
import com.offlineupi.app.worker.PriceSyncWorker
import com.offlineupi.app.util.applySystemBarInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Movement alerts: what fired, and the threshold cascade that decides when —
 * a holding rule beats its bucket rule beats the default.
 */
class AlertsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertsBinding
    private val db by lazy { PortfolioDb(this) }
    private var snapshot: Snapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlertsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.tvMeta.text = "checked after each price sync"
        binding.btnAddOverride.setOnClickListener { pickHoldingForRule() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) { PortfolioUi.snapshot(db) }
            snapshot = snap
            render()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        renderRecent()
        renderRules()
        renderOverrides()
    }

    private fun renderRecent() {
        val wrap = binding.layoutRecent
        wrap.removeAllViews()
        val alerts = db.recentAlerts()
        if (alerts.isEmpty()) {
            wrap.addView(TextView(this).apply {
                text = "Nothing yet. When a holding moves beyond its threshold you'll get a notification and it shows up here."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12.5f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        val fmt = DateTimeFormatter.ofPattern("d MMM")
        for (a in alerts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            row.addView(TextView(this).apply {
                text = LocalDate.ofEpochDay(a.day).format(fmt)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11.5f
            }, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = a.name
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                maxLines = 1
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = MoneyFmt.money(a.value, a.currency)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11.5f
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
            row.addView(TextView(this).apply {
                val up = a.pct > 0
                text = "${if (up) "▲" else "▼"} ${"%.1f".format(abs(a.pct))}%"
                textSize = 11.5f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                setTextColor(getColor(if (up) R.color.accent_green else R.color.accent_red))
                setBackgroundResource(if (up) R.drawable.bg_chip_verified else R.drawable.bg_chip_failed)
            })
            wrap.addView(row)
            wrap.addView(View(this).apply { setBackgroundColor(getColor(R.color.divider)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderRules() {
        val rules = db.alertRules()
        val def = rules["default"] ?: PriceSyncWorker.DEFAULT_THRESHOLD
        val wrap = binding.layoutRules
        wrap.removeAllViews()

        wrap.addView(ruleRow("Any holding", null, "±${trim(def)}%", rules.containsKey("default")) {
            promptThreshold("Default threshold ±%", rules["default"]) { v ->
                db.setAlertRule("default", v); render()
            }
        })
        for (b in Bucket.entries) {
            val scope = "bucket:${b.name}"
            val own = rules[scope]
            wrap.addView(ruleRow(b.label, PortfolioUi.bucketColors.getValue(b),
                "±${trim(own ?: def)}%", own != null) {
                promptThreshold("${b.label} threshold ±%", own, allowInherit = true) { v ->
                    db.setAlertRule(scope, v); render()
                }
            })
        }
    }

    private fun renderOverrides() {
        val rules = db.alertRules().filterKeys { it.startsWith("isin:") }
        val wrap = binding.layoutOverrides
        wrap.removeAllViews()
        if (rules.isEmpty()) {
            wrap.addView(TextView(this).apply {
                text = "No per-holding rules."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12.5f
                setPadding(0, dp(6), 0, dp(4))
            })
            return
        }
        val names = snapshot?.holdings?.associate { it.instrument.isin to it.instrument.name }.orEmpty()
        for ((scope, pct) in rules) {
            val isin = scope.removePrefix("isin:")
            wrap.addView(ruleRow(names[isin] ?: isin, null, "±${trim(pct)}%", true) {
                promptThreshold("${names[isin] ?: isin} threshold ±%", pct, allowInherit = true) { v ->
                    db.setAlertRule(scope, v); render()
                }
            })
        }
    }

    private fun ruleRow(label: String, dotColor: Int?, value: String,
                        overridden: Boolean, onTap: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            setBackgroundResource(R.drawable.ripple_group)
            setOnClickListener { onTap() }
        }
        if (dotColor != null) {
            row.addView(View(this).apply {
                setBackgroundResource(R.drawable.bg_dot)
                background.mutate().setTint(dotColor)
            }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(10) })
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setTextColor(getColor(if (overridden) R.color.accent_emerald_light else R.color.text_secondary))
            setBackgroundResource(if (overridden) R.drawable.bg_pill_selected else R.drawable.bg_input_pill)
        })
        return row
    }

    private fun promptThreshold(title: String, current: Double?,
                                allowInherit: Boolean = false, onSet: (Double?) -> Unit) {
        val input = EditText(this).apply {
            hint = "e.g. 3.5"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(current?.let { trim(it) } ?: "")
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                input.text.toString().toDoubleOrNull()?.let { onSet(it) }
            }
            .setNegativeButton("Cancel", null)
        if (allowInherit) dialog.setNeutralButton("Inherit") { _, _ -> onSet(null) }
        else if (current != null) dialog.setNeutralButton("Reset") { _, _ -> onSet(null) }
        dialog.show()
    }

    private fun pickHoldingForRule() {
        val holdings = snapshot?.holdings ?: return
        val existing = db.alertRules().keys
        val candidates = holdings.filter { "isin:${it.instrument.isin}" !in existing }
        val names = candidates.map { it.instrument.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Alert rule for…")
            .setItems(names) { _, which ->
                val h = candidates[which]
                promptThreshold("${h.instrument.name} threshold ±%", null) { v ->
                    db.setAlertRule("isin:${h.instrument.isin}", v); render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun trim(v: Double) =
        if (v == Math.floor(v)) "%.0f".format(v) else "%.1f".format(v)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
