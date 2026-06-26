package com.phantom.dashcam

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

data class StoredDashcamClip(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationMs: Long,
)

data class StorageTrimResult(
    val totalBytesBefore: Long,
    val totalBytesAfter: Long,
    val deletedCount: Int,
)

data class RecentClipSelection(
    val clips: List<StoredDashcamClip>,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val requestedDurationMs: Long,
    val availableDurationMs: Long,
)

object DashcamClipStorageManager {

    private const val CLIP_DIRECTORY_NAME = "PHANToM GO Cam"
    private const val DISPLAY_NAME_PREFIX = "GOCam_"
    private const val PENDING_PREFIX = ".pending-"
    private const val FILE_EXTENSION = ".mp4"
    private const val NOMEDIA_FILE_NAME = ".nomedia"
    private val clipNameTimestampRegex = Regex("_(\\d{8}_\\d{6})\\.mp4$", RegexOption.IGNORE_CASE)

    private data class ClipInterval(
        val startMs: Long,
        val endMs: Long,
    )

    fun trimToQuota(context: Context, quotaGb: Int): StorageTrimResult {
        val clips = queryStoredClips(context)
        val quotaBytes = quotaGb.toLong() * BYTES_PER_GB
        val clipsToDelete = StorageQuotaPlanner.pickClipsToDelete(clips, quotaBytes)
        clipsToDelete.forEach { clip ->
            runCatching { clip.file.delete() }
        }
        val deletedBytes = clipsToDelete.sumOf { it.sizeBytes }
        return StorageTrimResult(
            totalBytesBefore = clips.sumOf { it.sizeBytes },
            totalBytesAfter = (clips.sumOf { it.sizeBytes } - deletedBytes).coerceAtLeast(0L),
            deletedCount = clipsToDelete.count { !it.file.exists() },
        )
    }

    /**
     * Clips for the browser screen, newest first. Trimming keeps using oldest-first
     * ([queryStoredClips]) so the two concerns stay independent.
     */
    fun loadClipsNewestFirst(context: Context): List<StoredDashcamClip> {
        return queryStoredClips(context).sortedByDescending { it.startedAtEpochMs }
    }

    /**
     * Finalized clips that overlap the last [minutes], oldest first — ready to be concatenated
     * into one export.
     */
    fun loadClipsInLastMinutes(context: Context, minutes: Int): List<StoredDashcamClip> {
        return loadRecentClipSelection(context, minutes).clips
    }

    fun loadRecentClipSelection(context: Context, minutes: Int): RecentClipSelection {
        val nowMs = System.currentTimeMillis()
        val requestedDurationMs = minutes.toLong() * 60_000L
        return loadClipSelectionBetween(context, nowMs - requestedDurationMs, nowMs)
    }

    fun loadClipSelectionBetween(
        context: Context,
        windowStartMs: Long,
        windowEndMs: Long,
    ): RecentClipSelection {
        val normalizedStartMs = minOf(windowStartMs, windowEndMs)
        val normalizedEndMs = maxOf(windowStartMs, windowEndMs)
        val selectedClipsWithIntervals = queryStoredClips(context).mapNotNull { clip ->
            val interval = clipInterval(clip) ?: return@mapNotNull null
            if (interval.endMs > normalizedStartMs && interval.startMs < normalizedEndMs) {
                clip to interval
            } else {
                null
            }
        }
        val orderedSelection = selectedClipsWithIntervals.sortedBy { (_, interval) -> interval.startMs }
        return RecentClipSelection(
            clips = orderedSelection.map { (clip, _) -> clip },
            windowStartMs = normalizedStartMs,
            windowEndMs = normalizedEndMs,
            requestedDurationMs = (normalizedEndMs - normalizedStartMs).coerceAtLeast(0L),
            availableDurationMs = coveredDurationMs(
                orderedSelection.map { (_, interval) -> interval },
                normalizedStartMs,
                normalizedEndMs,
            ),
        )
    }

    /**
     * Remove incomplete dashcam temp clips. These are pending files that were being written when
     * recording was interrupted and never got renamed into finalized clips.
     */
    fun cleanupIncompleteClips(context: Context): Int {
        val dir = ensureClipDirectory(context)
        val candidates = dir.listFiles().orEmpty().filter { file ->
            file.isFile && (
                file.name.startsWith(PENDING_PREFIX) ||
                    (isFinalizedClipFile(file) && (file.length() <= 0L || readDurationMs(file) <= 0L))
                )
        }
        candidates.forEach { file ->
            runCatching { file.delete() }
        }
        return candidates.count { !it.exists() }
    }

