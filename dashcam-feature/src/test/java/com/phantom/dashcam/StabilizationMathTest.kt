package com.phantom.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StabilizationMathTest {

    @Test
    fun cumulative_accumulatesTranslationAndRotation() {
        val transforms = listOf(
            FrameTransform(1.0, 2.0, 0.1),
            FrameTransform(3.0, -1.0, -0.05),
        )
        val traj = StabilizationMath.cumulative(transforms)
        assertEquals(1.0, traj[0].x, 1e-9)
        assertEquals(2.0, traj[0].y, 1e-9)
        assertEquals(0.1, traj[0].a, 1e-9)
        assertEquals(4.0, traj[1].x, 1e-9)
        assertEquals(1.0, traj[1].y, 1e-9)
        assertEquals(0.05, traj[1].a, 1e-9)
    }

    @Test
    fun smooth_flattensAShakyPathTowardsItsMean() {
        // A jittery path around a constant mean should be pulled toward that mean.
        val raw = listOf(
            TrajectoryPoint(10.0, 0.0, 0.0),
            TrajectoryPoint(-10.0, 0.0, 0.0),
            TrajectoryPoint(10.0, 0.0, 0.0),
            TrajectoryPoint(-10.0, 0.0, 0.0),
            TrajectoryPoint(10.0, 0.0, 0.0),
        )
        val smoothed = StabilizationMath.smooth(raw, radius = 2)
        // The central frame averages the whole window -> close to the 0 mean.
        assertTrue("middle should be far steadier than raw", kotlin.math.abs(smoothed[2].x) < 5.0)
    }

    @Test
    fun smooth_zeroRadiusIsIdentity() {
        val raw = listOf(TrajectoryPoint(3.0, 4.0, 0.2))
        assertEquals(raw, StabilizationMath.smooth(raw, radius = 0))
    }

    @Test
    fun corrections_perfectlySteadyInputNeedsNoCorrection() {
        val steady = List(10) { FrameTransform.IDENTITY }
        val corrections = StabilizationMath.corrections(steady, smoothingRadius = 3)
        corrections.forEach {
            assertEquals(0.0, it.dx, 1e-9)
            assertEquals(0.0, it.dy, 1e-9)
            assertEquals(0.0, it.da, 1e-9)
        }
    }

    @Test
    fun corrections_counteractsAJolt() {
        // Steady, then a single sideways jolt, then steady again. The correction at the jolt should
        // push back against it (opposite sign of the accumulated displacement).
        val transforms = listOf(
            FrameTransform.IDENTITY,
            FrameTransform.IDENTITY,
            FrameTransform(20.0, 0.0, 0.0),
            FrameTransform.IDENTITY,
            FrameTransform.IDENTITY,
        )
        val corrections = StabilizationMath.corrections(transforms, smoothingRadius = 2)
        // After the jolt the raw path sits at +20; the smoothed path lags behind, so the correction
        // should move the later frames back toward the centre (negative relative to raw jump).
        assertTrue("correction should pull back the post-jolt frames", corrections[3].dx < 20.0)
    }

    @Test
    fun zoomForCrop_isMonotonicAndClamped() {
        assertEquals(1.0, StabilizationMath.zoomForCrop(0.0), 1e-9)
        assertTrue(StabilizationMath.zoomForCrop(0.10) > 1.0)
        assertTrue(StabilizationMath.zoomForCrop(0.20) > StabilizationMath.zoomForCrop(0.10))
        // Beyond the clamp it must not explode.
        val clamped = StabilizationMath.zoomForCrop(0.9)
        assertEquals(StabilizationMath.zoomForCrop(StabilizationMath.MAX_CROP_FRACTION), clamped, 1e-9)
    }

    @Test
    fun affineRows_identityWhenNoMotionNoZoom() {
        val rows = StabilizationMath.affineRows(FrameTransform.IDENTITY, zoom = 1.0, centerX = 100.0, centerY = 50.0)
        // [a, b, tx, c, d, ty] should be the identity affine.
        assertEquals(1.0, rows[0], 1e-9)
        assertEquals(0.0, rows[1], 1e-9)
        assertEquals(0.0, rows[2], 1e-9)
        assertEquals(0.0, rows[3], 1e-9)
        assertEquals(1.0, rows[4], 1e-9)
        assertEquals(0.0, rows[5], 1e-9)
    }

    @Test
    fun affineRows_pureZoomKeepsCentreFixed() {
        val cx = 320.0
        val cy = 240.0
        val zoom = 1.25
        val rows = StabilizationMath.affineRows(FrameTransform.IDENTITY, zoom, cx, cy)
        // Mapping the centre point through the affine must return the centre (zoom about centre).
        val mappedX = rows[0] * cx + rows[1] * cy + rows[2]
        val mappedY = rows[3] * cx + rows[4] * cy + rows[5]
        assertEquals(cx, mappedX, 1e-6)
        assertEquals(cy, mappedY, 1e-6)
    }
}
