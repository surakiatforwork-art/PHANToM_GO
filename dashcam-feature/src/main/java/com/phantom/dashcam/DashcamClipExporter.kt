package com.phantom.dashcam

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object DashcamClipExporter {

    private const val EXPORT_RELATIVE_PATH = "Movies/PHANToM GO Exports"

    fun saveToGallery(
        context: Context,
        sourceFile: File,
        displayName: String,
        onProgress: (fraction: Float) -> Unit = {},
    ): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToGalleryScoped(context, sourceFile, displayName, onProgress)
        } else {
            saveToGalleryLegacy(context, sourceFile, displayName)
        }
    }

    private fun saveToGalleryScoped(
        context: Context,
        sourceFile: File,
        displayName: String,
        onProgress: (fraction: Float) -> Unit,
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, EXPORT_RELATIVE_PATH)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val total = sourceFile.length().coerceAtLeast(1L)
            resolver.openOutputStream(uri)?.use { output ->
                sourceFile.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            } ?: return null

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun saveToGalleryLegacy(
        context: Context,
        sourceFile: File,
        displayName: String,
    ): Uri? {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val exportDir = File(moviesDir, "PHANToM GO Exports").apply { mkdirs() }
        val outFile = File(exportDir, displayName)
        return try {
            sourceFile.copyTo(outFile, overwrite = true)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(outFile.absolutePath),
                arrayOf("video/mp4"),
                null,
            )
            Uri.fromFile(outFile)
        } catch (_: Exception) {
            null
        }
    }
}