    fun ensureClipDirectory(context: Context): File {
        val dir = preferredClipDirectory(context)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        ensureNomedia(dir)
        return dir
    }

    fun createPendingClipFile(context: Context, displayNameBase: String): File {
        val dir = ensureClipDirectory(context)
        return File(dir, "$PENDING_PREFIX$displayNameBase$FILE_EXTENSION")
    }

    fun finalizedClipFile(context: Context, displayNameBase: String): File {
        val dir = ensureClipDirectory(context)
        return File(dir, "$displayNameBase$FILE_EXTENSION")
    }

    fun commitPendingClip(pendingFile: File, finalizedFile: File): Boolean {
        if (!pendingFile.exists()) return false
        if (finalizedFile.exists()) {
            runCatching { finalizedFile.delete() }
        }
        if (pendingFile.renameTo(finalizedFile)) return true
        return runCatching {
            pendingFile.copyTo(finalizedFile, overwrite = true)
            pendingFile.delete()
            true
        }.getOrDefault(false)
    }

    private fun queryStoredClips(context: Context): List<StoredDashcamClip> {
        val dir = ensureClipDirectory(context)
        return dir.listFiles()
            .orEmpty()
            .filter(::isFinalizedClipFile)
            .mapNotNull(::buildStoredClip)
            .sortedBy { it.startedAtEpochMs }
    }

    private fun isFinalizedClipFile(file: File): Boolean {
        return file.isFile &&
            file.name.startsWith(DISPLAY_NAME_PREFIX) &&
            file.name.endsWith(FILE_EXTENSION, ignoreCase = true)
    }

    private fun buildStoredClip(file: File): StoredDashcamClip? {
        val sizeBytes = file.length().coerceAtLeast(0L)
        if (sizeBytes <= 0L) return null

        val durationMs = readDurationMs(file)
        val modifiedAtMs = file.lastModified().coerceAtLeast(0L)
        val startedAtMs = parseClipStartTimeMs(file.name)
            ?: (modifiedAtMs - durationMs).coerceAtLeast(0L)
        val endedAtMs = maxOf(modifiedAtMs, startedAtMs + durationMs)

        return StoredDashcamClip(
            file = file,
            displayName = file.name,
            sizeBytes = sizeBytes,
            startedAtEpochMs = startedAtMs,
            endedAtEpochMs = endedAtMs,
            durationMs = durationMs.coerceAtLeast(0L),
        )
    }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun clipInterval(clip: StoredDashcamClip): ClipInterval? {
        if (clip.durationMs <= 0L) return null
        return if (clip.endedAtEpochMs > clip.startedAtEpochMs) {
            ClipInterval(
                startMs = clip.startedAtEpochMs,
                endMs = clip.endedAtEpochMs,
            )
        } else {
            null
        }
    }

    private fun coveredDurationMs(
        intervals: List<ClipInterval>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): Long {
        var coveredMs = 0L
        var activeStartMs = Long.MIN_VALUE
        var activeEndMs = Long.MIN_VALUE

        intervals.forEach { interval ->
            val overlapStartMs = maxOf(interval.startMs, windowStartMs)
            val overlapEndMs = minOf(interval.endMs, windowEndMs)
            if (overlapEndMs <= overlapStartMs) return@forEach

            if (activeStartMs == Long.MIN_VALUE) {
                activeStartMs = overlapStartMs
                activeEndMs = overlapEndMs
                return@forEach
            }

            if (overlapStartMs <= activeEndMs) {
                activeEndMs = maxOf(activeEndMs, overlapEndMs)
            } else {
                coveredMs += activeEndMs - activeStartMs
                activeStartMs = overlapStartMs
                activeEndMs = overlapEndMs
            }
        }

        if (activeStartMs != Long.MIN_VALUE) {
            coveredMs += activeEndMs - activeStartMs
        }
        return coveredMs
    }

    private fun parseClipStartTimeMs(displayName: String): Long? {
        val timestamp = clipNameTimestampRegex.find(displayName)?.groupValues?.getOrNull(1) ?: return null
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            isLenient = false
        }
        return formatter.parse(timestamp)?.time
    }

    private fun preferredClipDirectory(context: Context): File {
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        if (externalDir != null) {
            return File(externalDir, CLIP_DIRECTORY_NAME)
        }
        return File(context.filesDir, "dashcam/$CLIP_DIRECTORY_NAME")
    }

    private fun ensureNomedia(dir: File) {
        val nomediaFile = File(dir, NOMEDIA_FILE_NAME)
        if (!nomediaFile.exists()) {
            runCatching { nomediaFile.createNewFile() }
        }
    }

    private const val BYTES_PER_GB = 1024L * 1024L * 1024L
}
