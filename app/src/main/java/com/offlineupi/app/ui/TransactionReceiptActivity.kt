package com.offlineupi.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.offlineupi.app.R
import com.offlineupi.app.data.SecureBalanceStore
import com.offlineupi.app.data.TransactionStore
import com.offlineupi.app.databinding.ActivityTransactionReceiptBinding
import com.offlineupi.app.sms.SmsInboxReader
import com.offlineupi.app.util.formatIndianNumber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionReceiptActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRANSACTION_ID = "extra_transaction_id"
    }

    private lateinit var binding: ActivityTransactionReceiptBinding
    private lateinit var store: TransactionStore
    private lateinit var balanceStore: SecureBalanceStore
    private var txnId: String? = null
    private var balanceVisible = false

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) checkStatusFromSms()
            else Toast.makeText(this, "SMS permission required to check status", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionReceiptBinding.inflate(layoutInflater)
        setContentView(binding.root)

        txnId = intent.getStringExtra(EXTRA_TRANSACTION_ID)
        if (txnId == null) { finish(); return }

        store = TransactionStore(this)
        balanceStore = SecureBalanceStore(this)

        displayTransaction()

        binding.btnDone.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { shareReceipt() }
    }

    private fun displayTransaction() {
        val txn = store.getTransaction(txnId!!)
        if (txn == null) { finish(); return }

        // Status
        when (txn.status) {
            "success" -> {
                binding.tvStatusIcon.text = "\u2713"
                binding.tvStatusIcon.setBackgroundResource(R.drawable.bg_step_done)
                binding.tvStatusText.text = "Payment Successful"
                binding.tvStatusText.setTextColor(getColor(R.color.accent_green))
            }
            "reversed" -> {
                binding.tvStatusIcon.text = "\u21a9"
                binding.tvStatusIcon.setBackgroundResource(R.drawable.bg_step_active)
                binding.tvStatusText.text = "Payment Reversed"
                binding.tvStatusText.setTextColor(getColor(R.color.accent_amber))
            }
            "pending" -> {
                binding.tvStatusIcon.text = "\u2026"
                binding.tvStatusIcon.setBackgroundResource(R.drawable.bg_step_active)
                binding.tvStatusText.text = "Status Pending"
                binding.tvStatusText.setTextColor(getColor(R.color.accent_amber))
            }
            else -> {
                binding.tvStatusIcon.text = "!"
                binding.tvStatusIcon.setBackgroundResource(R.drawable.bg_step_active)
                binding.tvStatusText.text = "Payment Failed"
                binding.tvStatusText.setTextColor(getColor(R.color.accent_amber))
            }
        }

        // Reversal / refund block \u2014 shows the debit and its refund together.
        if (txn.status == "reversed") {
            binding.layoutReversal.visibility = View.VISIBLE
            binding.tvReversalNote.text = txn.amount?.let {
                "\u20b9 ${formatIndianNumber(it)} was debited, then refunded to your account."
            } ?: "The debited amount was refunded to your account."
            val refRrn = txn.reversalRrn ?: txn.rrn
            if (!refRrn.isNullOrBlank()) {
                binding.tvReversalRrn.text = "Refund ref: $refRrn"
                binding.tvReversalRrn.visibility = View.VISIBLE
            } else {
                binding.tvReversalRrn.visibility = View.GONE
            }
        } else {
            binding.layoutReversal.visibility = View.GONE
        }

        // Offer an SMS re-check when no SMS has been read yet, the reference is
        // still missing, or the payment isn't a confirmed success.
        binding.btnCheckStatus.visibility =
            if (txn.rawSmsText.isNullOrBlank() || txn.rrn.isNullOrBlank() || txn.status != "success")
                View.VISIBLE else View.GONE

        // Amount
        val amount = txn.amount
        if (!amount.isNullOrBlank()) {
            binding.tvAmount.text = "\u20B9 ${formatIndianNumber(amount)}"
        } else {
            binding.tvAmount.text = "-"
        }

        // RRN
        if (!txn.rrn.isNullOrBlank()) {
            binding.tvRrn.text = txn.rrn
            binding.layoutRrn.visibility = View.VISIBLE
        } else {
            binding.layoutRrn.visibility = View.GONE
        }

        // Account
        if (!txn.accountNumber.isNullOrBlank()) {
            binding.tvAccount.text = txn.accountNumber
            binding.layoutAccount.visibility = View.VISIBLE
            binding.dividerAccount.visibility = View.VISIBLE
        } else {
            binding.layoutAccount.visibility = View.GONE
            binding.dividerAccount.visibility = View.GONE
        }

        // Payee — show captured name (if any) above the address
        if (!txn.payeeName.isNullOrBlank() && txn.payeeName != "Unknown Payee") {
            binding.tvPayeeName.text = txn.payeeName
            binding.tvPayeeName.visibility = View.VISIBLE
        } else {
            binding.tvPayeeName.visibility = View.GONE
        }
        binding.tvPayee.text = txn.payeeAddress ?: "-"

        // Date & Time
        val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        binding.tvDateTime.text = dateFormat.format(Date(txn.timestamp))

        // Remarks
        if (!txn.remarks.isNullOrBlank()) {
            binding.tvRemarks.text = txn.remarks
            binding.layoutRemarks.visibility = View.VISIBLE
            binding.dividerRemarks.visibility = View.VISIBLE
        } else {
            binding.layoutRemarks.visibility = View.GONE
            binding.dividerRemarks.visibility = View.GONE
        }

        // Balance (hidden behind eye icon, outside shareable card)
        if (!txn.balance.isNullOrBlank()) {
            binding.tvBalance.text = "\u2022\u2022\u2022\u2022\u2022"
            binding.layoutBalance.visibility = View.VISIBLE
            balanceVisible = false
            binding.ivToggleBalance.setImageResource(R.drawable.ic_visibility_off)

            binding.ivToggleBalance.setOnClickListener {
                balanceVisible = !balanceVisible
                if (balanceVisible) {
                    binding.tvBalance.text = "\u20B9 ${formatIndianNumber(txn.balance)}"
                    binding.ivToggleBalance.setImageResource(R.drawable.ic_visibility)
                } else {
                    binding.tvBalance.text = "\u2022\u2022\u2022\u2022\u2022"
                    binding.ivToggleBalance.setImageResource(R.drawable.ic_visibility_off)
                }
            }
        } else {
            binding.layoutBalance.visibility = View.GONE
        }

        // Check Status button
        binding.btnCheckStatus.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                checkStatusFromSms()
            } else {
                smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
            }
        }
    }

    private fun checkStatusFromSms() {
        val txn = store.getTransaction(txnId!!) ?: return

        val parsed = SmsInboxReader.findMatchingSms(this, txn)
        if (parsed != null) {
            // A reversal/refund SMS means the payment was refunded.
            val newStatus = if (parsed.isReversal) "reversed"
                            else if (parsed.type == "debit") "success"
                            else txn.status
            store.updateTransaction(txnId!!) { t ->
                t.copy(
                    status = newStatus,
                    // Keep the original debit RRN; record the refund ref separately.
                    rrn = t.rrn ?: parsed.rrn,
                    reversalRrn = if (parsed.isReversal) parsed.rrn else t.reversalRrn,
                    balance = parsed.balance ?: t.balance,
                    accountNumber = parsed.accountNumber ?: t.accountNumber
                )
            }

            if (parsed.balance != null) {
                balanceStore.saveBalance(
                    "\u20B9 ${formatIndianNumber(parsed.balance)}",
                    SecureBalanceStore.SOURCE_SMS
                )
            }

            val msg = if (parsed.isReversal) "Payment was reversed \u2014 amount refunded."
                      else "Transaction confirmed from SMS"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            displayTransaction()
        } else {
            Toast.makeText(this, "No matching SMS found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareReceipt() {
        val card = binding.cardReceipt
        val bitmap = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        card.draw(canvas)

        val file = File(cacheDir, "receipt_${txnId}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Receipt"))
    }
}
