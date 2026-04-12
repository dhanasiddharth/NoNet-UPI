package com.offlineupi.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Securely stores the user's UPI account balance using EncryptedSharedPreferences.
 *
 * SECURITY:
 * - Data is encrypted at rest using AES-256-GCM (keys) + AES-256-SIV (values).
 * - Master key is stored in Android Keystore (hardware-backed where available).
 * - No other app can access this data.
 * - Balance is stored as a string exactly as returned by USSD.
 */
class SecureBalanceStore(context: Context) {

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

    fun saveBalance(balance: String, source: String = SOURCE_USSD) {
        prefs.edit()
            .putString(KEY_BALANCE, balance)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_SOURCE, source)
            .apply()
    }

    fun getBalance(): String? = prefs.getString(KEY_BALANCE, null)

    fun getTimestamp(): Long = prefs.getLong(KEY_TIMESTAMP, 0L)

    fun getSource(): String = prefs.getString(KEY_SOURCE, SOURCE_USSD) ?: SOURCE_USSD

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE_NAME = "secure_balance"
        private const val KEY_BALANCE = "balance"
        private const val KEY_TIMESTAMP = "balance_timestamp"
        private const val KEY_SOURCE = "balance_source"
        const val SOURCE_USSD = "USSD"
        const val SOURCE_SMS = "SMS"
    }
}
