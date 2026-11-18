package com.ellie.spendwatch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One screen, rebuilt from the store every time it comes forward. The app is small
 * enough that re-laying-out is cheaper to reason about than keeping adapters in sync.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_SpendWatch)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)
        askForNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        render()
        Alerts.updateStatus(this)
        SpendWidget.refreshAll(this)
    }

    private fun refresh() {
        render()
        Alerts.updateStatus(this)
        SpendWidget.refreshAll(this)
    }

    // --- Screen ---------------------------------------------------------------

    private fun render() {
        container.removeAllViews()

        addHeader()
        if (!SpendListener.hasAccess(this)) addAccessPrompt()
        addReviewQueue()
        addCategories()
        addQuickActions()
        addRecent()
        addSettings()
    }

    private fun addHeader() {
        val remaining = Store.totalRemainingCents(this)
        val spent = Store.totalSpentCents(this)
        val budget = Store.totalBudgetCents(this)
        val days = Store.daysLeftInMonth()
        val blown = Store.statuses(this).count { it.isOver }

        val month = DateTimeFormatter.ofPattern("MMMM").format(YearMonth.now())
        container.addView(
            Ui.text(this, month.uppercase(), 11f, Ui.color(this, R.color.ink_dim), bold = true)
                .also { it.letterSpacing = 0.14f }
        )

        val big = if (remaining < 0) "−${Money.format(-remaining)}" else Money.format(remaining)
        val bigColor = if (remaining < 0) {
            Ui.color(this, R.color.blown)
        } else {
            Ui.color(this, R.color.ink)
        }
        container.addView(Ui.text(this, big, 46f, bigColor, bold = true, topMargin = 2))

        val sub = if (remaining < 0) {
            "over budget with $days ${dayWord(days)} still to go"
        } else {
            val perDay = if (days > 0) remaining / days else remaining
            "${Money.format(perDay)} a day for $days more ${dayWord(days)}"
        }
        container.addView(
            Ui.text(this, sub, 14f, Ui.color(this, R.color.ink_dim), topMargin = 2)
        )

        container.addView(
            Ui.text(
                this,
                "${Money.format(spent)} spent of ${Money.format(budget)}",
                13f,
                Ui.color(this, R.color.ink_dim),
                topMargin = 10
            )
        )

        if (blown > 0) {
            container.addView(
                Ui.text(
                    this,
                    "$blown ${if (blown == 1) "category is" else "categories are"} blown. " +
                        "That debt lands on next month.",
                    13f,
                    Ui.color(this, R.color.blown),
                    bold = true,
                    topMargin = 8
                )
            )
        }
    }

    private fun addAccessPrompt() {
        val card = Ui.card(this)
        card.addView(
            Ui.text(
                this,
                "Auto-logging is off",
                16f,
                Ui.color(this, R.color.warning),
                bold = true
            )
        )
        card.addView(
            Ui.text(
                this,
                "SpendWatch needs permission to read your notifications so it can catch " +
                    "charges as they happen. Nothing is uploaded; the text is read on this " +
                    "phone and thrown away.",
                13f,
                Ui.color(this, R.color.ink_dim),
                topMargin = 6
            )
        )
        card.addView(
            Ui.button(this, "Grant notification access") {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                    toast("Open Settings → Notifications → Device & app notifications")
                }
            }
        )
        container.addView(card)
    }

    private fun addReviewQueue() {
        val queue = Store.reviewQueue(this)
        if (queue.isEmpty()) return

        container.addView(Ui.heading(this, "Needs a category"))
        val categories = Store.categories(this)

        for (txn in queue) {
            val card = Ui.card(this)
            val head = Ui.row(this)
            head.addView(Ui.grow(Ui.text(this, txn.merchant, 15f, bold = true)))
            head.addView(
                Ui.text(
                    this,
                    Money.format(txn.amountCents),
                    15f,
                    Ui.color(this, R.color.ink),
                    bold = true,
                    mono = true
                )
            )
            card.addView(head)
            card.addView(
                Ui.text(this, timeOf(txn.timestamp), 12f, Ui.color(this, R.color.ink_dim))
            )

            val chips = Ui.wrapRow(this)
            var line = Ui.row(this)
            var count = 0
            for (c in categories) {
                if (count == 2) {
                    chips.addView(line)
                    line = Ui.row(this)
                    count = 0
                }
                line.addView(
                    Ui.chip(this, c.name, Ui.color(this, R.color.accent)) {
                        Store.categorize(this, txn, c.id)
                        toast("Filed under ${c.name}. Future ${txn.merchant} charges go here too.")
                        refresh()
                    }
                )
                count++
            }
            if (count > 0) chips.addView(line)

            val skip = Ui.row(this)
            skip.addView(
                Ui.chip(this, "Not a purchase", Ui.color(this, R.color.blown)) {
                    Store.deleteTransaction(this, txn.id)
                    refresh()
                }
            )
            chips.addView(skip)
            card.addView(chips)
            container.addView(card)
        }
    }

    private fun addCategories() {
        container.addView(Ui.heading(this, "This month"))

        for (status in Store.statuses(this)) {
            val card = Ui.card(this)
            val color = SpendWidget.colorFor(status.severity)

            val head = Ui.row(this)
            head.addView(Ui.grow(Ui.text(this, status.category.name, 15f, bold = true)))
            val right = if (status.isOver) {
                "over by ${Money.format(-status.remainingCents)}"
            } else {
                "${Money.format(status.remainingCents)} left"
            }
            head.addView(Ui.text(this, right, 14f, color, bold = true, mono = true))
            card.addView(head)

            card.addView(Ui.bar(this, (status.fraction * 100f).toInt(), color))

            val detail = buildString {
                append(Money.format(status.spentCents))
                append(" of ")
                append(Money.format(status.category.effectiveBudgetCents))
                if (status.category.carryoverCents < 0L) {
                    append("  (")
                    append(Money.format(status.category.budgetCents))
                    append(" minus ")
                    append(Money.format(-status.category.carryoverCents))
                    append(" carried over)")
                } else if (status.category.carryoverCents > 0L) {
                    append("  (includes ")
                    append(Money.format(status.category.carryoverCents))
                    append(" rolled forward)")
                }
            }
            card.addView(
                Ui.text(this, detail, 12f, Ui.color(this, R.color.ink_dim), topMargin = 7)
            )

            if (status.severity == Severity.BLOWN) {
                card.addView(
                    Ui.text(
                        this,
                        "Next month this category starts at " +
                            "${Money.format(status.category.budgetCents + status.remainingCents)}.",
                        12f,
                        Ui.color(this, R.color.blown),
                        bold = true,
                        topMargin = 5
                    )
                )
            }

            card.setOnClickListener { showCategoryMenu(status) }
            container.addView(card)
        }
    }

    private fun addQuickActions() {
        container.addView(Ui.button(this, "Add a purchase by hand") { showAddDialog(null) })
    }

    private fun addRecent() {
        val recent = Store.thisMonth(this).filter { !it.needsCategory }.take(12)
        if (recent.isEmpty()) return

        container.addView(Ui.heading(this, "Recent"))
        val categories = Store.categories(this).associateBy { it.id }

        for (txn in recent) {
            val row = Ui.row(this)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = Ui.dp(this, 12)
            row.layoutParams = params

            val left = Ui.column(this)
            left.addView(Ui.text(this, txn.merchant, 14f))
            val categoryName = categories[txn.categoryId]?.name ?: "Uncategorized"
            val tag = if (txn.source == Txn.Source.NOTIFICATION) "auto" else "manual"
            left.addView(
                Ui.text(
                    this,
                    "$categoryName · ${timeOf(txn.timestamp)} · $tag",
                    11f,
                    Ui.color(this, R.color.ink_dim)
                )
            )
            Ui.grow(left)
            row.addView(left)

            val amountColor = if (txn.amountCents < 0L) {
                Ui.color(this, R.color.fine)
            } else {
                Ui.color(this, R.color.ink)
            }
            val amountText = if (txn.amountCents < 0L) {
                "+${Money.format(-txn.amountCents)}"
            } else {
                Money.format(txn.amountCents)
            }
            row.addView(Ui.text(this, amountText, 14f, amountColor, mono = true))

            row.setOnClickListener { showTxnMenu(txn) }
            container.addView(row)
        }
    }

    private fun addSettings() {
        container.addView(Ui.heading(this, "Settings"))

        container.addView(
            Ui.toggle(this, "Read charges from notifications", Store.listening(this)) {
                Store.setListening(this, it)
            }
        )
        container.addView(
            Ui.toggle(
                this,
                "File known merchants automatically",
                Store.autoConfirm(this)
            ) {
                Store.setAutoConfirm(this, it)
            }
        )
        container.addView(
            Ui.toggle(
                this,
                "Roll unspent money forward too",
                Store.carrySurplus(this)
            ) {
                Store.setCarrySurplus(this, it)
                refresh()
            }
        )

        container.addView(
            Ui.text(
                this,
                "Overspending always carries into next month. This adds the opposite: " +
                    "a good month gives you extra later.",
                12f,
                Ui.color(this, R.color.ink_dim),
                topMargin = 6
            )
        )

        container.addView(
            Ui.button(this, "Notification access settings") {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                    toast("Couldn't open that settings page")
                }
            }
        )
        container.addView(
            Ui.button(this, "Forget a learned merchant") { showForgetDialog() }
        )
    }

    // --- Dialogs --------------------------------------------------------------

    private fun showAddDialog(presetCategoryId: String?) {
        val categories = Store.categories(this)
        if (categories.isEmpty()) return

        val wrapper = Ui.column(this, 20)

        val amount = EditText(this)
        amount.hint = "0.00"
        amount.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        amount.textSize = 28f
        amount.gravity = Gravity.CENTER
        wrapper.addView(amount)

        val merchant = EditText(this)
        merchant.hint = "Where (optional)"
        merchant.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        wrapper.addView(merchant)

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            categories.map { it.name }
        )
        val preset = categories.indexOfFirst { it.id == presetCategoryId }
        if (preset >= 0) spinner.setSelection(preset)
        wrapper.addView(spinner)

        AlertDialog.Builder(this)
            .setTitle("Add a purchase")
            .setView(wrapper)
            .setPositiveButton("Add") { _, _ ->
                val cents = Money.parse(amount.text.toString())
                if (cents == null || cents <= 0L) {
                    toast("That amount didn't look like a number")
                    return@setPositiveButton
                }
                val category = categories[spinner.selectedItemPosition]
                val where = merchant.text.toString().trim().ifBlank { category.name }
                val now = System.currentTimeMillis()
                Store.addTransaction(
                    this,
                    Txn(
                        id = Store.newId(),
                        amountCents = cents,
                        merchant = where,
                        categoryId = category.id,
                        timestamp = now,
                        period = Store.periodOf(now),
                        source = Txn.Source.MANUAL
                    )
                )
                Alerts.afterCharge(this)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCategoryMenu(status: CategoryStatus) {
        val options = arrayOf(
            "Add a purchase here",
            "Change the monthly budget",
            "Edit the merchant keywords",
            "Clear the carried-over debt"
        )
        AlertDialog.Builder(this)
            .setTitle(status.category.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAddDialog(status.category.id)
                    1 -> showBudgetDialog(status.category)
                    2 -> showKeywordDialog(status.category)
                    3 -> {
                        updateCategory(status.category.copy(carryoverCents = 0L))
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun showBudgetDialog(category: Category) {
        val field = EditText(this)
        field.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        field.setText(
            (category.budgetCents / 100).toString() +
                "." + (category.budgetCents % 100).toString().padStart(2, '0')
        )
        AlertDialog.Builder(this)
            .setTitle("${category.name} budget")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                val cents = Money.parse(field.text.toString())
                if (cents == null) {
                    toast("That didn't look like a number")
                    return@setPositiveButton
                }
                updateCategory(category.copy(budgetCents = cents))
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showKeywordDialog(category: Category) {
        val field = EditText(this)
        field.setText(category.keywords.joinToString(", "))
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        field.minLines = 3

        AlertDialog.Builder(this)
            .setTitle("${category.name} keywords")
            .setMessage("Charges whose merchant contains any of these land here. Separate with commas.")
            .setView(field)
            .setPositiveButton("Save") { _, _ ->
                val words = field.text.toString()
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
                updateCategory(category.copy(keywords = words))
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTxnMenu(txn: Txn) {
        val categories = Store.categories(this)
        val options = categories.map { "Move to ${it.name}" } + "Delete this charge"

        AlertDialog.Builder(this)
            .setTitle("${txn.merchant} · ${Money.format(txn.amountCents)}")
            .setItems(options.toTypedArray()) { _, which ->
                if (which < categories.size) {
                    Store.categorize(this, txn, categories[which].id)
                } else {
                    Store.deleteTransaction(this, txn.id)
                }
                refresh()
            }
            .show()
    }

    private fun showForgetDialog() {
        val learned = Store.learned(this)
        if (learned.isEmpty()) {
            toast("Nothing learned yet")
            return
        }
        val categories = Store.categories(this).associateBy { it.id }
        val keys = learned.keys.toList()
        val labels = keys.map { key ->
            "$key → ${categories[learned[key]]?.name ?: "?"}"
        }
        AlertDialog.Builder(this)
            .setTitle("Forget which merchant?")
            .setItems(labels.toTypedArray()) { _, which ->
                Store.forgetMerchant(this, keys[which])
                toast("Forgot ${keys[which]}")
            }
            .show()
    }

    // --- Helpers --------------------------------------------------------------

    private fun updateCategory(updated: Category) {
        val all = Store.categories(this).map { if (it.id == updated.id) updated else it }
        Store.saveCategories(this, all)
    }

    private fun timeOf(millis: Long): String = DateTimeFormatter
        .ofPattern("MMM d, h:mm a")
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    private fun dayWord(days: Int) = if (days == 1) "day" else "days"

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1
            )
        }
    }
}
