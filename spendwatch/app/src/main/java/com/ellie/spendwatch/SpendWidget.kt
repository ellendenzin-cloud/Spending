package com.ellie.spendwatch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.widget.RemoteViews

/**
 * The home screen widget: what's left overall, then a bar per category that changes color
 * as the month gets worse.
 *
 * Six fixed rows rather than a collection widget — the category list is short and this
 * needs no extra service to stay in sync.
 */
class SpendWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val views = build(context)
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {

        private const val ROWS = 6

        private val ROW_IDS = intArrayOf(
            R.id.row_0, R.id.row_1, R.id.row_2, R.id.row_3, R.id.row_4, R.id.row_5
        )
        private val NAME_IDS = intArrayOf(
            R.id.name_0, R.id.name_1, R.id.name_2, R.id.name_3, R.id.name_4, R.id.name_5
        )
        private val LEFT_IDS = intArrayOf(
            R.id.left_0, R.id.left_1, R.id.left_2, R.id.left_3, R.id.left_4, R.id.left_5
        )
        private val BAR_IDS = intArrayOf(
            R.id.bar_0, R.id.bar_1, R.id.bar_2, R.id.bar_3, R.id.bar_4, R.id.bar_5
        )

        const val FINE = 0xFF7FB069.toInt()
        const val WARNING = 0xFFE9C46A.toInt()
        const val CRITICAL = 0xFFEE8C5A.toInt()
        const val BLOWN = 0xFFE2574C.toInt()
        private const val TEXT = 0xFFF0EDEA.toInt()
        private const val DIM = 0xA6F0EDEA.toInt()

        fun colorFor(severity: Severity): Int = when (severity) {
            Severity.FINE -> FINE
            Severity.WARNING -> WARNING
            Severity.CRITICAL -> CRITICAL
            Severity.BLOWN -> BLOWN
        }

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SpendWidget::class.java))
            if (ids.isEmpty()) return
            val views = build(context)
            for (id in ids) {
                manager.updateAppWidget(id, views)
            }
        }

        private fun build(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_spend)

            val statuses = Store.statuses(context)
            val remaining = Store.totalRemainingCents(context)
            val days = Store.daysLeftInMonth()
            val review = Store.reviewQueue(context).size

            // --- Header ---
            if (remaining < 0) {
                views.setTextViewText(R.id.total_left, "−${Money.format(-remaining)}")
                views.setTextColor(R.id.total_left, BLOWN)
                views.setTextViewText(
                    R.id.total_sub,
                    "OVER BUDGET · $days ${dayWord(days)} still to go"
                )
                views.setTextColor(R.id.total_sub, BLOWN)
            } else {
                val perDay = if (days > 0) remaining / days else remaining
                views.setTextViewText(R.id.total_left, Money.format(remaining))
                views.setTextColor(R.id.total_left, TEXT)
                views.setTextViewText(
                    R.id.total_sub,
                    "${Money.format(perDay)}/day for $days ${dayWord(days)}"
                )
                views.setTextColor(R.id.total_sub, DIM)
            }

            if (review > 0) {
                views.setViewVisibility(R.id.review_badge, View.VISIBLE)
                views.setTextViewText(R.id.review_badge, "$review to file")
            } else {
                views.setViewVisibility(R.id.review_badge, View.GONE)
            }

            // --- Category rows ---
            for (i in 0 until ROWS) {
                if (i >= statuses.size) {
                    views.setViewVisibility(ROW_IDS[i], View.GONE)
                    continue
                }
                val status = statuses[i]
                views.setViewVisibility(ROW_IDS[i], View.VISIBLE)
                views.setTextViewText(NAME_IDS[i], status.category.name)

                val color = colorFor(status.severity)

                if (status.isOver) {
                    views.setTextViewText(LEFT_IDS[i], "over ${Money.short(-status.remainingCents)}")
                } else {
                    views.setTextViewText(LEFT_IDS[i], Money.short(status.remainingCents))
                }
                views.setTextColor(LEFT_IDS[i], color)
                views.setTextColor(NAME_IDS[i], if (status.isOver) color else TEXT)

                val progress = (status.fraction * 100f).toInt().coerceIn(0, 100)
                views.setProgressBar(BAR_IDS[i], 100, progress, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    views.setColorStateList(
                        BAR_IDS[i],
                        "setProgressTintList",
                        ColorStateList.valueOf(color)
                    )
                }
            }

            views.setViewVisibility(
                R.id.empty_hint,
                if (statuses.isEmpty()) View.VISIBLE else View.GONE
            )

            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }

        private fun dayWord(days: Int) = if (days == 1) "day" else "days"
    }
}
