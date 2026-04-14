package com.offlineupi.app.util

/**
 * Normalizes an Indian mobile number to its 10-digit form for USSD entry.
 * Accepts input with +91 prefix, leading 0, spaces, dashes, or parentheses.
 * Returns null if the input does not contain a valid 10-digit mobile number
 * starting with 6–9.
 */
fun normalizeIndianMobile(raw: String): String? {
    val digits = raw.filter { it.isDigit() }
    val stripped = when {
        digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
        digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
        else -> digits
    }
    if (stripped.length != 10) return null
    if (stripped[0] !in '6'..'9') return null
    return stripped
}

/**
 * Formats a 10-digit mobile for display as "XXXXX XXXXX".
 */
fun formatMobileForDisplay(digits: String): String {
    if (digits.length != 10) return digits
    return "${digits.substring(0, 5)} ${digits.substring(5)}"
}
