package com.phantom.carnavrelay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

object MapsAutoShareCoordinator {
    private const val TAG = "MapsAutoShare"
    private const val SESSION_TIMEOUT_MS = 5500L
    private const val TARGET_TIMEOUT_MS = 2800L
    private const val SCAN_INTERVAL_MS = 40L
    private const val FAILURE_COOLDOWN_MS = 5000L
    private const val SUCCESS_COOLDOWN_MS = 12000L
    private const val ACCESSIBILITY_SIGNAL_DEDUP_MS = 15000L

    private enum class Phase {
        IDLE,
        FIND_ROUTE_ACTION,
        FIND_SHARE_AFTER_STOP,
        FIND_SHARE_TARGET
    }

    private val handler = Handler(Looper.getMainLooper())
    private var serviceRef = WeakReference<MapsAutoShareAccessibilityService>(null)
    private var phase = Phase.IDLE
    private var sessionStartedAt = 0L
    private var phaseStartedAt = 0L
    private var cooldownUntil = 0L
    private var scanQueued = false
    private var previewTried = false
    private var stopClicked = false
    private var lastAccessibilitySignalAt = 0L
    private val handledNotificationKeys = mutableSetOf<String>()

    fun onServiceConnected(service: MapsAutoShareAccessibilityService) {
        serviceRef = WeakReference(service)
        reset("service-connected")
    }

    fun onServiceDisconnected(service: MapsAutoShareAccessibilityService) {
        if (serviceRef.get() === service) {
            serviceRef.clear()
            reset("service-disconnected")
        }
    }

    fun onNotificationSignal(context: android.content.Context, notificationKey: String) {
        if (notificationKey in handledNotificationKeys) return
        if (phase != Phase.IDLE || SystemClock.elapsedRealtime() < cooldownUntil) {
            handledNotificationKeys.add(notificationKey)
            return
        }
        if (!canStart(context, "notification")) return
        if (startSession("maps-notification")) {
            handledNotificationKeys.add(notificationKey)
        }
    }

    fun onNotificationRemoved(notificationKey: String) {
        handledNotificationKeys.remove(notificationKey)
    }

    fun onAccessibilityEvent(
        service: MapsAutoShareAccessibilityService,
        event: AccessibilityEvent
    ) {
        serviceRef = WeakReference(service)
        val packageName = event.packageName?.toString()

        if (phase != Phase.IDLE) {
            if (packageName == MapsUiVocabulary.MAPS_PACKAGE ||
                MapsUiVocabulary.isAllowedResolverPackage(packageName)
            ) {
                scheduleScan(0L)
            }
            return
        }

        if (packageName != MapsUiVocabulary.MAPS_PACKAGE) return
        if (!canStart(service.applicationContext, "accessibility")) return

        val now = SystemClock.elapsedRealtime()
        if (MapsUiVocabulary.isLikelyNavigationSignal(event.text) &&
            now - lastAccessibilitySignalAt >= ACCESSIBILITY_SIGNAL_DEDUP_MS
        ) {
            lastAccessibilitySignalAt = now
            startSession("maps-accessibility-signal")
        }
    }

    private fun canStart(context: android.content.Context, source: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (phase != Phase.IDLE || now < cooldownUntil) return false

        val prefs = PrefsManager(context)
        if (prefs.getDeviceMode() != PrefsManager.MODE_MAIN) {
            log("Ignored $source: device is not Main mode")
            return false
        }
        if (!prefs.isPaired()) {
            log("Ignored $source: Main device is not paired")
            return false
        }
        if (!MapsAutoShareStatus.isAccessibilityEnabled(context)) {
            log("Ignored $source: accessibility service disabled")
            return false
        }
        return true
    }

    private fun startSession(reason: String): Boolean {
        if (serviceRef.get() == null) {
            log("Could not start $reason: accessibility service instance unavailable")
            return false
        }
        phase = Phase.FIND_ROUTE_ACTION
        sessionStartedAt = SystemClock.elapsedRealtime()
        phaseStartedAt = sessionStartedAt
        previewTried = false
        stopClicked = false
        log("Session started: $reason")
        scheduleScan(0L)
        return true
    }

