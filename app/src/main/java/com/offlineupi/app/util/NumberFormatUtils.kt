package com.offlineupi.app.util

/**
 * Formats a number string with Indian comma separators (thousands, lakhs, crores).
 * e.g., "112096.96" -> "1,12,096.96", "10000000" -> "1,00,00,000"
 */
fun formatIndianNumber(numberStr: String): String {
    val cleaned = numberStr.replace(",", "")
    val negative = cleaned.startsWith("-")
    val unsigned = if (negative) cleaned.removePrefix("-") else cleaned

    val dotIndex = unsigned.indexOf('.')
    val intPart = if (dotIndex >= 0) unsigned.substring(0, dotIndex) else unsigned
    val decPart = if (dotIndex >= 0) unsigned.substring(dotIndex) else ""

    val formatted = if (intPart.length <= 3) {
        intPart + decPart
    } else {
        val lastThree = intPart.takeLast(3)
        val remaining = intPart.dropLast(3)

        val groups = mutableListOf<String>()
        var i = remaining.length
        while (i > 0) {
            val start = maxOf(0, i - 2)
            groups.add(0, remaining.substring(start, i))
            i = start
        }

        groups.joinToString(",") + "," + lastThree + decPart
    }

    return if (negative) "-$formatted" else formatted
}
