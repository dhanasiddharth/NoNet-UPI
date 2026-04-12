package com.offlineupi.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.offlineupi.app.R
import com.offlineupi.app.data.AccountBalance
import com.offlineupi.app.data.AccountBalanceStore
import com.offlineupi.app.databinding.ActivityBalanceOverviewBinding
import com.offlineupi.app.sms.SmsBroadcastReceiver
import com.offlineupi.app.util.formatIndianNumber

class BalanceOverviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBalanceOverviewBinding
    private lateinit var store: AccountBalanceStore
    private val balances = mutableListOf<AccountBalance>()

    private val balanceUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadBalances()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBalanceOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = AccountBalanceStore(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.rvBalances.layoutManager = LinearLayoutManager(this)
        binding.rvBalances.adapter = BalanceAdapter()

        binding.btnCheckBalance.setOnClickListener { dialMissedCallNumber() }
        binding.btnCheckUssd.setOnClickListener {
            startActivity(Intent(this, BalanceCheckActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SmsBroadcastReceiver.ACTION_BALANCES_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(balanceUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(balanceUpdateReceiver, filter)
        }
        loadBalances()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(balanceUpdateReceiver)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun loadBalances() {
        balances.clear()
        balances.addAll(store.getBalances().sortedBy { it.accountNumber })

        if (balances.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.rvBalances.visibility = View.GONE
            binding.tvLastUpdated.visibility = View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.rvBalances.visibility = View.VISIBLE
            binding.rvBalances.adapter?.notifyDataSetChanged()

            val latest = balances.maxOf { it.timestamp }
            val relative = DateUtils.getRelativeTimeSpanString(
                latest, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            binding.tvLastUpdated.text = "Last updated: $relative"
            binding.tvLastUpdated.visibility = View.VISIBLE
        }
    }

    private fun dialMissedCallNumber() {
        val number = store.getMissedCallNumber()
        val uri = Uri.parse("tel:$number")

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, uri))
                Toast.makeText(this, "Calling $number for balance...", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_DIAL, uri))
            }
        } else {
            Toast.makeText(this, "Call permission required", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
    }

    private inner class BalanceAdapter : RecyclerView.Adapter<BalanceAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvAccountNumber: TextView = view.findViewById(R.id.tvAccountNumber)
            val tvBankName: TextView = view.findViewById(R.id.tvBankName)
            val tvBalance: TextView = view.findViewById(R.id.tvBalance)
            val tvUpdatedAt: TextView = view.findViewById(R.id.tvUpdatedAt)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_account_balance, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = balances[position]

            holder.tvAccountNumber.text = item.accountNumber

            if (!item.bankName.isNullOrBlank()) {
                holder.tvBankName.text = item.bankName
                holder.tvBankName.visibility = View.VISIBLE
            } else {
                holder.tvBankName.visibility = View.GONE
            }

            val balanceNum = item.balance.toDoubleOrNull()
            val formatted = "\u20B9 ${formatIndianNumber(item.balance)}"
            holder.tvBalance.text = formatted
            if (balanceNum != null && balanceNum < 0) {
                holder.tvBalance.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.accent_amber)
                )
            } else {
                holder.tvBalance.setTextColor(
                    ContextCompat.getColor(holder.itemView.context, R.color.text_primary)
                )
            }

            val relative = DateUtils.getRelativeTimeSpanString(
                item.timestamp, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            holder.tvUpdatedAt.text = "Updated $relative"
        }

        override fun getItemCount() = balances.size
    }
}
