package com.phantom.dashcam

import android.content.Context
import android.content.Intent
import android.content.IntentFilter

object PowerStateUtils {

    fun isCharging(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return isPluggedIn(intent)
    }

    fun isPluggedIn(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return isPluggedIn(intent)
    }

    fun isPluggedIn(intent: Intent): Boolean {
        return intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
}
