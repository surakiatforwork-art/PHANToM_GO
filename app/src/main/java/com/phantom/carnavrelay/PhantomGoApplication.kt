package com.phantom.carnavrelay

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * PhantomGoApplication - Application class สำหรับ initialize CrashReporter
 * ต้องประกาศใน AndroidManifest.xml แอตทริบิวต์ android:name
 */
class PhantomGoApplication : Application(), Application.ActivityLifecycleCallbacks {

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val appHandler = Handler(Looper.getMainLooper())
    @Volatile private var reconnectBurstSeq = 0L

    companion object {
        const val TAG = "PHANTOM_GO"
        @Volatile private var startedCount = 0
        @Volatile private var inForeground = false

        fun isInForeground(): Boolean = inForeground
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 PhantomGoApplication onCreate")
        
        // Initialize crash reporter
        CrashReporter.init(this)
        PhantomLog.init(this)
        FirebaseManager.initialize(this)
        restoreDisplayServiceIfNeeded()

        registerActivityLifecycleCallbacks(this)
        registerNetworkReconnectCallback()
        
        Log.d(TAG, "✅ Application initialization complete")
    }

    private fun restoreDisplayServiceIfNeeded() {
        val prefs = PrefsManager(this)
        if (prefs.getDeviceMode() != PrefsManager.MODE_DISPLAY) return
        runCatching {
            ContextCompat.startForegroundService(
                this,
                android.content.Intent(this, DisplayServerService::class.java)
            )
            PhantomLog.i("Display service restore requested after process start")
        }.onFailure { error ->
            Log.w(TAG, "⚠️ Failed to restore Display service", error)
            PhantomLog.e("Display service restore failed", error)
        }
    }

    private fun registerNetworkReconnectCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
                Log.d(TAG, "ℹ️ Network callback auto-reconnect requires API 24+")
                return
            }
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scheduleReconnectBurst("network-available")
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        scheduleReconnectBurst("network-capabilities")
                    }
                }

                override fun onLost(network: Network) {
                    maybeTriggerAutoReconnect("network-lost")
                }
            }
            networkCallback = callback
            cm.registerDefaultNetworkCallback(callback)
            Log.d(TAG, "✅ Network reconnect callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to register network callback", e)
        }
    }

    private fun scheduleReconnectBurst(reason: String) {
        val seq = System.currentTimeMillis()
        reconnectBurstSeq = seq
        val delays = longArrayOf(0L, 1500L, 4000L)
        delays.forEach { delayMs ->
            appHandler.postDelayed({
                if (reconnectBurstSeq != seq) return@postDelayed
                maybeTriggerAutoReconnect("$reason+$delayMs")
            }, delayMs)
        }
    }

    private fun maybeTriggerAutoReconnect(reason: String) {
        try {
            val prefs = PrefsManager(this)
            if (prefs.getDeviceMode() != PrefsManager.MODE_MAIN) return
            if (!prefs.isPaired()) return
            if (!prefs.hasLocalPairingTransport()) return
            if (prefs.getConnectionTransport() == PrefsManager.TRANSPORT_BLUETOOTH) return
            MainSender.triggerAutoReconnect(this, reason)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Auto reconnect trigger failed ($reason)", e)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        startedCount += 1
        inForeground = startedCount > 0
        FirebaseManager.syncDeviceRecord(this, "activity-started")
        if (startedCount == 1) {
            try {
                val intent = android.content.Intent(DisplayServerService.ACTION_APP_FOREGROUND).apply {
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Failed to broadcast APP_FOREGROUND", e)
            }
        }
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        startedCount = (startedCount - 1).coerceAtLeast(0)
        inForeground = startedCount > 0
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
