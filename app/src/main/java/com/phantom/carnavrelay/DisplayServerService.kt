package com.phantom.carnavrelay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import kotlin.math.abs

class DisplayServerService : Service() {

    private data class PendingNavigationLaunch(
        val url: String,
        val commandId: String?,
        val isRouteUpdate: Boolean
    )

    companion object {
        private const val TAG = "PHANTOM_GO"

        // When true, while Display mode is active the app feeds power events to the dashcam and
        // the dashcam skips its own PowerMonitoringService (avoids a duplicate FGS/receiver/poll).
        // Flip to false to fully revert: the dashcam runs its own independent power monitor again.
        private const val DEDUPE_POWER_MONITORING = true

        private const val NOTIFICATION_ID = 2001

        // Ongoing "car system" status (Display + dashcam) — quiet, grouped together, same section.
        private const val CHANNEL_STATUS = "phantom_go_display_status"
        private val GROUP_STATUS = com.phantom.dashcam.DashcamNotificationHelper.STATUS_GROUP
        // Navigation-received alerts live in their own channel/group, separate from the status row.
        private const val CHANNEL_NAV = "phantom_go_nav"
        private const val GROUP_NAV = "phantom_go_nav_group"
        private const val NAV_NOTIFICATION_ID = 2002
        private const val ACTION_OPEN_MAPS = "com.phantom.carnavrelay.OPEN_MAPS"
        const val ACTION_STOP_SERVER = "com.phantom.carnavrelay.STOP_SERVER"
        private const val ACTION_REFRESH_SPEEDOMETER = "com.phantom.carnavrelay.REFRESH_SPEEDOMETER"
        const val ACTION_NAVIGATION_RECEIVED = "com.phantom.carnavrelay.NAVIGATION_RECEIVED"
        const val ACTION_APP_FOREGROUND = "com.phantom.carnavrelay.APP_FOREGROUND"
        const val ACTION_SPEED_UPDATED = "com.phantom.carnavrelay.SPEED_UPDATED"
        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val EXTRA_SPEED_HAS_FIX = "speed_has_fix"
        const val EXTRA_SPEED_UPDATED_AT = "speed_updated_at"
        const val EXTRA_SPEED_PROVIDER = "speed_provider"
        const val EXTRA_SPEED_ACCURACY = "speed_accuracy"
        const val EXTRA_SPEED_SOURCE = "speed_source"
        private const val ROUTE_UPDATE_WINDOW_MS = 2 * 60 * 60 * 1000L
        private const val DISPLAY_LOCATION_SYNC_INTERVAL_MS = 15_000L
        private const val DISPLAY_LOCATION_MIN_DISTANCE_METERS = 35f
        @Volatile private var explicitStopRequested = false
        
        // Wake lock (CPU) — keeps the command listener / power watchdog alive while screen is off.
        private const val WAKE_LOCK_TAG = "PHANTOM_GO:DisplayServer"
        private var wakeLock: PowerManager.WakeLock? = null

        // Screen wake lock — keeps the display ON while Display mode is active AND external power is
        // connected, even when the user is in another app (e.g. Google Maps). Tied to charging so it
        // does not fight the lock-on-power-disconnect flow or drain the battery when unplugged.
        private const val SCREEN_WAKE_LOCK_TAG = "PHANTOM_GO:DisplayScreenOn"
        private var screenWakeLock: PowerManager.WakeLock? = null

        fun requestSpeedometerRefresh(context: Context) {
            val intent = Intent(context, DisplayServerService::class.java).apply {
                action = ACTION_REFRESH_SPEEDOMETER
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun requestStop(context: Context, reason: String) {
            val appContext = context.applicationContext
            explicitStopRequested = true
            PrefsManager(appContext).setDeviceMode(PrefsManager.MODE_MAIN)
            FirebaseManager.syncDeviceRecord(appContext, "display-stop:$reason")
            val stopped = appContext.stopService(Intent(appContext, DisplayServerService::class.java))
            if (!stopped) {
                explicitStopRequested = false
            }
        }
    }

    private var httpServer: HttpServer? = null
    private lateinit var prefsManager: PrefsManager
    private var overlayController: OverlayController? = null
    private var lastUrl: String? = null
    private var lastCmdAt: Long = 0
    private var foregroundStarted = false
    private var speedMonitor: DisplaySpeedMonitor? = null
    private var lastDisplayLocationSyncAt = 0L
    private var lastDisplayLocationLat: Double? = null
    private var lastDisplayLocationLng: Double? = null
    private var pendingNavigationLaunch: PendingNavigationLaunch? = null
    @Volatile private var resolveToken: Long = 0
    @Volatile private var navigationLaunchToken: Long = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private val appStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_APP_FOREGROUND -> {
                    Log.d(TAG, "📱 App foregrounded - hiding overlay")
                    overlayController?.hideOverlay()
                }
                HttpServer.ACTION_PAIRING_CHANGED -> {
                    Log.d(TAG, "🔁 Pairing changed - refreshing display tracking")
                    refreshSpeedometerTracking()
                }
            }
        }
    }

    private val antiTheftStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DisplayAntiTheftManager.ACTION_STATE_CHANGED) return
            val pending = pendingNavigationLaunch ?: return
            if (!isNavigationBlockedByAntiTheft()) {
                Log.d(TAG, "🔓 Anti-theft released - launching queued navigation")
                pendingNavigationLaunch = null
                launchNavigationPipeline(pending.url, pending.commandId, pending.isRouteUpdate)
            }
        }
    }

    private val displayPowerEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> {
                    DisplayPowerAutomationManager.handlePowerEvent(
                        context = context,
                        action = intent.action.orEmpty(),
                        source = "display-service-runtime-receiver"
                    )
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    // Sticky battery broadcast is the most reliable plug/unplug signal across OEMs.
                    val plugged = intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
                    DisplayPowerAutomationManager.handleBatteryChanged(context, plugged)
                }
            }
            // Keep the screen awake only while charging in Display mode, so the lock-on-power-
            // disconnect flow still works and the battery is not drained when unplugged/parked.
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> acquireScreenWakeLock()
                Intent.ACTION_POWER_DISCONNECTED -> releaseScreenWakeLock()
                Intent.ACTION_BATTERY_CHANGED ->
                    if (intent.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0) {
                        acquireScreenWakeLock()
                    } else {
                        releaseScreenWakeLock()
                    }
            }
            if (DEDUPE_POWER_MONITORING) forwardPowerEventToDashcam(context, intent)
        }
    }

    /**
     * Feed power events the Display service already receives into the dashcam, so the dashcam does
     * not need to run its own redundant power monitor while Display mode is active. Also nudges a
     * resume if the camera service was killed by the OS while still charging.
     */
    private fun forwardPowerEventToDashcam(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED ->
                com.phantom.dashcam.AutoRecordPowerManager.handlePowerBroadcast(
                    context, intent.action.orEmpty(), "display-forward"
                )
            Intent.ACTION_BATTERY_CHANGED -> {
                com.phantom.dashcam.AutoRecordPowerManager.handleBatteryChanged(
                    context, intent, "display-forward"
                )
                com.phantom.dashcam.AutoRecordPowerManager.maybeStartIfChargingAndReady(
                    context, "display-forward-resume"
                )
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
        DisplayAntiTheftManager.onDisplayServiceStarted(this)
        DisplayPowerAutomationManager.onDisplayServiceStarted(this)
        overlayController = OverlayController(this)
        acquireWakeLock()
        // Keep the screen on right away if we are already charging when Display mode starts.
        if (isExternalPowerConnected()) acquireScreenWakeLock()
        refreshSpeedometerTracking()

        try {
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                appStateReceiver,
                android.content.IntentFilter().apply {
                    addAction(ACTION_APP_FOREGROUND)
                    addAction(HttpServer.ACTION_PAIRING_CHANGED)
                },
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                antiTheftStateReceiver,
                android.content.IntentFilter(DisplayAntiTheftManager.ACTION_STATE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to register app state receivers", e)
        }

        try {
            ContextCompat.registerReceiver(
                this,
                displayPowerEventReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                    addAction(Intent.ACTION_BATTERY_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            DisplayPowerAutomationManager.reconcileCurrentPowerState(
                this,
                "display-service-start"
            )
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to register Display power receiver", e)
        }

        if (DEDUPE_POWER_MONITORING) {
            // Display service now owns power monitoring; let the dashcam rely on our feed.
            com.phantom.dashcam.AutoRecordPowerManager.setExternalPowerFeedActive(this, true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "▶️ DisplayServerService.onStartCommand()")
        val navigationUrl = if (intent?.action == ACTION_NAVIGATION_RECEIVED) intent.getStringExtra("url") else null
        val navigationCommandId = if (intent?.action == ACTION_NAVIGATION_RECEIVED) intent.getStringExtra("commandId") else null

        // Handle stop action
        if (intent?.action == ACTION_STOP_SERVER) {
            Log.d(TAG, "🛑 Stop server action received")
            requestStop(this, "notification-action")
            return START_NOT_STICKY
        }

        if (!ensureForegroundStarted()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_REFRESH_SPEEDOMETER) {
            refreshSpeedometerTracking()
            return START_STICKY
        }

        Log.d(TAG, "ℹ️ Bluetooth bridge disabled (Wi‑Fi only mode)")

        // Start HTTP server
        try {
            if (httpServer?.isServerRunning() != true) {
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
        refreshSpeedometerTracking()

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
        val wasExplicitStop = explicitStopRequested
        explicitStopRequested = false
        if (DEDUPE_POWER_MONITORING) {
            // Display service is going away; let the dashcam resume its own power monitor.
            com.phantom.dashcam.AutoRecordPowerManager.setExternalPowerFeedActive(this, false)
        }
        FirebaseManager.stopDisplayCommandListener()
        DisplayAntiTheftManager.onDisplayServiceStopped(wasExplicitStop)
        DisplayPowerAutomationManager.onDisplayServiceStopped()
        stopSpeedometerTracking()
        
        // Cleanup overlay
        overlayController?.cleanup()
        try {
            unregisterReceiver(appStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to unregister app state receiver", e)
        }
        try {
            unregisterReceiver(antiTheftStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to unregister anti-theft receiver", e)
        }
        try {
            unregisterReceiver(displayPowerEventReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to unregister display power receiver", e)
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
        
        // Release wake locks
        releaseWakeLock()
        releaseScreenWakeLock()

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Quiet ongoing status channel for Display mode (no peeking/sound). Grouped with the
            // dashcam recording notification so both sit together in one section.
            val statusChannel = NotificationChannel(
                CHANNEL_STATUS,
                "Display & recording status",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Ongoing status while Display mode / dashcam are active"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(statusChannel)

            // High-importance channel for navigation hand-offs so they alert and can be tapped.
            val navChannel = NotificationChannel(
                CHANNEL_NAV,
                "Navigation received",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "A destination was shared to this display"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(navChannel)
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
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
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

    private fun refreshSpeedometerTracking() {
        val keepLocationTracking = shouldKeepDisplayLocationTracking()
        val speedometerEnabled = prefsManager.isSpeedometerEnabled()

        if (!speedometerEnabled) {
            prefsManager.clearSpeedSnapshot()
            broadcastSpeedState(
                SpeedSnapshot(
                    kmh = null,
                    hasFix = false,
                    updatedAt = System.currentTimeMillis(),
                    source = "DISABLED"
                )
            )
        }

        if (!speedometerEnabled && !keepLocationTracking) {
            stopSpeedometerTracking()
            return
        }

        if (!DisplaySpeedMonitor.hasLocationPermission(this)) {
            stopSpeedometerTracking()
            if (speedometerEnabled) {
                prefsManager.clearSpeedSnapshot()
                broadcastSpeedState(
                    SpeedSnapshot(
                        kmh = null,
                        hasFix = false,
                        updatedAt = System.currentTimeMillis(),
                        source = "NO_PERMISSION"
                    )
                )
            }
            return
        }

        if (!DisplaySpeedMonitor.isLocationEnabled(this)) {
            stopSpeedometerTracking()
            if (speedometerEnabled) {
                prefsManager.clearSpeedSnapshot()
                broadcastSpeedState(
                    SpeedSnapshot(
                        kmh = null,
                        hasFix = false,
                        updatedAt = System.currentTimeMillis(),
                        source = "LOCATION_DISABLED"
                    )
                )
            }
            return
        }

        if (speedMonitor == null) {
            speedMonitor = DisplaySpeedMonitor(this) { snapshot ->
                if (prefsManager.isSpeedometerEnabled()) {
                    prefsManager.saveSpeedSnapshot(
                        kmh = snapshot.kmh,
                        hasFix = snapshot.hasFix,
                        updatedAt = snapshot.updatedAt,
                        accuracyMeters = snapshot.accuracyMeters,
                        provider = snapshot.provider
                    )
                    broadcastSpeedState(snapshot)
                }
                maybeSyncDisplayLocation(snapshot)
            }
        }
        speedMonitor?.start()
    }

    private fun stopSpeedometerTracking() {
        speedMonitor?.stop()
    }

    private fun shouldKeepDisplayLocationTracking(): Boolean {
        return prefsManager.isDisplayPaired() ||
            prefsManager.isAntiTheftAttachLocationEnabled()
    }

    private fun maybeSyncDisplayLocation(snapshot: SpeedSnapshot) {
        val lat = snapshot.lat ?: return
        val lng = snapshot.lng ?: return
        val now = System.currentTimeMillis()

        val timeDue = now - lastDisplayLocationSyncAt >= DISPLAY_LOCATION_SYNC_INTERVAL_MS
        val previousLat = lastDisplayLocationLat
        val previousLng = lastDisplayLocationLng
        val movedEnough = if (previousLat == null || previousLng == null) {
            true
        } else {
            distanceMeters(previousLat, previousLng, lat, lng) >= DISPLAY_LOCATION_MIN_DISTANCE_METERS
        }

        if (!timeDue && !movedEnough) return

        FirebaseManager.updateDisplayPresenceLocation(this, snapshot)
        lastDisplayLocationSyncAt = now
        lastDisplayLocationLat = lat
        lastDisplayLocationLng = lng
    }

    private fun distanceMeters(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(startLat, startLng, endLat, endLng, results)
        return abs(results.firstOrNull() ?: 0f)
    }

    private fun broadcastSpeedState(snapshot: SpeedSnapshot) {
        val intent = Intent(ACTION_SPEED_UPDATED).apply {
            setPackage(packageName)
            putExtra(EXTRA_SPEED_HAS_FIX, snapshot.hasFix)
            putExtra(EXTRA_SPEED_UPDATED_AT, snapshot.updatedAt)
            putExtra(EXTRA_SPEED_SOURCE, snapshot.source)
            snapshot.kmh?.let { putExtra(EXTRA_SPEED_KMH, it) }
            snapshot.provider?.let { putExtra(EXTRA_SPEED_PROVIDER, it) }
            snapshot.accuracyMeters?.let { putExtra(EXTRA_SPEED_ACCURACY, it) }
        }
        sendBroadcast(intent)
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

        val builder = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle("PHANToM GO - Display Mode")
            .setContentText("Server: $ip:$port | Token: $tokenHint")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setGroup(GROUP_STATUS)
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

        // Save to prefs for diagnostics
        val prefs = getSharedPreferences("phantom_go_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putLong("last_cmd_at", lastCmdAt)
            putString("last_url", finalUrl)
            apply()
        }

        if (isNavigationBlockedByAntiTheft()) {
            Log.d(TAG, "🔐 Navigation queued because anti-theft currently blocks route launch")
            pendingNavigationLaunch = PendingNavigationLaunch(
                url = finalUrl,
                commandId = commandId,
                isRouteUpdate = isRouteUpdate
            )
            return
        }

        launchNavigationPipeline(finalUrl, commandId, isRouteUpdate)
    }

    private fun isLikelyRouteUpdate(previousUrl: String?, previousCmdAtMs: Long, nextUrl: String, nowMs: Long): Boolean {
        if (previousUrl.isNullOrBlank()) return false
        if (previousCmdAtMs <= 0L) return false
        if ((nowMs - previousCmdAtMs) > ROUTE_UPDATE_WINDOW_MS) return false
        return previousUrl != nextUrl
    }

    private fun launchNavigationPipeline(url: String, commandId: String?, isRouteUpdate: Boolean) {
        navigationLaunchToken = System.currentTimeMillis()
        val currentToken = navigationLaunchToken

        val openMapsFlow = openMapsFlow@{
            if (navigationLaunchToken != currentToken) {
                Log.d(TAG, "⏭️ Skipping stale navigation launch")
                return@openMapsFlow
            }
            showNavigationNotification(url, allowFullScreenIntent = false)
            if (!commandId.isNullOrBlank()) {
                FirebaseManager.markCloudCommandOpened(this, commandId, url)
            }

            if (PhantomGoApplication.isInForeground()) {
                Log.d(TAG, "📱 App in foreground, opening Maps directly")
                openMapsDirectly(url)
                return@openMapsFlow
            }

            Log.d(TAG, "📱 App in background, opening Maps directly")
            openMapsDirectly(url)

            if (isRouteUpdate) {
                Log.d(TAG, "🛣️ Route update detected - skipping overlay to preserve Google Maps confirmation popup")
                PhantomLog.i("NAV routeUpdate overlaySkipped")
                return@openMapsFlow
            }

            overlayController?.let { controller ->
                if (controller.isOverlayEnabled() && controller.hasOverlayPermission()) {
                    Log.d(TAG, "🎯 Showing overlay widget")
                    controller.showNavigationOverlay(url)
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

        if (!DisplayPowerAutomationManager.isNavigationPreparationEnabled(this)) {
            openMapsFlow()
            return
        }

        val readiness = DisplayPowerAutomationManager.evaluateReadiness(this)
        if (readiness.ready) {
            Log.d(TAG, "⚡ Display already ready - opening Maps immediately")
            openMapsFlow()
            return
        }

        Log.d(
            TAG,
            "🌙 Preparing display before opening Maps (interactive=${readiness.interactive}, unlocked=${readiness.unlocked})"
        )
        DisplayPowerAutomationManager.prepareForNavigationLaunch(this) {
            if (navigationLaunchToken == currentToken && !isNavigationBlockedByAntiTheft()) {
                openMapsFlow()
            }
        }
    }

    private fun isNavigationBlockedByAntiTheft(): Boolean {
        return when (DisplayAntiTheftManager.getCurrentState(this)) {
            AntiTheftState.CHALLENGE_1,
            AntiTheftState.CHALLENGE_2,
            AntiTheftState.POST_ALERT_COUNTDOWN,
            AntiTheftState.ALARM_ACTIVE,
            AntiTheftState.FULLSCREEN_LOCK -> true
            else -> false
        }
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
            
            val builder = NotificationCompat.Builder(this, CHANNEL_NAV)
                .setContentTitle("🗺️ Navigation Received")
                .setContentText("Tap to open Maps")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setAutoCancel(true)
                .setGroup(GROUP_NAV)
                // Tapping the notification body opens navigation directly — no need to expand the
                // notification and press the action button (expand behaviour varies across ROMs).
                .setContentIntent(openNavPendingIntent)
                .addAction(
                    android.R.drawable.ic_menu_directions,
                    "Open Navigation",
                    openNavPendingIntent
                )
            if (allowFullScreenIntent) {
                builder.setFullScreenIntent(openNavPendingIntent, true)
            }
            val notification = builder.build()

            notificationManager?.notify(NAV_NOTIFICATION_ID, notification)
            Log.d(TAG, "✅ Navigation notification shown")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to show navigation notification", e)
        }
    }
    
    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                // Display mode must keep its command listener and power watchdog alive while
                // the screen is off. The lock is always released when the service stops.
                acquire()
            }
            Log.d(TAG, "✅ Persistent Display service wake lock acquired")
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

    private fun isExternalPowerConnected(): Boolean {
        return try {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireScreenWakeLock() {
        try {
            if (screenWakeLock?.isHeld == true) return
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            // A window FLAG_KEEP_SCREEN_ON only keeps the screen on while our own Activity is in the
            // foreground; to keep the display awake while the user is inside Google Maps we need a
            // service-held screen wake lock. Released when Display mode stops or power disconnects.
            screenWakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                SCREEN_WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "✅ Display screen-on wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to acquire screen wake lock", e)
        }
    }

    private fun releaseScreenWakeLock() {
        try {
            screenWakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "✅ Display screen-on wake lock released")
                }
            }
            screenWakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error releasing screen wake lock", e)
        }
    }
}
