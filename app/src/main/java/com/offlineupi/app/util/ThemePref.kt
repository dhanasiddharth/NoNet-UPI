package com.offlineupi.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** The user's appearance choice (System / Light / Dark), mapped to AppCompat's
 *  night mode. Applied once at startup and again — live — when it's changed. */
object ThemePref {
    private const val PREFS = "appearance"
    private const val KEY = "mode"
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun get(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM

    private fun nightMode(mode: String): Int = when (mode) {
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Persist + apply immediately; AppCompat recreates visible activities. */
    fun set(c: Context, mode: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
        AppCompatDelegate.setDefaultNightMode(nightMode(mode))
    }

    /** Apply the saved choice (call from Application.onCreate). */
    fun apply(c: Context) = AppCompatDelegate.setDefaultNightMode(nightMode(get(c)))
}
