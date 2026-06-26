package com.phantom.dashcam

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class PowerMonitoringService : Service() {

    private var receiverRegistered = false
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRunning = false

    // Safety-net poll (same idea as the Display power watchdog): if both POWER_* and
    // ACTION_BATTERY_CHANGED are delayed/dropped by an OEM, this still catches the edge.
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!watchdogRunning) return
            AutoRecordPowerManager.observeCurrentPowerState(this@PowerMonitoringService, "power-watchdog")
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val powerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    AutoRecordPowerManager.handleBatteryChanged(
                        context = context,
                        intent = intent,
                        source = "power-monitor-battery",
                    )
                }

                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> {
                    AutoRecordPowerManager.handlePowerBroadcast(
                        context = context,
                        action = intent.action.orEmpty(),
                        source = "power-monitor-broadcast",
                    )
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DashcamNotificationHelper.ensureChannels(this)
        registerPowerReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITOR) {
            stopWatchdog()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!DashcamPreferences(this).isAutoRecordOnPowerEnabled()) {
            stopWatchdog()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        promoteToForeground()
        AutoRecordPowerManager.reconcileCurrentPowerState(
            context = this,
            source = "power-monitor-start",
        )
        startWatchdog()
        return START_STICKY
    }

    private fun startWatchdog() {
        if (watchdogRunning) return
        watchdogRunning = true
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        watchdogRunning = false
        watchdogHandler.removeCallbacks(watchdogRunnable)
    }

    override fun onDestroy() {
        stopWatchdog()
        if (receiverRegistered) {
            runCatching {
                unregisterReceiver(powerStateReceiver)
            }.onFailure { error ->
                Log.w(TAG, "Failed to unregister power receiver", error)
            }
            receiverRegistered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerPowerReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        ContextCompat.registerReceiver(
            this,
            powerStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun promoteToForeground() {
        val notification = DashcamNotificationHelper.buildPowerMonitorNotification(this)
        ServiceCompat.startForeground(
            this,
            DashcamNotificationHelper.POWER_MONITOR_NOTIFICATION_ID,
            notification,
            monitorForegroundServiceType,
        )
    }

    companion object {
        private const val TAG = "GO_CAM_POWER_MONITOR"
        private const val WATCHDOG_INTERVAL_MS = 2_000L

        const val ACTION_START_MONITOR = "com.phantom.dashcam.action.START_POWER_MONITOR"
        const val ACTION_STOP_MONITOR = "com.phantom.dashcam.action.STOP_POWER_MONITOR"

        fun createStartIntent(context: Context): Intent {
            return Intent(context, PowerMonitoringService::class.java).apply {
                action = ACTION_START_MONITOR
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, PowerMonitoringService::class.java).apply {
                action = ACTION_STOP_MONITOR
            }
        }

        private val monitorForegroundServiceType: Int
            get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
    }
}
