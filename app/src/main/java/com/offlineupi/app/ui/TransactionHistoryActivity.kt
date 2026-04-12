package com.offlineupi.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.offlineupi.app.R
import com.offlineupi.app.data.Transaction
import com.offlineupi.app.data.TransactionStore
import com.offlineupi.app.databinding.ActivityTransactionHistoryBinding
import com.offlineupi.app.util.formatIndianNumber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTransactionHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        val store = TransactionStore(this)
        val transactions = store.getTransactions()

        if (transactions.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvTransactions.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvTransactions.visibility = View.VISIBLE
            binding.rvTransactions.layoutManager = LinearLayoutManager(this)
            binding.rvTransactions.adapter = TransactionAdapter(transactions) { txn ->
                startActivity(Intent(this, TransactionReceiptActivity::class.java).apply {
                    putExtra(TransactionReceiptActivity.EXTRA_TRANSACTION_ID, txn.id)
                })
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private class TransactionAdapter(
        private val items: List<Transaction>,
        private val onClick: (Transaction) -> Unit
    ) : RecyclerView.Adapter<TransactionAdapter.ViewHolder>() {

        private val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvStatusIcon: TextView = view.findViewById(R.id.tvStatusIcon)
            val tvPayee: TextView = view.findViewById(R.id.tvPayee)
            val tvDateTime: TextView = view.findViewById(R.id.tvDateTime)
            val tvAmount: TextView = view.findViewById(R.id.tvAmount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transaction, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val txn = items[position]

            // Status icon
            if (txn.status == "success") {
                holder.tvStatusIcon.text = "\u2713"
                holder.tvStatusIcon.setBackgroundResource(R.drawable.bg_step_done)
            } else {
                holder.tvStatusIcon.text = "!"
                holder.tvStatusIcon.setBackgroundResource(R.drawable.bg_step_active)
            }

            // Payee
            holder.tvPayee.text = txn.payeeAddress ?: "Payment"

            // Date
            holder.tvDateTime.text = dateFormat.format(Date(txn.timestamp))

            // Amount
            val amount = txn.amount
            if (!amount.isNullOrBlank()) {
                holder.tvAmount.text = "\u20B9 ${formatIndianNumber(amount)}"
                holder.tvAmount.setTextColor(
                    holder.itemView.context.getColor(
                        if (txn.status == "success") R.color.accent_green else R.color.accent_amber
                    )
                )
            } else {
                holder.tvAmount.text = "-"
            }

            holder.itemView.setOnClickListener { onClick(txn) }
        }

        override fun getItemCount() = items.size
    }
}
