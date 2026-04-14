package com.offlineupi.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.offlineupi.app.data.TransactionStore
import com.offlineupi.app.databinding.ActivityMainBinding
import com.offlineupi.app.util.normalizeIndianMobile
import com.offlineupi.app.worker.DailyBalanceCheckWorker

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        DailyBalanceCheckWorker.schedule(this)
        TransactionStore(this).deduplicateByRrn()

        binding.btnScanQr.setOnClickListener {
            startActivity(Intent(this, ScanQrActivity::class.java))
        }

        binding.btnPay.setOnClickListener {
            val raw = binding.etPayee.text?.toString().orEmpty().trim()
            if (raw.isEmpty()) {
                binding.tilPayee.error = "Enter a phone number or UPI ID"
                return@setOnClickListener
            }
            val intent = Intent(this, ConfirmationActivity::class.java)
            if (raw.contains('@')) {
                intent.putExtra(ConfirmationActivity.EXTRA_PAYEE_ADDRESS, raw)
                intent.putExtra(ConfirmationActivity.EXTRA_PAYEE_TYPE, ConfirmationActivity.TYPE_VPA)
            } else {
                val normalized = normalizeIndianMobile(raw)
                if (normalized == null) {
                    binding.tilPayee.error = "Enter a valid 10-digit phone number or UPI ID (with @)"
                    return@setOnClickListener
                }
                intent.putExtra(ConfirmationActivity.EXTRA_PAYEE_ADDRESS, normalized)
                intent.putExtra(ConfirmationActivity.EXTRA_PAYEE_TYPE, ConfirmationActivity.TYPE_PHONE)
            }
            binding.tilPayee.error = null
            startActivity(intent)
        }

        binding.etPayee.setOnFocusChangeListener { _, _ ->
            binding.tilPayee.error = null
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, TransactionHistoryActivity::class.java))
        }

        binding.btnAccounts.setOnClickListener {
            startActivity(Intent(this, BalanceOverviewActivity::class.java))
        }

        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }
}
