package com.ellie.spendwatch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * The scary part. After every charge the app checks where you stand and, when it's bad,
 * says so out loud.
 *
 * Three channels on purpose: the ambient one is silent and permanent, the warning one is
 * quiet, and the blown one is loud enough to be unpleasant.
 */
object Alerts {

    private const val CHANNEL_STATUS = "status"
    private const val CHANNEL_WARNING = "warning"
    private const val CHANNEL_BLOWN = "blown"

    private const val ID_STATUS = 10
    private const val ID_ALERT = 11

    fun afterCharge(context: Context) {
        createChannels(context)
        updateStatus(context)

        val worst = Store.statuses(context)
            .filter { it.severity != Severity.FINE }
            .maxByOrNull { it.fraction }
            ?: return

        when (worst.severity) {
            Severity.BLOWN -> fire(
                context,
                CHANNEL_BLOWN,
                "${worst.category.name} is BLOWN",
                "Over by ${Money.format(-worst.remainingCents)}. " +
                    "This comes out of next month."
            )

            Severity.CRITICAL -> fire(
                context,
                CHANNEL_WARNING,
                "${worst.category.name}: ${Money.format(worst.remainingCents)} left",
                "That's ${(worst.fraction * 100).toInt()}% gone with " +
                    "${Store.daysLeftInMonth()} days to go."
            )

            Severity.WARNING -> fire(
                context,
                CHANNEL_WARNING,
                "${worst.category.name} is at ${(worst.fraction * 100).toInt()}%",
                "${Money.format(worst.remainingCents)} left for " +
                    "${Store.daysLeftInMonth()} more days."
            )

            Severity.FINE -> Unit
        }
    }

    /** The permanent, silent line in the shade showing what's left overall. */
    fun updateStatus(context: Context) {
        createChannels(context)

        val remaining = Store.totalRemainingCents(context)
        val days = Store.daysLeftInMonth()
        val perDay = if (days > 0) remaining / days else remaining
        val blown = Store.statuses(context).count { it.isOver }

        val title = if (remaining < 0) {
            "${Money.format(-remaining)} OVER BUDGET"
        } else {
            "${Money.format(remaining)} left this month"
        }

        val text = buildString {
            if (remaining >= 0) {
                append(Money.format(perDay))
                append("/day for ")
                append(days)
                append(if (days == 1) " day" else " days")
            } else {
                append("You have ")
                append(days)
                append(if (days == 1) " day" else " days")
                append(" left and nothing to spend")
            }
            if (blown > 0) {
                append("  ·  ")
                append(blown)
                append(if (blown == 1) " category blown" else " categories blown")
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_wallet)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(if (remaining < 0) 0xFFE2574C.toInt() else 0xFF4FA3A5.toInt())
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

        try {
            manager(context)?.notify(ID_STATUS, notification)
        } catch (_: SecurityException) {
        }
    }

    fun clearStatus(context: Context) {
        manager(context)?.cancel(ID_STATUS)
    }

    private fun fire(context: Context, channel: String, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_wallet)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(0xFFE2574C.toInt())
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()

        try {
            manager(context)?.notify(ID_ALERT, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun manager(context: Context) =
        context.getSystemService(NotificationManager::class.java)

    private fun createChannels(context: Context) {
        val m = manager(context) ?: return

        val status = NotificationChannel(
            CHANNEL_STATUS,
            "Running total",
            NotificationManager.IMPORTANCE_LOW
        )
        status.description = "The quiet line showing what's left this month."
        status.setShowBadge(false)
        status.enableVibration(false)
        status.setSound(null, null)

        val warning = NotificationChannel(
            CHANNEL_WARNING,
            "Getting close",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        warning.description = "A nudge when a category is running low."

        val blown = NotificationChannel(
            CHANNEL_BLOWN,
            "Over budget",
            NotificationManager.IMPORTANCE_HIGH
        )
        blown.description = "The loud one, for when a category is blown."
        blown.enableVibration(true)
        blown.vibrationPattern = longArrayOf(0, 250, 120, 250, 120, 400)

        m.createNotificationChannel(status)
        m.createNotificationChannel(warning)
        m.createNotificationChannel(blown)
    }
}
