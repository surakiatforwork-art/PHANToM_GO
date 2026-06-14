package com.phantom.carnavrelay

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.os.BatteryManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

data class DisplayReadinessSnapshot(
    val ready: Boolean,
    val interactive: Boolean,
    val unlocked: Boolean
)

/**
 * Display power automation, intentionally limited to the two behaviours the product needs:
 *  - external power connected  -> turn the display screen on (and show it)
 *  - external power disconnected -> lock the display (Device Admin)
 *
 * Screen brightness is deliberately NOT touched anymore. The reliability problem this
 * class previously had came from two places that are both fixed here:
 *  1. Trigger coverage: besides ACTION_POWER_CONNECTED/DISCONNECTED we now also derive the
 *     plugged edge from the sticky ACTION_BATTERY_CHANGED broadcast, which fires on every
 *     device on plug/unplug, so we never miss the event.
 *  2. Turning the screen on: the deprecated FULL_WAKE_LOCK is ignored on modern Android, so
 *     we wake the screen through an Activity that uses setTurnScreenOn()/setShowWhenLocked().
 */
object DisplayPowerAutomationManager {

    private const val TAG = "PHANTOM_GO"
    private const val READY_DELAY_MS = 900L
    private const val POWER_EVENT_DEBOUNCE_MS = 1_000L
    private const val SCREEN_WAKE_TIMEOUT_MS = 8_000L
    private const val POWER_STATE_MONITOR_INTERVAL_MS = 2_000L
    private const val LOCK_VERIFY_DELAY_MS = 350L
    private const val MAX_LOCK_ATTEMPTS = 3

    private lateinit var appContext: Context
    private lateinit var prefs: PrefsManager
    private val handler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var pendingPreparationToken = 0L
    private var pendingNavigationRunnable: Runnable? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var lastPowerEventAction: String? = null
    private var lastPowerEventAtElapsed = 0L
    private var displayServiceStarted = false
    private var powerMonitorRunning = false

    // Edge detector for ACTION_BATTERY_CHANGED. Null means "unknown yet".
    @Volatile private var lastPluggedState: Boolean? = null

