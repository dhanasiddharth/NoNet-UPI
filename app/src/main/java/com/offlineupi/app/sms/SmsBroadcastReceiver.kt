package com.offlineupi.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import com.offlineupi.app.data.AccountBalanceStore
import com.offlineupi.app.data.SecureBalanceStore
import com.offlineupi.app.data.TransactionStore
import com.offlineupi.app.util.formatIndianNumber

/**
 * Manifest-declared receiver that captures bank SMS as they arrive,
 * even if the payment screen has been closed. Matches against the most
 * recent pending (failed/no-RRN) transaction by amount and account.
 */
class SmsBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_BALANCES_UPDATED = "com.offlineupi.app.BALANCES_UPDATED"
    }

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

        val body = fullBody.toString()

        // Check if this is a missed-call balance SMS (multi-account)
        val balances = MissedCallBalanceParser.parse(body)
        if (balances != null) {
            val accountBalanceStore = AccountBalanceStore(context)
            accountBalanceStore.updateBalances(balances)
            context.sendBroadcast(Intent(ACTION_BALANCES_UPDATED).setPackage(context.packageName))
            return
        }

        val parsed = SmsParser.parseUpiSms(body) ?: return
        val smsAmount = parsed.amount.replace(",", "").trim().toDoubleOrNull() ?: return

        val store = TransactionStore(context)
        val transactions = store.getTransactions()

        // Find the most recent transaction that matches and hasn't been confirmed yet
        val match = transactions.firstOrNull { txn ->
            if (txn.type != "payment") return@firstOrNull false
            if (txn.rrn != null) return@firstOrNull false // already matched

            val txnAmount = txn.amount?.replace(",", "")?.trim()?.toDoubleOrNull()
                ?: return@firstOrNull false
            if (txnAmount != smsAmount) return@firstOrNull false

            // Check account suffix if both available
            if (txn.accountNumber != null && parsed.accountNumber != null) {
                val txnSuffix = txn.accountNumber.takeLast(4)
                val smsSuffix = parsed.accountNumber.takeLast(4)
                if (txnSuffix != smsSuffix) return@firstOrNull false
            }

            // Only match transactions from the last 30 minutes
            val age = System.currentTimeMillis() - txn.timestamp
            age < 30 * 60 * 1000L
        } ?: return

        store.updateTransaction(match.id) { txn ->
            txn.copy(
                status = "success",
                rrn = parsed.rrn ?: txn.rrn,
                balance = parsed.balance ?: txn.balance,
                accountNumber = parsed.accountNumber ?: txn.accountNumber,
                rawSmsText = body
            )
        }

        if (parsed.balance != null) {
            val balanceStore = SecureBalanceStore(context)
            balanceStore.saveBalance(
                "\u20B9 ${formatIndianNumber(parsed.balance)}",
                SecureBalanceStore.SOURCE_SMS
            )
        }
    }
}
