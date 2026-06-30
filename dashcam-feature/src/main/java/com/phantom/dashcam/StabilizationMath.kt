package com.phantom.dashcam

import kotlin.math.cos
import kotlin.math.sin

/** Rigid (translation + rotation) motion measured between two consecutive frames. */
data class FrameTransform(val dx: Double, val dy: Double, val da: Double) {
    companion object {
        val IDENTITY = FrameTransform(0.0, 0.0, 0.0)
    }
}

/** Absolute accumulated camera path at a given frame (origin at frame 0). */
data class TrajectoryPoint(val x: Double, val y: Double, val a: Double)

/**
 * Pure, device-independent maths behind the optical-flow video stabilizer. Kept separate from the
 * codec/OpenCV plumbing so the smoothing — the part that actually decides how steady the result
 * looks — can be unit tested without a device or native libraries.
 *
 * The approach is the classic "smooth the trajectory" stabilizer: accumulate the frame-to-frame
 * motion into an absolute path, low-pass filter that path, then for each frame derive the extra
 * transform that nudges the original frame onto the smoothed path.
 */
object StabilizationMath {

    /** Accumulate frame-to-frame transforms into an absolute trajectory. */
    fun cumulative(transforms: List<FrameTransform>): List<TrajectoryPoint> {
        var x = 0.0
        var y = 0.0
        var a = 0.0
        return transforms.map { t ->
            x += t.dx
            y += t.dy
            a += t.da
            TrajectoryPoint(x, y, a)
        }
    }

    /**
     * Box (moving-average) smoothing over +/- [radius] frames. A larger radius means a steadier but
     * less responsive result, which also needs a larger crop to hide the borders.
     */
    fun smooth(trajectory: List<TrajectoryPoint>, radius: Int): List<TrajectoryPoint> {
        if (radius <= 0 || trajectory.isEmpty()) return trajectory
        val n = trajectory.size
        return List(n) { i ->
            var sx = 0.0
            var sy = 0.0
            var sa = 0.0
            var count = 0
            val from = (i - radius).coerceAtLeast(0)
            val to = (i + radius).coerceAtMost(n - 1)
            for (j in from..to) {
                sx += trajectory[j].x
                sy += trajectory[j].y
                sa += trajectory[j].a
                count++
            }
            TrajectoryPoint(sx / count, sy / count, sa / count)
        }
    }

    /**
     * Per-frame correction transform to apply on top of each original frame so the sequence follows
     * the smoothed path. correction = original frame transform + (smoothed - raw trajectory).
     */
    fun corrections(transforms: List<FrameTransform>, smoothingRadius: Int): List<FrameTransform> {
        if (transforms.isEmpty()) return emptyList()
        val raw = cumulative(transforms)
        val smoothed = smooth(raw, smoothingRadius)
        return transforms.indices.map { i ->
            FrameTransform(
                dx = transforms[i].dx + (smoothed[i].x - raw[i].x),
                dy = transforms[i].dy + (smoothed[i].y - raw[i].y),
                da = transforms[i].da + (smoothed[i].a - raw[i].a),
            )
        }
    }

    /**
     * Zoom factor that keeps the frame filled while it is shifted/rotated to follow the smooth path.
     * A [cropFraction] of 0.10 means we are willing to lose ~10% of the frame, which lets the image
     * move by that much before an empty border would show. Clamped so an extreme setting cannot blow
     * the image up to uselessness.
     */
    fun zoomForCrop(cropFraction: Double): Double {
        val clamped = cropFraction.coerceIn(0.0, MAX_CROP_FRACTION)
        return 1.0 / (1.0 - clamped)
    }

    /**
     * Build the 2x3 affine matrix rows for the combined stabilization + zoom transform about the
     * frame centre. Returned as [a, b, tx, c, d, ty] (row-major), matching OpenCV's warpAffine. Kept
     * here (rather than relying on OpenCV's getRotationMatrix2D) so the geometry is unit testable.
     */
    fun affineRows(
        correction: FrameTransform,
        zoom: Double,
        centerX: Double,
        centerY: Double,
    ): DoubleArray {
        // Rotation+uniform-scale block.
        val cosA = cos(correction.da) * zoom
        val sinA = sin(correction.da) * zoom
        // Rotate/scale about the centre, then add the stabilizing translation.
        val tx = centerX - (cosA * centerX - sinA * centerY) + correction.dx
        val ty = centerY - (sinA * centerX + cosA * centerY) + correction.dy
        return doubleArrayOf(cosA, -sinA, tx, sinA, cosA, ty)
    }

    const val MAX_CROP_FRACTION = 0.45
}