    private val powerMonitorRunnable = object : Runnable {
        override fun run() {
            if (!powerMonitorRunning || !displayServiceStarted) return
            observeCurrentPowerState("power-watchdog")
            if (
                prefs.isDisplayPowerAutomationEnabled() &&
                prefs.isDisplayDisconnectLockPending() &&
                lastPluggedState == false
            ) {
                verifyDisconnectLock("power-watchdog")
            }
            handler.postDelayed(this, POWER_STATE_MONITOR_INTERVAL_MS)
        }
    }

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = PrefsManager(appContext)
        initialized = true
    }

    fun onDisplayServiceStarted(context: Context) {
        initialize(context)
        displayServiceStarted = true
        startPowerMonitoring("display-service-start", recoverMissedEdge = true)
    }

    fun onDisplayServiceStopped() {
        if (!initialized) return
        displayServiceStarted = false
        stopPowerMonitoring()
        cancelPendingNavigationPreparation()
        releaseScreenWakeLock()
    }

    /**
     * Seed the plugged-state edge detector when the service starts and wake the screen if we
     * are already charging. We never *lock* from here, because reconciliation runs when the
     * user opens Display mode and locking the device at that moment would be surprising.
     */
    fun reconcileCurrentPowerState(context: Context, source: String) {
        initialize(context)
        startPowerMonitoring("$source-reconcile", recoverMissedEdge = true)
    }

    /**
     * Robust trigger: derive a plug/unplug edge from the sticky ACTION_BATTERY_CHANGED
     * broadcast. This catches cases where ACTION_POWER_CONNECTED/DISCONNECTED is delayed,
     * coalesced or never delivered on a given OEM build.
     */
    fun handleBatteryChanged(context: Context, plugged: Boolean) {
        initialize(context)
        val previous = lastPluggedState
        lastPluggedState = plugged
        if (previous == null) {
            val persistedPrevious = if (prefs.hasDisplayPowerState()) prefs.isDisplayPowerPlugged() else null
            prefs.setDisplayPowerState(plugged)
            val recoveredEdge = DisplayPowerStatePolicy.edge(persistedPrevious, plugged) ?: return
            handlePowerEdge(recoveredEdge, "battery-changed-recovery")
            return
        } else if (DisplayPowerStatePolicy.edge(previous, plugged) == null) {
            prefs.setDisplayPowerState(plugged)
            return
        }
        handlePowerEdge(
            if (plugged) DisplayPowerEdge.CONNECTED else DisplayPowerEdge.DISCONNECTED,
            "battery-changed"
        )
    }

    fun handlePowerEvent(context: Context, action: String, source: String) {
        initialize(context)
        if (action != Intent.ACTION_POWER_CONNECTED && action != Intent.ACTION_POWER_DISCONNECTED) {
            return
        }
        // Keep the edge detector in sync no matter which source delivered the event, so the
        // ACTION_BATTERY_CHANGED path does not re-fire the same edge.
        val plugged = action == Intent.ACTION_POWER_CONNECTED
        lastPluggedState = plugged
        prefs.setDisplayPowerState(plugged)
        if (plugged) {
            prefs.setDisplayDisconnectLockPending(false)
        }

        val now = SystemClock.elapsedRealtime()
        val duplicate = synchronized(this) {
            val isDuplicate = action == lastPowerEventAction &&
                now - lastPowerEventAtElapsed < POWER_EVENT_DEBOUNCE_MS
            if (!isDuplicate) {
                lastPowerEventAction = action
                lastPowerEventAtElapsed = now
            }
            isDuplicate
        }
        if (duplicate) {
            Log.d(TAG, "Display power event skipped because it was duplicated: $action source=$source")
            return
        }
        val automationEnabled = prefs.isDisplayPowerAutomationEnabled()
        val lockEnabled = prefs.isDisplayLockOnPowerDisconnectEnabled()
        val adminActive = isDeviceAdminActive(appContext)
        val deviceMode = prefs.getDeviceMode()
        PhantomLog.i(
            "DISPLAY POWER event=$action source=$source automation=$automationEnabled " +
                "lockOnDisconnect=$lockEnabled deviceAdmin=$adminActive mode=$deviceMode"
        )
        if (deviceMode != PrefsManager.MODE_DISPLAY) {
            Log.d(TAG, "Display power event skipped because this device is not in Display mode")
            return
        }
        if (!automationEnabled) {
            Log.d(TAG, "Display power event skipped because automation is disabled")
            return
        }
        if (isAntiTheftBlocking()) {
            Log.d(TAG, "Display power event skipped because anti-theft has priority")
            PhantomLog.w("DISPLAY POWER skipped: anti-theft has priority")
            return
        }

        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                Log.d(TAG, "Power connected - turning the display screen on")
                applyConnectedProfile("$source-power-connected", wakeScreen = true)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.d(TAG, "Power disconnected - locking the display")
                applyDisconnectedProfile("$source-power-disconnected")
            }
        }
    }

    fun isNavigationPreparationEnabled(context: Context): Boolean {
        initialize(context)
        return prefs.isDisplayWakeBeforeMapsEnabled()
    }

    fun isPowerAutomationEnabled(context: Context): Boolean {
        initialize(context)
        return prefs.isDisplayPowerAutomationEnabled()
    }

    fun setPowerAutomationEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        prefs.setDisplayPowerAutomationEnabled(enabled)
        synchronized(this) {
            lastPowerEventAction = null
            lastPowerEventAtElapsed = 0L
        }
        if (enabled) {
            if (displayServiceStarted) {
                startPowerMonitoring("settings-enabled", recoverMissedEdge = false)
            } else if (prefs.getDeviceMode() == PrefsManager.MODE_DISPLAY) {
                runCatching {
                    androidx.core.content.ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, DisplayServerService::class.java)
                    )
                    PhantomLog.i("DISPLAY POWER requested Display service restart after enable")
                }.onFailure {
                    PhantomLog.e("DISPLAY POWER failed to restart Display service after enable", it)
                }
            }
        } else {
            prefs.setDisplayDisconnectLockPending(false)
            stopPowerMonitoring()
        }
    }

    fun isWakeBeforeMapsEnabled(context: Context): Boolean {
        initialize(context)
        return prefs.isDisplayWakeBeforeMapsEnabled()
    }

    fun setWakeBeforeMapsEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        prefs.setDisplayWakeBeforeMapsEnabled(enabled)
    }

    fun isLockOnPowerDisconnectEnabled(context: Context): Boolean {
        initialize(context)
        return prefs.isDisplayLockOnPowerDisconnectEnabled()
    }

    fun setLockOnPowerDisconnectEnabled(context: Context, enabled: Boolean) {
        initialize(context)
        prefs.setDisplayLockOnPowerDisconnectEnabled(enabled)
        if (!enabled) {
            prefs.setDisplayDisconnectLockPending(false)
        }
    }

    fun canWriteSystemSettings(context: Context): Boolean {
        initialize(context)
        return Settings.System.canWrite(appContext)
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        initialize(context)
        val manager = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(appContext, PhantomDisplayDeviceAdminReceiver::class.java)
        return manager?.isAdminActive(admin) == true
    }

    fun buildWriteSettingsIntent(context: Context): Intent {
        initialize(context)
        return Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            android.net.Uri.parse("package:${appContext.packageName}")
        )
    }

    fun buildDeviceAdminIntent(context: Context): Intent {
        initialize(context)
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                ComponentName(appContext, PhantomDisplayDeviceAdminReceiver::class.java)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Allow PHANToM GO to lock the display device when external power is disconnected."
            )
        }
    }

    fun evaluateReadiness(context: Context): DisplayReadinessSnapshot {
        initialize(context)
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val interactive = powerManager?.isInteractive == true
        val unlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager?.isDeviceLocked == false
        } else {
            keyguardManager?.isKeyguardLocked == false
        }
        return DisplayReadinessSnapshot(
            ready = interactive && unlocked,
            interactive = interactive,
            unlocked = unlocked
        )
    }

    fun prepareForNavigationLaunch(context: Context, onReady: () -> Unit) {
        initialize(context)
        cancelPendingNavigationPreparation()

        val snapshot = evaluateReadiness(context)
        if (!prefs.isDisplayWakeBeforeMapsEnabled() || snapshot.ready) {
            onReady()
            return
        }

        applyConnectedProfile("navigation-prepare", wakeScreen = true)
        val token = System.currentTimeMillis()
        pendingPreparationToken = token
        val runnable = Runnable {
            if (pendingPreparationToken == token) {
                onReady()
            }
        }
        pendingNavigationRunnable = runnable
        handler.postDelayed(runnable, READY_DELAY_MS)
    }

    fun cancelPendingNavigationPreparation() {
        pendingPreparationToken = 0L
        pendingNavigationRunnable?.let(handler::removeCallbacks)
        pendingNavigationRunnable = null
    }

    fun applyConnectedProfile(reason: String, wakeScreen: Boolean) {
        if (wakeScreen) {
            val wakeResult = wakeDisplayScreen(reason)
            Log.d(TAG, "🌞 Connected profile [$reason]: $wakeResult")
            PhantomLog.i("DISPLAY POWER connect wake: $wakeResult")
        }
    }

    fun applyDisconnectedProfile(reason: String) {
        if (isAntiTheftBlocking()) {
            Log.d(TAG, "🔐 Disconnected profile [$reason] skipped because anti-theft has priority")
            PhantomLog.w("DISPLAY POWER disconnect skipped: anti-theft has priority")
            return
        }
        if (prefs.isDisplayLockOnPowerDisconnectEnabled()) {
            prefs.setDisplayDisconnectLockPending(true)
            requestLockWithVerification(reason, attempt = 1)
        } else {
            prefs.setDisplayDisconnectLockPending(false)
            Log.d(TAG, "Disconnect lock [$reason] skipped: lock-on-disconnect disabled")
        }
    }

    /**
     * Turn the display screen on without changing the app/page the user was viewing.
     * DisplayWakeActivity is transparent and immediately finishes after requesting the wake.
     */
    private fun wakeDisplayScreen(reason: String): String {
        acquireLegacyWake()
        return runCatching {
            val intent = Intent(appContext, DisplayWakeActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(DisplayWakeActivity.EXTRA_REASON, reason)
            }
            appContext.startActivity(intent)
            "screen wake requested without changing foreground app"
        }.getOrElse { "screen wake failed: ${it.message}" }
    }

    @Suppress("DEPRECATION")
    private fun acquireLegacyWake() {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val wakeLock = runCatching {
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "${appContext.packageName}:displayWake"
            )
        }.getOrNull() ?: return
        wakeLock.setReferenceCounted(false)
        releaseScreenWakeLock()
        screenWakeLock = wakeLock
        runCatching { wakeLock.acquire(SCREEN_WAKE_TIMEOUT_MS) }
    }

    private fun requestLockWithVerification(reason: String, attempt: Int) {
        if (!prefs.isDisplayDisconnectLockPending() || lastPluggedState == true) return
        if (!isDeviceAdminActive(appContext)) {
            prefs.setDisplayDisconnectLockPending(false)
            PhantomLog.w("DISPLAY POWER lock skipped: Device administrator is disabled")
            return
        }
        val lockResult = lockDeviceOnce()
        PhantomLog.i("DISPLAY POWER lock attempt=$attempt reason=$reason result=$lockResult")
        handler.postDelayed({
            if (lastPluggedState == true || !prefs.isDisplayDisconnectLockPending()) {
                return@postDelayed
            }
            val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isInteractive != true) {
                prefs.setDisplayDisconnectLockPending(false)
                PhantomLog.i("DISPLAY POWER disconnect lock confirmed after attempt=$attempt")
            } else if (attempt < MAX_LOCK_ATTEMPTS) {
                requestLockWithVerification(reason, attempt + 1)
            } else {
                PhantomLog.w("DISPLAY POWER lock requested $MAX_LOCK_ATTEMPTS times; screen still interactive")
            }
        }, LOCK_VERIFY_DELAY_MS)
    }

    private fun verifyDisconnectLock(reason: String) {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isInteractive == true) {
            requestLockWithVerification(reason, attempt = 1)
        } else {
            prefs.setDisplayDisconnectLockPending(false)
        }
    }

    private fun lockDeviceOnce(): String {
        releaseScreenWakeLock()
        val manager = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val admin = ComponentName(appContext, PhantomDisplayDeviceAdminReceiver::class.java)
        if (manager == null || !manager.isAdminActive(admin)) {
            return "lock skipped: Device administrator is disabled"
        }
        return runCatching {
            manager.lockNow()
            "device locked"
        }.getOrElse { "lock failed: ${it.message}" }
    }

    private fun releaseScreenWakeLock() {
        screenWakeLock?.let { current ->
            if (current.isHeld) {
                runCatching { current.release() }
            }
        }
        screenWakeLock = null
    }

    private fun startPowerMonitoring(source: String, recoverMissedEdge: Boolean) {
        if (!displayServiceStarted || !prefs.isDisplayPowerAutomationEnabled()) return
        val current = readCurrentPluggedState()
        if (current != null) {
            val previous = if (prefs.hasDisplayPowerState()) prefs.isDisplayPowerPlugged() else null
            lastPluggedState = current
            prefs.setDisplayPowerState(current)
            val recoveredEdge = DisplayPowerStatePolicy.edge(previous, current)
            if (recoverMissedEdge && recoveredEdge != null) {
                PhantomLog.w("DISPLAY POWER recovered missed edge: $previous -> $current source=$source")
                handlePowerEdge(recoveredEdge, "$source-recovery")
            } else if (current == false && prefs.isDisplayDisconnectLockPending()) {
                verifyDisconnectLock("$source-pending")
            }
        }
        if (!powerMonitorRunning) {
            powerMonitorRunning = true
            handler.postDelayed(powerMonitorRunnable, POWER_STATE_MONITOR_INTERVAL_MS)
            PhantomLog.i("DISPLAY POWER monitor started source=$source")
        }
    }

    private fun stopPowerMonitoring() {
        powerMonitorRunning = false
        handler.removeCallbacks(powerMonitorRunnable)
    }

    private fun observeCurrentPowerState(source: String) {
        val plugged = readCurrentPluggedState() ?: return
        val previous = lastPluggedState
        if (previous == null) {
            lastPluggedState = plugged
            prefs.setDisplayPowerState(plugged)
            return
        }
        val edge = DisplayPowerStatePolicy.edge(previous, plugged) ?: return
        handlePowerEdge(edge, source)
    }

    private fun readCurrentPluggedState(): Boolean? {
        val batteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null
        return batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    private fun handlePowerEdge(edge: DisplayPowerEdge, source: String) {
        handlePowerEvent(
            appContext,
            if (edge == DisplayPowerEdge.CONNECTED) {
                Intent.ACTION_POWER_CONNECTED
            } else {
                Intent.ACTION_POWER_DISCONNECTED
            },
            source
        )
    }

    private fun isAntiTheftBlocking(): Boolean {
        return when (DisplayAntiTheftManager.getCurrentState(appContext)) {
            AntiTheftState.CHALLENGE_1,
            AntiTheftState.CHALLENGE_2,
            AntiTheftState.POST_ALERT_COUNTDOWN,
            AntiTheftState.ALARM_ACTIVE,
            AntiTheftState.FULLSCREEN_LOCK -> true
            else -> false
        }
    }
}
