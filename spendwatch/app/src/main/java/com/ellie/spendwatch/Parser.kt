package com.ellie.spendwatch

/**
 * Pulls an amount and a merchant out of a bank or card notification.
 *
 * Deliberately conservative: it would rather ignore a notification than invent a
 * charge, so anything that doesn't look like a completed purchase is dropped. Pure
 * functions only, so the logic is testable without a phone.
 */
object Parser {

    data class Parsed(
        val amountCents: Long,
        val merchant: String,
        /** A refund or credit, which should reduce the month's spending. */
        val isRefund: Boolean
    )

    /** "$42.17", "$1,234.56", "USD 42.17", "42.17 USD". */
    private val AMOUNT = Regex(
        """(?:\$|usd\s*)\s?([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{2})?|[0-9]+(?:\.[0-9]{2})?)""",
        RegexOption.IGNORE_CASE
    )

    /** Phrases that mean money left the account. */
    private val SPEND_HINTS = listOf(
        "spent", "purchase", "charged", "transaction", "paid", "payment of",
        "was used at", "card ending", "debit", "withdrawal", "you used",
        "charge of", "sent", "bought"
    )

    /** Phrases that mean this isn't a purchase at all. */
    private val IGNORE_HINTS = listOf(
        "declined", "denied", "failed", "balance is", "current balance",
        "available balance", "statement", "due date", "minimum payment",
        "autopay", "deposited", "deposit of", "direct deposit", "received",
        "transfer to savings", "interest", "rewards", "points", "offer",
        "save with", "limited time", "earn ", "cash back bonus", "security alert",
        "sign in", "signed in", "password", "verification", "verify", "code is",
        "low balance", "overdraft protection", "has posted to", "bill is",
        "scheduled", "reminder", "fraud"
    )

    private val REFUND_HINTS = listOf("refund", "credited", "credit of", "returned", "reversal")

    /** Words that end a merchant name, so the match doesn't run into the rest of the sentence. */
    private const val STOP =
        "(?:on|for|using|with|at|is|was|were|has|have|had|and|to|from|ending|your|card|" +
            "account|available|this|pending|if|you|in|of|no|not)"

    /** Captures the merchant that follows a preposition. */
    private val MERCHANT = Regex(
        """\b(?:at|with|to|from)\s+([A-Za-z0-9&'.#\-* ]{2,40}?)(?=\s+""" + STOP + """\b|[.,;!?]|$)""",
        RegexOption.IGNORE_CASE
    )

    /** A trailing store number or reference code: "#338812", "F1234", "2A4B9", "0054". */
    private val JUNK_TOKEN = Regex("""^(?:[#*]\S*|[A-Za-z]{0,2}\d[A-Za-z0-9]*|\d+)$""")

    /** Words that are never a merchant name, even if the grammar points at them. */
    private val NOT_MERCHANTS = listOf(
        "your", "a", "an", "the account", "account", "card", "checking", "savings",
        "atm", "this", "that", "it", "you", "us", "pending", "unknown"
    )

    /**
     * Returns null when the notification isn't a purchase. [title] and [body] are the
     * notification's own fields; both are searched together.
     */
    fun parse(title: String?, body: String?): Parsed? {
        val raw = listOfNotNull(title, body).joinToString(". ").trim()
        if (raw.isBlank()) return null
        val lower = raw.lowercase()

        val isRefund = REFUND_HINTS.any { lower.contains(it) }

        // A refund is a legitimate money event; everything else on the ignore list isn't.
        if (!isRefund && IGNORE_HINTS.any { lower.contains(it) }) return null
        if (!isRefund && SPEND_HINTS.none { lower.contains(it) }) return null

        val amountCents = extractAmount(raw) ?: return null
        if (amountCents <= 0L) return null
        // A five-figure charge from a parsing slip would wreck the month; ignore it.
        if (amountCents > 2_000_000L) return null

        val merchant = extractMerchant(raw) ?: "Unknown"
        return Parsed(amountCents, merchant, isRefund)
    }

    /** The first plausible dollar figure in the text, as cents. */
    fun extractAmount(text: String): Long? {
        val match = AMOUNT.find(text) ?: return null
        val digits = match.groupValues[1].replace(",", "")
        return toCents(digits)
    }

    /** "42.17" -> 4217, "42" -> 4200. */
    fun toCents(decimal: String): Long? {
        val parts = decimal.split(".")
        return try {
            val whole = parts[0].ifBlank { "0" }.toLong()
            val fraction = if (parts.size > 1) parts[1].padEnd(2, '0').take(2).toLong() else 0L
            whole * 100L + fraction
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Best guess at the merchant, tidied for display. Longest plausible match wins. */
    fun extractMerchant(text: String): String? {
        var best: String? = null
        for (match in MERCHANT.findAll(text)) {
            val candidate = clean(match.groupValues[1])
            if (candidate.isNotBlank() && isPlausible(candidate)) {
                if (best == null || candidate.length > best.length) best = candidate
            }
        }
        return best
    }

    private fun clean(raw: String): String {
        var s = raw.trim().replace(Regex("""\s+"""), " ")

        // Drop trailing store numbers and reference codes, however many there are.
        val tokens = s.split(" ").toMutableList()
        while (tokens.size > 1 && JUNK_TOKEN.matches(tokens.last().trim('.', ',', ';', '-', '*'))) {
            tokens.removeAt(tokens.size - 1)
        }
        s = tokens.joinToString(" ")

        s = s.replace(Regex("""[#*]\s*\S+$"""), "").trim()
        s = s.trimEnd('.', ',', ';', '-', '*', ' ')
        if (s.length > 32) s = s.take(32).trim()
        return titleCase(s)
    }

    /** Bank text is usually SHOUTING; make it readable without mangling "McDonald's". */
    private fun titleCase(s: String): String {
        if (s.isNotEmpty() && s.none { it.isLowerCase() }) {
            return s.split(" ").joinToString(" ") { word ->
                if (word.length <= 1) word.uppercase()
                else word[0].uppercase() + word.substring(1).lowercase()
            }
        }
        return s
    }

    private fun isPlausible(candidate: String): Boolean {
        val lower = candidate.lowercase()
        if (candidate.length < 2) return false
        if (NOT_MERCHANTS.any { lower == it || lower.startsWith("$it ") }) return false
        // Needs at least one letter; a bare number isn't a merchant.
        return candidate.any { it.isLetter() }
    }

    /**
     * Picks a category for a merchant: a learned mapping first, then the keyword lists.
     * Returns null when nothing matches, which sends the charge to the review queue.
     */
    fun categoryFor(
        merchant: String,
        categories: List<Category>,
        learned: Map<String, String>
    ): String? {
        val key = Store.merchantKey(merchant)
        if (key.isNotBlank()) {
            learned[key]?.let { id ->
                if (categories.any { it.id == id }) return id
            }
            // A learned merchant whose name picked up extra words still counts.
            for ((learnedKey, id) in learned) {
                if (learnedKey.length >= 4 && (key.contains(learnedKey) || learnedKey.contains(key))) {
                    if (categories.any { it.id == id }) return id
                }
            }
        }

        val lower = " ${merchant.lowercase()} "
        var best: String? = null
        var bestLength = 0
        for (c in categories) {
            for (word in c.keywords) {
                if (word.isBlank()) continue
                if (lower.contains(word.lowercase()) && word.length > bestLength) {
                    best = c.id
                    bestLength = word.length
                }
            }
        }
        return best
    }
}
