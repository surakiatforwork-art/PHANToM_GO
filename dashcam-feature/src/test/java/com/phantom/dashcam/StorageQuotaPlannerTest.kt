package com.phantom.dashcam

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class StorageQuotaPlannerTest {

    @Test
    fun `returns no deletions when total usage fits quota`() {
        val clips = listOf(
            storedClip(displayName = "a", sizeBytes = 2, startedAtEpochMs = 1_000L),
            storedClip(displayName = "b", sizeBytes = 3, startedAtEpochMs = 2_000L),
        )

        val deletions = StorageQuotaPlanner.pickClipsToDelete(clips, quotaBytes = 5)

        assertEquals(emptyList<StoredDashcamClip>(), deletions)
    }

    @Test
    fun `deletes oldest clips first until usage fits quota`() {
        val clips = listOf(
            storedClip(displayName = "oldest", sizeBytes = 3, startedAtEpochMs = 10_000L),
            storedClip(displayName = "middle", sizeBytes = 4, startedAtEpochMs = 20_000L),
            storedClip(displayName = "newest", sizeBytes = 5, startedAtEpochMs = 30_000L),
        )

        val deletions = StorageQuotaPlanner.pickClipsToDelete(clips, quotaBytes = 8)

        assertEquals(listOf("oldest", "middle"), deletions.map { it.displayName })
    }

    private fun storedClip(
        displayName: String,
        sizeBytes: Long,
        startedAtEpochMs: Long,
        durationMs: Long = 60_000L,
    ): StoredDashcamClip {
        return StoredDashcamClip(
            file = File(displayName),
            displayName = displayName,
            sizeBytes = sizeBytes,
            startedAtEpochMs = startedAtEpochMs,
            endedAtEpochMs = startedAtEpochMs + durationMs,
            durationMs = durationMs,
        )
    }
}
