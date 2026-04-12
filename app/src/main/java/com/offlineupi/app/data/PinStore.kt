package com.offlineupi.app.data

/**
 * Volatile in-memory store for UPI PIN.
 *
 * The PIN lives only in the app's process memory — it is never written to disk,
 * SharedPreferences, or any persistent storage. A device reboot (or process death)
 * automatically clears it.
 */
object PinStore {

    @Volatile
    var pin: String? = null
        private set

    fun setPin(value: String) {
        pin = value
    }

    fun hasPin(): Boolean = !pin.isNullOrEmpty()

    fun clear() {
        pin = null
    }
}
