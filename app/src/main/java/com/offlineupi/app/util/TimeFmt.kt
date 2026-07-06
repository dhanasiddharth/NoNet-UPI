package com.offlineupi.app.util

import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Every user-facing time in the app is shown in IST (Asia/Kolkata) on a
 * 12-hour clock with a lowercase am/pm marker, regardless of the device's
 * timezone — the payments and portfolio are Indian, so one fixed zone keeps
 * every timestamp unambiguous. All formatters here are read on the UI thread.
 */
object TimeFmt {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val IST_TZ: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

    // the `a` token renders "AM"/"PM"; override the symbols for lowercase,
    // which leaves month names (also drawn from these symbols) untouched
    private val SYMBOLS = DateFormatSymbols(Locale.ENGLISH).apply {
        amPmStrings = arrayOf("am", "pm")
    }

    private fun sdf(pattern: String) =
        SimpleDateFormat(pattern, SYMBOLS).apply { timeZone = IST_TZ }

    private val timeSdf = sdf("h:mm a")
    private val dateTimeSdf = sdf("d MMM yyyy, h:mm a")
    private val daySdf = sdf("d MMM yyyy")

    /** epoch millis → "9:41 am IST" (transaction feed). */
    fun time(ts: Long): String = timeSdf.format(Date(ts)) + " IST"

    /** epoch millis → "6 Jul 2026, 9:41 am IST" (receipt / records). */
    fun dateTime(ts: Long): String = dateTimeSdf.format(Date(ts)) + " IST"

    /** epoch millis → "Today" / "Yesterday" / "6 Jul 2026", with the day
     *  boundary evaluated in IST so it matches the times shown. */
    fun dayLabel(ts: Long): String {
        val now = Calendar.getInstance(IST_TZ)
        val today = now.get(Calendar.DAY_OF_YEAR); val year = now.get(Calendar.YEAR)
        val cal = Calendar.getInstance(IST_TZ).apply { timeInMillis = ts }
        return when {
            cal.get(Calendar.YEAR) == year && cal.get(Calendar.DAY_OF_YEAR) == today -> "Today"
            cal.get(Calendar.YEAR) == year && cal.get(Calendar.DAY_OF_YEAR) == today - 1 -> "Yesterday"
            else -> daySdf.format(Date(ts))
        }
    }

    /** epoch seconds → "Mon 9:41 am" in IST (intraday chart axis/crosshair). */
    private val intradayFmt = DateTimeFormatterBuilder()
        .appendPattern("EEE h:mm ")
        .appendText(ChronoField.AMPM_OF_DAY, mapOf(0L to "am", 1L to "pm"))
        .toFormatter(Locale.ENGLISH)
        .withZone(IST)
    fun intraday(epochSec: Long): String =
        intradayFmt.format(Instant.ofEpochSecond(epochSec))
}
