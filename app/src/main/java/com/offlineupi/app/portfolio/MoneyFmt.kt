package com.offlineupi.app.portfolio

import java.util.Locale
import kotlin.math.abs

/** Native-currency display: ₹ in Cr/L, $ in k/M. INR appears only for INR assets or portfolio totals. */
object MoneyFmt {

    fun inr(v: Double): String {
        val a = abs(v)
        return when {
            a >= 1e7 -> "₹%.2f Cr".format(v / 1e7)
            a >= 1e5 -> "₹%.1f L".format(v / 1e5)
            else -> "₹" + String.format(Locale("en", "IN"), "%,.0f", v)
        }
    }

    fun money(v: Double, currency: String): String {
        if (currency != "USD") return inr(v)
        val a = abs(v)
        return when {
            a >= 1e6 -> "$%.2fM".format(v / 1e6)
            a >= 1e4 -> "$%.1fk".format(v / 1e3)
            else -> "$" + String.format(Locale.US, "%,.0f", v)
        }
    }

    /** Full-precision unit price, e.g. ₹2,431.55 / $187.20. */
    fun price(v: Double, currency: String): String =
        (if (currency == "USD") "$" else "₹") +
            String.format(if (currency == "USD") Locale.US else Locale("en", "IN"), "%,.2f", v)

    /** Chart-axis labels: always one compact token, never a full comma number. */
    fun axis(v: Double, currency: String): String {
        val a = abs(v)
        return if (currency == "USD") when {
            a >= 1e6 -> "%.1fM".format(v / 1e6)
            a >= 1e3 -> "%.0fk".format(v / 1e3)
            else -> "%.0f".format(v)
        } else when {
            a >= 1e7 -> "%.1f Cr".format(v / 1e7)
            a >= 1e5 -> "%.1f L".format(v / 1e5)
            a >= 1e3 -> "%.0fk".format(v / 1e3)
            else -> "%.0f".format(v)
        }
    }

    /** Fractional rate (0.12 → +12.0%). */
    fun pct(v: Double?): String =
        v?.let { (if (it >= 0) "+" else "") + "%.1f%%".format(it * 100) } ?: "—"

    /** Already-in-percent value (4.2 → +4.2%). */
    fun signedPct(v: Double): String = (if (v >= 0) "+" else "") + "%.1f%%".format(v)
}
