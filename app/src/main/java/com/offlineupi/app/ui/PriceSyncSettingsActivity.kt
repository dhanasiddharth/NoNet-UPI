package com.offlineupi.app.ui

import android.os.Bundle
import android.widget.TimePicker
import androidx.appcompat.app.AppCompatActivity
import com.offlineupi.app.databinding.ActivityPriceSyncSettingsBinding
import com.offlineupi.app.portfolio.PortfolioDb
import com.offlineupi.app.util.applySystemBarInsets
import com.offlineupi.app.worker.PriceSyncWorker

/**
 * The two daily IST price-refresh times, edited inline with spinner pickers —
 * no dialogs. Changes persist and re-anchor the background work when the screen
 * is left (there's no Save button; the live summary line is the feedback).
 */
class PriceSyncSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPriceSyncSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPriceSyncSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.btnBack.setOnClickListener { finish() }

        val times = PriceSyncWorker.configuredTimes(this)
        seed(binding.timePicker1, times[0])
        seed(binding.timePicker2, times[1])
        val onChange = TimePicker.OnTimeChangedListener { _, _, _ -> renderSummary() }
        binding.timePicker1.setOnTimeChangedListener(onChange)
        binding.timePicker2.setOnTimeChangedListener(onChange)
        renderSummary()
        renderStatus()
    }

    private fun seed(tp: TimePicker, t: Pair<Int, Int>) {
        tp.setIs24HourView(false)
        tp.hour = t.first
        tp.minute = t.second
    }

    private fun clock(h: Int, m: Int): String {
        val h12 = ((h + 11) % 12) + 1
        return "%d:%02d %s".format(h12, m, if (h < 12) "am" else "pm")
    }

    private fun renderSummary() {
        binding.tvSummary.text = "Auto-updates at " +
            "${clock(binding.timePicker1.hour, binding.timePicker1.minute)} and " +
            "${clock(binding.timePicker2.hour, binding.timePicker2.minute)} IST"
    }

    private fun renderStatus() {
        val last = PortfolioDb(this).getMeta("priceSyncedAt")?.toLongOrNull()
        val tail = "Also refreshes when you open the app after an hour."
        binding.tvStatus.text = if (last == null) "Not synced yet. $tail" else {
            val mins = (System.currentTimeMillis() - last) / 60_000
            val ago = when {
                mins < 1 -> "just now"
                mins < 60 -> "${mins}m ago"
                mins < 2880 -> "${mins / 60}h ago"
                else -> "${mins / 1440}d ago"
            }
            "Last updated $ago. $tail"
        }
    }

    override fun onStop() {
        super.onStop()
        // no Save button by design — persist + re-anchor the timers on the way out
        PriceSyncWorker.setTimes(
            this,
            binding.timePicker1.hour to binding.timePicker1.minute,
            binding.timePicker2.hour to binding.timePicker2.minute,
        )
    }
}
