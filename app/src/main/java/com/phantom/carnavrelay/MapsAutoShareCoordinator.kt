package com.phantom.carnavrelay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference
import kotlin.math.max

/**
 * Drives the Main-side Google Maps auto-share flow through a scoped Accessibility Service.
 *
 * Design goals (see docs/MAIN_AUTO_SHARE_LOW_POWER_RELIABILITY_PLAN.md):
 *  - Act instantly when the UI is ready; tolerate slow devices with per-phase ceilings.
 *  - One node-tree snapshot per scan (no repeated tree walks).
 *  - A new accessibility event cancels any pending poll and scans immediately.
 *  - Search every allow-listed resolver window for "PHANToM GO" with a fast text query.
 *  - Verify clicks (Share Sheet appeared / handoff happened) and never click a non-allow-listed package.
 */
object MapsAutoShareCoordinator {
    private const val TAG = "MapsAutoShare"
    private const val FAILURE_COOLDOWN_MS = 5_000L
    private const val SUCCESS_COOLDOWN_MS = 12_000L
    private const val ACCESSIBILITY_SIGNAL_DEDUP_MS = 15_000L
    private const val HANDOFF_VERIFY_DELAY_MS = 150L

    private val handler = Handler(Looper.getMainLooper())
    private val scanRunnable = Runnable { scan() }
    private val pendingTriggerRunnable = Runnable { startPendingNotificationIfReady() }
    private var serviceRef = WeakReference<MapsAutoShareAccessibilityService>(null)

    private var phase = AutoSharePhase.IDLE
    private var sessionStartedAt = 0L
    private var phaseStartedAt = 0L
    private var shareClickedAt = 0L
    private var lastShareAttemptAt = 0L
    private var cooldownUntil = 0L

    private var routeControlsFirstSeenAt = 0L
    private var shareFirstSeenAt = 0L
    private var previewTried = false
    private var stopClicked = false
    private var shareClicked = false
    private var shareRetryCount = 0
    private var targetClicked = false
    private var payloadReceived = false
    private var resolverDismissedLogged = false

    private var lastAccessibilitySignalAt = 0L
    private val handledNotificationKeys = mutableSetOf<String>()
    private var pendingNotificationKey: String? = null

    // --- Diagnostics (no route text / PII is ever stored) ---
    private var diagScanCount = 0
    private var diagTotalNodes = 0
    private var diagMaxWindows = 0
    private var diagSheetAt = 0L
    private var diagTargetClickAt = 0L
    fun onServiceConnected(service: MapsAutoShareAccessibilityService) {
        serviceRef = WeakReference(service)
        reset("service-connected")
    }

    fun onServiceDisconnected(service: MapsAutoShareAccessibilityService) {
        if (serviceRef.get() === service) {
            serviceRef.clear()
            pendingNotificationKey = null
            reset("service-disconnected")
        }
    }

    fun onNotificationSignal(context: Context, notificationKey: String) {
        if (notificationKey in handledNotificationKeys) return
        val now = SystemClock.elapsedRealtime()
        if (phase != AutoSharePhase.IDLE) {
            pendingNotificationKey = notificationKey
            log("Queued notification trigger while $phase is active")
            return
        }
        if (now < cooldownUntil) {
            pendingNotificationKey = notificationKey
            schedulePendingNotification(cooldownUntil - now)
            log("Queued notification trigger until cooldown ends")
            return
        }
        if (!canStart(context, "notification")) return
        if (startSession("maps-notification")) {
            handledNotificationKeys.add(notificationKey)
        }
    }

    fun onNotificationRemoved(notificationKey: String) {
        handledNotificationKeys.remove(notificationKey)
        if (pendingNotificationKey == notificationKey) {
            pendingNotificationKey = null
            handler.removeCallbacks(pendingTriggerRunnable)
        }
    }

    /**
     * Called by [ShareReceiverActivity] as soon as Android delivers the shared payload to this app.
     * Resolver dismissal alone is not proof of a successful handoff on every ROM.
     */
    fun onSharePayloadReceived() {
        if (phase != AutoSharePhase.FIND_SHARE_TARGET &&
            phase != AutoSharePhase.VERIFY_HANDOFF
        ) {
            return
        }
        payloadReceived = true
        complete("share payload received by PHANToM GO")
    }

