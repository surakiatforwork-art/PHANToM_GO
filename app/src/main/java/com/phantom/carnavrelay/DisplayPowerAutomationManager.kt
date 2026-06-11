package com.phantom.carnavrelay

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.Calendar
import kotlin.math.abs

data class DisplayReadinessSnapshot(
    val ready: Boolean,
    val interactive: Boolean,
    val unlocked: Boolean,
    val brightnessMatchesProfile: Boolean,
    val expectedBrightness: Int,
    val currentBrightness: Int?
)

object DisplayPowerAutomationManager {

    private const val TAG = "PHANTOM_GO"
    private const val DAY_BRIGHTNESS = 255
    private const val NIGHT_BRIGHTNESS = 125
    private const val DISCONNECTED_BRIGHTNESS = 0
    private const val BRIGHTNESS_TOLERANCE = 10
    private const val READY_DELAY_MS = 900L
    private const val NIGHT_START_HOUR = 18
    private const val NIGHT_START_MINUTE = 30
    private const val NIGHT_END_HOUR = 5
    private const val NIGHT_END_MINUTE = 0

    private lateinit var appContext: Context
    private lateinit var prefs: PrefsManager
    private val handler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var pendingPreparationToken = 0L
    private var screenWakeLock: PowerManager.WakeLock? = null

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

    fun handlePowerEvent(context: Context, action: String, source: String) {
        initialize(context)
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
                Log.d(TAG, "Power connected - applying display profile")
                applyConnectedProfile("$source-power-connected", wakeScreen = true)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.d(TAG, "Power disconnected - applying display dim/lock profile")
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

    fun getExpectedBrightnessNow(): Int {
        return if (isNightNow()) NIGHT_BRIGHTNESS else DAY_BRIGHTNESS
    }

    fun evaluateReadiness(context: Context): DisplayReadinessSnapshot {
        initialize(context)
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val currentBrightness = getCurrentBrightness()
        val expectedBrightness = getExpectedBrightnessNow()
        val interactive = powerManager?.isInteractive == true
        val unlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            keyguardManager?.isDeviceLocked == false
        } else {
            keyguardManager?.isKeyguardLocked == false
        }
        val brightnessMatches = currentBrightness != null &&
            abs(currentBrightness - expectedBrightness) <= BRIGHTNESS_TOLERANCE
        return DisplayReadinessSnapshot(
            ready = interactive && unlocked && brightnessMatches,
            interactive = interactive,
            unlocked = unlocked,
            brightnessMatchesProfile = brightnessMatches,
            expectedBrightness = expectedBrightness,
            currentBrightness = currentBrightness
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
        val targetBrightness = getExpectedBrightnessNow()
        val brightnessResult = setBrightness(targetBrightness)
        Log.d(TAG, "💡 Connected profile [$reason]: $brightnessResult")
        if (wakeScreen) {
            val wakeResult = wakeAndDismissSwipeBestEffort()
            Log.d(TAG, "🌙 Wake profile [$reason]: $wakeResult")
        }
    }

    fun applyDisconnectedProfile(reason: String) {
        if (isAntiTheftBlocking()) {
            Log.d(TAG, "🔐 Disconnected profile [$reason] skipped because anti-theft has priority")
            PhantomLog.w("DISPLAY POWER disconnect skipped: anti-theft has priority")
            return
        }
        val brightnessResult = setBrightness(DISCONNECTED_BRIGHTNESS)
        Log.d(TAG, "💡 Disconnected profile [$reason]: $brightnessResult")
        PhantomLog.i("DISPLAY POWER disconnect brightness: $brightnessResult")
        if (prefs.isDisplayLockOnPowerDisconnectEnabled()) {
            val lockResult = lockDevice()
            Log.d(TAG, "🔐 Disconnect lock [$reason]: $lockResult")
            if (lockResult == "device locked") {
                PhantomLog.i("DISPLAY POWER disconnect lock: $lockResult")
            } else {
                PhantomLog.w("DISPLAY POWER disconnect lock: $lockResult")
            }
        }
    }

    private fun getCurrentBrightness(): Int? {
        return runCatching {
            Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()
    }

    private fun setBrightness(value: Int): String {
        if (!Settings.System.canWrite(appContext)) {
            return "brightness skipped: Modify system settings is disabled"
        }
        return runCatching {
            Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val success = Settings.System.putInt(
                appContext.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value.coerceIn(0, 255)
            )
            if (success) "brightness=${value.coerceIn(0, 255)}" else "brightness write failed"
        }.getOrElse { "brightness failed: ${it.message}" }
    }

    @Suppress("DEPRECATION")
    private fun wakeAndDismissSwipeBestEffort(): String {
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return "wake failed: power service unavailable"
        val wakeLock = powerManager.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "${appContext.packageName}:displayWake"
        )
        wakeLock.setReferenceCounted(false)
        acquireScreenWakeLock(wakeLock)

        val keyguardManager = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager != null && !keyguardManager.isDeviceSecure) {
            val lock = keyguardManager.newKeyguardLock("${appContext.packageName}:displayKeyguard")
            lock.disableKeyguard()
            return "screen awake for 5s; swipe keyguard dismiss requested"
        }
        return "screen awake for 5s; secure keyguard not dismissed"
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

    private fun acquireScreenWakeLock(wakeLock: PowerManager.WakeLock) {
        releaseScreenWakeLock()
        screenWakeLock = wakeLock
        wakeLock.acquire(5_000L)
    }

    private fun releaseScreenWakeLock() {
        screenWakeLock?.let { current ->
            if (current.isHeld) {
                runCatching { current.release() }
            }
        }
        screenWakeLock = null
    }

    private fun isNightNow(): Boolean {
        val calendar = Calendar.getInstance()
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        val startMinutes = NIGHT_START_HOUR * 60 + NIGHT_START_MINUTE
        val endMinutes = NIGHT_END_HOUR * 60 + NIGHT_END_MINUTE
        return nowMinutes >= startMinutes || nowMinutes < endMinutes
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
