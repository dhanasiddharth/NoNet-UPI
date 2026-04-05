package com.offlineupi.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that auto-fills USSD (*99#) payment prompts.
 *
 * Navigates the interactive USSD menu automatically:
 *   Step 0: Welcome dialog → dismiss (click OK)
 *   Step 1: Main menu → select "Send Money" (option 1)
 *   Step 2: Payment method → select "UPI ID" (option 3)
 *   Step 3: Enter beneficiary VPA
 *   Step 4: Enter amount
 *   Step 5: Remarks → skip (option 1)
 *
 * SECURITY: UPI PIN is NEVER auto-filled, the user must enter it manually.
 *
 * Broadcasts step completion via ACTION_STEP_COMPLETED so the instruction
 * screen can show real-time progress.
 */
class UssdAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_STEP_COMPLETED = "com.offlineupi.app.USSD_STEP_COMPLETED"
        const val EXTRA_STEP = "extra_step"
        const val EXTRA_STEP_LABEL = "extra_step_label"

        const val STEP_WELCOME = 0
        const val STEP_SEND_MONEY = 1
        const val STEP_UPI_ID_SELECTED = 2
        const val STEP_VPA_ENTERED = 3
        const val STEP_AMOUNT_ENTERED = 4
        const val STEP_REMARKS_SKIPPED = 5
        const val STEP_PIN_PROMPT = 6
        const val STEP_RESULT_SUCCESS = 7
        const val STEP_RESULT_FAILURE = 8

        const val EXTRA_RESULT_TEXT = "extra_result_text"

        @Volatile var pendingVpa: String? = null
            private set
        @Volatile var pendingAmount: String? = null
            private set

        private val handler = Handler(Looper.getMainLooper())
        private var timeoutRunnable: Runnable? = null

        fun setPendingPayment(vpa: String, amount: String?) {
            pendingVpa = vpa
            pendingAmount = amount
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            timeoutRunnable = Runnable { clearPending() }
            handler.postDelayed(timeoutRunnable!!, 5 * 60 * 1000L)
        }

        fun clearPending() {
            pendingVpa = null
            pendingAmount = null
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            timeoutRunnable = null
        }

        fun hasPending(): Boolean = pendingVpa != null
    }

    private var lastRespondedMessage: String? = null
    private var awaitingResult = false

    private val TAG = "UssdAutoFill"

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: "null"
        Log.d(TAG, "Event: type=${event.eventType} pkg=$pkg hasPending=${hasPending()}")

        if (!hasPending() && !awaitingResult) return

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

        Log.d(TAG, "Dialog text: ${messageText.take(200)}")

        // Don't respond to the same dialog twice
        if (messageText == lastRespondedMessage) {
            Log.d(TAG, "Same message as last, skipping")
            return
        }

        val lower = messageText.lowercase()

        // NEVER auto-fill PIN — but start awaiting the result
        if (isPinEntryPrompt(lower)) {
            broadcastStep(STEP_PIN_PROMPT, "Enter UPI PIN")
            clearPending()
            awaitingResult = true
            lastRespondedMessage = null
            return
        }

        // If there's no EditText, this is an info dialog.
        val editText = findNodeByClass(root, "android.widget.EditText")
        if (editText == null) {
            Log.d(TAG, "No EditText found, looking for OK button")

            // If awaiting result after PIN, this is the transaction result dialog
            if (awaitingResult) {
                val resultStep = parseTransactionResult(lower)
                val resultText = messageText.trim()
                Log.d(TAG, "Transaction result: step=$resultStep text=${resultText.take(100)}")
                broadcastStep(resultStep, resultText)
                awaitingResult = false
                // Dismiss the result dialog
                val okButton = findButtonByText(root, "ok")
                if (okButton != null) {
                    okButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                lastRespondedMessage = messageText
                return
            }

            val okButton = findButtonByText(root, "ok")
            if (okButton != null) {
                Log.d(TAG, "Clicking OK button")
                okButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastRespondedMessage = messageText
                broadcastStep(STEP_WELCOME, "Welcome dialog dismissed")
            } else {
                Log.d(TAG, "No OK button found either")
            }
            return
        }

        val (step, response) = determineStepAndResponse(lower) ?: run {
            Log.d(TAG, "No step matched for message")
            return
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

        // After remarks (step 5), we're done — PIN entry is next
        if (step >= STEP_REMARKS_SKIPPED) {
            clearPending()
            lastRespondedMessage = null
        }
    }

    private fun broadcastStep(step: Int, label: String) {
        sendBroadcast(Intent(ACTION_STEP_COMPLETED).apply {
            setPackage(packageName)
            putExtra(EXTRA_STEP, step)
            putExtra(EXTRA_STEP_LABEL, label)
            if (step == STEP_RESULT_SUCCESS || step == STEP_RESULT_FAILURE) {
                putExtra(EXTRA_RESULT_TEXT, label)
            }
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
        // Default to success if we can't determine — the user will see the raw text
        return STEP_RESULT_SUCCESS
    }

    private fun stepLabel(step: Int, response: String): String = when (step) {
        STEP_SEND_MONEY -> "Send Money selected"
        STEP_UPI_ID_SELECTED -> "UPI ID option selected"
        STEP_VPA_ENTERED -> "VPA entered: $response"
        STEP_AMOUNT_ENTERED -> "Amount entered: $response"
        STEP_REMARKS_SKIPPED -> "Remarks skipped"
        else -> ""
    }

    private fun isPinEntryPrompt(message: String): Boolean {
        if (message.contains("1.") && message.contains("2.")) return false
        return message.contains("enter") && (message.contains("pin") || message.contains("mpin")) ||
                message.contains("mpin") && !message.contains("1.")
    }

    private fun determineStepAndResponse(message: String): Pair<Int, String>? {
        val vpa = pendingVpa ?: return null
        val amount = pendingAmount
        val isMenu = message.contains("1.") && (message.contains("3.") || message.contains("2."))

        return when {
            // Step 5: Remarks
            message.contains("remark") ->
                STEP_REMARKS_SKIPPED to "1"

            // Step 2: "Send Money to:" menu → select UPI ID (always option 3)
            isMenu && message.contains("send money to") ->
                STEP_UPI_ID_SELECTED to "3"

            // Step 1: Main menu → select Send Money (option 1)
            isMenu && message.contains("send money") ->
                STEP_SEND_MONEY to "1"

            // Step 4: Enter amount (non-menu prompt)
            !isMenu && message.contains("amount") ->
                STEP_AMOUNT_ENTERED to (amount ?: return null)

            // Step 3: Enter VPA (non-menu prompt)
            !isMenu && (message.contains("vpa") || message.contains("upi") ||
                message.contains("virtual payment") || message.contains("beneficiary") ||
                message.contains("payee")) ->
                STEP_VPA_ENTERED to vpa

            else -> null
        }
    }

    private fun collectAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        gatherText(node, sb)
        return sb.toString()
    }

    private fun gatherText(node: AccessibilityNodeInfo, sb: StringBuilder) {
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
        lastRespondedMessage = null
    }

    override fun onDestroy() {
        super.onDestroy()
        clearPending()
        awaitingResult = false
        lastRespondedMessage = null
    }
}
