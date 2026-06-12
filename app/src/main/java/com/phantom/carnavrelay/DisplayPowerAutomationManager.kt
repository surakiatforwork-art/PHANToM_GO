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

    private lateinit var appContext: Context
    private lateinit var prefs: PrefsManager
    private val handler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var pendingPreparationToken = 0L
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var lastPowerEventAction: String? = null
    private var lastPowerEventAtElapsed = 0L

    // Edge detector for ACTION_BATTERY_CHANGED. Null means "unknown yet".
    @Volatile private var lastPluggedState: Boolean? = null

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = PrefsManager(appContext)
        initialized = true
    }

    fun onDisplayServiceStarted(context: Context) {
        initialize(context)
    }

    fun onDisplayServiceStopped() {
        if (!initialized) return
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
        val batteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        if (batteryIntent == null) {
            PhantomLog.w("DISPLAY POWER reconcile skipped: battery state unavailable source=$source")
            return
        }
        val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        lastPluggedState = plugged
        if (plugged) {
            handlePowerEvent(appContext, Intent.ACTION_POWER_CONNECTED, "$source-reconcile")
        } else {
            PhantomLog.i("DISPLAY POWER reconcile: unplugged at start (source=$source), no action")
        }
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
        if (previous == null || previous == plugged) return
        val action = if (plugged) Intent.ACTION_POWER_CONNECTED else Intent.ACTION_POWER_DISCONNECTED
        handlePowerEvent(appContext, action, "battery-changed")
    }

    fun handlePowerEvent(context: Context, action: String, source: String) {
        initialize(context)
        if (action != Intent.ACTION_POWER_CONNECTED && action != Intent.ACTION_POWER_DISCONNECTED) {
            return
        }
        // Keep the edge detector in sync no matter which source delivered the event, so the
        // ACTION_BATTERY_CHANGED path does not re-fire the same edge.
        lastPluggedState = action == Intent.ACTION_POWER_CONNECTED

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
        handler.postDelayed({
            if (pendingPreparationToken == token) {
                onReady()
            }
        }, READY_DELAY_MS)
    }

    fun cancelPendingNavigationPreparation() {
        pendingPreparationToken = 0L
        handler.removeCallbacksAndMessages(null)
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
            val lockResult = lockDevice()
            Log.d(TAG, "🔐 Disconnect lock [$reason]: $lockResult")
            if (lockResult == "device locked") {
                PhantomLog.i("DISPLAY POWER disconnect lock: $lockResult")
            } else {
                PhantomLog.w("DISPLAY POWER disconnect lock: $lockResult")
            }
        } else {
            Log.d(TAG, "Disconnect lock [$reason] skipped: lock-on-disconnect disabled")
        }
    }

    /**
     * Turn the display screen on reliably. The screen is woken by launching the Display screen
     * with setTurnScreenOn()/setShowWhenLocked() flags (handled in MainActivity); the legacy
     * wake lock below is only a best-effort assist for very old devices.
     */
    private fun wakeDisplayScreen(reason: String): String {
        acquireLegacyWake()
        return runCatching {
            val intent = MainActivity.createIntent(appContext, BottomNavHelper.Tab.DISPLAY).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(MainActivity.EXTRA_WAKE_SCREEN, true)
            }
            appContext.startActivity(intent)
            "screen wake requested via Display activity"
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

    private fun lockDevice(): String {
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
