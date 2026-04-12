package com.offlineupi.app.sms

/**
 * Parses bank SMS messages for UPI transaction details.
 *
 * Example SMS:
 * "UPI debit:Rs.590.00,A/c X1256, 12-04-26 06:56:00 RRN:065559358417.
 *  Bal:Rs.113846.96 Block A/c?Call18004251809/SMS BLK<A/c>to 9840777222-South Indian Bank"
 */
object SmsParser {

    data class ParsedSms(
        val type: String, // "debit" or "credit"
        val amount: String,
        val accountNumber: String?,
        val dateTime: String?,
        val rrn: String?,
        val balance: String?
    )

    fun parseUpiSms(body: String): ParsedSms? {
        val lower = body.lowercase()

        // Must be a UPI transaction SMS
        if (!lower.contains("upi")) return null
        if (!lower.contains("debit") && !lower.contains("credit")) return null

        val type = if (lower.contains("debit")) "debit" else "credit"

        // Amount: Rs.590.00 or INR 590.00
        val amountPattern = Regex(
            """(?:rs\.?|inr)\s*([0-9,]+(?:\.\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )
        val amountMatch = amountPattern.find(body) ?: return null
        val amount = amountMatch.groupValues[1].replace(",", "")

        // Account number: A/c X1256 or Ac XXXXXX1256
        val acPattern = Regex(
            """(?:a/?c|account)\s*\.?\s*:?\s*([A-Z0-9X]+\d{3,})""",
            RegexOption.IGNORE_CASE
        )
        val accountNumber = acPattern.find(body)?.groupValues?.get(1)

        // Date/Time: 12-04-26 06:56:00
        val dtPattern = Regex("""(\d{2}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})""")
        val dateTime = dtPattern.find(body)?.groupValues?.get(1)

        // RRN: 065559358417
        val rrnPattern = Regex(
            """(?:rrn|ref(?:erence)?)\s*:?\s*(\d{6,})""",
            RegexOption.IGNORE_CASE
        )
        val rrn = rrnPattern.find(body)?.groupValues?.get(1)

        // Balance after transaction: Bal:Rs.113846.96
        val balPattern = Regex(
            """bal(?:ance)?\s*:?\s*(?:rs\.?|inr)\s*([0-9,]+(?:\.\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )
        val balance = balPattern.find(body)?.groupValues?.get(1)?.replace(",", "")

        return ParsedSms(type, amount, accountNumber, dateTime, rrn, balance)
    }
}
