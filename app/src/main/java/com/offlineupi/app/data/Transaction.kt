package com.offlineupi.app.data

import org.json.JSONObject
import java.util.UUID

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // "payment" or "balance_check"
    val amount: String?,
    val payeeAddress: String?,
    val payeeName: String?,
    val accountNumber: String?,
    val rrn: String?,
    val balance: String?,
    val remarks: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String, // "success", "failure", "reversed", or "pending"
    val rawSmsText: String?,
    /** Reference number of the refund when the payment was reversed. */
    val reversalRrn: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("amount", amount ?: JSONObject.NULL)
        put("payeeAddress", payeeAddress ?: JSONObject.NULL)
        put("payeeName", payeeName ?: JSONObject.NULL)
        put("accountNumber", accountNumber ?: JSONObject.NULL)
        put("rrn", rrn ?: JSONObject.NULL)
        put("balance", balance ?: JSONObject.NULL)
        put("remarks", remarks ?: JSONObject.NULL)
        put("timestamp", timestamp)
        put("status", status)
        put("rawSmsText", rawSmsText ?: JSONObject.NULL)
        put("reversalRrn", reversalRrn ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): Transaction = Transaction(
            id = json.getString("id"),
            type = json.getString("type"),
            amount = json.nullString("amount"),
            payeeAddress = json.nullString("payeeAddress"),
            payeeName = json.nullString("payeeName"),
            accountNumber = json.nullString("accountNumber"),
            rrn = json.nullString("rrn"),
            balance = json.nullString("balance"),
            remarks = json.nullString("remarks"),
            timestamp = json.getLong("timestamp"),
            status = json.getString("status"),
            rawSmsText = json.nullString("rawSmsText"),
            reversalRrn = json.nullString("reversalRrn")
        )

        private fun JSONObject.nullString(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null
    }
}
