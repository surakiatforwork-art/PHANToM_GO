package com.phantom.dashcam

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageQuotaPlannerTest {

    @Test
    fun `returns no deletions when total usage fits quota`() {
        val clips = listOf(
            StoredDashcamClip(id = 1, displayName = "a", sizeBytes = 2, createdAtEpochSeconds = 1),
            StoredDashcamClip(id = 2, displayName = "b", sizeBytes = 3, createdAtEpochSeconds = 2),
        )

        val deletions = StorageQuotaPlanner.pickClipsToDelete(clips, quotaBytes = 5)

        assertEquals(emptyList<StoredDashcamClip>(), deletions)
    }

    @Test
    fun `deletes oldest clips first until usage fits quota`() {
        val clips = listOf(
            StoredDashcamClip(id = 10, displayName = "oldest", sizeBytes = 3, createdAtEpochSeconds = 10),
            StoredDashcamClip(id = 11, displayName = "middle", sizeBytes = 4, createdAtEpochSeconds = 20),
            StoredDashcamClip(id = 12, displayName = "newest", sizeBytes = 5, createdAtEpochSeconds = 30),
        )

        val deletions = StorageQuotaPlanner.pickClipsToDelete(clips, quotaBytes = 8)

        assertEquals(listOf(10L, 11L), deletions.map { it.id })
    }
}
