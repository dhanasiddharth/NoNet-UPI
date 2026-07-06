package com.offlineupi.app.ui

import com.offlineupi.app.util.applySystemBarInsets

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.offlineupi.app.R
import com.offlineupi.app.data.SecureBalanceStore
import com.offlineupi.app.data.TransactionStore
import com.offlineupi.app.data.storedName
import com.offlineupi.app.databinding.ActivityTransactionReceiptBinding
import com.offlineupi.app.sms.SmsInboxReader
import com.offlineupi.app.util.ContactsHelper
import com.offlineupi.app.util.TimeFmt
import com.offlineupi.app.util.formatIndianNumber
import java.io.File
import java.io.FileOutputStream

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
        applySystemBarInsets(binding.root)

        txnId = intent.getStringExtra(EXTRA_TRANSACTION_ID)
        if (txnId == null) { finish(); return }

        store = TransactionStore(this)
        balanceStore = SecureBalanceStore(this)

        displayTransaction()

        binding.btnDone.setOnClickListener { finish() }
        binding.btnShare.setOnClickListener { shareReceipt() }
    }

    /**
     * Opens the system contact editor with the UPI ID pre-filled — the picker
     * lets you attach it to an existing contact or create a new one.
     */
    private fun addUpiIdToContact(upiId: String, name: String?) {
        val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
            type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
            name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
            putExtra(ContactsContract.Intents.Insert.NOTES, "UPI ID: $upiId")
            // UPI IDs share the email format, so file it there too for visibility
            putExtra(ContactsContract.Intents.Insert.EMAIL, upiId)
            putExtra(ContactsContract.Intents.Insert.EMAIL_TYPE,
                ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "No contacts app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayTransaction() {
        val txn = store.getTransaction(txnId!!)
        if (txn == null) { finish(); return }

        // Avatar initials from payee name; a device-contact photo replaces it
        binding.tvAvatar.text = MainActivity.initials(txn.payeeName, txn.payeeAddress)
        val contactName = ContactsHelper.applyPhotoToAvatar(binding.tvAvatar, txn.payeeAddress)

        // "Add UPI ID to contact" — only when the payee is a UPI ID
        val vpa = txn.payeeAddress?.takeIf { it.contains('@') }
        binding.btnAddToContact.visibility = if (vpa != null) View.VISIBLE else View.GONE
        binding.btnAddToContact.setOnClickListener {
            addUpiIdToContact(vpa ?: return@setOnClickListener, txn.storedName)
        }

        // Status \u2014 small badge on the avatar + status word (Google Pay style)
        when (txn.status) {
            "success" -> {
                binding.tvStatusIcon.setImageResource(R.drawable.ic_status_check)
                binding.tvStatusIcon.setBackgroundResource(R.drawable.bg_status_success)
                binding.tvStatusText.text = "Completed"
                binding.tvStatusText.setTextColor(getColor(R.color.accent_green))
            }
            "reversed" -> {
                binding.tvStatusIcon.setImageResource(R.drawable.ic_status_reverse)
                binding.tvStatusIcon.setBackgroundResource(R.drawable.bg_status_reversed)
                binding.tvStatusText.text = "Reversed"
                binding.tvStatusText.setTextColor(getColor(R.color.accent_amber))
            }
            "pending" -> {
                binding.tvStatusIcon.setImageResource(R.drawable.ic_status_pending)
                binding.tvStatusIcon.setBackgroundResource(R.drawable.bg_status_pending)
                binding.tvStatusText.text = "Pending"
                binding.tvStatusText.setTextColor(getColor(R.color.status_progress))
            }
            else -> {
                binding.tvStatusIcon.setImageResource(R.drawable.ic_status_fail)
                binding.tvStatusIcon.setBackgroundResource(R.drawable.bg_status_failed)
                binding.tvStatusText.text = "Failed"
                binding.tvStatusText.setTextColor(getColor(R.color.accent_red))
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

        // UPI transaction ID (tap to copy)
        if (!txn.rrn.isNullOrBlank()) {
            binding.tvRrn.text = txn.rrn
            binding.layoutRrn.visibility = View.VISIBLE
            binding.layoutRrn.setOnClickListener { copyToClipboard("UPI transaction ID", txn.rrn) }
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

        // Hero name — captured name, else the address as the title
        binding.tvPayeeName.text =
            txn.storedName ?: contactName ?: (txn.payeeAddress ?: "Payment")
        binding.tvPayee.text = txn.payeeAddress ?: "-"
        txn.payeeAddress?.let { addr ->
            binding.tvPayee.setOnClickListener {
                copyToClipboard(if (addr.contains('@')) "UPI ID" else "Phone number", addr)
            }
        }

        // Date & Time — IST, am/pm (see util/TimeFmt)
        binding.tvDateTime.text = TimeFmt.dateTime(txn.timestamp)

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

    private fun copyToClipboard(label: String, value: String) {
        val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, "$label copied", Toast.LENGTH_SHORT).show()
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
