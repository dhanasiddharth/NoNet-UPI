package com.offlineupi.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that auto-fills USSD (*99#) payment prompts.
 *
 * Navigates the interactive USSD menu automatically:
 *   Step 1: Main menu → select "Send Money" (option 1)
 *   Step 2: Payment method → select "UPI ID" (option number varies)
 *   Step 3: Enter beneficiary VPA
 *   Step 4: Enter amount
 *   Step 5: Remarks → skip (option 1)
 *
 * SECURITY: UPI PIN is NEVER auto-filled, the user must enter it manually.
 */
class UssdAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var pendingVpa: String? = null
            private set
        @Volatile var pendingAmount: String? = null
            private set

        private val handler = Handler(Looper.getMainLooper())
        private var timeoutRunnable: Runnable? = null

        fun setPendingPayment(vpa: String, amount: String?) {
            pendingVpa = vpa
            pendingAmount = amount
            // Auto-clear after 5 minutes to avoid stale data
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

    /** Tracks the last dialog message we responded to, to avoid double-sending. */
    private var lastRespondedMessage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!hasPending()) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (!isUssdPackage(pkg)) return

        val root = rootInActiveWindow ?: return
        try {
            handleUssdDialog(root)
        } finally {
            root.recycle()
        }
    }

    private fun isUssdPackage(pkg: String): Boolean {
        return pkg == "com.android.phone" ||
               pkg.contains("dialer") ||
               pkg.contains("phone")
    }

    private fun handleUssdDialog(root: AccessibilityNodeInfo) {
        // Find the USSD dialog specifically — look for the AlertDialog or dialog container
        // that contains an EditText or "OK" button, to avoid reading text from the app behind it.
        val dialogNode = findDialogContainer(root) ?: root
        val messageText = collectAllText(dialogNode)
        if (messageText.isBlank()) return

        // Don't respond to the same dialog twice
        if (messageText == lastRespondedMessage) return

        val lower = messageText.lowercase()

        // NEVER auto-fill PIN — but only when it's a PIN entry prompt,
        // not when "PIN" appears as a menu option (e.g. "7. UPI PIN")
        if (isPinEntryPrompt(lower)) {
            clearPending()
            lastRespondedMessage = null
            return
        }

        // If there's no EditText, this is an info dialog (e.g. "Welcome to *99#").
        // Auto-dismiss by clicking OK.
        val editText = findNodeByClass(dialogNode, "android.widget.EditText")
        if (editText == null) {
            val okButton = findButtonByText(dialogNode, "ok")
            if (okButton != null) {
                okButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                lastRespondedMessage = messageText
            }
            return
        }

        val (step, response) = determineStepAndResponse(lower) ?: return

        // Set text in the EditText
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                response
            )
        }
        editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // Click Send/Reply button
        val sendBtn = findSendButton(dialogNode) ?: return
        sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        lastRespondedMessage = messageText

        // After remarks (step 5), we're done — PIN entry is next (manual)
        if (step >= 5) {
            clearPending()
            lastRespondedMessage = null
        }
    }

    /**
     * Returns true only when the dialog is asking the user to ENTER their PIN,
     * not when "PIN" just appears as a menu item (e.g. "7. UPI PIN").
     */
    private fun isPinEntryPrompt(message: String): Boolean {
        // If it looks like a numbered menu, "PIN" is just an option label
        if (message.contains("1.") && message.contains("2.")) return false
        // Actual PIN entry prompts
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
                5 to "1"

            // Step 2: "Send Money to:" menu → select UPI ID (always option 3)
            isMenu && message.contains("send money to") ->
                2 to "3"

            // Step 1: Main menu → select Send Money (option 1)
            isMenu && message.contains("send money") ->
                1 to "1"

            // Step 4: Enter amount (non-menu prompt)
            !isMenu && message.contains("amount") ->
                4 to (amount ?: return null)

            // Step 3: Enter VPA (non-menu prompt asking for input)
            !isMenu && (message.contains("vpa") || message.contains("upi") ||
                message.contains("virtual payment") || message.contains("beneficiary") ||
                message.contains("payee")) ->
                3 to vpa

            else -> null
        }
    }

    /**
     * Finds the USSD dialog container in the accessibility tree.
     * Looks for AlertDialog, Dialog, or any node with "dialog" in its class/role
     * that contains a Button (SEND/OK/CANCEL).
     */
    private fun findDialogContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = root.className?.toString()?.lowercase() ?: ""
        if (cls.contains("dialog") || cls.contains("alertdialog")) {
            return root
        }
        // Also check for a FrameLayout/LinearLayout that directly contains both
        // a TextView (message) and a Button (send/cancel) — typical USSD dialog structure
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findDialogContainer(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    /** Collects all text content from the accessibility node tree. */
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

    /** Finds a node by its widget class name (DFS). */
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

    /** Finds the Send/Reply/OK button in the USSD dialog. */
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
            if (result != null) {
                // don't recycle — caller needs the result
                return result
            }
            child.recycle()
        }
        return null
    }

    override fun onInterrupt() {
        clearPending()
        lastRespondedMessage = null
    }

    override fun onDestroy() {
        super.onDestroy()
        clearPending()
        lastRespondedMessage = null
    }
}
