package com.ellie.spendwatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * All the state, in one SharedPreferences file as JSON. Small enough that a database
 * would be overhead, and it keeps the whole thing to zero dependencies.
 *
 * Month rollover happens lazily: every read checks whether the stored period is still
 * the current one, and if not, settles up the old month first.
 */
object Store {

    private const val FILE = "spendwatch"
    private const val KEY_CATEGORIES = "categories"
    private const val KEY_TXNS = "txns"
    private const val KEY_PERIOD = "period"
    private const val KEY_LEARNED = "learned"
    private const val KEY_CARRY_SURPLUS = "carry_surplus"
    private const val KEY_LISTENING = "listening"
    private const val KEY_AUTO_CONFIRM = "auto_confirm"

    // --- Reading --------------------------------------------------------------

    fun categories(context: Context): List<Category> {
        settleIfNewMonth(context)
        return rawCategories(context)
    }

    fun transactions(context: Context): List<Txn> {
        settleIfNewMonth(context)
        return rawTransactions(context)
    }

    /** This month's charges, newest first. */
    fun thisMonth(context: Context): List<Txn> {
        val period = currentPeriod()
        return transactions(context)
            .filter { it.period == period }
            .sortedByDescending { it.timestamp }
    }

    /** Charges waiting for a category. */
    fun reviewQueue(context: Context): List<Txn> =
        thisMonth(context).filter { it.needsCategory }

    fun statuses(context: Context): List<CategoryStatus> {
        val month = thisMonth(context).filter { !it.needsCategory }
        val spentByCategory = HashMap<String, Long>()
        for (t in month) {
            val id = t.categoryId ?: continue
            spentByCategory[id] = (spentByCategory[id] ?: 0L) + t.amountCents
        }
        return categories(context).map {
            CategoryStatus(it, spentByCategory[it.id] ?: 0L)
        }
    }

    fun totalBudgetCents(context: Context): Long =
        categories(context).sumOf { it.effectiveBudgetCents }

    fun totalSpentCents(context: Context): Long =
        thisMonth(context).filter { !it.needsCategory }.sumOf { it.amountCents }

    fun totalRemainingCents(context: Context): Long =
        totalBudgetCents(context) - totalSpentCents(context)

    // --- Writing --------------------------------------------------------------

    fun addTransaction(context: Context, txn: Txn) {
        settleIfNewMonth(context)
        val all = rawTransactions(context).toMutableList()
        all.add(txn)
        writeTransactions(context, all)
    }

    fun updateTransaction(context: Context, txn: Txn) {
        val all = rawTransactions(context).toMutableList()
        val index = all.indexOfFirst { it.id == txn.id }
        if (index >= 0) {
            all[index] = txn
            writeTransactions(context, all)
        }
    }

    fun deleteTransaction(context: Context, id: String) {
        val all = rawTransactions(context).filter { it.id != id }
        writeTransactions(context, all)
    }

    fun saveCategories(context: Context, categories: List<Category>) {
        val array = JSONArray()
        for (c in categories) array.put(c.toJson())
        prefs(context).edit().putString(KEY_CATEGORIES, array.toString()).apply()
    }

    /**
     * Files a charge into a category and remembers the merchant, so the next one like
     * it lands on its own.
     */
    fun categorize(context: Context, txn: Txn, categoryId: String) {
        updateTransaction(context, txn.copy(categoryId = categoryId, confirmed = true))
        learn(context, txn.merchant, categoryId)
    }

    // --- Merchant memory ------------------------------------------------------

