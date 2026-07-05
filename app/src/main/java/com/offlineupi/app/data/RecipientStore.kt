package com.offlineupi.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Remembers recipient names captured from the *99# flow, keyed by payee
 * address (mobile number or VPA).
 *
 * A "known" recipient (one we've captured a name for) can be paid via the
 * fast one-shot dial. An unknown recipient is routed through the interactive
 * flow first so the USSD confirmation/amount screen reveals — and we capture —
 * their name.
 */
class RecipientStore(context: Context) {

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

    private fun keyFor(address: String) = address.trim().lowercase()

    fun getName(address: String): String? {
        if (address.isBlank()) return null
        val name = prefs.getString(keyFor(address), null) ?: return null
        // Ignore (and forget) a previously mis-captured instruction word so the
        // recipient is treated as unknown and re-routed to capture the real name.
        if (isInstructionWord(name)) {
            prefs.edit().remove(keyFor(address)).apply()
            return null
        }
        return name
    }

    fun isKnown(address: String): Boolean = !getName(address).isNullOrBlank()

    fun saveName(address: String, name: String) {
        if (address.isBlank() || name.isBlank() || isInstructionWord(name)) return
        prefs.edit().putString(keyFor(address), name.trim()).apply()
    }

    private fun isInstructionWord(name: String): Boolean =
        name.trim().lowercase() in INSTRUCTION_WORDS

    companion object {
        private const val FILE_NAME = "secure_recipients"
        private val INSTRUCTION_WORDS = setOf(
            "proceed", "continue", "confirm", "cancel", "exit", "pay", "enter",
            "skip", "send", "save", "yes", "no", "back", "retry", "complete"
        )
    }
}
