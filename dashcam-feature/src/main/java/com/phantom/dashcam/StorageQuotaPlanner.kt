package com.phantom.dashcam

object StorageQuotaPlanner {

    fun pickClipsToDelete(
        clips: List<StoredDashcamClip>,
        quotaBytes: Long,
    ): List<StoredDashcamClip> {
        if (quotaBytes <= 0L) {
            return clips.sortedBy { it.startedAtEpochMs }
        }

        val orderedClips = clips.sortedBy { it.startedAtEpochMs }
        var totalBytes = orderedClips.sumOf { it.sizeBytes }
        if (totalBytes <= quotaBytes) {
            return emptyList()
        }

        val clipsToDelete = mutableListOf<StoredDashcamClip>()
        for (clip in orderedClips) {
            if (totalBytes <= quotaBytes) {
                break
            }
            clipsToDelete += clip
            totalBytes -= clip.sizeBytes
        }
        return clipsToDelete
    }
}
