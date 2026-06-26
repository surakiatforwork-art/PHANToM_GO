package com.phantom.dashcam

import android.view.Surface

object SurfaceRotationStore {

    @Volatile
    private var rotationHint: Int = Surface.ROTATION_0

    fun update(rotation: Int) {
        rotationHint = rotation
    }

    fun getRotationHint(): Int = rotationHint
}
