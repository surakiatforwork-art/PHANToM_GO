package com.phantom.carnavrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives power changes independently from DisplayServerService so display automation
 * continues to work if an OEM temporarily stops the foreground service.
 */
class DisplayPowerEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED -> {
                DisplayPowerAutomationManager.handlePowerEvent(
                    context = context,
                    action = intent.action.orEmpty(),
                    source = "manifest-receiver"
                )
            }
        }
    }
}