    /** merchant key -> category id, built up as charges get filed by hand. */
    fun learned(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_LEARNED, "{}") ?: "{}"
        val out = HashMap<String, String>()
        try {
            val o = JSONObject(raw)
            for (key in o.keys()) out[key] = o.getString(key)
        } catch (_: Exception) {
        }
        return out
    }

    fun learn(context: Context, merchant: String, categoryId: String) {
        val key = merchantKey(merchant)
        if (key.isBlank()) return
        val map = learned(context).toMutableMap()
        map[key] = categoryId
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        prefs(context).edit().putString(KEY_LEARNED, o.toString()).apply()
    }

    fun forgetMerchant(context: Context, merchant: String) {
        val map = learned(context).toMutableMap()
        map.remove(merchantKey(merchant))
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        prefs(context).edit().putString(KEY_LEARNED, o.toString()).apply()
    }

    /** Normalized so "TARGET #1234" and "Target" are the same merchant. */
    fun merchantKey(merchant: String): String =
        merchant.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(28)

    // --- Settings -------------------------------------------------------------

    fun carrySurplus(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CARRY_SURPLUS, false)

    fun setCarrySurplus(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_CARRY_SURPLUS, value).apply()

    fun listening(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LISTENING, true)

    fun setListening(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_LISTENING, value).apply()

    fun autoConfirm(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CONFIRM, true)

    fun setAutoConfirm(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_CONFIRM, value).apply()

    // --- Month rollover -------------------------------------------------------

    fun currentPeriod(): String = YearMonth.now().toString()

    /** Days left in the month, today included. */
    fun daysLeftInMonth(): Int {
        val today = LocalDate.now()
        return today.lengthOfMonth() - today.dayOfMonth + 1
    }

    /**
     * If the stored period isn't the current month, close out every month in between:
     * each category's overspend becomes next month's debt.
     */
    private fun settleIfNewMonth(context: Context) {
        val p = prefs(context)
        val stored = p.getString(KEY_PERIOD, null)
        val now = currentPeriod()

        if (stored == null) {
            p.edit().putString(KEY_PERIOD, now).apply()
            return
        }
        if (stored == now) return

        var cursor = try {
            YearMonth.parse(stored)
        } catch (_: Exception) {
            p.edit().putString(KEY_PERIOD, now).apply()
            return
        }
        val target = YearMonth.parse(now)
        val carrySurplus = carrySurplus(context)
        var categories = rawCategories(context)
        val txns = rawTransactions(context)

        // Walk forward one month at a time so a gap of several months still settles.
        var guard = 0
        while (cursor.isBefore(target) && guard < 240) {
            val period = cursor.toString()
            val spent = HashMap<String, Long>()
            for (t in txns) {
                if (t.period != period) continue
                val id = t.categoryId ?: continue
                spent[id] = (spent[id] ?: 0L) + t.amountCents
            }
            categories = categories.map { c ->
                val used = spent[c.id] ?: 0L
                val left = c.effectiveBudgetCents - used
                val next = when {
                    left < 0L -> left                 // debt follows you
                    carrySurplus -> left              // optional: savings follow too
                    else -> 0L                        // clean slate on a good month
                }
                c.copy(carryoverCents = next)
            }
            cursor = cursor.plusMonths(1)
            guard++
        }

        saveCategories(context, categories)
        p.edit().putString(KEY_PERIOD, now).apply()
    }

    // --- Raw access -----------------------------------------------------------

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun rawCategories(context: Context): List<Category> {
        val raw = prefs(context).getString(KEY_CATEGORIES, null)
        if (raw.isNullOrBlank()) {
            val seeded = defaultCategories()
            saveCategories(context, seeded)
            return seeded
        }
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<Category>(array.length())
            for (i in 0 until array.length()) out.add(Category.fromJson(array.getJSONObject(i)))
            out
        } catch (_: Exception) {
            defaultCategories()
        }
    }

    private fun rawTransactions(context: Context): List<Txn> {
        val raw = prefs(context).getString(KEY_TXNS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<Txn>(array.length())
            for (i in 0 until array.length()) out.add(Txn.fromJson(array.getJSONObject(i)))
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeTransactions(context: Context, txns: List<Txn>) {
        // Keep the file from growing forever: 18 months of history is plenty.
        val cutoff = YearMonth.now().minusMonths(18).toString()
        val kept = txns.filter { it.period >= cutoff }
        val array = JSONArray()
        for (t in kept) array.put(t.toJson())
        prefs(context).edit().putString(KEY_TXNS, array.toString()).apply()
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun periodOf(timestamp: Long): String =
        YearMonth.from(
            java.time.Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
        ).toString()

    /** Ellie's split, $185 a month. */
    private fun defaultCategories(): List<Category> = listOf(
        Category(
            id = "groceries",
            name = "Groceries & household",
            budgetCents = 10000L,
            colorIndex = 0,
            keywords = listOf(
                "target", "sams club", "sam s club", "walmart", "kroger", "meijer",
                "aldi", "costco", "grocery", "supermarket", "dollar general", "cvs",
                "walgreens"
            )
        ),
        Category(
            id = "eatingout",
            name = "Eating out",
            budgetCents = 3000L,
            colorIndex = 1,
            keywords = listOf(
                "doordash", "uber eats", "ubereats", "grubhub", "starbucks", "dunkin",
                "mcdonald", "chipotle", "taco bell", "wendy", "subway", "panera",
                "pizza", "cafe", "coffee", "restaurant", "diner", "chick fil a",
                "burger", "deli", "bakery"
            )
        ),
        Category(
            id = "laundry",
            name = "Laundry",
            budgetCents = 2000L,
            colorIndex = 2,
            keywords = listOf("laundr", "wash", "cleaners", "dry clean")
        ),
        Category(
            id = "gas",
            name = "Gas & parking",
            budgetCents = 1500L,
            colorIndex = 3,
            keywords = listOf(
                "shell", "bp ", "exxon", "mobil", "speedway", "marathon", "sunoco",
                "citgo", "chevron", "circle k", "wawa", "parking", "park mobile",
                "parkmobile", "passport parking", "gas", "fuel"
            )
        ),
        Category(
            id = "fun",
            name = "Spotify / fun",
            budgetCents = 1000L,
            colorIndex = 4,
            keywords = listOf(
                "spotify", "netflix", "hulu", "disney", "apple music", "youtube",
                "steam", "nintendo", "cinema", "theater", "theatre", "amc", "regal",
                "patreon", "twitch"
            )
        ),
        Category(
            id = "extras",
            name = "Clothes, Amazon, extras",
            budgetCents = 1000L,
            colorIndex = 5,
            keywords = listOf(
                "amazon", "amzn", "etsy", "shein", "h m", "old navy", "gap", "tj maxx",
                "marshalls", "ross", "nordstrom", "zara", "uniqlo", "ulta", "sephora",
                "ebay", "temu"
            )
        )
    )
}
