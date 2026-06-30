package com.phantom.dashcam

/**
 * How much of the frame the user is willing to lose so the stabilizer has room to move the image.
 * More crop = steadier result, narrower field of view (license plates near the edge may fall out of
 * frame). Exposed in settings; MEDIUM is the recommended default for dashcam use.
 */
enum class StabilizationCropStrength(
    val storageValue: String,
    val label: String,
    val cropFraction: Double,
    /** Trajectory smoothing radius in frames; a wider window needs the bigger crop to hide borders. */
    val smoothingRadius: Int,
) {
    LOW("low", "Low", 0.05, 15),
    MEDIUM("medium", "Medium", 0.10, 30),
    HIGH("high", "High", 0.20, 45);

    companion object {
        val DEFAULT = MEDIUM

        fun fromStorageValue(value: String?): StabilizationCropStrength {
            return entries.firstOrNull { it.storageValue == value } ?: DEFAULT
        }
    }
}
