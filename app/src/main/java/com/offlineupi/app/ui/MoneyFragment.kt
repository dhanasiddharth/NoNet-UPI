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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.offlineupi.app.R
import com.offlineupi.app.data.AccountBalanceStore
import com.offlineupi.app.databinding.FragmentMoneyBinding
import com.offlineupi.app.sms.SmsBroadcastReceiver
import com.offlineupi.app.util.formatIndianNumber

/** Bank tab — account balances up top, bank-statement reconciliation below (Phase 3). */
class MoneyFragment : Fragment() {

    private var _binding: FragmentMoneyBinding? = null
    private val binding get() = _binding!!

    // Lazy: EncryptedSharedPreferences init hits the Keystore (~100ms on the main
    // thread) — defer it past the tab-slide animation so the transition stays smooth.
    private val store by lazy { AccountBalanceStore(requireContext()) }

    private val balanceUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadBalances()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoneyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.btnMissedCall.setOnClickListener { dialMissedCallNumber() }
        binding.btnUssdCheck.setOnClickListener {
            startActivity(Intent(requireContext(), BalanceCheckActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SmsBroadcastReceiver.ACTION_BALANCES_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                balanceUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            requireContext().registerReceiver(balanceUpdateReceiver, filter)
        }
        // Load after the 220ms tab slide has finished so Keystore/JSON work
        // never janks the transition.
        view?.postDelayed({ if (_binding != null) loadBalances() }, 240)
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(balanceUpdateReceiver)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun loadBalances() {
        val b = _binding ?: return
        val balances = store.getBalances().sortedBy { it.accountNumber }
        b.layoutBalances.removeAllViews()

        if (balances.isEmpty()) {
            b.layoutBalances.visibility = View.GONE
            b.tvNoBalances.visibility = View.VISIBLE
            return
        }
        b.layoutBalances.visibility = View.VISIBLE
        b.tvNoBalances.visibility = View.GONE

        val ctx = requireContext()
        balances.forEachIndexed { i, bal ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            val left = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            left.addView(TextView(ctx).apply {
                text = bal.accountNumber
                setTextColor(ctx.getColor(R.color.text_primary))
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            left.addView(TextView(ctx).apply {
                text = "Updated " + DateUtils.getRelativeTimeSpanString(
                    bal.timestamp, System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE
                )
                setTextColor(ctx.getColor(R.color.text_secondary))
                textSize = 11f
            })
            row.addView(left)
            row.addView(TextView(ctx).apply {
                text = "₹ ${formatIndianNumber(bal.balance)}"
                val neg = bal.balance.toDoubleOrNull()?.let { it < 0 } == true
                setTextColor(ctx.getColor(if (neg) R.color.accent_amber else R.color.text_primary))
                textSize = 16f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            })
            b.layoutBalances.addView(row)
            if (i < balances.lastIndex) {
                b.layoutBalances.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(ctx.getColor(R.color.divider))
                })
            }
        }
    }

    private fun dialMissedCallNumber() {
        val number = store.getMissedCallNumber()
        val uri = Uri.parse("tel:$number")
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, uri))
                Toast.makeText(requireContext(), "Calling $number for balance...", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_DIAL, uri))
            }
        } else {
            Toast.makeText(requireContext(), "Call permission required", Toast.LENGTH_SHORT).show()
            startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