    fun onAccessibilityEvent(
        service: MapsAutoShareAccessibilityService,
        event: AccessibilityEvent
    ) {
        serviceRef = WeakReference(service)
        val packageName = event.packageName?.toString()

        if (phase != AutoSharePhase.IDLE) {
            // A new event means the UI changed: cancel the pending poll and scan now.
            val relevant = packageName == null || // typeWindowsChanged often has no package
                packageName == MapsUiVocabulary.MAPS_PACKAGE ||
                MapsUiVocabulary.isAllowedResolverPackage(packageName)
            if (relevant) scheduleScan(MapsAutoSharePolicy.IMMEDIATE_SCAN_MS)
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

    private fun canStart(context: Context, source: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (phase != AutoSharePhase.IDLE || now < cooldownUntil) return false

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
        val now = SystemClock.elapsedRealtime()
        phase = AutoSharePhase.FIND_ROUTE_ACTION
        sessionStartedAt = now
        phaseStartedAt = now
        shareClickedAt = 0L
        lastShareAttemptAt = 0L
        routeControlsFirstSeenAt = 0L
        shareFirstSeenAt = 0L
        previewTried = false
        stopClicked = false
        shareClicked = false
        shareRetryCount = 0
        targetClicked = false
        payloadReceived = false
        resolverDismissedLogged = false
        diagScanCount = 0
        diagTotalNodes = 0
        diagMaxWindows = 0
        diagSheetAt = 0L
        diagTargetClickAt = 0L
        log("Session started: $reason")
        scheduleScan(MapsAutoSharePolicy.IMMEDIATE_SCAN_MS)
        return true
    }

    private fun schedulePendingNotification(delayMs: Long) {
        handler.removeCallbacks(pendingTriggerRunnable)
        handler.postDelayed(pendingTriggerRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun startPendingNotificationIfReady() {
        val key = pendingNotificationKey ?: return
        val service = serviceRef.get() ?: return
        val now = SystemClock.elapsedRealtime()
        if (phase != AutoSharePhase.IDLE) {
            schedulePendingNotification(MapsAutoSharePolicy.SCAN_SLOW_MS)
            return
        }
        if (now < cooldownUntil) {
            schedulePendingNotification(cooldownUntil - now)
            return
        }
        pendingNotificationKey = null
        if (key in handledNotificationKeys) return
        if (canStart(service.applicationContext, "pending-notification") &&
            startSession("maps-pending-notification")
        ) {
            handledNotificationKeys.add(key)
        }
    }

    /** Cancel any pending scan and post a new one. delayMs = 0 means "scan now". */
    private fun scheduleScan(delayMs: Long) {
        if (phase == AutoSharePhase.IDLE) return
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, delayMs)
    }

    private fun rescheduleAdaptive(now: Long) {
        val waited = now - phaseStartedAt
        scheduleScan(MapsAutoSharePolicy.adaptiveScanIntervalMs(waited))
    }

    private fun scan() {
        val service = serviceRef.get() ?: return fail("service unavailable")
        val now = SystemClock.elapsedRealtime()
        diagScanCount += 1

        if (MapsAutoSharePolicy.isSessionTimedOut(now - sessionStartedAt)) {
            return fail("session safety timeout")
        }

        when (phase) {
            AutoSharePhase.FIND_ROUTE_ACTION,
            AutoSharePhase.FIND_SHARE_AFTER_STOP -> scanMapsPhase(service, now)
            AutoSharePhase.VERIFY_SHARE_SHEET -> scanVerifyShareSheet(service, now)
            AutoSharePhase.FIND_SHARE_TARGET -> scanFindTarget(service, now)
            AutoSharePhase.VERIFY_HANDOFF -> scanVerifyHandoff(service, now)
            AutoSharePhase.IDLE -> Unit
        }
    }

    private fun scanMapsPhase(service: MapsAutoShareAccessibilityService, now: Long) {
        if (MapsAutoSharePolicy.isPhaseTimedOut(phase, now - phaseStartedAt)) {
            return fail("$phase timeout")
        }

        val root = service.rootInActiveWindow ?: return rescheduleAdaptive(now)
        if (root.packageName?.toString() != MapsUiVocabulary.MAPS_PACKAGE) {
            return rescheduleAdaptive(now)
        }

        val scanGoal = if (phase == AutoSharePhase.FIND_SHARE_AFTER_STOP) {
            MapsUiMatcher.ScanGoal.SHARE_ONLY
        } else {
            MapsUiMatcher.ScanGoal.ROUTE_ACTION
        }
        val snapshot = MapsUiMatcher.scanMapsUi(root, scanGoal)
        diagTotalNodes += snapshot.visitedNodeCount

        // 1) Dismiss a closed-destination warning, then rescan immediately.
        if (snapshot.hasWarning && MapsUiMatcher.click(snapshot.warningExit)) {
            resetRouteStability()
            phase = AutoSharePhase.FIND_SHARE_AFTER_STOP
            phaseStartedAt = now
            log("Closed-destination warning dismissed; scanning Share immediately")
            scheduleScan(MapsAutoSharePolicy.IMMEDIATE_SCAN_MS)
            return
        }

        updateRouteStability(snapshot, now)
        val sessionElapsed = now - sessionStartedAt
        val routeReady = snapshot.stop != null &&
            MapsAutoSharePolicy.isRouteReady(sessionElapsed, now - routeControlsFirstSeenAt)
        val shareReady = snapshot.share != null &&
            MapsAutoSharePolicy.isShareReady(
                sessionElapsedMs = sessionElapsed,
                shareStableMs = now - shareFirstSeenAt,
                routeReady = routeReady,
                afterStop = phase == AutoSharePhase.FIND_SHARE_AFTER_STOP
            )

        // Share is clicked only after it remains stable and the route is ready.
        if (shareReady && clickShare(snapshot.share, now)) return

        // After Stop we only wait for a newly surfaced, stable Share control.
        if (phase == AutoSharePhase.FIND_SHARE_AFTER_STOP) return rescheduleAdaptive(now)

        // If a stable Share is appearing, give it time to settle instead of stopping navigation.
        if (routeReady && snapshot.share != null) return rescheduleAdaptive(now)

        // Navigation active and stable: try Preview once, then Stop, to surface Share.
        if (routeReady) {
            if (!previewTried && snapshot.preview != null && MapsUiMatcher.click(snapshot.preview)) {
                previewTried = true
                log("Route preview clicked")
                scheduleScan(MapsAutoSharePolicy.IMMEDIATE_SCAN_MS)
                return
            }
            if (!stopClicked && MapsUiMatcher.click(snapshot.stop)) {
                stopClicked = true
                phase = AutoSharePhase.FIND_SHARE_AFTER_STOP
                phaseStartedAt = now
                shareFirstSeenAt = 0L
                log("Stop navigation clicked; scanning Share immediately")
                scheduleScan(MapsAutoSharePolicy.IMMEDIATE_SCAN_MS)
                return
            }
        }

        rescheduleAdaptive(now)
    }

    private fun updateRouteStability(snapshot: MapsUiMatcher.MapsUiSnapshot, now: Long) {
        routeControlsFirstSeenAt = when {
            snapshot.stop == null -> 0L
            routeControlsFirstSeenAt == 0L -> now
            else -> routeControlsFirstSeenAt
        }
        shareFirstSeenAt = when {
            snapshot.share == null -> 0L
            shareFirstSeenAt == 0L -> now
            else -> shareFirstSeenAt
        }
    }

    private fun resetRouteStability() {
        routeControlsFirstSeenAt = 0L
        shareFirstSeenAt = 0L
    }

    private fun clickShare(node: AccessibilityNodeInfo?, now: Long): Boolean {
        if (!MapsUiMatcher.click(node)) return false
        shareClicked = true
        shareClickedAt = now
        lastShareAttemptAt = now
        phase = AutoSharePhase.VERIFY_SHARE_SHEET
        phaseStartedAt = now
        log("Share clicked (${now - sessionStartedAt}ms); verifying Share Sheet")
        scheduleScan(MapsAutoSharePolicy.IMMEDIATE_SCAN_MS)
        return true
    }

    private fun scanVerifyShareSheet(service: MapsAutoShareAccessibilityService, now: Long) {
        if (MapsAutoSharePolicy.isPhaseTimedOut(phase, now - phaseStartedAt)) {
            return fail("Share Sheet did not appear in time")
        }

        if (isResolverPresent(service)) {
            diagSheetAt = now
            phase = AutoSharePhase.FIND_SHARE_TARGET
            phaseStartedAt = now
            log("Share Sheet detected (${now - shareClickedAt}ms after Share)")
            scheduleScan(MapsAutoSharePolicy.IMMEDIATE_SCAN_MS)
            return
        }

        // Still on Maps: the Share tap may not have registered. Retry a bounded number of times.
        val root = service.rootInActiveWindow
        if (root?.packageName?.toString() == MapsUiVocabulary.MAPS_PACKAGE &&
            MapsAutoSharePolicy.canRetryShare(now - lastShareAttemptAt, shareRetryCount)
        ) {
            val snapshot = MapsUiMatcher.scanMapsUi(root, MapsUiMatcher.ScanGoal.SHARE_ONLY)
            diagTotalNodes += snapshot.visitedNodeCount
            if (snapshot.share != null && MapsUiMatcher.click(snapshot.share)) {
                shareRetryCount += 1
                lastShareAttemptAt = now
                log("Share Sheet not shown yet; retried Share click ($shareRetryCount)")
            }
        }
        rescheduleAdaptive(now)
    }

    private fun scanFindTarget(service: MapsAutoShareAccessibilityService, now: Long) {
        if (MapsAutoSharePolicy.isPhaseTimedOut(phase, now - phaseStartedAt)) {
            return fail("PHANToM GO not found; user may select it manually")
        }

        var clicked = false
        forEachWindowRoot(service) { root, packageName ->
            if (clicked) return@forEachWindowRoot
            if (!MapsUiVocabulary.isResolverPackage(packageName)) return@forEachWindowRoot
            val target = MapsUiMatcher.findPhantomGoTarget(root) ?: return@forEachWindowRoot
            if (MapsUiMatcher.click(target)) {
                clicked = true
                targetClicked = true
                diagTargetClickAt = now
            }
        }

        if (clicked) {
            phase = AutoSharePhase.VERIFY_HANDOFF
            phaseStartedAt = now
            resolverDismissedLogged = false
            log("PHANToM GO selected (${now - diagSheetAt}ms in Share Sheet); verifying handoff")
            scheduleScan(HANDOFF_VERIFY_DELAY_MS)
            return
        }

        rescheduleAdaptive(now)
    }

    private fun scanVerifyHandoff(service: MapsAutoShareAccessibilityService, now: Long) {
        if (payloadReceived) {
            return complete("share payload received by PHANToM GO")
        }
        if (MapsAutoSharePolicy.isPhaseTimedOut(phase, now - phaseStartedAt)) {
            return fail("share target clicked but payload was not received")
        }
        if (!isResolverPresent(service) && !resolverDismissedLogged) {
            resolverDismissedLogged = true
            log("Resolver dismissed; waiting for PHANToM GO payload acknowledgement")
        }
        rescheduleAdaptive(now)
    }

    /** True if any visible window belongs to a share-sheet resolver package. */
    private fun isResolverPresent(service: MapsAutoShareAccessibilityService): Boolean {
        var present = false
        forEachWindowRoot(service) { _, packageName ->
            if (MapsUiVocabulary.isResolverPackage(packageName)) present = true
        }
        return present
    }

    /** Visit each interactive window root plus the active-window root. */
    private inline fun forEachWindowRoot(
        service: MapsAutoShareAccessibilityService,
        block: (root: AccessibilityNodeInfo, packageName: String?) -> Unit
    ) {
        val windows = try {
            service.windows
        } catch (_: Exception) {
            emptyList()
        }
        diagMaxWindows = max(diagMaxWindows, windows.size)
        for (window in windows) {
            val root = try {
                window?.root
            } catch (_: Exception) {
                null
            } ?: continue
            block(root, root.packageName?.toString())
        }
        // Always include the active-window root in case windows list is empty on some ROMs.
        val activeRoot = try {
            service.rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        if (activeRoot != null) block(activeRoot, activeRoot.packageName?.toString())
    }

    private fun complete(reason: String) {
        logDiagnostics("complete", reason)
        cooldownUntil = SystemClock.elapsedRealtime() + SUCCESS_COOLDOWN_MS
        reset("complete")
        if (pendingNotificationKey != null) {
            schedulePendingNotification(SUCCESS_COOLDOWN_MS)
        }
    }

    private fun fail(reason: String) {
        logDiagnostics("stopped", reason)
        cooldownUntil = SystemClock.elapsedRealtime() + FAILURE_COOLDOWN_MS
        reset("failed")
        if (pendingNotificationKey != null) {
            schedulePendingNotification(FAILURE_COOLDOWN_MS)
        }
    }

    private fun logDiagnostics(outcome: String, reason: String) {
        val sessionMs = if (sessionStartedAt > 0) SystemClock.elapsedRealtime() - sessionStartedAt else 0
        val avgNodes = if (diagScanCount > 0) diagTotalNodes / diagScanCount else 0
        log(
            "Session $outcome: $reason | totalMs=$sessionMs scans=$diagScanCount avgNodes=$avgNodes " +
                "windows=$diagMaxWindows shareClicked=$shareClicked shareRetries=$shareRetryCount " +
                "targetClicked=$targetClicked payloadReceived=$payloadReceived"
        )
    }

    private fun reset(reason: String) {
        handler.removeCallbacks(scanRunnable)
        phase = AutoSharePhase.IDLE
        sessionStartedAt = 0L
        phaseStartedAt = 0L
        shareClickedAt = 0L
        lastShareAttemptAt = 0L
        resetRouteStability()
        previewTried = false
        stopClicked = false
        shareClicked = false
        shareRetryCount = 0
        targetClicked = false
        payloadReceived = false
        resolverDismissedLogged = false
        Log.d(TAG, "Coordinator reset: $reason")
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        PhantomLog.i("Maps Auto Share: $message")
    }
}
