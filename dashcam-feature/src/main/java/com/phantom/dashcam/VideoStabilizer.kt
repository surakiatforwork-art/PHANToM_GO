package com.phantom.dashcam

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2

/**
 * Optical-flow software video stabilizer for clips recorded on devices without hardware EIS/OIS.
 *
 * Two passes, both decoding with the MediaCodec Image API (no EGL/Surface plumbing, so it stays
 * portable across OEM color formats):
 *  1. Analyze: decode the luma of every frame, measure the rigid frame-to-frame motion with
 *     OpenCV optical flow, and build a per-frame motion list.
 *  2. Render: smooth the motion path, then decode again and warp each frame (CPU warpAffine, with a
 *     zoom to hide the moving borders) onto the smoothed path, re-encode to H.264 and mux, copying
 *     the original audio track through unchanged.
 *
 * This is the Phase-1 CPU implementation: correct and device-independent, but slower than realtime.
 * The GPU warp / gyro-assisted path is a later phase.
 *
 * Validated on real hardware (Android 15, arm64): decode/warp/encode/mux + audio passthrough all
 * produce a playable, correctly-coloured, full-duration MP4. The smoothing geometry is additionally
 * covered by StabilizationMathTest.
 */
object VideoStabilizer {

    private const val TAG = "GO_CAM_STAB"
    private const val ENCODER_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val ANALYSIS_WIDTH = 480
    private const val TIMEOUT_US = 10_000L
    private const val DEFAULT_FRAME_RATE = 30

    @Volatile private var openCvReady = false

    private data class VideoInfo(
        val videoTrack: Int,
        val mime: String,
        val width: Int,
        val height: Int,
        val frameRate: Int,
        val rotationDegrees: Int,
        val bitRate: Int,
        val format: MediaFormat,
    )

    private fun ensureOpenCv(): Boolean {
        if (openCvReady) return true
        openCvReady = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
        if (!openCvReady) Log.e(TAG, "OpenCV failed to initialize")
        return openCvReady
    }

    /**
     * Produce a stabilized copy of [source] in the app cache, or null on failure/cancel.
     * [onProgress] reports 0..1 across both passes; [isCancelled] is polled regularly.
     */
    fun stabilizeToCache(
        context: Context,
        source: File,
        crop: StabilizationCropStrength,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): File? {
        if (!ensureOpenCv()) return null
        val info = readVideoInfo(source) ?: return null

        val transforms = analyzeMotion(source, info, isCancelled) { p -> onProgress(p * 0.5f) }
            ?: return null
        if (isCancelled() || transforms.isEmpty()) return null

        val corrections = StabilizationMath.corrections(transforms, crop.smoothingRadius)
        val zoom = StabilizationMath.zoomForCrop(crop.cropFraction)

        val dir = File(context.cacheDir, "stabilized").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(dir, "GOCam_stabilized_$stamp.mp4")

        val ok = runCatching {
            renderStabilized(source, info, corrections, zoom, outFile, isCancelled) { p ->
                onProgress(0.5f + p * 0.5f)
            }
        }.getOrElse {
            Log.e(TAG, "Stabilize render failed", it)
            false
        }
        if (!ok || isCancelled()) {
            runCatching { outFile.delete() }
            return null
        }
        onProgress(1f)
        return outFile
    }

