package com.phantom.dashcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
class PowerConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        DashcamStateStore.bootstrap(context)

        val prefs = DashcamPreferences(context)
        if (!prefs.isAutoRecordOnPowerEnabled()) {
            return
        }

        AutoRecordPowerManager.handlePowerBroadcast(
            context = context,
            action = intent.action.orEmpty(),
            source = "manifest-receiver",
        )
        AutoRecordPowerManager.syncMonitoring(context)
    }
}
