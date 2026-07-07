package com.offlineupi.app.data

import org.json.JSONObject
import java.util.UUID

/** Payee name only if one was actually captured — filters the legacy "Unknown Payee" placeholder. */
val Transaction.storedName: String?
    get() = payeeName?.takeIf { it.isNotBlank() && it != "Unknown Payee" }

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
    val reversalRrn: String? = null,
    // Where the payment was made — captured on the processing screen.
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float? = null,     // metres, if the fix reported it
    val placeName: String? = null,           // user-confirmed place / business
    val placeId: String? = null              // Google place_id of the selection
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
        put("latitude", latitude ?: JSONObject.NULL)
        put("longitude", longitude ?: JSONObject.NULL)
        put("locationAccuracy", locationAccuracy?.toDouble() ?: JSONObject.NULL)
        put("placeName", placeName ?: JSONObject.NULL)
        put("placeId", placeId ?: JSONObject.NULL)
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
            reversalRrn = json.nullString("reversalRrn"),
            latitude = json.nullDouble("latitude"),
            longitude = json.nullDouble("longitude"),
            locationAccuracy = json.nullDouble("locationAccuracy")?.toFloat(),
            placeName = json.nullString("placeName"),
            placeId = json.nullString("placeId")
        )

        private fun JSONObject.nullString(key: String): String? =
            if (has(key) && !isNull(key)) getString(key) else null

        private fun JSONObject.nullDouble(key: String): Double? =
            if (has(key) && !isNull(key)) getDouble(key) else null
    }
}
