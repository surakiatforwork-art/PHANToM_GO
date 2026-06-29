package com.phantom.dashcam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Concatenates recorded dashcam segments into one MP4 by remuxing (no re-encode), so it is fast
 * and keeps the original quality. The merged file is written to the app cache and can then be
 * either shared out or copied into the gallery on demand.
 */
object DashcamClipMerger {

    private const val TAG = "GO_CAM_MERGE"
    private const val DEFAULT_BUFFER_SIZE = 1 shl 20 // 1 MB
    private const val FRAME_GAP_US = 33_000L
    private const val SHARE_CACHE_MAX_AGE_MS = 6L * 60L * 60L * 1000L // 6 hours

    private data class ClipSampleWindow(
        val startUs: Long,
        val endUsExclusive: Long,
    )

    /**
     * @param clips ordered oldest -> newest (playback order).
     * @param exportStartMs optional absolute trim start.
     * @param exportEndMs optional absolute trim end.
     */
    fun mergeToCache(
        context: Context,
        clips: List<StoredDashcamClip>,
        exportStartMs: Long? = null,
        exportEndMs: Long? = null,
        onProgress: (fraction: Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): File? {
        if (clips.isEmpty()) return null

        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // Keep recent exports so a pending "tap to share" notification still resolves; only purge
        // files older than the cutoff instead of clearing everything on each run.
        val staleBeforeMs = System.currentTimeMillis() - SHARE_CACHE_MAX_AGE_MS
        dir.listFiles()?.forEach { file ->
            if (file.lastModified() < staleBeforeMs) runCatching { file.delete() }
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "GOCam_merged_$stamp.mp4")

        // Remuxing can only append samples to a track of one fixed format, so pick the single video
        // format (codec + resolution) that covers the most footage in the window. Segments of a
        // different resolution — e.g. recorded across a 720p<->1080p change — are skipped instead of
        // corrupting the output (writing 1080p samples into a 720p-configured track).
        val durationByFormatUs = durationByVideoFormat(clips, exportStartMs, exportEndMs)
        val targetVideoKey = durationByFormatUs.maxByOrNull { it.value }?.key ?: return null
        val totalExpectedUs = (durationByFormatUs[targetVideoKey] ?: 1L).coerceAtLeast(1L)

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var success = false
        var cancelled = false
        var sampleCounter = 0
        var outVideoTrack = -1
        var outAudioTrack = -1
        var ptsOffsetUs = 0L
        var bufferSize = DEFAULT_BUFFER_SIZE

        try {
            for (clip in clips) {
                val window = clipSampleWindow(clip, exportStartMs, exportEndMs) ?: continue
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(clip.file.absolutePath)

                    val formats = LinkedHashMap<Int, MediaFormat>()
                    var clipVideoKey: String? = null
                    for (t in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(t)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                        }
                        if (mime.startsWith("video/")) clipVideoKey = videoFormatKey(format)
                        formats[t] = format
                    }

                    // Only merge segments whose video matches the chosen format; skipping the rest
                    // avoids corruption when the window spans a resolution/quality change.
                    if (clipVideoKey != targetVideoKey) {
                        Log.w(TAG, "Skipping segment ${clip.displayName} (format $clipVideoKey != $targetVideoKey)")
                        continue
                    }

                    val trackMap = LinkedHashMap<Int, Int>()
                    for ((t, format) in formats) {
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        when {
                            mime.startsWith("video/") -> {
                                if (outVideoTrack < 0) outVideoTrack = muxer.addTrack(format)
                                trackMap[t] = outVideoTrack
                            }
                            mime.startsWith("audio/") -> {
                                if (outAudioTrack < 0) outAudioTrack = muxer.addTrack(format)
                                trackMap[t] = outAudioTrack
                            }
                        }
                    }
                    if (trackMap.isEmpty()) continue

                    if (!muxerStarted) {
                        muxer.start()
                        muxerStarted = true
                    }

                    val buffer = ByteBuffer.allocate(bufferSize)
                    val bufferInfo = MediaCodec.BufferInfo()
                    trackMap.keys.forEach { extractor.selectTrack(it) }
                    extractor.seekTo(window.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                    var clipMaxPtsUs = ptsOffsetUs
                    var firstKeptSampleUs = -1L
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break

                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0L) break
                        if (sampleTimeUs < window.startUs) {
                            extractor.advance()
                            continue
                        }
                        if (sampleTimeUs >= window.endUsExclusive) {
                            break
                        }

                        val outTrack = trackMap[extractor.sampleTrackIndex]
                        if (outTrack != null) {
                            if (firstKeptSampleUs < 0L) {
                                firstKeptSampleUs = sampleTimeUs
                            }
                            bufferInfo.offset = 0
                            bufferInfo.size = size
                            bufferInfo.presentationTimeUs = (sampleTimeUs - firstKeptSampleUs) + ptsOffsetUs
                            bufferInfo.flags = sampleFlagsToBufferFlags(extractor.sampleFlags)
                            muxer.writeSampleData(outTrack, buffer, bufferInfo)
                            if (bufferInfo.presentationTimeUs > clipMaxPtsUs) {
                                clipMaxPtsUs = bufferInfo.presentationTimeUs
                            }
                        }
                        sampleCounter++
                        if (sampleCounter % 30 == 0) {
                            if (isCancelled()) {
                                cancelled = true
                                break
                            }
                            val writtenUs = ptsOffsetUs +
                                (if (firstKeptSampleUs >= 0L) sampleTimeUs - firstKeptSampleUs else 0L)
                            onProgress((writtenUs.toFloat() / totalExpectedUs).coerceIn(0f, 1f))
                        }
                        extractor.advance()
                    }

                    if (firstKeptSampleUs >= 0L) {
                        ptsOffsetUs = clipMaxPtsUs + FRAME_GAP_US
                    }
                    if (cancelled) break
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read segment ${clip.displayName}", e)
                } finally {
                    runCatching { extractor.release() }
                }
            }

