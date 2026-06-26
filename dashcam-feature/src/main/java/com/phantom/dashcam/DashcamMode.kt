package com.phantom.dashcam

import androidx.camera.core.CameraSelector

enum class DashcamMode(
    val storageValue: String,
    val label: String,
    val lensFacing: Int?,
) {
    REAR("rear", "Rear", CameraSelector.LENS_FACING_BACK),
    FRONT("front", "Front", CameraSelector.LENS_FACING_FRONT),
    DUAL("dual", "Dual", null);

    companion object {
        fun fromStorageValue(value: String?): DashcamMode {
            return entries.firstOrNull { it.storageValue == value } ?: REAR
        }
    }
}
