package com.offlineupi.app.sms

import com.offlineupi.app.data.AccountBalance

/**
 * Parses SMS responses from missed-call balance services.
 *
 * Example SMS:
 * "Dear Customer, Account balance in XX00892: Rs. 26484.48; XX01256: Rs. 110644.96; XX00835: Rs. -3557088.00 -SIB"
 */
object MissedCallBalanceParser {

    private val ACCOUNT_PATTERN = Regex(
        """([A-Z0-9]{2,}\d{3,})\s*:\s*Rs\.?\s*([-\d,]+\.?\d*)""",
        RegexOption.IGNORE_CASE
    )

    private val BANK_NAME_PATTERN = Regex("""\s+-\s*([A-Z]{2,10})\s*$""")

    fun parse(body: String): List<AccountBalance>? {
        // Only match SMS that look like balance responses
        if (!body.contains("balance", ignoreCase = true)) return null

        val matches = ACCOUNT_PATTERN.findAll(body).toList()
        if (matches.isEmpty()) return null

        val bankName = BANK_NAME_PATTERN.find(body)?.groupValues?.get(1)
        val now = System.currentTimeMillis()

        return matches.map { match ->
            val raw = match.groupValues[1]
            val last4 = raw.filter { it.isDigit() }.takeLast(4)
            AccountBalance(
                accountNumber = "XX$last4",
                balance = match.groupValues[2].replace(",", ""),
                bankName = bankName,
                timestamp = now
            )
        }
    }
}
