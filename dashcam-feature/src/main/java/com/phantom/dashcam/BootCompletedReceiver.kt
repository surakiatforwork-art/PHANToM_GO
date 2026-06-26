package com.phantom.dashcam

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restores dashcam auto-record after a reboot or app update. If auto-record is enabled we restart
 * the power monitor and, when the device booted while already charging, begin recording right away
 * (subject to permissions and Android 12+ background-launch rules, which the connect flow handles).
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                DashcamStateStore.bootstrap(context)
                if (!DashcamPreferences(context).isAutoRecordOnPowerEnabled()) return
                AutoRecordPowerManager.syncMonitoring(context)
                AutoRecordPowerManager.maybeStartIfChargingAndReady(context, "boot-completed")
            }
        }
    }
}