            if (cancelled || !muxerStarted) {
                return null
            }
            success = true
            onProgress(1f)
            return outFile
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed", e)
            return null
        } finally {
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
            if (!success) runCatching { outFile.delete() }
        }
    }

    private fun clipSampleWindow(
        clip: StoredDashcamClip,
        exportStartMs: Long?,
        exportEndMs: Long?,
    ): ClipSampleWindow? {
        val clipStartMs = clip.startedAtEpochMs
        val clipEndMs = clip.endedAtEpochMs
        val overlapStartMs = maxOf(exportStartMs ?: clipStartMs, clipStartMs)
        val overlapEndMs = minOf(exportEndMs ?: clipEndMs, clipEndMs)
        if (overlapEndMs <= overlapStartMs) return null

        val startUs = (overlapStartMs - clipStartMs).coerceAtLeast(0L) * 1000L
        val endUsExclusive = maxOf(startUs + 1L, (overlapEndMs - clipStartMs).coerceAtLeast(0L) * 1000L)
        return ClipSampleWindow(startUs = startUs, endUsExclusive = endUsExclusive)
    }

    private fun videoFormatKey(format: MediaFormat): String {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "?"
        val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else -1
        val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else -1
        return "$mime|$width|$height"
    }

    private fun readVideoFormatKey(file: File): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).asSequence()
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?.let { videoFormatKey(it) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Total trimmed duration (us) of footage grouped by video format key, for the export window. */
    private fun durationByVideoFormat(
        clips: List<StoredDashcamClip>,
        exportStartMs: Long?,
        exportEndMs: Long?,
    ): Map<String, Long> {
        val byKey = HashMap<String, Long>()
        for (clip in clips) {
            val window = clipSampleWindow(clip, exportStartMs, exportEndMs) ?: continue
            val key = readVideoFormatKey(clip.file) ?: continue
            byKey[key] = (byKey[key] ?: 0L) + (window.endUsExclusive - window.startUs)
        }
        return byKey
    }

    private fun sampleFlagsToBufferFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }
}
