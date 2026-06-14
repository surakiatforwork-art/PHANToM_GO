package com.phantom.carnavrelay

/**
 * Phases of the Main-side Google Maps auto-share flow.
 *
 *   IDLE
 *     -> FIND_ROUTE_ACTION   (wait for a stable route, then click Share or Preview+Stop fallback)
 *     -> FIND_SHARE_AFTER_STOP (after Stop navigation, wait for Share)
 *     -> VERIFY_SHARE_SHEET  (after Share click, wait for an allow-listed resolver window)
 *     -> FIND_SHARE_TARGET   (find & click "PHANToM GO" in the resolver window)
 *     -> VERIFY_HANDOFF      (confirm the resolver dismissed / share handed off)
 *     -> COMPLETE
 */
internal enum class AutoSharePhase {
    IDLE,
    FIND_ROUTE_ACTION,
    FIND_SHARE_AFTER_STOP,
    VERIFY_SHARE_SHEET,
    FIND_SHARE_TARGET,
    VERIFY_HANDOFF
}

/**
 * Pure (no Android dependency) timing policy for the auto-share coordinator so the timeout and
 * adaptive-polling rules can be unit tested without an emulator.
 *
 * Principle: respond instantly when the UI is ready, but tolerate slow devices by allowing longer
 * per-phase ceilings and backing off the fallback polling instead of busy-looping.
 */
internal object MapsAutoSharePolicy {

    // Per-phase ceilings (max wait, not a fixed delay).
    const val FIND_ROUTE_TIMEOUT_MS = 12_000L
    const val FIND_SHARE_AFTER_STOP_TIMEOUT_MS = 10_000L

    /** Each post-share phase receives its own ceiling; the session safety timeout remains global. */
    const val POST_SHARE_TIMEOUT_MS = 10_000L

    /** Absolute safety ceiling for one whole session. */
    const val SESSION_SAFETY_TIMEOUT_MS = 30_000L

    // Route readiness gates. These are stability windows, not fixed sleeps.
    const val ROUTE_READY_MIN_SESSION_MS = 1_000L
    const val ROUTE_CONTROLS_STABLE_MS = 450L
    const val SHARE_STABLE_MS = 400L
    const val SHARE_AFTER_STOP_STABLE_MS = 250L

    // Adaptive fallback polling.
    const val IMMEDIATE_SCAN_MS = 0L
    const val SCAN_FAST_MS = 60L
    const val SCAN_MEDIUM_MS = 120L
    const val SCAN_SLOW_MS = 180L
    const val BACKOFF_MEDIUM_AFTER_MS = 1_500L
    const val BACKOFF_SLOW_AFTER_MS = 4_000L

    /** Share retries are paced so a slow resolver is not hit by duplicate taps. */
    const val MAX_SHARE_RETRY = 2
    const val SHARE_RETRY_DELAY_MS = 1_500L

    fun adaptiveScanIntervalMs(waitedMs: Long): Long = when {
        waitedMs < BACKOFF_MEDIUM_AFTER_MS -> SCAN_FAST_MS
        waitedMs < BACKOFF_SLOW_AFTER_MS -> SCAN_MEDIUM_MS
        else -> SCAN_SLOW_MS
    }

    fun phaseTimeoutMs(phase: AutoSharePhase): Long = when (phase) {
        AutoSharePhase.FIND_ROUTE_ACTION -> FIND_ROUTE_TIMEOUT_MS
        AutoSharePhase.FIND_SHARE_AFTER_STOP -> FIND_SHARE_AFTER_STOP_TIMEOUT_MS
        AutoSharePhase.VERIFY_SHARE_SHEET,
        AutoSharePhase.FIND_SHARE_TARGET,
        AutoSharePhase.VERIFY_HANDOFF -> POST_SHARE_TIMEOUT_MS
        AutoSharePhase.IDLE -> 0L
    }

    /** True when the given phase has exceeded its own ceiling. */
    fun isPhaseTimedOut(phase: AutoSharePhase, phaseElapsedMs: Long): Boolean {
        val ceiling = phaseTimeoutMs(phase)
        return ceiling > 0L && phaseElapsedMs > ceiling
    }

    fun isSessionTimedOut(sessionElapsedMs: Long): Boolean {
        return sessionElapsedMs > SESSION_SAFETY_TIMEOUT_MS
    }

    fun isRouteReady(sessionElapsedMs: Long, controlsStableMs: Long): Boolean {
        return sessionElapsedMs >= ROUTE_READY_MIN_SESSION_MS &&
            controlsStableMs >= ROUTE_CONTROLS_STABLE_MS
    }

    fun isShareReady(
        sessionElapsedMs: Long,
        shareStableMs: Long,
        routeReady: Boolean,
        afterStop: Boolean
    ): Boolean {
        if (afterStop) return shareStableMs >= SHARE_AFTER_STOP_STABLE_MS
        return sessionElapsedMs >= ROUTE_READY_MIN_SESSION_MS &&
            shareStableMs >= SHARE_STABLE_MS &&
            routeReady
    }

    fun canRetryShare(sinceLastAttemptMs: Long, retryCount: Int): Boolean {
        return retryCount < MAX_SHARE_RETRY && sinceLastAttemptMs >= SHARE_RETRY_DELAY_MS
    }
}
