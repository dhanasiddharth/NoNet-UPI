package com.offlineupi.app.sms

import android.content.Context
import android.net.Uri
import com.offlineupi.app.data.Transaction

/**
 * Searches the device SMS inbox for a bank confirmation matching a transaction.
 * Matches by amount (numeric) and account number (last 4 digits), within a
 * time window around the transaction timestamp.
 */
object SmsInboxReader {

    /**
     * Searches SMS inbox for a UPI transaction SMS matching the given transaction.
     * Looks within +/- 5 minutes of the transaction timestamp.
     */
    fun findMatchingSms(context: Context, txn: Transaction): SmsParser.ParsedSms? {
        val txnAmount = txn.amount?.replace(",", "")?.trim()?.toDoubleOrNull() ?: return null
        val accountSuffix = txn.accountNumber?.takeLast(4)

        // Search window: 5 minutes before to 30 minutes after the transaction
        val startTime = txn.timestamp - 5 * 60 * 1000L
        val endTime = txn.timestamp + 30 * 60 * 1000L

        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("body", "date")
        val selection = "date >= ? AND date <= ?"
        val selectionArgs = arrayOf(startTime.toString(), endTime.toString())
        val sortOrder = "date DESC"

        val cursor = context.contentResolver.query(
            uri, projection, selection, selectionArgs, sortOrder
        ) ?: return null

        cursor.use {
            val bodyIndex = it.getColumnIndex("body")
            while (it.moveToNext()) {
                val body = it.getString(bodyIndex) ?: continue
                val parsed = SmsParser.parseUpiSms(body) ?: continue

                // Match amount (numeric)
                val smsAmount = parsed.amount.replace(",", "").trim().toDoubleOrNull() ?: continue
                if (smsAmount != txnAmount) continue

                // Match account (last 4 digits) if available
                if (accountSuffix != null && parsed.accountNumber != null) {
                    val smsSuffix = parsed.accountNumber.takeLast(4)
                    if (smsSuffix != accountSuffix) continue
                }

                return parsed
            }
        }

        return null
    }
}
