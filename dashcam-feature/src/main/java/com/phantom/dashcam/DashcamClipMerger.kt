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
    ): File? {
        if (clips.isEmpty()) return null

        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "GOCam_merged_$stamp.mp4")

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var outVideoTrack = -1
        var outAudioTrack = -1
        var videoMime: String? = null
        var ptsOffsetUs = 0L
        var bufferSize = DEFAULT_BUFFER_SIZE

        try {
            for ((index, clip) in clips.withIndex()) {
                val window = clipSampleWindow(clip, exportStartMs, exportEndMs) ?: continue
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(clip.file.absolutePath)

                    val trackMap = LinkedHashMap<Int, Int>()
                    var clipVideoMime: String? = null
                    for (t in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(t)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                            bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                        }
                        when {
                            mime.startsWith("video/") -> {
                                clipVideoMime = mime
                                if (index == 0) {
                                    outVideoTrack = muxer.addTrack(format)
                                    videoMime = mime
                                    trackMap[t] = outVideoTrack
                                } else if (outVideoTrack >= 0 && mime == videoMime) {
                                    trackMap[t] = outVideoTrack
                                }
                            }
                            mime.startsWith("audio/") -> {
                                if (index == 0) {
                                    outAudioTrack = muxer.addTrack(format)
                                    trackMap[t] = outAudioTrack
                                } else if (outAudioTrack >= 0) {
                                    trackMap[t] = outAudioTrack
                                }
                            }
                        }
                    }

                    if (index != 0 && clipVideoMime != null && clipVideoMime != videoMime) {
                        Log.w(TAG, "Skipping incompatible segment ${clip.displayName} ($clipVideoMime)")
                        continue
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
                        extractor.advance()
                    }

                    if (firstKeptSampleUs >= 0L) {
                        ptsOffsetUs = clipMaxPtsUs + FRAME_GAP_US
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read segment ${clip.displayName}", e)
                } finally {
                    runCatching { extractor.release() }
                }
            }

            if (!muxerStarted) {
                runCatching { outFile.delete() }
                return null
            }
            return outFile
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed", e)
            runCatching { outFile.delete() }
            return null
        } finally {
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
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

    private fun sampleFlagsToBufferFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }
}
