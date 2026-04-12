package com.offlineupi.app.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class AccountBalance(
    val accountNumber: String,
    val balance: String,
    val bankName: String?,
    val timestamp: Long
)

class AccountBalanceStore(context: Context) {

    companion object {
        private const val PREF_NAME = "account_balances"
        private const val KEY_BALANCES = "balances"
        private const val KEY_MISSED_CALL_NUMBER = "missed_call_number"
        const val DEFAULT_MISSED_CALL_NUMBER = "09223008488"

        /** Extract the last 4 digits — the only significant part of masked account numbers. */
        fun normalizeAccount(accountNumber: String): String {
            val digits = accountNumber.filter { it.isDigit() }
            return digits.takeLast(4)
        }
    }

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREF_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveBalances(balances: List<AccountBalance>) {
        val arr = JSONArray()
        balances.forEach { b ->
            arr.put(JSONObject().apply {
                put("accountNumber", b.accountNumber)
                put("balance", b.balance)
                put("bankName", b.bankName ?: "")
                put("timestamp", b.timestamp)
            })
        }
        prefs.edit().putString(KEY_BALANCES, arr.toString()).apply()
    }

    fun getBalances(): List<AccountBalance> {
        val json = prefs.getString(KEY_BALANCES, null) ?: return emptyList()
        val arr = JSONArray(json)
        val list = mutableListOf<AccountBalance>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(AccountBalance(
                accountNumber = obj.getString("accountNumber"),
                balance = obj.getString("balance"),
                bankName = obj.optString("bankName").ifBlank { null },
                timestamp = obj.getLong("timestamp")
            ))
        }
        return list
    }

    fun updateBalances(newBalances: List<AccountBalance>) {
        val existing = getBalances().associateBy { normalizeAccount(it.accountNumber) }.toMutableMap()
        newBalances.forEach { existing[normalizeAccount(it.accountNumber)] = it }
        saveBalances(existing.values.toList())
    }

    fun getMissedCallNumber(): String {
        return prefs.getString(KEY_MISSED_CALL_NUMBER, DEFAULT_MISSED_CALL_NUMBER)
            ?: DEFAULT_MISSED_CALL_NUMBER
    }

    fun setMissedCallNumber(number: String) {
        prefs.edit().putString(KEY_MISSED_CALL_NUMBER, number).apply()
    }
}
