package com.phantom.carnavrelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Restores the Display foreground service after reboot or app replacement when the device
 * was already operating as a Display or anti-theft was armed.
 */
class DisplayServiceRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PrefsManager(context)
        if (prefs.getDeviceMode() != PrefsManager.MODE_DISPLAY && !prefs.isAntiTheftArmed()) return

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DisplayServerService::class.java)
            )
            PhantomLog.i("Display service restore requested by ${intent.action}")
        }.onFailure {
            PhantomLog.e("Display service restore failed after ${intent.action}", it)
        }
    }
}
