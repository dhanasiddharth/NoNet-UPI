package com.offlineupi.app.util

/**
 * Parses the *99*6*1# recent-transactions list (NUUP 6.1).
 *
 * The exact wording varies by bank/PSP, so parsing is deliberately tolerant:
 * entries are split on numbered lines ("1. …", "2) …"), and each entry is
 * scanned for an amount, a status keyword, and a 12-digit RRN.
 */
object TxnStatusParser {

    data class TxnEntry(
        val raw: String,
        val amount: Double?,
        /** true = success, false = failure, null = pending/unknown */
        val isSuccess: Boolean?,
        val rrn: String?
    )

    private val AMOUNT = Regex("""(?:rs\.?|inr|₹)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val RRN = Regex("""\b(\d{12})\b""")
    private val ENTRY_START = Regex("""^\d{1,2}[.)]""")
    // Checked after FAILURE_WORDS, so "Failed to send" never reads as success.
    private val SUCCESS_WORDS = listOf("success", "sent", "paid", "completed", "credit", "debited")
    private val FAILURE_WORDS = listOf("fail", "declined", "rejected", "expired", "reversed")
    private val PENDING_WORDS = listOf("pending", "deemed", "in progress")

    fun parseEntries(text: String): List<TxnEntry> {
        val body = text.trim()
        if (body.isEmpty()) return emptyList()

        val numbered = body
            .split(Regex("""(?=(?:^|\n)\s*\d{1,2}[.)]\s?)"""))
            .map { it.trim() }
            .filter { ENTRY_START.containsMatchIn(it) }
        val chunks = numbered.ifEmpty {
            body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }

        return chunks.mapNotNull { chunk ->
            val lower = chunk.lowercase()
            val amount = AMOUNT.find(chunk)?.groupValues?.get(1)
                ?.replace(",", "")?.toDoubleOrNull()
            val isSuccess = when {
                FAILURE_WORDS.any { lower.contains(it) } -> false
                PENDING_WORDS.any { lower.contains(it) } -> null
                SUCCESS_WORDS.any { lower.contains(it) } -> true
                else -> null
            }
            val rrn = RRN.find(chunk)?.groupValues?.get(1)
            if (amount == null && isSuccess == null && rrn == null) null
            else TxnEntry(chunk, amount, isSuccess, rrn)
        }
    }

    /**
     * Finds the entry for our payment — the first (most recent) entry whose
     * amount matches. Without an amount to match, falls back to the first entry.
     */
    fun findMatch(text: String, amount: String?): TxnEntry? {
        val entries = parseEntries(text)
        if (entries.isEmpty()) return null
        val target = amount?.replace(",", "")?.trim()?.toDoubleOrNull()
            ?: return entries.first()
        return entries.firstOrNull { it.amount != null && it.amount == target }
    }
}