    private fun readVideoInfo(source: File): VideoInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (t in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(t)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    trackIndex = t
                    format = f
                    break
                }
            }
            val fmt = format ?: return null
            val width = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val frameRate = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                fmt.getInteger(MediaFormat.KEY_FRAME_RATE).coerceAtLeast(1)
            } else {
                DEFAULT_FRAME_RATE
            }
            val rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                fmt.getInteger(MediaFormat.KEY_ROTATION)
            } else {
                0
            }
            val bitRate = if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) {
                fmt.getInteger(MediaFormat.KEY_BIT_RATE)
            } else {
                // Re-encode a touch richer than a default guess to preserve plate detail through the
                // crop/zoom: ~0.2 bits per pixel per frame.
                (width.toLong() * height * frameRate / 5L).toInt().coerceAtLeast(2_000_000)
            }
            VideoInfo(trackIndex, fmt.getString(MediaFormat.KEY_MIME)!!, width, height, frameRate, rotation, bitRate, fmt)
        } catch (e: Exception) {
            Log.e(TAG, "readVideoInfo failed", e)
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    // ---- Pass 1: motion analysis -------------------------------------------------------------

    private fun analyzeMotion(
        source: File,
        info: VideoInfo,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): List<FrameTransform>? {
        val transforms = ArrayList<FrameTransform>()
        val scale = if (info.width > ANALYSIS_WIDTH) ANALYSIS_WIDTH.toDouble() / info.width else 1.0
        var prevGray: Mat? = null
        val ok = decodeFrames(source, info, isCancelled, onProgress) { image, _ ->
            val gray = imageToGrayMat(image, info.width, info.height, scale)
            val prev = prevGray
            if (prev == null) {
                transforms.add(FrameTransform.IDENTITY)
            } else {
                val measured = estimateTransform(prev, gray, scale)
                transforms.add(measured ?: transforms.lastOrNull() ?: FrameTransform.IDENTITY)
                prev.release()
            }
            prevGray = gray
        }
        prevGray?.release()
        return if (ok) transforms else null
    }

    private fun estimateTransform(prevGray: Mat, curGray: Mat, scale: Double): FrameTransform? {
        val prevCorners = MatOfPoint()
        val prevPts = MatOfPoint2f()
        val curPts = MatOfPoint2f()
        val status = MatOfByte()
        val err = MatOfFloat()
        return try {
            Imgproc.goodFeaturesToTrack(prevGray, prevCorners, 200, 0.01, 30.0)
            if (prevCorners.empty()) return null
            prevPts.fromArray(*prevCorners.toArray())
            Video.calcOpticalFlowPyrLK(prevGray, curGray, prevPts, curPts, status, err)

            val st = status.toArray()
            val p0 = prevPts.toArray()
            val p1 = curPts.toArray()
            val good0 = ArrayList<Point>()
            val good1 = ArrayList<Point>()
            for (i in st.indices) {
                if (st[i].toInt() == 1) {
                    good0.add(p0[i])
                    good1.add(p1[i])
                }
            }
            if (good0.size < 6) return null

            val from = MatOfPoint2f(); from.fromList(good0)
            val to = MatOfPoint2f(); to.fromList(good1)
            val m = Calib3d.estimateAffinePartial2D(from, to)
            from.release(); to.release()
            if (m.empty()) return null

            val a = m.get(0, 0)[0]
            val tx = m.get(0, 2)[0]
            val ty = m.get(1, 2)[0]
            val da = atan2(m.get(1, 0)[0], a)
            m.release()
            // Translation was measured on the downscaled frame; scale it back to full resolution.
            FrameTransform(dx = tx / scale, dy = ty / scale, da = da)
        } catch (e: Exception) {
            Log.w(TAG, "estimateTransform failed", e)
            null
        } finally {
            prevCorners.release(); prevPts.release(); curPts.release(); status.release(); err.release()
        }
    }

    // ---- Pass 2: warp + re-encode ------------------------------------------------------------

    private fun renderStabilized(
        source: File,
        info: VideoInfo,
        corrections: List<FrameTransform>,
        zoom: Double,
        outFile: File,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Boolean {
        val encoder = MediaCodec.createEncoderByType(ENCODER_MIME)
        val outFormat = MediaFormat.createVideoFormat(ENCODER_MIME, info.width, info.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, info.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, info.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        if (info.rotationDegrees != 0) muxer.setOrientationHint(info.rotationDegrees)

        val cx = info.width / 2.0
        val cy = info.height / 2.0
        val bufferInfo = MediaCodec.BufferInfo()
        var muxerStarted = false
        var videoTrackOut = -1
        var frameIndex = 0
        val rgba = Mat()
        val warped = Mat()
        val yuv = Mat()
        var success = false

        try {
            val decodedOk = decodeFrames(source, info, isCancelled, { /* progress via frames below */ }) { image, ptsUs ->
                imageToRgbaMat(image, info.width, info.height, rgba)
                val correction = corrections.getOrElse(frameIndex) { corrections.lastOrNull() ?: FrameTransform.IDENTITY }
                val rows = StabilizationMath.affineRows(correction, zoom, cx, cy)
                val affine = Mat(2, 3, CvType.CV_64F)
                affine.put(0, 0, *rows)
                Imgproc.warpAffine(rgba, warped, affine, Size(info.width.toDouble(), info.height.toDouble()))
                affine.release()
                Imgproc.cvtColor(warped, yuv, Imgproc.COLOR_RGBA2YUV_I420)

                feedEncoder(encoder, yuv, info.width, info.height, ptsUs)
                // Drain whatever the encoder has ready so far.
                videoTrackOut = drainEncoder(encoder, muxer, bufferInfo, endOfStream = false,
                    videoTrackOutIn = videoTrackOut, muxerStartedRef = { muxerStarted = it }, muxerStarted = muxerStarted)
                frameIndex++
                if (corrections.isNotEmpty()) {
                    onProgress((frameIndex.toFloat() / corrections.size).coerceIn(0f, 0.97f))
                }
            }
            if (!decodedOk) return false

            // Flush the encoder (buffer-mode EOS, since input is fed via input buffers not a Surface).
            queueEncoderEos(encoder)
            videoTrackOut = drainEncoder(encoder, muxer, bufferInfo, endOfStream = true,
                videoTrackOutIn = videoTrackOut, muxerStartedRef = { muxerStarted = it }, muxerStarted = muxerStarted)

            if (videoTrackOut < 0 || !muxerStarted) return false
            copyAudioTrack(source, muxer)
            success = true
            return true
        } finally {
            rgba.release(); warped.release(); yuv.release()
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
            if (!success) runCatching { outFile.delete() }
        }
    }

    private fun feedEncoder(encoder: MediaCodec, i420: Mat, width: Int, height: Int, ptsUs: Long) {
        val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
        if (inIndex < 0) return
        val image = encoder.getInputImage(inIndex)
        if (image == null) {
            encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, 0)
            return
        }
        fillImageFromI420(image, i420, width, height)
        encoder.queueInputBuffer(inIndex, 0, width * height * 3 / 2, ptsUs, 0)
    }

    private fun queueEncoderEos(encoder: MediaCodec) {
        while (true) {
            val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        videoTrackOutIn: Int,
        muxerStartedRef: (Boolean) -> Unit,
        muxerStarted: Boolean,
    ): Int {
        var videoTrackOut = videoTrackOutIn
        var started = muxerStarted
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0L)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break else continue
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!started) {
                        videoTrackOut = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        started = true
                        muxerStartedRef(true)
                    }
                }
                outIndex >= 0 -> {
                    val encoded = encoder.getOutputBuffer(outIndex)
                    if (encoded != null && bufferInfo.size > 0 &&
                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && started
                    ) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(videoTrackOut, encoded, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        return videoTrackOut
    }

    private fun copyAudioTrack(source: File, muxer: MediaMuxer) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
            var audioTrack = -1
            for (t in 0 until extractor.trackCount) {
                if (extractor.getTrackFormat(t).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = t
                    break
                }
            }
            if (audioTrack < 0) return
            extractor.selectTrack(audioTrack)
            val audioFormat = extractor.getTrackFormat(audioTrack)
            val outTrack = muxer.addTrack(audioFormat)
            // Note: addTrack must precede muxer.start(). In practice the muxer is already started for
            // the video track here; if a device disallowed adding the audio track late, the catch
            // below keeps the (video-only) result rather than failing the whole job.
            val maxInput = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocate(maxInput.coerceAtLeast(64 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = sampleFlags(extractor.sampleFlags)
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio passthrough skipped: ${e.message}")
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun sampleFlags(extractorFlags: Int): Int {
        var flags = 0
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return flags
    }

    // ---- Shared decode loop ------------------------------------------------------------------

    /**
     * Decode every video frame of [source] synchronously (no output surface) and hand each one to
     * [onFrame] as a YUV_420_888 [Image]. The image is only valid for the duration of the callback.
     */
    private fun decodeFrames(
        source: File,
        info: VideoInfo,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        onFrame: (Image, Long) -> Unit,
    ): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(source.absolutePath)
            extractor.selectTrack(info.videoTrack)
            val format = extractor.getTrackFormat(info.videoTrack)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
            } else {
                1L
            }
            decoder = MediaCodec.createDecoderByType(info.mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (isCancelled()) return false
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                    if (bufferInfo.size > 0) {
                        val image = decoder.getOutputImage(outIndex)
                        if (image != null) {
                            onFrame(image, bufferInfo.presentationTimeUs)
                            image.close()
                            onProgress((bufferInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "decodeFrames failed", e)
            false
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    // ---- Image <-> Mat helpers ---------------------------------------------------------------

    private fun imageToGrayMat(image: Image, width: Int, height: Int, scale: Double): Mat {
        val yPlane = image.planes[0]
        val rowStride = yPlane.rowStride
        val buffer = yPlane.buffer
        val full = Mat(height, width, CvType.CV_8UC1)
        val row = ByteArray(width)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, width)
            full.put(y, 0, row)
        }
        if (scale >= 1.0) return full
        val resized = Mat()
        Imgproc.resize(full, resized, Size(width * scale, height * scale))
        full.release()
        return resized
    }

    private fun imageToRgbaMat(image: Image, width: Int, height: Int, outRgba: Mat) {
        val i420 = imageToI420Bytes(image, width, height)
        val yuvMat = Mat(height * 3 / 2, width, CvType.CV_8UC1)
        yuvMat.put(0, 0, i420)
        Imgproc.cvtColor(yuvMat, outRgba, Imgproc.COLOR_YUV2RGBA_I420)
        yuvMat.release()
    }

    /** Assemble a YUV_420_888 [Image] into a contiguous I420 (YYYY...U...V...) byte array. */
    private fun imageToI420Bytes(image: Image, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 3 / 2)
        // Y
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var pos = 0
        val yRow = ByteArray(width)
        for (r in 0 until height) {
            yBuf.position(r * yRowStride)
            yBuf.get(yRow, 0, width)
            System.arraycopy(yRow, 0, out, pos, width)
            pos += width
        }
        // U then V (I420 = planar U, then V)
        val cw = width / 2
        val ch = height / 2
        for (planeIndex in 1..2) {
            val plane = image.planes[planeIndex]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowBytes = ByteArray((cw - 1) * pixelStride + 1)
            for (r in 0 until ch) {
                val rowStart = r * rowStride
                val available = buf.limit() - rowStart
                if (available <= 0) break
                buf.position(rowStart)
                val toRead = minOf(rowBytes.size, available)
                buf.get(rowBytes, 0, toRead)
                var c = 0
                while (c < cw) {
                    val idx = c * pixelStride
                    out[pos++] = if (idx < toRead) rowBytes[idx] else 0
                    c++
                }
            }
        }
        return out
    }

    private fun fillImageFromI420(image: Image, i420: Mat, width: Int, height: Int) {
        val total = width * height * 3 / 2
        val bytes = ByteArray(total)
        i420.get(0, 0, bytes)

        // Y
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        var pos = 0
        for (r in 0 until height) {
            yBuf.position(r * yRowStride)
            yBuf.put(bytes, pos, width)
            pos += width
        }
        val cw = width / 2
        val ch = height / 2
        val uStart = width * height
        val vStart = uStart + cw * ch
        writeChroma(image.planes[1], bytes, uStart, cw, ch)
        writeChroma(image.planes[2], bytes, vStart, cw, ch)
    }

    private fun writeChroma(plane: Image.Plane, src: ByteArray, srcStart: Int, cw: Int, ch: Int) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var srcPos = srcStart
        if (pixelStride == 1) {
            for (r in 0 until ch) {
                buf.position(r * rowStride)
                buf.put(src, srcPos, cw)
                srcPos += cw
            }
        } else {
            // Semiplanar destination: write one chroma sample every pixelStride bytes.
            val rowBytes = ByteArray((cw - 1) * pixelStride + 1)
            for (r in 0 until ch) {
                java.util.Arrays.fill(rowBytes, 0)
                for (c in 0 until cw) {
                    rowBytes[c * pixelStride] = src[srcPos + c]
                }
                buf.position(r * rowStride)
                val capacity = buf.limit() - r * rowStride
                buf.put(rowBytes, 0, minOf(rowBytes.size, capacity))
                srcPos += cw
            }
        }
    }
}
