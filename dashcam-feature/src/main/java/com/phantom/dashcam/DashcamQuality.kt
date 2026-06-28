package com.phantom.dashcam

/**
 * User-selectable recording resolution. 720p is the default because it roughly halves the encoder
 * load versus 1080p, which keeps the device responsive while heavy apps (e.g. Google Maps) run at
 * the same time. The mapping to a CameraX [androidx.camera.video.Quality] stays inside the library
 * so the host app never needs CameraX on its classpath.
 */
enum class DashcamQuality(
    val storageValue: String,
    val label: String,
    val heightPx: Int,
) {
    HD("hd", "720p", 720),
    FHD("fhd", "1080p", 1080);

    companion object {
        val DEFAULT = HD

        fun fromStorageValue(value: String?): DashcamQuality {
            return entries.firstOrNull { it.storageValue == value } ?: DEFAULT
        }
    }
}