    private fun scheduleScan(delayMs: Long) {
        if (phase == Phase.IDLE || scanQueued) return
        scanQueued = true
        handler.postDelayed({
            scanQueued = false
            scan()
        }, delayMs)
    }

    private fun scan() {
        val service = serviceRef.get() ?: return fail("service unavailable")
        val now = SystemClock.elapsedRealtime()
        if (now - sessionStartedAt > SESSION_TIMEOUT_MS) return fail("session timeout")
        if (phase == Phase.FIND_SHARE_TARGET && now - phaseStartedAt > TARGET_TIMEOUT_MS) {
            return fail("share target timeout; user may select PHANToM GO manually")
        }

        val root = service.rootInActiveWindow
        if (root == null) {
            scheduleScan(SCAN_INTERVAL_MS)
            return
        }
        val packageName = root.packageName?.toString()

        if (phase == Phase.FIND_SHARE_TARGET) {
            if (!MapsUiVocabulary.isAllowedResolverPackage(packageName)) {
                scheduleScan(SCAN_INTERVAL_MS)
                return
            }
            val target = MapsUiMatcher.findPhantomGoTarget(root)
            if (target != null && MapsUiMatcher.click(target)) {
                return complete("PHANToM GO share target selected")
            }
            scheduleScan(SCAN_INTERVAL_MS)
            return
        }

        if (packageName != MapsUiVocabulary.MAPS_PACKAGE) {
            scheduleScan(SCAN_INTERVAL_MS)
            return
        }

        val warning = MapsUiMatcher.findClosedWarning(root)
        if (warning != null && MapsUiMatcher.click(warning.exit)) {
            log("Closed-destination warning dismissed; scanning Share immediately")
            scheduleScan(0L)
            return
        }

        val share = MapsUiMatcher.findShare(root)
        if (share != null && MapsUiMatcher.click(share)) {
            phase = Phase.FIND_SHARE_TARGET
            phaseStartedAt = SystemClock.elapsedRealtime()
            log("Share clicked; waiting for PHANToM GO target")
            scheduleScan(0L)
            return
        }

        if (phase == Phase.FIND_SHARE_AFTER_STOP) {
            scheduleScan(SCAN_INTERVAL_MS)
            return
        }

        val stop = MapsUiMatcher.findStop(root)
        if (stop != null) {
            if (!previewTried) {
                previewTried = true
                val preview = MapsUiMatcher.findPreview(root)
                if (preview != null && MapsUiMatcher.click(preview)) {
                    log("Route preview clicked")
                    scheduleScan(0L)
                    return
                }
            }
            if (!stopClicked && MapsUiMatcher.click(stop)) {
                stopClicked = true
                phase = Phase.FIND_SHARE_AFTER_STOP
                phaseStartedAt = SystemClock.elapsedRealtime()
                log("Stop navigation clicked; scanning Share immediately")
                scheduleScan(0L)
                return
            }
        }

        scheduleScan(SCAN_INTERVAL_MS)
    }

    private fun complete(reason: String) {
        log("Session complete: $reason")
        cooldownUntil = SystemClock.elapsedRealtime() + SUCCESS_COOLDOWN_MS
        reset("complete")
    }

    private fun fail(reason: String) {
        log("Session stopped: $reason")
        cooldownUntil = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
        reset("failed")
    }

    private fun reset(reason: String) {
        handler.removeCallbacksAndMessages(null)
        scanQueued = false
        phase = Phase.IDLE
        sessionStartedAt = 0L
        phaseStartedAt = 0L
        previewTried = false
        stopClicked = false
        Log.d(TAG, "Coordinator reset: $reason")
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        PhantomLog.i("Maps Auto Share: $message")
    }
}
