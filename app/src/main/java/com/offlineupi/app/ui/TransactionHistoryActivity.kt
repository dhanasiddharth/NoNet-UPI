package com.offlineupi.app.ui

import com.offlineupi.app.util.applySystemBarInsets

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.offlineupi.app.R
import com.offlineupi.app.data.Transaction
import com.offlineupi.app.data.TransactionStore
import com.offlineupi.app.databinding.ActivityTransactionHistoryBinding

/** Dedicated transactions list with search and status filters. */
class TransactionHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTransactionHistoryBinding
    private lateinit var all: List<Transaction>

    private var query = ""
    private var statusFilter: String? = null // null = all

    private val filters = listOf(
        "All" to null,
        "Verified" to "success",
        "Pending" to "pending",
        "Reversed" to "reversed",
        "Failed" to "failure",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.rvTransactions.layoutManager = LinearLayoutManager(this)
        binding.etSearch.doAfterTextChanged {
            query = it?.toString().orEmpty().trim()
            refresh()
        }
        buildFilterChips()
    }

    override fun onResume() {
        super.onResume()
        all = TransactionStore(this).getTransactions().filter { it.type == "payment" }
        refresh()
    }

    private fun buildFilterChips() {
        binding.filterRow.removeAllViews()
        filters.forEach { (label, status) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                gravity = android.view.Gravity.CENTER
                minHeight = dp(38)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                foreground = getDrawable(R.drawable.ripple_pill)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    statusFilter = status
                    styleChips()
                    refresh()
                }
            }
            binding.filterRow.addView(chip)
        }
        styleChips()
    }

    private fun styleChips() {
        filters.forEachIndexed { i, (_, status) ->
            val chip = binding.filterRow.getChildAt(i) as TextView
            val active = status == statusFilter
            chip.setBackgroundResource(if (active) R.drawable.bg_pill_active else R.drawable.bg_input_pill)
            chip.setTextColor(getColor(if (active) R.color.accent_emerald_light else R.color.text_secondary))
        }
    }

    private fun refresh() {
        val q = query.lowercase()
        val filtered = all.filter { t ->
            (statusFilter == null || t.status == statusFilter) &&
                (q.isEmpty() ||
                    t.payeeName?.lowercase()?.contains(q) == true ||
                    t.payeeAddress?.lowercase()?.contains(q) == true ||
                    t.amount?.contains(q) == true ||
                    t.rrn?.contains(q) == true)
        }
        binding.tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.rvTransactions.adapter = FeedAdapter(buildFeedItems(filtered)) { txn ->
            startActivity(Intent(this, TransactionReceiptActivity::class.java).apply {
                putExtra(TransactionReceiptActivity.EXTRA_TRANSACTION_ID, txn.id)
            })
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
