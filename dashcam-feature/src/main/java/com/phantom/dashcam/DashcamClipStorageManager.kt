package com.phantom.dashcam

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

data class StoredDashcamClip(
    val id: Long,
    val displayName: String,
    val sizeBytes: Long,
    val createdAtEpochSeconds: Long,
)

data class StorageTrimResult(
    val totalBytesBefore: Long,
    val totalBytesAfter: Long,
    val deletedCount: Int,
)

object DashcamClipStorageManager {

    private const val RELATIVE_PATH_PREFIX = "Movies/PHANToM GO Cam"
    private const val DISPLAY_NAME_PREFIX = "GOCam_"

    fun trimToQuota(context: Context, quotaGb: Int): StorageTrimResult {
        val clips = queryStoredClips(context)
        val quotaBytes = quotaGb.toLong() * BYTES_PER_GB
        val clipsToDelete = StorageQuotaPlanner.pickClipsToDelete(clips, quotaBytes)
        clipsToDelete.forEach { clip ->
            context.contentResolver.delete(contentUriFor(clip.id), null, null)
        }
        val deletedBytes = clipsToDelete.sumOf { it.sizeBytes }
        return StorageTrimResult(
            totalBytesBefore = clips.sumOf { it.sizeBytes },
            totalBytesAfter = (clips.sumOf { it.sizeBytes } - deletedBytes).coerceAtLeast(0L),
            deletedCount = clipsToDelete.size,
        )
    }

    /**
     * Clips for the browser screen, newest first. Trimming keeps using oldest-first
     * ([queryStoredClips]) so the two concerns stay independent.
     */
    fun loadClipsNewestFirst(context: Context): List<StoredDashcamClip> {
        return queryStoredClips(context).sortedByDescending { it.createdAtEpochSeconds }
    }

    fun contentUriFor(id: Long): Uri {
        return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
    }

    private fun queryStoredClips(context: Context): List<StoredDashcamClip> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.RELATIVE_PATH,
        )

        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?" to
                arrayOf("$RELATIVE_PATH_PREFIX%", "$DISPLAY_NAME_PREFIX%")
        } else {
            "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?" to arrayOf("$DISPLAY_NAME_PREFIX%")
        }

        val clips = mutableListOf<StoredDashcamClip>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Video.Media.DATE_ADDED} ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                clips += StoredDashcamClip(
                    id = cursor.getLong(idColumn),
                    displayName = cursor.getString(nameColumn) ?: "",
                    sizeBytes = cursor.getLong(sizeColumn),
                    createdAtEpochSeconds = cursor.getLong(dateAddedColumn),
                )
            }
        }
        return clips
    }

    private const val BYTES_PER_GB = 1024L * 1024L * 1024L
}
