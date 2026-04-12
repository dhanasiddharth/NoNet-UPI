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
import android.provider.Telephony
import android.telephony.SmsMessage
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.offlineupi.app.R
import com.offlineupi.app.accessibility.UssdAccessibilityService
import com.offlineupi.app.data.AccountStore
import com.offlineupi.app.data.PinStore
import com.offlineupi.app.data.SecureBalanceStore
import com.offlineupi.app.data.SecureBalanceStore.Companion.SOURCE_SMS
import com.offlineupi.app.data.Transaction
import com.offlineupi.app.data.TransactionStore
import com.offlineupi.app.databinding.ActivityUssdInstructionBinding
import com.offlineupi.app.sms.SmsParser
import com.offlineupi.app.util.formatIndianNumber

class UssdInstructionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAYEE_ADDRESS = "extra_payee_address"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_REMARKS = "extra_remarks"
    }

    private lateinit var binding: ActivityUssdInstructionBinding
    private lateinit var transactionStore: TransactionStore
    private lateinit var accountStore: AccountStore
    private lateinit var balanceStore: SecureBalanceStore
    private var payeeAddress = ""
    private var amount: String? = null
    private var remarks: String? = null
    private var autoFilling = false
    private var capturedAccountNumber: String? = null
    private var currentTransactionId: String? = null
    private var smsReceiverRegistered = false
    private var smsAlreadyMatched = false

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) registerSmsReceiver()
        }

    private val stepReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val step = intent.getIntExtra(UssdAccessibilityService.EXTRA_STEP, -1)
            val resultText = intent.getStringExtra(UssdAccessibilityService.EXTRA_RESULT_TEXT)
            val accountNumber = intent.getStringExtra(UssdAccessibilityService.EXTRA_ACCOUNT_NUMBER)
            val bankName = intent.getStringExtra(UssdAccessibilityService.EXTRA_BANK_NAME)
            if (accountNumber != null) {
                capturedAccountNumber = accountNumber
                accountStore.saveAccountNumber(accountNumber)
                if (bankName != null) accountStore.saveBankName(bankName)
            }
            onStepCompleted(step, resultText)
        }
    }

    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
            val bundle = intent.extras ?: return
            val pdus = bundle.get("pdus") as? Array<*> ?: return
            val format = bundle.getString("format", "3gpp")
            val fullBody = StringBuilder()
            pdus.forEach { pdu ->
                val msg = SmsMessage.createFromPdu(pdu as ByteArray, format)
                fullBody.append(msg.messageBody)
            }
            handleIncomingSms(fullBody.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUssdInstructionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        transactionStore = TransactionStore(this)
        accountStore = AccountStore(this)
        balanceStore = SecureBalanceStore(this)

        payeeAddress = intent.getStringExtra(EXTRA_PAYEE_ADDRESS) ?: ""
        amount = intent.getStringExtra(EXTRA_AMOUNT)
        remarks = intent.getStringExtra(EXTRA_REMARKS)
        autoFilling = UssdAccessibilityService.hasPending()

        setupSteps()
        requestSmsPermission()

        binding.btnRetry.setOnClickListener { retryTransaction() }

        binding.btnDone.setOnClickListener {
            UssdAccessibilityService.clearPending()
            finishAffinity()
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(UssdAccessibilityService.ACTION_STEP_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stepReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stepReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stepReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (smsReceiverRegistered) {
            try { unregisterReceiver(smsReceiver) } catch (_: Exception) {}
            smsReceiverRegistered = false
        }
    }

    private fun requestSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            registerSmsReceiver()
        } else {
            smsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
        }
    }

    private fun registerSmsReceiver() {
        if (smsReceiverRegistered) return
        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        filter.priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        registerReceiver(smsReceiver, filter)
        smsReceiverRegistered = true
    }

    private fun handleIncomingSms(body: String) {
        if (smsAlreadyMatched) return
        val parsed = SmsParser.parseUpiSms(body) ?: return
        val txnId = currentTransactionId ?: return

        // Match by amount (numeric comparison to handle "500" vs "500.00")
        val txnAmount = amount?.replace(",", "")?.trim()?.toDoubleOrNull()
        val smsAmount = parsed.amount.replace(",", "").trim().toDoubleOrNull()
        if (txnAmount != null && smsAmount != null && txnAmount != smsAmount) return

        // Match by account number (last 4 digits) if both are available
        val knownAccount = capturedAccountNumber ?: accountStore.getAccountNumber()
        if (knownAccount != null && parsed.accountNumber != null) {
            val knownSuffix = knownAccount.takeLast(4)
            val smsSuffix = parsed.accountNumber.takeLast(4)
            if (knownSuffix != smsSuffix) return
        }

        // Prevent duplicate matches
        smsAlreadyMatched = true

        // A debit SMS confirms the payment actually succeeded
        transactionStore.updateTransaction(txnId) { txn ->
            txn.copy(
                status = "success",
                rrn = parsed.rrn ?: txn.rrn,
                balance = parsed.balance ?: txn.balance,
                accountNumber = parsed.accountNumber ?: txn.accountNumber,
                rawSmsText = body
            )
        }

        // Update stored balance if available
        if (parsed.balance != null) {
            balanceStore.saveBalance("\u20B9 ${formatIndianNumber(parsed.balance)}", SecureBalanceStore.SOURCE_SMS)
        }

        runOnUiThread {
            showResult(true, null)
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_success)
            binding.btnRetry.visibility = View.GONE

            binding.btnDone.text = "View Receipt"
            binding.btnDone.setOnClickListener {
                startActivity(Intent(this, TransactionReceiptActivity::class.java).apply {
                    putExtra(TransactionReceiptActivity.EXTRA_TRANSACTION_ID, txnId)
                })
            }
        }
    }

    private fun setupSteps() {
        if (autoFilling) {
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_autofill)
        } else {
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle)
        }

        binding.tvStep1.text = getString(R.string.ussd_step1_pending)
        setStepPending(binding.tvStep1Status, "1")

        binding.tvStep2.text = getString(R.string.ussd_step2_pending)
        setStepPending(binding.tvStep2Status, "2")

        if (autoFilling) {
            binding.tvStep3.text = getString(R.string.ussd_step3_pending, payeeAddress)
        } else {
            binding.tvStep3.text = getString(R.string.ussd_step3_manual, payeeAddress)
        }
        setStepPending(binding.tvStep3Status, "3")

        val displayAmount = amount
        binding.tvStep4.text = if (!displayAmount.isNullOrBlank()) {
            getString(R.string.ussd_step4_pending, formatIndianNumber(displayAmount))
        } else {
            getString(R.string.ussd_step4_no_amount)
        }
        setStepPending(binding.tvStep4Status, "4")

        binding.tvStep5.text = getString(R.string.ussd_step5_pending)
        setStepPending(binding.tvStep5Status, "5")

        binding.tvClipboardBadge.visibility = if (!autoFilling) View.VISIBLE else View.GONE
        binding.tvClipboardBadge.text = getString(R.string.ussd_clipboard_copied_badge)

        binding.tvDisclaimer.text = getString(R.string.ussd_sms_disclaimer)
        binding.tvSecurityNote.text = getString(R.string.ussd_security_note)
    }

    private fun onStepCompleted(step: Int, resultText: String? = null) {
        when (step) {
            UssdAccessibilityService.STEP_WELCOME -> {
                setStepActive(binding.tvStep1Status)
            }
            UssdAccessibilityService.STEP_SEND_MONEY -> {
                setStepDone(binding.tvStep1Status)
                binding.tvStep1.text = getString(R.string.ussd_step1_done)
                setStepActive(binding.tvStep2Status)
            }
            UssdAccessibilityService.STEP_UPI_ID_SELECTED -> {
                setStepDone(binding.tvStep2Status)
                binding.tvStep2.text = getString(R.string.ussd_step2_done)
                setStepActive(binding.tvStep3Status)
            }
            UssdAccessibilityService.STEP_VPA_ENTERED -> {
                setStepDone(binding.tvStep3Status)
                binding.tvStep3.text = getString(R.string.ussd_step3_done, payeeAddress)
                setStepActive(binding.tvStep4Status)
            }
            UssdAccessibilityService.STEP_AMOUNT_ENTERED -> {
                setStepDone(binding.tvStep4Status)
                val doneAmount = amount
                binding.tvStep4.text = getString(R.string.ussd_step4_done, if (!doneAmount.isNullOrBlank()) formatIndianNumber(doneAmount) else "")
                setStepActive(binding.tvStep5Status)
                binding.tvStep5.text = getString(R.string.ussd_step5_ready)
            }
            UssdAccessibilityService.STEP_REMARKS_SKIPPED -> {
                setStepActive(binding.tvStep5Status)
                if (PinStore.hasPin()) {
                    binding.tvStep5.text = getString(R.string.ussd_step5_autofill)
                    binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_pin_autofill)
                } else {
                    binding.tvStep5.text = getString(R.string.ussd_step5_ready)
                    binding.tvUssdSubtitle.text = "Auto-fill complete. Enter your UPI PIN to finish."
                }
            }
            UssdAccessibilityService.STEP_PIN_PROMPT -> {
                if (PinStore.hasPin()) {
                    setStepDone(binding.tvStep5Status)
                    binding.tvStep5.text = getString(R.string.ussd_step5_autofilled)
                    binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_pin_autofill)
                } else {
                    setStepActive(binding.tvStep5Status)
                    binding.tvStep5.text = getString(R.string.ussd_step5_ready)
                    binding.tvUssdSubtitle.text = "Enter your UPI PIN to complete the payment."
                }
            }
            UssdAccessibilityService.STEP_RESULT_SUCCESS -> {
                setStepDone(binding.tvStep5Status)
                binding.tvStep5.text = "UPI PIN entered"
                showResult(true, resultText)
                saveTransaction("success", resultText)
                binding.btnRetry.visibility = View.GONE
            }
            UssdAccessibilityService.STEP_RESULT_FAILURE -> {
                setStepDone(binding.tvStep5Status)
                binding.tvStep5.text = "UPI PIN entered"
                showResult(false, resultText)
                saveTransaction("failure", resultText)
                showRetryButton()
            }
        }
    }

    private fun saveTransaction(status: String, rawUssdText: String?) {
        val txn = Transaction(
            type = "payment",
            amount = amount,
            payeeAddress = payeeAddress,
            payeeName = null,
            accountNumber = capturedAccountNumber ?: accountStore.getAccountNumber(),
            rrn = null,
            balance = null,
            remarks = remarks,
            status = status,
            rawSmsText = null
        )
        transactionStore.saveTransaction(txn)
        currentTransactionId = txn.id

        // Update Done button to View Receipt
        binding.btnDone.text = "View Receipt"
        binding.btnDone.setOnClickListener {
            startActivity(Intent(this, TransactionReceiptActivity::class.java).apply {
                putExtra(TransactionReceiptActivity.EXTRA_TRANSACTION_ID, txn.id)
            })
        }
    }

    private fun retryTransaction() {
        UssdAccessibilityService.clearPending()
        UssdAccessibilityService.setPendingPayment(payeeAddress, amount, remarks)
        autoFilling = true

        setupSteps()
        binding.btnRetry.visibility = View.GONE
        binding.dividerResult.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
        binding.tvResultDetails.visibility = View.GONE
        binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_retrying)
        currentTransactionId = null
        smsAlreadyMatched = false

        dialUssd99()
    }

    private fun dialUssd99() {
        val uri = Uri.parse("tel:*99" + Uri.encode("#"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, uri))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_DIAL, uri))
            }
        } else {
            Toast.makeText(this, getString(R.string.toast_dial_fallback), Toast.LENGTH_LONG).show()
            startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
    }

    private fun showRetryButton() {
        binding.btnRetry.visibility = View.VISIBLE
    }

    private fun showResult(success: Boolean, rawText: String?) {
        binding.dividerResult.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.VISIBLE

        if (success) {
            binding.tvResultStatus.text = "\u2713"
            binding.tvResultStatus.setBackgroundResource(R.drawable.bg_step_done)
            binding.tvResultStatus.setTextColor(getColor(R.color.white))
            binding.tvResult.text = getString(R.string.ussd_result_success)
            binding.tvResult.setTextColor(getColor(R.color.accent_green))
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_success)
        } else {
            binding.tvResultStatus.text = "!"
            binding.tvResultStatus.setBackgroundResource(R.drawable.bg_step_active)
            binding.tvResultStatus.setTextColor(getColor(R.color.white))
            binding.tvResult.text = getString(R.string.ussd_result_failure)
            binding.tvResult.setTextColor(getColor(R.color.accent_amber))
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_failure)
        }

        if (!rawText.isNullOrBlank()) {
            val buttonWords = setOf("ok", "cancel", "send", "reply")
            val cleaned = rawText.lines()
                .filter { it.trim().lowercase() !in buttonWords }
                .joinToString("\n").trim()
            if (cleaned.isNotBlank()) {
                binding.tvResultDetails.text = cleaned
                binding.tvResultDetails.visibility = View.VISIBLE
            }
        }
    }

    private fun setStepPending(indicator: TextView, number: String) {
        indicator.text = number
        indicator.setBackgroundResource(R.drawable.bg_step_pending)
        indicator.setTextColor(getColor(R.color.text_secondary))
    }

    private fun setStepActive(indicator: TextView) {
        indicator.text = "..."
        indicator.setBackgroundResource(R.drawable.bg_step_active)
        indicator.setTextColor(getColor(R.color.white))
    }

    private fun setStepDone(indicator: TextView) {
        indicator.text = "\u2713"
        indicator.setBackgroundResource(R.drawable.bg_step_done)
        indicator.setTextColor(getColor(R.color.white))
    }
}
