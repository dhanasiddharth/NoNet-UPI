package com.offlineupi.app.util

/**
 * Builds the deepest safe *99# dial string for a payment.
 *
 * NUUP allows pre-answering menu levels inside the dial string itself:
 *   *99*1*1*<mobile>*<amount>*<remarks>#  — Send Money → Mobile No. (one shot)
 *   *99*1*3*<vpa>*<amount>*<remarks>#     — Send Money → UPI ID    (one shot)
 * which jumps straight to the beneficiary confirmation / UPI PIN prompt,
 * replacing five USSD round trips with one.
 *
 * CONSTRAINT: Android's telephony stack strips non-dialable characters
 * (anything other than 0-9 * # +) from an ACTION_CALL dial string. A VPA
 * ("name@bank") or a decimal amount ("100.50" → "10050") would be silently
 * corrupted — the latter into a 100x overpayment. So a value is embedded in
 * the dial string only when it is strictly digits; anything else stays on
 * the interactive path where the accessibility service (or the user) fills
 * it in after a *99*1*<method># deep link.
 */
object UssdCodeBuilder {

    /** Plain NUUP entry point — full interactive menu. */
    const val ENTRY_CODE = "*99#"

    /** NUUP 6.1 — list of recent transactions (no PIN required). */
    const val RECENT_TXNS_CODE = "*99*6*1#"

    private const val OPTION_SEND_MONEY = "1"
    private const val OPTION_TO_MOBILE = "1"
    private const val OPTION_TO_UPI_ID = "3"

    /** How far the dial string itself gets before interactive prompts take over. */
    enum class Depth {
        /** Plain *99# — every step is interactive. */
        MENU_ONLY,

        /** *99*1*<method># — session opens at the payee (mobile/VPA) prompt. */
        PAYEE_PROMPT,

        /** Payee + amount (+ remarks) embedded — session opens at confirm/PIN. */
        ONE_SHOT,
    }

    data class DialPlan(val code: String, val depth: Depth)

    private fun isDialSafe(value: String) = value.isNotEmpty() && value.all { it.isDigit() }

    fun buildPaymentPlan(
        payeeAddress: String,
        amount: String?,
        remarks: String?,
        isPhone: Boolean
    ): DialPlan {
        val method = if (isPhone) OPTION_TO_MOBILE else OPTION_TO_UPI_ID
        val base = "*99*$OPTION_SEND_MONEY*$method"

        // Full one-shot only when every embedded value survives dialing intact.
        // Non-digit remarks force the interactive path so they aren't dropped.
        val payeeSafe = isPhone && isDialSafe(payeeAddress)
        val amountSafe = amount != null && isDialSafe(amount)
        val remarksSafe = remarks == null || isDialSafe(remarks)
        if (payeeSafe && amountSafe && remarksSafe) {
            // Per the NUUP spec the one-shot format always carries a remarks
            // segment; "1" disables remarks.
            val remarksSegment = remarks ?: "1"
            return DialPlan("$base*$payeeAddress*$amount*$remarksSegment#", Depth.ONE_SHOT)
        }

        return DialPlan("$base#", Depth.PAYEE_PROMPT)
    }
}
