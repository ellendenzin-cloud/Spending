package com.ellie.spendwatch

import kotlin.math.abs

/** Cents in, readable dollars out. No floats anywhere near the arithmetic. */
object Money {

    /** 4217 -> "$42.17", -1200 -> "-$12.00". */
    fun format(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val value = abs(cents)
        return "$sign$${value / 100}.${(value % 100).toString().padStart(2, '0')}"
    }

    /** 4217 -> "$42", for tight spaces. Rounds to the nearest dollar. */
    fun short(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val dollars = (abs(cents) + 50) / 100
        return "$sign$$dollars"
    }

    /** "42.17", "$42.17", "42" -> cents, or null if it isn't a number. */
    fun parse(input: String): Long? {
        val cleaned = input.trim().removePrefix("$").replace(",", "").trim()
        if (cleaned.isBlank()) return null
        if (!Regex("""^[0-9]*\.?[0-9]{0,2}$""").matches(cleaned)) return null
        val parts = cleaned.split(".")
        return try {
            val whole = parts[0].ifBlank { "0" }.toLong()
            val fraction = if (parts.size > 1 && parts[1].isNotBlank()) {
                parts[1].padEnd(2, '0').take(2).toLong()
            } else 0L
            whole * 100L + fraction
        } catch (_: NumberFormatException) {
            null
        }
    }
}
