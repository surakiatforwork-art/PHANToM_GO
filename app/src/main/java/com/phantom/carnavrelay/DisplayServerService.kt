package com.phantom.carnavrelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.phantom.carnavrelay.receiver.OpenMapsReceiver

class DisplayServerService : Service() {

    companion object {
        private const val TAG = "PHANTOM_GO"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "phantom_go_display"
        private const val ACTION_OPEN_MAPS = "com.phantom.carnavrelay.OPEN_MAPS"
        private const val ACTION_STOP_SERVER = "com.phantom.carnavrelay.STOP_SERVER"
        const val ACTION_NAVIGATION_RECEIVED = "com.phantom.carnavrelay.NAVIGATION_RECEIVED"
        const val ACTION_APP_FOREGROUND = "com.phantom.carnavrelay.APP_FOREGROUND"
        private const val ROUTE_UPDATE_WINDOW_MS = 2 * 60 * 60 * 1000L
        
        // Wake lock
        private const val WAKE_LOCK_TAG = "PHANTOM_GO:DisplayServer"
        private var wakeLock: PowerManager.WakeLock? = null
    }

    private var httpServer: HttpServer? = null
    private lateinit var prefsManager: PrefsManager
    private var overlayController: OverlayController? = null
    private var lastUrl: String? = null
    private var lastCmdAt: Long = 0
    private var foregroundStarted = false
    @Volatile private var resolveToken: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val appStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_APP_FOREGROUND) {
                Log.d(TAG, "📱 App foregrounded - hiding overlay")
                overlayController?.hideOverlay()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "▶️ DisplayServerService.onCreate()")
        prefsManager = PrefsManager(this)
        createNotificationChannel()
        if (!ensureForegroundStarted()) {
            stopSelf()
            return
        }
        prefsManager.setDeviceMode(PrefsManager.MODE_DISPLAY)
        FirebaseManager.ensureSignedInAndSync(this, "display-service-create")
        overlayController = OverlayController(this)
        acquireWakeLock()

        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                appStateReceiver,
                android.content.IntentFilter(ACTION_APP_FOREGROUND),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to register app state receiver", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "▶️ DisplayServerService.onStartCommand()")
        val navigationUrl = if (intent?.action == ACTION_NAVIGATION_RECEIVED) intent.getStringExtra("url") else null
        val navigationCommandId = if (intent?.action == ACTION_NAVIGATION_RECEIVED) intent.getStringExtra("commandId") else null

        // Handle stop action
        if (intent?.action == ACTION_STOP_SERVER) {
            Log.d(TAG, "🛑 Stop server action received")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ensureForegroundStarted()) {
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "ℹ️ Bluetooth bridge disabled (Wi‑Fi only mode)")

        // Start HTTP server
        try {
            if (httpServer == null || !httpServer!!.isServerRunning()) {
                httpServer = HttpServer(this, prefsManager)
                httpServer?.start()
                Log.d(TAG, "✅ HTTP Server started successfully")
            } else {
                Log.d(TAG, "ℹ️ HTTP Server already running")
                updateNotification()
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to start HTTP Server", e)
            CrashReporter.recordException(this, "DisplayServerService:startHttpServer", e)
            stopSelf()
            return START_NOT_STICKY
        }

        FirebaseManager.startDisplayCommandListener(this, "display-service-start-command")

        if (!navigationUrl.isNullOrEmpty()) {
            onNavigationReceived(navigationUrl, navigationCommandId)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        Log.d(TAG, "💀 DisplayServerService.onDestroy()")
        prefsManager.setDeviceMode(PrefsManager.MODE_MAIN)
        FirebaseManager.syncDeviceRecord(this, "display-service-destroy")
        FirebaseManager.stopDisplayCommandListener()
        
        // Cleanup overlay
        overlayController?.cleanup()
        try {
            unregisterReceiver(appStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to unregister app state receiver", e)
        }
        
        // Cleanup HTTP server
        try {
            httpServer?.stop()
            httpServer = null
            Log.d(TAG, "🛑 HTTP Server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error stopping HTTP Server", e)
        }

        Log.d(TAG, "ℹ️ Bluetooth bridge stop skipped (Wi‑Fi only mode)")
        
        // Release wake lock
        releaseWakeLock()
        
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Display Mode"
            val descriptionText = "HTTP Server running for Display mode"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun ensureForegroundStarted(): Boolean {
        if (foregroundStarted) {
            return true
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
            foregroundStarted = true
            Log.d(TAG, "✅ Foreground service started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to start foreground service", e)
            false
        }
    }

    private fun startBluetoothBridgeService() {
        Log.d(TAG, "ℹ️ startBluetoothBridgeService() no-op (disabled)")
    }

    private fun stopBluetoothBridgeService() {
        Log.d(TAG, "ℹ️ stopBluetoothBridgeService() no-op (disabled)")
    }

    private fun createNotification(): Notification {
        val ip = httpServer?.getLocalIpAddress() ?: "0.0.0.0"
        val port = prefsManager.getServerPort()
        val tokenHint = prefsManager.getTokenHint(prefsManager.getServerToken())

        // Main notification intent
        val mainIntent = Intent(this, DisplayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Open Maps action
        val openMapsIntent = Intent(this, OpenMapsReceiver::class.java).apply {
            action = ACTION_OPEN_MAPS
            lastUrl?.let { putExtra(OpenMapsReceiver.EXTRA_URL, it) }
        }
        val openMapsPendingIntent = PendingIntent.getBroadcast(
            this, 1, openMapsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop server action
        val stopIntent = Intent(this, DisplayServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PHANToM GO - Display Mode")
            .setContentText("Server: $ip:$port | Token: $tokenHint")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_compass,
                "Open PHANToM GO",
                mainPendingIntent
            )

        // Add Open Maps action if there's a recent URL
        if (!lastUrl.isNullOrEmpty() && (System.currentTimeMillis() - lastCmdAt) < 300000) { // 5 minutes
            builder.addAction(
                android.R.drawable.ic_menu_directions,
                "Open Maps",
                openMapsPendingIntent
            )
        }

        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop Server",
            stopPendingIntent
        )

        return builder.build()
    }

    fun updateNotification() {
        try {
            val notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error updating notification", e)
        }
    }

    fun onNavigationReceived(url: String, commandId: String? = null) {
        Log.d(TAG, "📡 Navigation received: $url")
        resolveForDisplayAsync(url, commandId)
    }

    private fun resolveForDisplayAsync(rawUrl: String, commandId: String? = null) {
        if (!NavLinkUtils.isShortMapsLink(rawUrl)) {
            handleNavigationReady(rawUrl, rawUrl, commandId)
            return
        }

        val token = System.currentTimeMillis()
        resolveToken = token
        Thread {
            val resolvedUrl = NavLinkUtils.resolveShortLinkIfNeeded(prefsManager, rawUrl)
            mainHandler.post {
                if (resolveToken != token) {
                    Log.d(TAG, "⏭️ Skipping stale resolve result")
                    return@post
                }
                handleNavigationReady(rawUrl, resolvedUrl, commandId)
            }
        }.start()
    }

    private fun handleNavigationReady(rawUrl: String, resolvedUrl: String, commandId: String? = null) {
        val finalUrl = if (resolvedUrl.isBlank()) rawUrl else resolvedUrl
        val previousUrl = lastUrl
        val previousCmdAt = lastCmdAt
        val now = System.currentTimeMillis()
        val isRouteUpdate = isLikelyRouteUpdate(previousUrl, previousCmdAt, finalUrl, now)
        if (finalUrl != rawUrl) {
            Log.d(TAG, "🔁 Display resolved short link: $rawUrl -> $finalUrl")
            PhantomLog.i("NAV display resolved: $rawUrl -> $finalUrl")
        }

        lastUrl = finalUrl
        lastCmdAt = now
        if (!commandId.isNullOrBlank()) {
            FirebaseManager.markCloudCommandOpened(this, commandId, finalUrl)
        }

        // Save to prefs for diagnostics
        val prefs = getSharedPreferences("phantom_go_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putLong("last_cmd_at", lastCmdAt)
            putString("last_url", finalUrl)
            apply()
        }

        // Show navigation notification with Open Navigation action
        showNavigationNotification(finalUrl, allowFullScreenIntent = false)

        // Check if app is in foreground
        if (PhantomGoApplication.isInForeground()) {
            Log.d(TAG, "📱 App in foreground, opening Maps directly")
            openMapsDirectly(finalUrl)
        } else {
            Log.d(TAG, "📱 App in background, opening Maps directly")
            openMapsDirectly(finalUrl)

            // In active navigation updates, avoid overlay focus churn so Google Maps reroute popup stays visible.
            if (isRouteUpdate) {
                Log.d(TAG, "🛣️ Route update detected - skipping overlay to preserve Google Maps confirmation popup")
                PhantomLog.i("NAV routeUpdate overlaySkipped")
                return
            }

            // Try to show overlay if enabled and permission granted
            overlayController?.let { controller ->
                if (controller.isOverlayEnabled() && controller.hasOverlayPermission()) {
                    Log.d(TAG, "🎯 Showing overlay widget")
                    controller.showNavigationOverlay(finalUrl)
                } else {
                    Log.d(TAG, "🚫 Overlay disabled or no permission, using notification only")
                    if (!controller.hasOverlayPermission()) {
                        Log.w(TAG, "⚠️ Overlay permission not granted")
                    }
                    if (!controller.isOverlayEnabled()) {
                        Log.d(TAG, "ℹ️ Overlay feature disabled in settings")
                    }
                }
            } ?: run {
                Log.w(TAG, "⚠️ Overlay controller not initialized")
            }
        }
    }

    private fun isLikelyRouteUpdate(previousUrl: String?, previousCmdAtMs: Long, nextUrl: String, nowMs: Long): Boolean {
        if (previousUrl.isNullOrBlank()) return false
        if (previousCmdAtMs <= 0L) return false
        if ((nowMs - previousCmdAtMs) > ROUTE_UPDATE_WINDOW_MS) return false
        return previousUrl != nextUrl
    }
    
    private fun openMapsDirectly(url: String) {
        try {
            val openBehavior = prefsManager.getDisplayOpenBehavior()
            val openUrl = NavLinkUtils.toDisplayOpenUrl(
                url,
                prefsManager.getDisplayNavMode(),
                openBehavior
            )
            val mapsIntent = Intent(Intent.ACTION_VIEW, Uri.parse(openUrl)).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }

            startActivity(mapsIntent)
            Log.d(TAG, "✅ Opened Google Maps directly")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to open Maps directly", e)
            // Fallback to preview URL without forcing navigation
            try {
                val previewUrl = NavLinkUtils.toDirectionsPreviewUrl(url, prefsManager.getDisplayNavMode())
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(previewUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallbackIntent)
                Log.d(TAG, "✅ Opened with fallback preview URL")
            } catch (e2: Exception) {
                Log.e(TAG, "💥 Failed to open fallback preview", e2)
                // Fallback to notification
                showNavigationNotification(url)
            }
        }
    }
    
    private fun showNavigationNotification(url: String, allowFullScreenIntent: Boolean = true) {
        try {
            val notificationManager = ContextCompat.getSystemService(this, NotificationManager::class.java)
            
            // Create a high-priority notification for navigation
            val openNavIntent = Intent(this, OpenNavigationActivity::class.java).apply {
                putExtra("url", url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val openNavPendingIntent = PendingIntent.getActivity(
                this, 2001, openNavIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🗺️ Navigation Received")
                .setContentText("Tap to open Maps")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setAutoCancel(true)
                .addAction(
                    android.R.drawable.ic_menu_directions,
                    "Open Navigation",
                    openNavPendingIntent
                )
            if (allowFullScreenIntent) {
                builder.setFullScreenIntent(openNavPendingIntent, true)
            }
            val notification = builder.build()
            
            notificationManager?.notify(NOTIFICATION_ID + 1, notification)
            Log.d(TAG, "✅ Navigation notification shown")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to show navigation notification", e)
        }
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(10*60*1000L) // 10 minutes
            }
            Log.d(TAG, "✅ Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to acquire wake lock", e)
        }
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "✅ Wake lock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error releasing wake lock", e)
        }
    }
}
