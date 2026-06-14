package com.phantom.carnavrelay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsAutoSharePolicyTest {

    @Test
    fun adaptiveInterval_isFastWhileEarly() {
        assertEquals(MapsAutoSharePolicy.SCAN_FAST_MS, MapsAutoSharePolicy.adaptiveScanIntervalMs(0))
        assertEquals(MapsAutoSharePolicy.SCAN_FAST_MS, MapsAutoSharePolicy.adaptiveScanIntervalMs(1_499))
    }

    @Test
    fun adaptiveInterval_isMediumAfter1_5s() {
        assertEquals(MapsAutoSharePolicy.SCAN_MEDIUM_MS, MapsAutoSharePolicy.adaptiveScanIntervalMs(1_500))
        assertEquals(MapsAutoSharePolicy.SCAN_MEDIUM_MS, MapsAutoSharePolicy.adaptiveScanIntervalMs(3_999))
    }

    @Test
    fun adaptiveInterval_isSlowAfter4s() {
        assertEquals(MapsAutoSharePolicy.SCAN_SLOW_MS, MapsAutoSharePolicy.adaptiveScanIntervalMs(4_000))
        assertEquals(MapsAutoSharePolicy.SCAN_SLOW_MS, MapsAutoSharePolicy.adaptiveScanIntervalMs(60_000))
    }

    @Test
    fun phaseTimeouts_matchPlan() {
        assertEquals(12_000L, MapsAutoSharePolicy.phaseTimeoutMs(AutoSharePhase.FIND_ROUTE_ACTION))
        assertEquals(10_000L, MapsAutoSharePolicy.phaseTimeoutMs(AutoSharePhase.FIND_SHARE_AFTER_STOP))
        assertEquals(10_000L, MapsAutoSharePolicy.phaseTimeoutMs(AutoSharePhase.VERIFY_SHARE_SHEET))
        assertEquals(10_000L, MapsAutoSharePolicy.phaseTimeoutMs(AutoSharePhase.FIND_SHARE_TARGET))
        assertEquals(10_000L, MapsAutoSharePolicy.phaseTimeoutMs(AutoSharePhase.VERIFY_HANDOFF))
        assertEquals(0L, MapsAutoSharePolicy.phaseTimeoutMs(AutoSharePhase.IDLE))
    }

    @Test
    fun routeReady_requiresMinimumSessionAgeAndStableControls() {
        assertFalse(MapsAutoSharePolicy.isRouteReady(999, 1_000))
        assertFalse(MapsAutoSharePolicy.isRouteReady(10_000, 449))
        assertTrue(MapsAutoSharePolicy.isRouteReady(1_000, 450))
    }

    @Test
    fun shareReady_waitsForStableRouteAndNeverUsesAnEarlyTimeFallback() {
        assertFalse(
            MapsAutoSharePolicy.isShareReady(
                sessionElapsedMs = 1_000,
                shareStableMs = 400,
                routeReady = false,
                afterStop = false
            )
        )
        assertTrue(
            MapsAutoSharePolicy.isShareReady(
                sessionElapsedMs = 1_000,
                shareStableMs = 400,
                routeReady = true,
                afterStop = false
            )
        )
        assertFalse(
            MapsAutoSharePolicy.isShareReady(
                sessionElapsedMs = 30_000,
                shareStableMs = 30_000,
                routeReady = false,
                afterStop = false
            )
        )
        assertTrue(
            MapsAutoSharePolicy.isShareReady(
                sessionElapsedMs = 100,
                shareStableMs = 250,
                routeReady = false,
                afterStop = true
            )
        )
    }

    @Test
    fun shareRetry_isPacedAndBounded() {
        assertFalse(MapsAutoSharePolicy.canRetryShare(1_499, 0))
        assertTrue(MapsAutoSharePolicy.canRetryShare(1_500, 0))
        assertTrue(MapsAutoSharePolicy.canRetryShare(9_000, 1))
        assertFalse(MapsAutoSharePolicy.canRetryShare(9_000, 2))
    }

    @Test
    fun isPhaseTimedOut_respectsCeiling() {
        assertFalse(MapsAutoSharePolicy.isPhaseTimedOut(AutoSharePhase.FIND_ROUTE_ACTION, 11_999))
        assertTrue(MapsAutoSharePolicy.isPhaseTimedOut(AutoSharePhase.FIND_ROUTE_ACTION, 12_001))
        // IDLE has a 0 ceiling and must never be reported as timed out.
        assertFalse(MapsAutoSharePolicy.isPhaseTimedOut(AutoSharePhase.IDLE, 999_999))
    }

    @Test
    fun isSessionTimedOut_at30s() {
        assertFalse(MapsAutoSharePolicy.isSessionTimedOut(30_000))
        assertTrue(MapsAutoSharePolicy.isSessionTimedOut(30_001))
    }

}
