package com.offlineupi.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/**
 * Stores transaction history using EncryptedSharedPreferences.
 * Transactions are serialized as a JSON array.
 */
class TransactionStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveTransaction(txn: Transaction) {
        val list = getTransactions().toMutableList()
        list.add(0, txn) // newest first
        val jsonArray = JSONArray()
        list.forEach { jsonArray.put(it.toJson()) }
        prefs.edit().putString(KEY_TRANSACTIONS, jsonArray.toString()).apply()
    }

    fun updateTransaction(id: String, update: (Transaction) -> Transaction) {
        val list = getTransactions().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            list[index] = update(list[index])
            val jsonArray = JSONArray()
            list.forEach { jsonArray.put(it.toJson()) }
            prefs.edit().putString(KEY_TRANSACTIONS, jsonArray.toString()).apply()
        }
    }

    fun getTransactions(): List<Transaction> {
        val json = prefs.getString(KEY_TRANSACTIONS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { Transaction.fromJson(array.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getTransaction(id: String): Transaction? =
        getTransactions().firstOrNull { it.id == id }

    fun findByRrn(rrn: String): Transaction? =
        getTransactions().firstOrNull { it.rrn == rrn }

    /**
     * Ensures each RRN maps to at most one transaction. For groups sharing an RRN,
     * keeps the newest (largest timestamp) and reverts the rest: clears rrn/balance/
     * rawSmsText and sets status back to "failure". Returns the number of entries fixed.
     */
    fun deduplicateByRrn(): Int {
        val list = getTransactions().toMutableList()
        val groups = list.filter { !it.rrn.isNullOrBlank() }.groupBy { it.rrn!! }
        var fixed = 0
        groups.forEach { (_, group) ->
            if (group.size <= 1) return@forEach
            val duplicates = group.sortedByDescending { it.timestamp }.drop(1)
            duplicates.forEach { dup ->
                val idx = list.indexOfFirst { it.id == dup.id }
                if (idx >= 0) {
                    list[idx] = dup.copy(
                        rrn = null,
                        balance = null,
                        rawSmsText = null,
                        status = "failure"
                    )
                    fixed++
                }
            }
        }
        if (fixed > 0) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            prefs.edit().putString(KEY_TRANSACTIONS, arr.toString()).apply()
        }
        return fixed
    }

    companion object {
        private const val FILE_NAME = "secure_transactions"
        private const val KEY_TRANSACTIONS = "transactions"
    }
}
