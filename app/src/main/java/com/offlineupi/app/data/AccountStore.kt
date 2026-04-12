package com.offlineupi.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the user's masked bank account number securely.
 * Captured from the USSD PIN prompt (e.g. "XXXXXX1256").
 */
class AccountStore(context: Context) {

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

    fun saveAccountNumber(accountNumber: String) {
        prefs.edit().putString(KEY_ACCOUNT, accountNumber).apply()
    }

    fun saveBankName(bankName: String) {
        prefs.edit().putString(KEY_BANK, bankName).apply()
    }

    fun getAccountNumber(): String? = prefs.getString(KEY_ACCOUNT, null)

    fun getBankName(): String? = prefs.getString(KEY_BANK, null)

    companion object {
        private const val FILE_NAME = "secure_account"
        private const val KEY_ACCOUNT = "account_number"
        private const val KEY_BANK = "bank_name"
    }
}
