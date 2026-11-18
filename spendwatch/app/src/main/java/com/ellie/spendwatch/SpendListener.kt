package com.ellie.spendwatch

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlin.math.abs

/**
 * Watches incoming notifications for purchase alerts and logs them.
 *
 * Nothing is sent anywhere — the text is read, matched, and either turned into a local
 * transaction or thrown away. Notifications that don't look like purchases never leave
 * this method.
 */
class SpendListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Store.listening(this)) return
        if (sbn.packageName == packageName) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString()
        val body = extras.getCharSequence("android.text")?.toString()
            ?: extras.getCharSequence("android.bigText")?.toString()

        val parsed = Parser.parse(title, body) ?: return

        val now = System.currentTimeMillis()
        if (isDuplicate(parsed, now)) return

        val categories = Store.categories(this)
        val categoryId = Parser.categoryFor(parsed.merchant, categories, Store.learned(this))

        // A refund is stored as a negative amount, so it gives the category its money back.
        val signed = if (parsed.isRefund) -parsed.amountCents else parsed.amountCents

        Store.addTransaction(
            this,
            Txn(
                id = Store.newId(),
                amountCents = signed,
                merchant = parsed.merchant,
                categoryId = if (Store.autoConfirm(this)) categoryId else null,
                timestamp = now,
                period = Store.periodOf(now),
                source = Txn.Source.NOTIFICATION,
                confirmed = categoryId != null && Store.autoConfirm(this)
            )
        )

        SpendWidget.refreshAll(this)
        Alerts.afterCharge(this)
    }

    /**
     * A single purchase often arrives twice — once from the bank, once from the card
     * app. Same amount within two minutes counts as one charge.
     */
    private fun isDuplicate(parsed: Parser.Parsed, now: Long): Boolean {
        val recent = Store.transactions(this).filter { now - it.timestamp < 120_000L }
        val amount = if (parsed.isRefund) -parsed.amountCents else parsed.amountCents
        return recent.any { existing ->
            existing.amountCents == amount &&
                (
                    existing.merchant.equals(parsed.merchant, ignoreCase = true) ||
                        Store.merchantKey(existing.merchant) == Store.merchantKey(parsed.merchant) ||
                        existing.merchant == "Unknown" ||
                        parsed.merchant == "Unknown"
                    )
        } || recent.any { existing ->
            // Same amount, different merchant string, seconds apart: still one charge.
            existing.amountCents == amount && abs(now - existing.timestamp) < 20_000L
        }
    }

    companion object {
        /** Whether the user has granted notification access to this app. */
        fun hasAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val me = ComponentName(context, SpendListener::class.java)
            return enabled.split(":").any {
                val parsed = ComponentName.unflattenFromString(it)
                parsed != null && parsed.packageName == context.packageName
            } || enabled.contains(me.flattenToString())
        }
    }
}
