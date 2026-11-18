package com.ellie.spendwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Puts the running-total line and the widget back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alerts.updateStatus(context)
        SpendWidget.refreshAll(context)
    }
}
