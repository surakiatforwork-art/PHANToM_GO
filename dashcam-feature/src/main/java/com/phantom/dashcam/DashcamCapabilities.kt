package com.phantom.dashcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * Lightweight device capability checks for the dashcam UI, so we can disable options the device
 * cannot actually do (e.g. simultaneous front+rear recording) instead of failing at record time.
 */
object DashcamCapabilities {

    /**
     * True if the platform advertises a concurrent camera combination that includes both a
     * front-facing and a back-facing camera. Concurrent camera streaming is only queryable on
     * Android 11 (API 30)+; below that we conservatively report unsupported.
     */
    fun supportsDualCamera(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        return runCatching {
            manager.concurrentCameraIds.any { combo ->
                val facings = combo.mapNotNull { id ->
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                }
                facings.contains(CameraCharacteristics.LENS_FACING_FRONT) &&
                    facings.contains(CameraCharacteristics.LENS_FACING_BACK)
            }
        }.getOrDefault(false)
    }
}
