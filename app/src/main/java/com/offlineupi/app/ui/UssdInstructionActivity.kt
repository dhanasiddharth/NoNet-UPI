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
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SmsMessage
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
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
import com.offlineupi.app.util.TxnStatusParser
import com.offlineupi.app.util.UssdCodeBuilder
import com.offlineupi.app.util.formatIndianNumber
import com.offlineupi.app.util.formatMobileForDisplay

class UssdInstructionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAYEE_ADDRESS = "extra_payee_address"
        const val EXTRA_AMOUNT = "extra_amount"
        const val EXTRA_REMARKS = "extra_remarks"
        const val EXTRA_PAYEE_TYPE = "extra_payee_type"
        const val EXTRA_DIAL_CODE = "extra_dial_code"
        const val EXTRA_DIAL_DEPTH = "extra_dial_depth"

        // The txn can take a while to appear in *99# history — retry with backoff.
        private const val MAX_VERIFY_ATTEMPTS = 3
        private const val VERIFY_FIRST_DELAY_MS = 6_000L
        private const val VERIFY_RETRY_DELAY_MS = 15_000L
    }

    private lateinit var binding: ActivityUssdInstructionBinding
    private lateinit var transactionStore: TransactionStore
    private lateinit var accountStore: AccountStore
    private lateinit var balanceStore: SecureBalanceStore
    private var payeeAddress = ""
    private var amount: String? = null
    private var remarks: String? = null
    private var payeeType: String = ConfirmationActivity.TYPE_VPA
    private var dialCode: String = UssdCodeBuilder.ENTRY_CODE
    private var dialDepth: UssdCodeBuilder.Depth = UssdCodeBuilder.Depth.MENU_ONLY
    private var autoFilling = false
    private var capturedAccountNumber: String? = null
    private var capturedPayeeName: String? = null
    private var currentTransactionId: String? = null
    private var smsReceiverRegistered = false
    private var smsAlreadyMatched = false
    private var pinPromptSeen = false
    // Bank SMS that arrived before the transaction record existed; replayed on save.
    private val pendingSmsBodies = mutableListOf<String>()

    // Post-payment verification via *99*6*1# (NUUP 6.1 recent transactions).
    // This check is the ONLY source that may declare success/failure on screen
    // (besides a matched bank SMS); the USSD result dialog is provisional.
    private val verifyHandler = Handler(Looper.getMainLooper())
    private var verified = false
    private var verifyAttempts = 0
    private var pendingVerifyOnResume = false

    private val smsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
                == PackageManager.PERMISSION_GRANTED
            ) registerSmsReceiver()
        }

    private val stepReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val step = intent.getIntExtra(UssdAccessibilityService.EXTRA_STEP, -1)
            val resultText = intent.getStringExtra(UssdAccessibilityService.EXTRA_RESULT_TEXT)
            val accountNumber = intent.getStringExtra(UssdAccessibilityService.EXTRA_ACCOUNT_NUMBER)
            val bankName = intent.getStringExtra(UssdAccessibilityService.EXTRA_BANK_NAME)
            val payeeName = intent.getStringExtra(UssdAccessibilityService.EXTRA_PAYEE_NAME)
            if (accountNumber != null) {
                capturedAccountNumber = accountNumber
                accountStore.saveAccountNumber(accountNumber)
                if (bankName != null) accountStore.saveBankName(bankName)
            }
            if (payeeName != null && capturedPayeeName.isNullOrBlank()) {
                capturedPayeeName = payeeName
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
        payeeType = intent.getStringExtra(EXTRA_PAYEE_TYPE) ?: ConfirmationActivity.TYPE_VPA
        dialCode = intent.getStringExtra(EXTRA_DIAL_CODE) ?: UssdCodeBuilder.ENTRY_CODE
        dialDepth = intent.getStringExtra(EXTRA_DIAL_DEPTH)
            ?.let { runCatching { UssdCodeBuilder.Depth.valueOf(it) }.getOrNull() }
            ?: UssdCodeBuilder.Depth.MENU_ONLY
        autoFilling = UssdAccessibilityService.hasPending()

        // Back (toolbar or gesture) goes straight home — not back through
        // the confirmation/scanner screens of a finished transaction.
        binding.toolbar.setNavigationOnClickListener { goHome() }
        onBackPressedDispatcher.addCallback(this) { goHome() }

        setupSteps()
        requestSmsPermission()

        // Register for the whole activity lifetime: the USSD dialog belongs to
        // the phone app, so this activity is PAUSED during the entire session —
        // an onResume/onPause-scoped receiver misses every step broadcast.
        val filter = IntentFilter(UssdAccessibilityService.ACTION_STEP_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stepReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stepReceiver, filter)
        }

        binding.btnRetry.setOnClickListener { retryTransaction() }

        // Manual status check: one *99*6*1# attempt per tap; the button
        // reappears if the transaction still isn't listed.
        binding.btnCheckStatus.setOnClickListener {
            binding.btnCheckStatus.visibility = View.GONE
            verifyAttempts = MAX_VERIFY_ATTEMPTS - 1
            runStatusVerification()
        }

        binding.btnDone.setOnClickListener { goHome() }
    }

    private fun goHome() {
        UssdAccessibilityService.clearPending()
        finishAffinity()
        startActivity(Intent(this, MainActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        if (pendingVerifyOnResume) {
            pendingVerifyOnResume = false
            runStatusVerification()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        verifyHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(stepReceiver) } catch (_: Exception) {}
        if (smsReceiverRegistered) {
            try { unregisterReceiver(smsReceiver) } catch (_: Exception) {}
            smsReceiverRegistered = false
        }
    }

    private fun requestSmsPermission() {
        val needed = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            .filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        if (needed.isEmpty()) {
            registerSmsReceiver()
        } else {
            smsPermissionLauncher.launch(needed.toTypedArray())
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
        val parsed = SmsParser.parseUpiSms(body) ?: return

        // SMS can beat the USSD result dialog. If the transaction record isn't
        // created yet, buffer the body and replay it once it exists.
        val txnId = currentTransactionId
        if (txnId == null) {
            if (pendingSmsBodies.size < 20) pendingSmsBodies.add(body)
            return
        }

        // A reversal is allowed to override an already-matched success (debit
        // then refund). Anything else is ignored once we have a verdict.
        if (smsAlreadyMatched && !parsed.isReversal) return

        // If this RRN is already assigned to a different transaction, skip.
        if (parsed.rrn != null) {
            val existing = transactionStore.findByRrn(parsed.rrn)
            if (existing != null && existing.id != txnId) return
        }

        // Match by amount (numeric comparison to handle "500" vs "500.00")
        val txnAmount = amount?.replace(",", "")?.trim()?.toDoubleOrNull()
        val smsAmount = parsed.amount.replace(",", "").trim().toDoubleOrNull()
        if (txnAmount != null && smsAmount != null && txnAmount != smsAmount) return

        // Match by account number (last 4 digits) if both are available
        val knownAccount = capturedAccountNumber ?: accountStore.getAccountNumber()
        if (knownAccount != null && parsed.accountNumber != null) {
            if (knownAccount.takeLast(4) != parsed.accountNumber.takeLast(4)) return
        }

        val success = !parsed.isReversal && parsed.type == "debit"
        smsAlreadyMatched = true
        verified = true
        verifyHandler.removeCallbacksAndMessages(null)

        transactionStore.updateTransaction(txnId) { txn ->
            txn.copy(
                status = if (parsed.isReversal) "reversed" else if (success) "success" else "failure",
                // Preserve the original debit RRN; store the refund ref separately.
                rrn = if (parsed.isReversal) (txn.rrn ?: parsed.rrn) else (parsed.rrn ?: txn.rrn),
                reversalRrn = if (parsed.isReversal) parsed.rrn else txn.reversalRrn,
                balance = parsed.balance ?: txn.balance,
                accountNumber = parsed.accountNumber ?: txn.accountNumber,
                payeeName = capturedPayeeName ?: txn.payeeName,
                rawSmsText = body
            )
        }

        if (parsed.balance != null) {
            balanceStore.saveBalance("\u20B9 ${formatIndianNumber(parsed.balance)}", SecureBalanceStore.SOURCE_SMS)
        }

        runOnUiThread {
            binding.btnCheckStatus.visibility = View.GONE
            if (success) {
                showResult(true, null)
                binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_success)
                binding.btnRetry.visibility = View.GONE
            } else {
                showResult(false, getString(R.string.sms_reversal_detail))
                binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_verified_failure)
                showRetryButton()
            }
            binding.btnDone.text = "View Receipt"
            binding.btnDone.setOnClickListener {
                startActivity(Intent(this, TransactionReceiptActivity::class.java).apply {
                    putExtra(TransactionReceiptActivity.EXTRA_TRANSACTION_ID, txnId)
                })
            }
        }
    }

    private fun setupSteps() {
        val isPhone = payeeType == ConfirmationActivity.TYPE_PHONE
        val payeeDisplay = if (isPhone) "+91 ${formatMobileForDisplay(payeeAddress)}" else payeeAddress

        if (autoFilling) {
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_autofill)
        } else {
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle)
        }

        binding.tvStep1.text = getString(R.string.ussd_step1_pending)
        setStepPending(binding.tvStep1Status, "1")

        binding.tvStep2.text = getString(
            if (isPhone) R.string.ussd_step2_pending_phone else R.string.ussd_step2_pending
        )
        setStepPending(binding.tvStep2Status, "2")

        val step3Res = when {
            autoFilling && isPhone -> R.string.ussd_step3_pending_phone
            autoFilling -> R.string.ussd_step3_pending
            isPhone -> R.string.ussd_step3_manual_phone
            else -> R.string.ussd_step3_manual
        }
        binding.tvStep3.text = getString(step3Res, payeeDisplay)
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

        binding.tvDisclaimer.visibility = View.GONE
        binding.tvSecurityNote.text = getString(R.string.ussd_security_note)

        // Steps pre-answered inside the dial string are already complete.
        when (dialDepth) {
            UssdCodeBuilder.Depth.ONE_SHOT -> {
                markPriorStepsDone(4)
                setStepActive(binding.tvStep5Status)
                binding.tvStep5.text = getString(
                    if (PinStore.hasPin()) R.string.ussd_step5_autofill else R.string.ussd_step5_ready
                )
                binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_oneshot)
            }
            UssdCodeBuilder.Depth.PAYEE_PROMPT -> {
                markPriorStepsDone(2)
                setStepActive(binding.tvStep3Status)
                binding.tvUssdSubtitle.text = getString(
                    if (autoFilling) R.string.ussd_subtitle_deeplink_autofill
                    else R.string.ussd_subtitle_deeplink
                )
            }
            UssdCodeBuilder.Depth.MENU_ONLY -> Unit
        }
    }

    /**
     * Marks steps 1..upTo as done — used when the dial string pre-answered
     * them, or when the USSD session jumps ahead of the last step we saw.
     */
    private fun markPriorStepsDone(upTo: Int) {
        val isPhone = payeeType == ConfirmationActivity.TYPE_PHONE
        if (upTo >= 1) {
            setStepDone(binding.tvStep1Status)
            binding.tvStep1.text = getString(R.string.ussd_step1_done)
        }
        if (upTo >= 2) {
            setStepDone(binding.tvStep2Status)
            binding.tvStep2.text = getString(
                if (isPhone) R.string.ussd_step2_done_phone else R.string.ussd_step2_done
            )
        }
        if (upTo >= 3) {
            setStepDone(binding.tvStep3Status)
            val payeeDisplay = if (isPhone) "+91 ${formatMobileForDisplay(payeeAddress)}" else payeeAddress
            binding.tvStep3.text = getString(
                if (isPhone) R.string.ussd_step3_done_phone else R.string.ussd_step3_done,
                payeeDisplay
            )
        }
        if (upTo >= 4) {
            setStepDone(binding.tvStep4Status)
            val doneAmount = amount
            binding.tvStep4.text = getString(
                R.string.ussd_step4_done,
                if (!doneAmount.isNullOrBlank()) formatIndianNumber(doneAmount) else ""
            )
        }
    }

    private fun onStepCompleted(step: Int, resultText: String? = null) {
        when (step) {
            UssdAccessibilityService.STEP_WELCOME -> {
                // Deep-link/one-shot dials skip the welcome screen; if an info
                // dialog still appears, don't regress a pre-completed step.
                if (dialDepth == UssdCodeBuilder.Depth.MENU_ONLY) {
                    setStepActive(binding.tvStep1Status)
                }
            }
            UssdAccessibilityService.STEP_SEND_MONEY -> {
                setStepDone(binding.tvStep1Status)
                binding.tvStep1.text = getString(R.string.ussd_step1_done)
                setStepActive(binding.tvStep2Status)
            }
            UssdAccessibilityService.STEP_UPI_ID_SELECTED,
            UssdAccessibilityService.STEP_MOBILE_SELECTED -> {
                setStepDone(binding.tvStep2Status)
                val isPhone = payeeType == ConfirmationActivity.TYPE_PHONE
                binding.tvStep2.text = getString(
                    if (isPhone) R.string.ussd_step2_done_phone else R.string.ussd_step2_done
                )
                setStepActive(binding.tvStep3Status)
            }
            UssdAccessibilityService.STEP_VPA_ENTERED,
            UssdAccessibilityService.STEP_PHONE_ENTERED -> {
                setStepDone(binding.tvStep3Status)
                val isPhone = payeeType == ConfirmationActivity.TYPE_PHONE
                val payeeDisplay = if (isPhone) "+91 ${formatMobileForDisplay(payeeAddress)}" else payeeAddress
                binding.tvStep3.text = getString(
                    if (isPhone) R.string.ussd_step3_done_phone else R.string.ussd_step3_done,
                    payeeDisplay
                )
                setStepActive(binding.tvStep4Status)
            }
            UssdAccessibilityService.STEP_NAME_CONFIRMED -> {
                markPriorStepsDone(if (dialDepth == UssdCodeBuilder.Depth.ONE_SHOT) 4 else 3)
                capturedPayeeName?.let {
                    binding.tvUssdSubtitle.text = "Confirmed: $it"
                }
            }
            UssdAccessibilityService.STEP_AMOUNT_ENTERED -> {
                setStepDone(binding.tvStep4Status)
                val doneAmount = amount
                binding.tvStep4.text = getString(R.string.ussd_step4_done, if (!doneAmount.isNullOrBlank()) formatIndianNumber(doneAmount) else "")
                setStepActive(binding.tvStep5Status)
                binding.tvStep5.text = getString(R.string.ussd_step5_ready)
            }
            UssdAccessibilityService.STEP_REMARKS_SKIPPED -> {
                markPriorStepsDone(4)
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
                pinPromptSeen = true
                markPriorStepsDone(4)
                // Fallback: if the session ends without a result dialog we can
                // read, verify via *99*6*1# anyway. A result step reschedules
                // this sooner.
                scheduleStatusVerification(30_000)
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
            UssdAccessibilityService.STEP_RESULT_SUCCESS,
            UssdAccessibilityService.STEP_RESULT_FAILURE,
            UssdAccessibilityService.STEP_RESULT_UNKNOWN -> {
                if (step == UssdAccessibilityService.STEP_RESULT_FAILURE && !pinPromptSeen) {
                    // Nothing was submitted to the bank (e.g. invalid payee) —
                    // this failure IS definitive, no verification needed.
                    verified = true
                    showResult(false, resultText)
                    saveTransaction("failure", resultText)
                    showRetryButton()
                } else {
                    // The USSD result dialog is only provisional. Record as
                    // pending and let the *99*6*1# history check (or a bank
                    // SMS) declare the outcome.
                    setStepDone(binding.tvStep5Status)
                    binding.tvStep5.text = "UPI PIN entered"
                    saveTransaction("pending", resultText)
                    if (!smsAlreadyMatched) {
                        showProvisional(resultText)
                        scheduleStatusVerification(VERIFY_FIRST_DELAY_MS)
                    }
                }
            }
            UssdAccessibilityService.STEP_TXN_STATUS_RESULT -> {
                handleStatusResult(resultText)
            }
        }
    }

    /**
     * Dials *99*6*1# after the USSD payment session ends and cross-checks the
     * recent-transactions list — the authoritative status source.
     */
    private fun scheduleStatusVerification(delayMs: Long) {
        if (verified || smsAlreadyMatched) return
        verifyHandler.removeCallbacksAndMessages(null)
        verifyHandler.postDelayed({ runStatusVerification() }, delayMs)
    }

    private fun runStatusVerification() {
        if (verified || smsAlreadyMatched || isFinishing) return
        if (verifyAttempts >= MAX_VERIFY_ATTEMPTS) {
            onVerificationExhausted()
            return
        }
        // Dialing a new USSD code needs the activity in the foreground; if the
        // previous USSD dialog is still up, wait for onResume.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingVerifyOnResume = true
            return
        }
        verifyAttempts++
        UssdAccessibilityService.setPendingTxnStatusCheck()
        binding.tvUssdSubtitle.text = getString(
            R.string.ussd_subtitle_verifying_attempt, verifyAttempts, MAX_VERIFY_ATTEMPTS
        )
        dialUssd(UssdCodeBuilder.RECENT_TXNS_CODE)
    }

    private fun handleStatusResult(text: String?) {
        if (verified || smsAlreadyMatched) return
        val match = text?.let { TxnStatusParser.findMatch(it, amount) }

        // If verification fired before any result dialog was captured,
        // there is no transaction record yet — create one now.
        if (currentTransactionId == null && match?.isSuccess != null) {
            saveTransaction("pending", text)
        }

        when (match?.isSuccess) {
            true -> {
                verified = true
                currentTransactionId?.let { txnId ->
                    val rrn = match.rrn?.takeIf { r ->
                        val existing = transactionStore.findByRrn(r)
                        existing == null || existing.id == txnId
                    }
                    transactionStore.updateTransaction(txnId) { txn ->
                        txn.copy(status = "success", rrn = rrn ?: txn.rrn)
                    }
                }
                showResult(true, match.raw)
                binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_verified_success)
                binding.btnRetry.visibility = View.GONE
                binding.btnCheckStatus.visibility = View.GONE
            }
            false -> {
                verified = true
                currentTransactionId?.let { txnId ->
                    transactionStore.updateTransaction(txnId) { txn ->
                        txn.copy(status = "failure", rrn = match.rrn ?: txn.rrn)
                    }
                }
                showResult(false, match.raw)
                binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_verified_failure)
                binding.btnCheckStatus.visibility = View.GONE
                showRetryButton()
            }
            null -> {
                // The payment isn't in the history yet (NPCI can lag) — sweep
                // SMS, then retry the check with backoff before giving up.
                scanInboxForConfirmation()
                if (smsAlreadyMatched) return
                if (verifyAttempts < MAX_VERIFY_ATTEMPTS) {
                    binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_verify_recheck)
                    scheduleStatusVerification(VERIFY_RETRY_DELAY_MS)
                } else {
                    onVerificationExhausted()
                }
            }
        }
    }

    /** All auto-checks done without an answer — hand control to the user. */
    private fun onVerificationExhausted() {
        binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_verify_pending_manual)
        binding.btnCheckStatus.visibility = View.VISIBLE
    }

    /**
     * Sweeps recent inbox SMS for the bank's debit confirmation and runs it
     * through the same matcher as live SMS. Covers messages that arrived
     * before the transaction record was created.
     */
    private fun scanInboxForConfirmation() {
        if (smsAlreadyMatched || currentTransactionId == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val since = System.currentTimeMillis() - 10 * 60 * 1000L
            contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("body"),
                "date >= ?",
                arrayOf(since.toString()),
                "date DESC"
            )?.use { cursor ->
                val bodyIndex = cursor.getColumnIndex("body")
                while (cursor.moveToNext() && !smsAlreadyMatched) {
                    val body = cursor.getString(bodyIndex) ?: continue
                    handleIncomingSms(body)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun saveTransaction(status: String, rawUssdText: String?) {
        val txn = Transaction(
            type = "payment",
            amount = amount,
            payeeAddress = payeeAddress,
            payeeName = capturedPayeeName,
            accountNumber = capturedAccountNumber ?: accountStore.getAccountNumber(),
            rrn = null,
            balance = null,
            remarks = remarks,
            status = status,
            rawSmsText = null
        )
        transactionStore.saveTransaction(txn)
        currentTransactionId = txn.id

        // Replay any bank SMS that arrived before this record existed, then
        // sweep the inbox for anything the live receiver missed entirely.
        if (pendingSmsBodies.isNotEmpty()) {
            val buffered = pendingSmsBodies.toList()
            pendingSmsBodies.clear()
            buffered.forEach { handleIncomingSms(it) }
        }
        scanInboxForConfirmation()

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
        val mode = if (payeeType == ConfirmationActivity.TYPE_PHONE)
            UssdAccessibilityService.MODE_PAYMENT_PHONE
        else UssdAccessibilityService.MODE_PAYMENT
        UssdAccessibilityService.setPendingPayment(payeeAddress, amount, remarks, mode)
        autoFilling = true

        // If the shortcut code failed before the PIN prompt, the operator may
        // not support it — retry through the full interactive *99# menu.
        if (!pinPromptSeen && dialDepth != UssdCodeBuilder.Depth.MENU_ONLY) {
            dialCode = UssdCodeBuilder.ENTRY_CODE
            dialDepth = UssdCodeBuilder.Depth.MENU_ONLY
        }

        setupSteps()
        binding.btnRetry.visibility = View.GONE
        binding.dividerResult.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
        binding.tvResultDetails.visibility = View.GONE
        binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_retrying)
        currentTransactionId = null
        smsAlreadyMatched = false
        pinPromptSeen = false
        verified = false
        verifyAttempts = 0
        pendingVerifyOnResume = false
        binding.btnCheckStatus.visibility = View.GONE
        verifyHandler.removeCallbacksAndMessages(null)

        dialUssd99()
    }

    private fun dialUssd99() = dialUssd(dialCode)

    private fun dialUssd(code: String) {
        val uri = Uri.parse("tel:" + Uri.encode(code))
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

        setResultDetails(rawText)
    }

    /**
     * Neutral "payment submitted" state — shown until the *99*6*1# history
     * check (or a bank SMS) delivers the definitive verdict.
     */
    private fun showProvisional(rawText: String?) {
        binding.dividerResult.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.VISIBLE
        binding.tvResultStatus.text = "…"
        binding.tvResultStatus.setBackgroundResource(R.drawable.bg_step_active)
        binding.tvResultStatus.setTextColor(getColor(R.color.white))
        binding.tvResult.text = getString(R.string.ussd_result_verifying)
        binding.tvResult.setTextColor(getColor(R.color.accent_amber))
        binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_result_pending_verify)
        binding.btnRetry.visibility = View.GONE
        setResultDetails(rawText)
    }

    private fun setResultDetails(rawText: String?) {
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
