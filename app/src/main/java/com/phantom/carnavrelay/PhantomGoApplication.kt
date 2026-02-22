package com.phantom.carnavrelay

import android.app.Activity
import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log

/**
 * PhantomGoApplication - Application class สำหรับ initialize CrashReporter
 * ต้องประกาศใน AndroidManifest.xml แอตทริบิวต์ android:name
 */
class PhantomGoApplication : Application(), Application.ActivityLifecycleCallbacks {

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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

        registerActivityLifecycleCallbacks(this)
        registerNetworkReconnectCallback()
        
        Log.d(TAG, "✅ Application initialization complete")
    }

    private fun registerNetworkReconnectCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
                Log.d(TAG, "ℹ️ Network callback auto-reconnect requires API 24+")
                return
            }
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    maybeTriggerAutoReconnect("network-available")
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        maybeTriggerAutoReconnect("network-capabilities")
                    }
                }

                override fun onLost(network: Network) {
                    maybeTriggerAutoReconnect("network-lost")
                }
            }
            cm.registerDefaultNetworkCallback(networkCallback!!)
            Log.d(TAG, "✅ Network reconnect callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to register network callback", e)
        }
    }

    private fun maybeTriggerAutoReconnect(reason: String) {
        try {
            val prefs = PrefsManager(this)
            if (prefs.getDeviceMode() != PrefsManager.MODE_MAIN) return
            if (!prefs.isPaired()) return
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
