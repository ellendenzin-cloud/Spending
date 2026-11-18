package com.ellie.spendwatch

import org.json.JSONArray
import org.json.JSONObject

/**
 * One spending category. Amounts are whole cents everywhere in this app — no floats
 * touch money, so nothing drifts.
 *
 * [carryoverCents] is the debt dragged in from previous months. It is zero or negative:
 * blow past Groceries by $12 in March and April starts at -1200, so April's real
 * allowance is $88 instead of $100.
 */
data class Category(
    val id: String,
    val name: String,
    val budgetCents: Long,
    val carryoverCents: Long = 0L,
    /** Merchant fragments that land a charge here, lowercase. */
    val keywords: List<String> = emptyList(),
    val colorIndex: Int = 0
) {
    /** What's actually available this month, debt included. Can go negative. */
    val effectiveBudgetCents: Long
        get() = budgetCents + carryoverCents

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("budget", budgetCents)
        put("carryover", carryoverCents)
        put("color", colorIndex)
        put("keywords", JSONArray(keywords))
    }

    companion object {
        fun fromJson(o: JSONObject): Category {
            val words = ArrayList<String>()
            val arr = o.optJSONArray("keywords") ?: JSONArray()
            for (i in 0 until arr.length()) words.add(arr.getString(i))
            return Category(
                id = o.getString("id"),
                name = o.getString("name"),
                budgetCents = o.getLong("budget"),
                carryoverCents = o.optLong("carryover", 0L),
                keywords = words,
                colorIndex = o.optInt("color", 0)
            )
        }
    }
}

/** One charge. [period] is "YYYY-MM", so a month's spending is a cheap filter. */
data class Txn(
    val id: String,
    val amountCents: Long,
    val merchant: String,
    val categoryId: String?,
    val timestamp: Long,
    val period: String,
    val source: Source,
    /** False while a charge sits in the review queue. */
    val confirmed: Boolean = true,
    val note: String = ""
) {
    enum class Source { NOTIFICATION, MANUAL }

    val needsCategory: Boolean
        get() = categoryId == null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("amount", amountCents)
        put("merchant", merchant)
        put("category", categoryId ?: JSONObject.NULL)
        put("ts", timestamp)
        put("period", period)
        put("source", source.name)
        put("confirmed", confirmed)
        put("note", note)
    }

    companion object {
        fun fromJson(o: JSONObject): Txn = Txn(
            id = o.getString("id"),
            amountCents = o.getLong("amount"),
            merchant = o.getString("merchant"),
            categoryId = if (o.isNull("category")) null else o.getString("category"),
            timestamp = o.getLong("ts"),
            period = o.getString("period"),
            source = try {
                Source.valueOf(o.optString("source", "MANUAL"))
            } catch (_: IllegalArgumentException) {
                Source.MANUAL
            },
            confirmed = o.optBoolean("confirmed", true),
            note = o.optString("note", "")
        )
    }
}

/** How a category is doing right now. Everything the UI needs to shout at you. */
data class CategoryStatus(
    val category: Category,
    val spentCents: Long
) {
    val remainingCents: Long
        get() = category.effectiveBudgetCents - spentCents

    val isOver: Boolean
        get() = remainingCents < 0

    /** 0f..1f+ — how much of the allowance is gone. Over 1 means over budget. */
    val fraction: Float
        get() {
            val budget = category.effectiveBudgetCents
            if (budget <= 0L) return if (spentCents > 0L) 1.5f else 1f
            return (spentCents.toFloat() / budget.toFloat()).coerceAtLeast(0f)
        }

    val severity: Severity
        get() = when {
            isOver -> Severity.BLOWN
            fraction >= 0.9f -> Severity.CRITICAL
            fraction >= 0.7f -> Severity.WARNING
            else -> Severity.FINE
        }
}

enum class Severity { FINE, WARNING, CRITICAL, BLOWN }
