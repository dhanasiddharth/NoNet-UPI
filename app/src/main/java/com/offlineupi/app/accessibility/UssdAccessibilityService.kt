package com.offlineupi.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.offlineupi.app.data.PinStore

/**
 * Accessibility service that auto-fills USSD (*99#) prompts.
 *
 * Supports two modes:
 *
 * PAYMENT mode — navigates the interactive USSD menu automatically:
 *   Step 0: Welcome dialog → dismiss (click OK)
 *   Step 1: Main menu → select "Send Money" (option 1)
 *   Step 2: Payment method → select "UPI ID" (option 3)
 *   Step 3: Enter beneficiary VPA
 *   Step 4: Enter amount
 *   Step 5: Remarks → skip (option 1)
 *
 * BALANCE CHECK mode — navigates to check balance:
 *   Step 0: Welcome dialog → dismiss (click OK)
 *   Step 1: Main menu → select "Check Balance" option
 *   Step 2: User enters UPI PIN (manual)
 *   Step 3: Balance displayed → captured and broadcast
 *
 * SECURITY: UPI PIN is NEVER auto-filled, the user must enter it manually.
 *
 * Broadcasts step completion via ACTION_STEP_COMPLETED so the UI
 * screen can show real-time progress.
 */
class UssdAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_STEP_COMPLETED = "com.offlineupi.app.USSD_STEP_COMPLETED"
        const val EXTRA_STEP = "extra_step"
        const val EXTRA_STEP_LABEL = "extra_step_label"

        // Payment flow steps
        const val STEP_WELCOME = 0
        const val STEP_SEND_MONEY = 1
        const val STEP_UPI_ID_SELECTED = 2
        const val STEP_VPA_ENTERED = 3
        const val STEP_AMOUNT_ENTERED = 4
        const val STEP_REMARKS_SKIPPED = 5
        const val STEP_PIN_PROMPT = 6
        const val STEP_RESULT_SUCCESS = 7
        const val STEP_RESULT_FAILURE = 8

        // Phone payment flow extra steps
        const val STEP_MOBILE_SELECTED = 10
        const val STEP_PHONE_ENTERED = 11
        const val STEP_NAME_CONFIRMED = 12

        // Balance check flow steps
        const val STEP_BALANCE_WELCOME = 100
        const val STEP_BALANCE_SELECTED = 101
        const val STEP_BALANCE_PIN_PROMPT = 102
        const val STEP_BALANCE_RESULT = 103
        const val STEP_BALANCE_FAILURE = 104

        const val EXTRA_RESULT_TEXT = "extra_result_text"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_ACCOUNT_NUMBER = "extra_account_number"
        const val EXTRA_BANK_NAME = "extra_bank_name"
        const val EXTRA_PAYEE_NAME = "extra_payee_name"

        const val MODE_PAYMENT = "payment"
        const val MODE_PAYMENT_PHONE = "payment_phone"
        const val MODE_BALANCE = "balance"

        @Volatile var pendingVpa: String? = null
            private set
        @Volatile var pendingAmount: String? = null
            private set
        @Volatile var pendingRemarks: String? = null
            private set
        @Volatile var pendingMode: String = MODE_PAYMENT
            private set
        @Volatile var capturedPayeeName: String? = null
            private set

        private val handler = Handler(Looper.getMainLooper())
        private var timeoutRunnable: Runnable? = null

        fun setPendingPayment(
            vpa: String,
            amount: String?,
            remarks: String? = null,
            mode: String = MODE_PAYMENT
        ) {
            pendingVpa = vpa
            pendingAmount = amount
            pendingRemarks = remarks
            pendingMode = mode
            capturedPayeeName = null
            resetTimeout()
        }

        fun setPendingBalanceCheck() {
            pendingVpa = null
            pendingAmount = null
            pendingMode = MODE_BALANCE
            resetTimeout()
        }

        private fun resetTimeout() {
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            timeoutRunnable = Runnable { clearPending() }
            handler.postDelayed(timeoutRunnable!!, 5 * 60 * 1000L)
        }

        fun clearPending() {
            pendingVpa = null
            pendingAmount = null
            pendingRemarks = null
            pendingMode = MODE_PAYMENT
            capturedPayeeName = null
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            timeoutRunnable = null
        }

        fun hasPending(): Boolean = pendingVpa != null || pendingMode == MODE_BALANCE
    }

    private var lastRespondedMessage: String? = null
    private var awaitingResult = false
    private var awaitingBalanceResult = false

    private val TAG = "UssdAutoFill"

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: "null"
        Log.d(TAG, "Event: type=${event.eventType} pkg=$pkg hasPending=${hasPending()}")

        if (!hasPending() && !awaitingResult && !awaitingBalanceResult) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        if (!isUssdPackage(pkg)) {
            Log.d(TAG, "Skipping non-USSD package: $pkg")
            return
        }

        // Find the USSD dialog window from com.android.phone (not our app's window)
        val root = findUssdWindowRoot()
        if (root == null) {
            Log.d(TAG, "No USSD window found, skipping")
            return
        }
        val rootPkg = root.packageName?.toString() ?: "unknown"
        Log.d(TAG, "Using root from package: $rootPkg")
        try {
            handleUssdDialog(root)
        } finally {
            root.recycle()
        }
    }

    /**
     * Iterates over all windows to find the one belonging to the phone/dialer app.
     * This ensures we read text from the USSD dialog, not from our own app behind it.
     */
    private fun findUssdWindowRoot(): AccessibilityNodeInfo? {
        try {
            Log.d(TAG, "Searching ${windows.size} windows")
            for (window in windows) {
                val root = window.root ?: continue
                val pkg = root.packageName?.toString()
                Log.d(TAG, "Window: pkg=$pkg type=${window.type}")
                if (pkg != null && isUssdPackage(pkg)) {
                    return root
                }
                root.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error iterating windows", e)
        }
        return null
    }

    private fun isUssdPackage(pkg: String): Boolean {
        return pkg == "com.android.phone" ||
               pkg == "com.google.android.dialer" ||
               pkg == "com.samsung.android.dialer"
    }

    private fun handleUssdDialog(root: AccessibilityNodeInfo) {
        val messageText = collectAllText(root)
        if (messageText.isBlank()) {
            Log.d(TAG, "Empty message text")
            return
        }

        Log.d(TAG, "Dialog text (mode=$pendingMode): ${messageText.take(200)}")

        // Don't respond to the same dialog twice
        if (messageText == lastRespondedMessage) {
            Log.d(TAG, "Same message as last, skipping")
            return
        }

        val lower = messageText.lowercase()

        // Pre-PIN error dialog (e.g. "UPI ID is invalid") — surface failure and cancel session
        if (hasPending() && isPrePinErrorPrompt(lower)) {
            Log.d(TAG, "Pre-PIN error detected — cancelling USSD and reporting failure")
            val resultText = messageText.trim()
            broadcastStep(STEP_RESULT_FAILURE, resultText)
            clearPending()
            lastRespondedMessage = messageText
            val cancelBtn = findButtonByText(root, "cancel") ?: findButtonByText(root, "ok")
            cancelBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return
        }

        // PIN entry prompt detected
        if (isPinEntryPrompt(lower)) {
            val isBalance = awaitingBalanceResult || pendingMode == MODE_BALANCE
            val pinStep = if (isBalance) STEP_BALANCE_PIN_PROMPT else STEP_PIN_PROMPT

            // Parse account number and bank name from PIN prompt
            val accountNumber = parseAccountNumber(messageText)
            val bankName = parseBankName(messageText)
            Log.d(TAG, "PIN prompt — account: $accountNumber, bank: $bankName")

            // If we have a stored PIN, auto-fill it
            val storedPin = PinStore.pin
            if (!storedPin.isNullOrEmpty()) {
                val editText = findNodeByClass(root, "android.widget.EditText")
                if (editText != null) {
                    Log.d(TAG, "Auto-filling PIN from PinStore")
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            storedPin
                        )
                    }
                    editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    val sendBtn = findSendButton(root)
                    sendBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    broadcastStep(pinStep, "UPI PIN auto-filled", accountNumber, bankName)
                    if (isBalance) awaitingBalanceResult = true
                    else awaitingResult = true
                    clearPending()
                    lastRespondedMessage = messageText
                    return
                }
            }

            // No stored PIN — broadcast prompt and wait for manual entry
            broadcastStep(pinStep, "Enter UPI PIN", accountNumber, bankName)
            if (isBalance) awaitingBalanceResult = true
            else awaitingResult = true
            clearPending()
            lastRespondedMessage = null
            return
        }

        // If awaiting balance result after PIN, capture the balance.
        // The result dialog may or may not have an EditText, so check this first.
        if (awaitingBalanceResult) {
            val resultText = messageText.trim()
            Log.d(TAG, "Balance result: ${resultText.take(100)}")
            val balanceStep = if (isBalanceError(lower)) STEP_BALANCE_FAILURE else STEP_BALANCE_RESULT
            broadcastStep(balanceStep, resultText)
            awaitingBalanceResult = false
            // Dismiss with Cancel (preferred) or OK
            val dismissBtn = findButtonByText(root, "cancel") ?: findButtonByText(root, "ok")
            dismissBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            lastRespondedMessage = messageText
            return
        }

        // If awaiting payment result after PIN, capture the transaction result.
        if (awaitingResult) {
            val resultStep = parseTransactionResult(lower)
            val resultText = messageText.trim()
            Log.d(TAG, "Transaction result: step=$resultStep text=${resultText.take(100)}")
            broadcastStep(resultStep, resultText)
            awaitingResult = false
            val dismissBtn = findButtonByText(root, "cancel") ?: findButtonByText(root, "ok")
            dismissBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            lastRespondedMessage = messageText
            return
        }

        // If there's no EditText, this is an info dialog (e.g. welcome screen).
        val editText = findNodeByClass(root, "android.widget.EditText")
        if (editText == null) {
            Log.d(TAG, "No EditText found, looking for OK button")
            val okButton = findButtonByText(root, "ok")
            if (okButton != null) {
                Log.d(TAG, "Clicking OK button")
                okButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastRespondedMessage = messageText
                val welcomeStep = if (pendingMode == MODE_BALANCE) STEP_BALANCE_WELCOME else STEP_WELCOME
                broadcastStep(welcomeStep, "Welcome dialog dismissed")
            } else {
                Log.d(TAG, "No OK button found either")
            }
            return
        }

        // Route to appropriate mode handler
        val (step, response) = if (pendingMode == MODE_BALANCE) {
            determineBalanceStepAndResponse(lower) ?: run {
                Log.d(TAG, "No balance step matched for message")
                return
            }
        } else {
            determinePaymentStepAndResponse(lower) ?: run {
                Log.d(TAG, "No payment step matched for message")
                return
            }
        }
        Log.d(TAG, "Step $step -> response: $response")

        // Set text in the EditText
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                response
            )
        }
        editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // Click Send button
        val sendBtn = findSendButton(root)
        if (sendBtn == null) {
            Log.d(TAG, "No SEND button found")
            return
        }
        Log.d(TAG, "Clicking SEND button")
        sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        lastRespondedMessage = messageText
        broadcastStep(step, stepLabel(step, response))

        // After balance selected, PIN entry is next — keep mode but stop auto-filling
        if (step == STEP_BALANCE_SELECTED) {
            lastRespondedMessage = null
        }

        // After remarks (step 5), PIN entry is next — don't clearPending,
        // the PIN handler will do it after filling/prompting
        if (step >= STEP_REMARKS_SKIPPED && step < 100) {
            lastRespondedMessage = null
        }
    }

    private fun broadcastStep(step: Int, label: String, accountNumber: String? = null, bankName: String? = null) {
        sendBroadcast(Intent(ACTION_STEP_COMPLETED).apply {
            setPackage(packageName)
            putExtra(EXTRA_STEP, step)
            putExtra(EXTRA_STEP_LABEL, label)
            putExtra(EXTRA_MODE, if (step >= 100) MODE_BALANCE else pendingMode)
            if (step == STEP_RESULT_SUCCESS || step == STEP_RESULT_FAILURE ||
                step == STEP_BALANCE_RESULT || step == STEP_BALANCE_FAILURE) {
                putExtra(EXTRA_RESULT_TEXT, label)
            }
            accountNumber?.let { putExtra(EXTRA_ACCOUNT_NUMBER, it) }
            bankName?.let { putExtra(EXTRA_BANK_NAME, it) }
            capturedPayeeName?.let { putExtra(EXTRA_PAYEE_NAME, it) }
        })
    }

    /**
     * Parses the transaction result dialog text to determine success or failure.
     */
    private fun parseTransactionResult(message: String): Int {
        val successKeywords = listOf(
            "successful", "success", "completed", "accepted",
            "txn id", "transaction id", "ref no", "reference"
        )
        val failureKeywords = listOf(
            "failed", "failure", "declined", "rejected", "unable",
            "insufficient", "expired", "timeout", "timed out", "error"
        )

        for (keyword in successKeywords) {
            if (message.contains(keyword)) return STEP_RESULT_SUCCESS
        }
        for (keyword in failureKeywords) {
            if (message.contains(keyword)) return STEP_RESULT_FAILURE
        }
        return STEP_RESULT_SUCCESS
    }

    private fun isBalanceError(message: String): Boolean {
        val errorKeywords = listOf(
            "failed", "failure", "error", "unable", "declined",
            "timeout", "timed out", "try again", "not available"
        )
        return errorKeywords.any { message.contains(it) }
    }

    private fun stepLabel(step: Int, response: String): String = when (step) {
        STEP_SEND_MONEY -> "Send Money selected"
        STEP_UPI_ID_SELECTED -> "UPI ID option selected"
        STEP_VPA_ENTERED -> "VPA entered: $response"
        STEP_AMOUNT_ENTERED -> "Amount entered: $response"
        STEP_REMARKS_SKIPPED -> "Remarks skipped"
        STEP_MOBILE_SELECTED -> "Mobile No. option selected"
        STEP_PHONE_ENTERED -> "Phone entered: $response"
        STEP_NAME_CONFIRMED -> capturedPayeeName?.let { "Confirmed: $it" } ?: "Confirmed"
        STEP_BALANCE_SELECTED -> "Check Balance selected"
        else -> ""
    }

    private fun isPinEntryPrompt(message: String): Boolean {
        if (message.contains("1.") && message.contains("2.")) return false
        return message.contains("enter") && (message.contains("pin") || message.contains("mpin")) ||
                message.contains("mpin") && !message.contains("1.")
    }

    /**
     * Detects USSD error dialogs that occur before PIN entry.
     * e.g. "the entered UPI ID is invalid", "Invalid mobile number", "not linked to UPI".
     */
    private fun isPrePinErrorPrompt(message: String): Boolean {
        if (message.contains("pin") || message.contains("mpin")) return false
        val errorKeywords = listOf(
            "invalid", "incorrect", "not found", "not linked",
            "not registered", "try again later", "failed"
        )
        return errorKeywords.any { message.contains(it) }
    }

    /**
     * Determines the balance check step from USSD menu text.
     * Finds the option number for "Check Balance" / "Balance Enquiry" in the main menu.
     */
    private fun determineBalanceStepAndResponse(message: String): Pair<Int, String>? {
        val isMenu = message.contains("1.") && (message.contains("3.") || message.contains("2."))
        if (!isMenu) return null

        // Find the option number for balance check
        val balanceOption = findMenuOptionNumber(message, listOf(
            "check balance", "bal enquiry", "balance enquiry",
            "balance inquiry", "know balance", "bal enq"
        ))

        if (balanceOption != null) {
            return STEP_BALANCE_SELECTED to balanceOption
        }

        return null
    }

    /**
     * Scans numbered menu text (e.g. "1. Send Money\n2) Request\n3 Check Balance")
     * and returns the number corresponding to the first keyword match.
     * Supports separators: `.`, `)`, `-`, ` `.
     */
    private fun findMenuOptionNumber(message: String, keywords: List<String>): String? {
        for (keyword in keywords) {
            val pattern = Regex(
                """(\d+)\s*[.)\-]?\s*${Regex.escape(keyword)}""",
                RegexOption.IGNORE_CASE
            )
            val match = pattern.find(message)
            if (match != null) return match.groupValues[1]
        }
        // Fallback: look for lines containing keyword and extract leading number
        val lines = message.lines()
        for (keyword in keywords) {
            for (line in lines) {
                if (line.lowercase().contains(keyword)) {
                    val num = Regex("""^(\d+)""").find(line.trim())
                    if (num != null) return num.groupValues[1]
                }
            }
        }
        return null
    }

    /**
     * Determines the payment step from USSD menu/prompt text.
     *
     * Per NUUP spec (*99*1*…):
     *   Main menu:           1 Send Money, 2 Request Money, 3 Check Balance, 4 Profile, ...
     *   Send Money sub-menu: 1 Mobile Number, 3 UPI ID, 4 Saved Beneficiary, 5 Bank A/c
     */
    private fun determinePaymentStepAndResponse(message: String): Pair<Int, String>? {
        val payee = pendingVpa ?: return null
        val amount = pendingAmount
        val isMenu = Regex("""\d+[.)]""").findAll(message).count() >= 2
        val isPhoneMode = pendingMode == MODE_PAYMENT_PHONE

        // Yes/No confirmation dialog — capture beneficiary name and confirm
        val isYesNo = Regex("""1[.)]?\s*yes""", RegexOption.IGNORE_CASE).containsMatchIn(message) &&
                Regex("""2[.)]?\s*no""", RegexOption.IGNORE_CASE).containsMatchIn(message)
        if (isYesNo) {
            capturedPayeeName = parsePayeeName(message)
            return STEP_NAME_CONFIRMED to "1"
        }

        // Remarks prompt (may be non-menu or short menu)
        if (message.contains("remark")) {
            val remarks = pendingRemarks
            return STEP_REMARKS_SKIPPED to (if (!remarks.isNullOrBlank()) remarks else "1")
        }

        if (isMenu) {
            // Sub-menu signatures: contains payment method options (mobile/upi/ifsc/saved)
            val hasMobileOpt = findMenuOptionNumber(message, listOf(
                "mobile no", "mobile number", "mobile", "phone no", "phone number"
            ))
            val hasUpiOpt = findMenuOptionNumber(message, listOf("upi id", "upi", "vpa"))
            val hasIfscOpt = findMenuOptionNumber(message, listOf("ifsc", "bank a/c", "account"))
            val hasSavedOpt = findMenuOptionNumber(message, listOf("saved beneficiary", "beneficiary", "saved"))
            val isPaymentMethodSubMenu = listOf(hasMobileOpt, hasUpiOpt, hasIfscOpt, hasSavedOpt).count { it != null } >= 2

            if (isPaymentMethodSubMenu) {
                val opt = if (isPhoneMode) (hasMobileOpt ?: "1") else (hasUpiOpt ?: "3")
                Log.d(TAG, "Payment-method sub-menu: mode=$pendingMode → option $opt")
                return (if (isPhoneMode) STEP_MOBILE_SELECTED else STEP_UPI_ID_SELECTED) to opt
            }

            // Main menu: contains Send Money option
            val sendMoneyOpt = findMenuOptionNumber(message, listOf("send money", "send"))
            if (sendMoneyOpt != null && message.lowercase().contains("send money")) {
                Log.d(TAG, "Main menu → Send Money option $sendMoneyOpt")
                return STEP_SEND_MONEY to sendMoneyOpt
            }
        }

        // Non-menu prompts: identify by keywords in order of specificity
        return when {
            // Amount prompt
            message.contains("amount") ->
                STEP_AMOUNT_ENTERED to (amount ?: return null)

            // Phone number prompt (phone mode only)
            isPhoneMode && (message.contains("mobile") || message.contains("phone")) ->
                STEP_PHONE_ENTERED to payee

            // VPA prompt (VPA mode only)
            !isPhoneMode && (message.contains("vpa") || message.contains("upi") ||
                message.contains("virtual payment") || message.contains("beneficiary") ||
                message.contains("payee")) ->
                STEP_VPA_ENTERED to payee

            else -> null
        }
    }

    /**
     * Extracts the beneficiary name from a confirmation prompt.
     * e.g. "Send Rs. 100 to RAM KUMAR? 1. Yes 2. No" → "RAM KUMAR"
     */
    private fun parsePayeeName(message: String): String? {
        val cleaned = message.replace("\n", " ").trim()
        val patterns = listOf(
            Regex("""to\s+([A-Za-z][A-Za-z\s.]+?)(?=\s+of\b|\s+for\b|\s*[?.,]|\s*\d|\s*1\.)""", RegexOption.IGNORE_CASE),
            Regex("""name\s*:?\s*([A-Za-z][A-Za-z\s.]+?)(?=\s*[?.,]|\s*\d|\s*1\.)""", RegexOption.IGNORE_CASE),
            Regex("""beneficiary\s*:?\s*([A-Za-z][A-Za-z\s.]+?)(?=\s*[?.,]|\s*\d|\s*1\.)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(cleaned) ?: continue
            val name = match.groupValues[1].trim().trimEnd('.', ',')
            if (name.length in 2..60) return name
        }
        return null
    }

    /**
     * Extracts the masked account number from the PIN prompt text.
     * e.g. "account No. XXXXXX1256" → "XXXXXX1256"
     */
    private fun parseAccountNumber(message: String): String? {
        val patterns = listOf(
            Regex("""account\s*no\.?\s*:?\s*([A-Z0-9X]+\d{3,})""", RegexOption.IGNORE_CASE),
            Regex("""a/?c\s*\.?\s*:?\s*([A-Z0-9X]+\d{3,})""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(message)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    /**
     * Extracts the bank name from the PIN prompt text.
     * e.g. "for your South Indian Bank account" → "South Indian Bank"
     */
    private fun parseBankName(message: String): String? {
        val pattern = Regex("""(?:your|for)\s+(.+?)\s+account""", RegexOption.IGNORE_CASE)
        val match = pattern.find(message) ?: return null
        return match.groupValues[1].trim()
    }

    private fun collectAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        gatherText(node, sb)
        return sb.toString()
    }

    private fun gatherText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val cls = node.className?.toString()
        // Skip button text — we don't want "SEND", "CANCEL", "OK" in the message
        if (cls == "android.widget.Button") return
        node.text?.let { sb.append(it).append("\n") }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            gatherText(child, sb)
            child.recycle()
        }
    }

    private fun findNodeByClass(root: AccessibilityNodeInfo, className: String): AccessibilityNodeInfo? {
        if (root.className?.toString() == className) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            if (child.className?.toString() == className) return child
            val result = findNodeByClass(child, className)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findButtonByText(root, "send")
            ?: findButtonByText(root, "reply")
            ?: findButtonByText(root, "ok")
    }

    private fun findButtonByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val cls = node.className?.toString()
        if ((cls == "android.widget.Button" || node.isClickable) &&
            node.text?.toString()?.lowercase()?.contains(text) == true
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findButtonByText(child, text)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    override fun onInterrupt() {
        clearPending()
        awaitingResult = false
        awaitingBalanceResult = false
        lastRespondedMessage = null
    }

    override fun onDestroy() {
        super.onDestroy()
        clearPending()
        awaitingResult = false
        awaitingBalanceResult = false
        lastRespondedMessage = null
    }
}
