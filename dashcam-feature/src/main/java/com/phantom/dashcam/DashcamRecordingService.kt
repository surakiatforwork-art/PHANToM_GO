package com.phantom.dashcam

import android.annotation.SuppressLint
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class DashcamRecordingService : LifecycleService() {

    private data class PreviewTargets(
        val main: Preview.SurfaceProvider?,
        val secondary: Preview.SurfaceProvider?,
    )

    inner class LocalBinder : Binder() {
        val service: DashcamRecordingService
            get() = this@DashcamRecordingService
    }

    private interface ActiveSession {
        val activeMode: DashcamMode
        fun stop()
        fun attachPreviews(previewTargets: PreviewTargets)
        fun updateRotation(rotation: Int)
    }

    private inner class SingleCameraSession(
        override val activeMode: DashcamMode,
        private val preview: Preview,
        private val videoCapture: VideoCapture<Recorder>,
        private val overlayEffect: OverlayEffect,
        private val recorder: SegmentedRecorder,
    ) : ActiveSession {
        override fun stop() {
            runCatching { recorder.stop() }
            runCatching { overlayEffect.close() }
        }

        override fun attachPreviews(previewTargets: PreviewTargets) {
            preview.setSurfaceProvider(previewTargets.main)
        }

        override fun updateRotation(rotation: Int) {
            preview.targetRotation = rotation
            videoCapture.targetRotation = rotation
        }
    }

    private inner class DualCameraSession(
        private val rearPreview: Preview,
        private val frontPreview: Preview,
        private val rearVideoCapture: VideoCapture<Recorder>,
        private val frontVideoCapture: VideoCapture<Recorder>,
        private val rearRecorder: SegmentedRecorder,
        private val frontRecorder: SegmentedRecorder,
        private val rearOverlay: OverlayEffect,
        private val frontOverlay: OverlayEffect,
    ) : ActiveSession {
        override val activeMode: DashcamMode = DashcamMode.DUAL

        override fun stop() {
            runCatching { rearRecorder.stop() }
            runCatching { frontRecorder.stop() }
            runCatching { rearOverlay.close() }
            runCatching { frontOverlay.close() }
        }

        override fun attachPreviews(previewTargets: PreviewTargets) {
            rearPreview.setSurfaceProvider(previewTargets.main)
            frontPreview.setSurfaceProvider(previewTargets.secondary)
        }

        override fun updateRotation(rotation: Int) {
            rearPreview.targetRotation = rotation
            frontPreview.targetRotation = rotation
            rearVideoCapture.targetRotation = rotation
            frontVideoCapture.targetRotation = rotation
        }
    }

    /**
     * Records a single camera as a chain of short, self-contained MP4 segments. Each segment is
     * finalized via CameraX's duration limit, so a sudden power loss only ever risks the segment
     * that is currently being written — every earlier segment is already a complete, playable
     * file. When one segment finalizes for a planned split we immediately start the next one.
     */
    private inner class SegmentedRecorder(
        private val output: Recorder,
        private val cameraLabel: String,
        private val onClipSaved: (String?) -> Unit,
    ) {
        @Volatile private var stopped = false
        @Volatile private var current: Recording? = null

        fun start() {
            stopped = false
            // Kick the first segment off the recording executor so the whole segment loop — and its
            // file I/O — stays off the main thread. Using the same single-thread executor as the
            // start-time storage cleanup guarantees cleanup runs before this pending file is created.
            recordingCallbackExecutor.execute { beginSegment() }
        }

        fun stop() {
            stopped = true
            runCatching { current?.stop() }
            current = null
        }

        @SuppressLint("MissingPermission")
        private fun beginSegment() {
            if (stopped) return
            try {
                beginSegmentInternal()
            } catch (t: Throwable) {
                if (!stopped) {
                    mainHandler.post { stopRecording("Unable to continue recording: ${t.message ?: "error"}") }
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun beginSegmentInternal() {
            val displayNameBase = buildFileName(cameraLabel)
            val pendingFile = DashcamClipStorageManager.createPendingClipFile(
                this@DashcamRecordingService,
                displayNameBase,
            ).apply {
                parentFile?.mkdirs()
                if (exists()) delete()
            }
            val finalFile = DashcamClipStorageManager.finalizedClipFile(
                this@DashcamRecordingService,
                displayNameBase,
            ).apply {
                if (exists()) delete()
            }
            val outputOptions = FileOutputOptions.Builder(pendingFile)
                .setDurationLimitMillis(SEGMENT_DURATION_MS)
                .build()

            val audioPermissionGranted = ContextCompat.checkSelfPermission(
                this@DashcamRecordingService,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED

            if (audioPermissionGranted) {
                routeAudioToBuiltInMicBestEffort()
            }

            val pendingRecording = output.prepareRecording(this@DashcamRecordingService, outputOptions)
                .let { prepared ->
                    if (audioPermissionGranted) {
                        prepared.withAudioEnabled()
                    } else {
                        DashcamStateStore.update {
                            it.copy(detailText = getString(R.string.audio_missing_detail))
                        }
                        prepared
                    }
                }

            current = pendingRecording.start(recordingCallbackExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        DashcamStateStore.update {
                            it.copy(
                                isPreparing = false,
                                isRecording = true,
                                audioPermissionGranted = audioPermissionGranted,
                            )
                        }
                    }

                    is VideoRecordEvent.Finalize -> {
                        val code = event.error
                        val segmentComplete = code == VideoRecordEvent.Finalize.ERROR_NONE ||
                            code == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
                            code == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
                        if (segmentComplete) {
                            val committed = DashcamClipStorageManager.commitPendingClip(pendingFile, finalFile)
                            if (!committed) {
                                if (!stopped) {
                                    mainHandler.post {
                                        stopRecording("Could not finalize the $cameraLabel camera clip.")
                                    }
                                }
                                return@start
                            }
                            onClipSaved(finalFile.name)
                            val plannedSplit = code != VideoRecordEvent.Finalize.ERROR_NONE
                            if (!stopped && plannedSplit) {
                                // The finished segment is safe; keep the loop going. Trim only once
                                // every few segments instead of every minute so the quota scan (file
                                // I/O) stays off the recording cadence. This callback already runs on
                                // the background recording executor, so the trim never blocks capture.
                                if (segmentsSinceTrim.incrementAndGet() >= TRIM_EVERY_N_SEGMENTS) {
                                    segmentsSinceTrim.set(0)
                                    trimStoredClipsToQuota(notifyUser = false)
                                }
                                beginSegment()
                            } else {
                                segmentsSinceTrim.set(0)
                                trimStoredClipsToQuota(notifyUser = true)
                            }
                        } else if (!stopped) {
                            mainHandler.post {
                                stopRecording("Recording failed on $cameraLabel camera (code $code).")
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appMainExecutor by lazy { ContextCompat.getMainExecutor(this) }

    private val segmentsSinceTrim = AtomicInteger(0)

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeSession: ActiveSession? = null
    private var previewTargets = PreviewTargets(null, null)
    private var targetRotation: Int = Surface.ROTATION_0
    private var recordingStartRotation: Int = Surface.ROTATION_0
    private var pendingStartTrigger: String? = null

    override fun onCreate() {
        super.onCreate()
        DashcamNotificationHelper.ensureChannels(this)
        DashcamStateStore.bootstrap(this)
        ProcessCameraProvider.getInstance(this).apply {
            addListener(
                {
                    cameraProvider = get()
                    pendingStartTrigger?.let { trigger ->
                        pendingStartTrigger = null
                        startRecording(trigger)
                    }
                },
                appMainExecutor,
            )
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                // A fresh start clears any previous manual-stop so the watchdog may auto-resume.
                DashcamPreferences(this).setUserStopped(false)
                targetRotation = intent.getIntExtra(EXTRA_ROTATION, SurfaceRotationStore.getRotationHint())
                startRecording(intent.getStringExtra(EXTRA_TRIGGER) ?: TRIGGER_MANUAL)
            }
            ACTION_STOP -> {
                if (intent.getBooleanExtra(EXTRA_USER_INITIATED, false)) {
                    DashcamPreferences(this).setUserStopped(true)
                }
                stopRecording(intent.getStringExtra(EXTRA_STOP_REASON) ?: "Stopped")
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        activeSession?.stop()
        cameraProvider?.unbindAll()
        activeSession = null
        // NOTE: the recording executor is a process-lifetime singleton and is intentionally NOT shut
        // down here. CameraX delivers the final VideoRecordEvent.Finalize (which commits the last
        // segment from its ".pending-" temp to its finalized name) asynchronously, often after the
        // service has already been torn down by stopRecording -> stopSelf. Shutting the executor down
        // in onDestroy rejected that final task, leaving the last clip stuck as an uncommitted
        // ".pending-" file that never appears in the clip list.
        super.onDestroy()
    }

    fun setPreviewSurfaces(
        mainSurfaceProvider: Preview.SurfaceProvider?,
        secondarySurfaceProvider: Preview.SurfaceProvider?,
    ) {
        previewTargets = PreviewTargets(mainSurfaceProvider, secondarySurfaceProvider)
        activeSession?.attachPreviews(previewTargets)
    }

    fun updateTargetRotation(rotation: Int) {
        if (activeSession == null) {
            targetRotation = rotation
        }
    }

    private fun startRecording(trigger: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            DashcamStateStore.update {
                it.copy(
                    permissionGranted = false,
                    isPreparing = false,
                    isRecording = false,
                    statusText = getString(R.string.status_idle),
                    detailText = "Camera permission is missing. Open GO Cam and grant permission first.",
                )
            }
            DashcamNotificationHelper.showPowerStartNotification(this)
            stopSelf()
            return
        }

        val provider = cameraProvider
        if (provider == null) {
            pendingStartTrigger = trigger
            DashcamStateStore.update {
                it.copy(
                    isPreparing = true,
                    isRecording = false,
                    statusText = getString(R.string.status_preparing),
                    detailText = "Camera provider is still starting up.",
                    backgroundStartPending = false,
                )
            }
            promoteToForeground(getString(R.string.notification_recording_text))
            return
        }

        DashcamNotificationHelper.cancelPowerStartNotification(this)
        activeSession?.stop()
        provider.unbindAll()
        activeSession = null
        // Cleanup + quota trim list every clip and (for cleanup) open suspect files, so run them on
        // the recording executor. Because it is single-threaded and the new session's first segment
        // is also posted there (SegmentedRecorder.start), cleanup is guaranteed to finish before the
        // first pending file is created — no race with the just-deleted ".pending-" temp files.
        segmentsSinceTrim.set(0)
        recordingCallbackExecutor.execute {
            DashcamClipStorageManager.cleanupIncompleteClips(this)
            trimStoredClipsToQuota(notifyUser = false)
        }

        DashcamStateStore.syncPreferences(this)
        val requestedMode = DashcamPreferences(this).getMode()
        val previewEnabled = DashcamPreferences(this).isPreviewEnabled()
        recordingStartRotation = targetRotation

        DashcamStateStore.update {
            it.copy(
                isPreparing = true,
                isRecording = false,
                statusText = getString(R.string.status_preparing),
                detailText = "Starting ${requestedMode.label.lowercase(Locale.US)} camera recording.",
                backgroundStartPending = false,
                previewAvailable = true,
                activeMode = null,
            )
        }
        promoteToForeground(getString(R.string.notification_recording_text))

        activeSession = runCatching {
            when (requestedMode) {
                DashcamMode.REAR -> createSingleCameraSession(provider, DashcamMode.REAR, previewEnabled)
                DashcamMode.FRONT -> createSingleCameraSession(provider, DashcamMode.FRONT, previewEnabled)
                DashcamMode.DUAL -> createDualCameraSession(provider)
            }
        }.getOrElse { dualError ->
            if (requestedMode == DashcamMode.DUAL) {
                DashcamStateStore.update {
                    it.copy(
                        detailText = getString(R.string.dual_not_supported_detail),
                        previewAvailable = true,
                    )
                }
                createSingleCameraSession(provider, DashcamMode.REAR, previewEnabled)
            } else {
                stopRecording("Unable to start recording: ${dualError.message ?: "unknown error"}")
                return
            }
        }

        DashcamStateStore.update {
            it.copy(
                isPreparing = false,
                isRecording = true,
                activeMode = activeSession?.activeMode,
                statusText = getString(R.string.status_recording),
                detailText = buildRecordingDetail(activeSession?.activeMode ?: requestedMode, trigger),
                previewAvailable = true,
                backgroundStartPending = false,
            )
        }
        promoteToForeground(buildRecordingDetail(activeSession?.activeMode ?: requestedMode, trigger))
    }

    private fun createSingleCameraSession(
        provider: ProcessCameraProvider,
        mode: DashcamMode,
        previewEnabled: Boolean,
    ): ActiveSession {
        val selector = when (mode) {
            DashcamMode.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        require(provider.hasCamera(selector)) { "${mode.label} camera is not available." }

        val preview = Preview.Builder()
            .setTargetRotation(recordingStartRotation)
            .build()
        val overlay = createOverlayEffect(cameraLabel = mode.label, surfaceRotation = recordingStartRotation)
        val videoCapture = buildVideoCapture(selectedSingleCameraQuality(), stabilize = stabilizationRequested())
        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(videoCapture)
            .addEffect(overlay)
            .build()

        provider.bindToLifecycle(this, selector, useCaseGroup)
        preview.setSurfaceProvider(if (previewEnabled) previewTargets.main else null)

        val recorder = SegmentedRecorder(
            output = videoCapture.output,
            cameraLabel = mode.label,
            onClipSaved = ::onClipSaved,
        )
        recorder.start()
        return SingleCameraSession(mode, preview, videoCapture, overlay, recorder)
    }

    private fun onClipSaved(displayName: String?) {
        if (displayName != null) {
            DashcamStateStore.update {
                it.copy(lastOutputLabel = getString(R.string.last_output_prefix, displayName))
            }
        }
    }

    private fun createDualCameraSession(provider: ProcessCameraProvider): ActiveSession {
        require(supportsFrontRearConcurrent(provider)) { getString(R.string.dual_not_supported_detail) }
        require(provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) { "Rear camera is not available." }
        require(provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) { "Front camera is not available." }

        val rearPreview = Preview.Builder()
            .setTargetRotation(recordingStartRotation)
            .build()
        val frontPreview = Preview.Builder()
            .setTargetRotation(recordingStartRotation)
            .build()
        val rearOverlay = createOverlayEffect("Rear", recordingStartRotation)
        val frontOverlay = createOverlayEffect("Front", recordingStartRotation)
        val rearVideoCapture = buildVideoCapture(Quality.HD, stabilize = false)
        val frontVideoCapture = buildVideoCapture(Quality.HD, stabilize = false)

        val rearGroup = UseCaseGroup.Builder()
            .addUseCase(rearPreview)
            .addUseCase(rearVideoCapture)
            .addEffect(rearOverlay)
            .build()
        val frontGroup = UseCaseGroup.Builder()
            .addUseCase(frontPreview)
            .addUseCase(frontVideoCapture)
            .addEffect(frontOverlay)
            .build()

        provider.bindToLifecycle(
            listOf(
                ConcurrentCamera.SingleCameraConfig(
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    rearGroup,
                    this,
                ),
                ConcurrentCamera.SingleCameraConfig(
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    frontGroup,
                    this,
                ),
            ),
        )
        rearPreview.setSurfaceProvider(previewTargets.main)
        frontPreview.setSurfaceProvider(previewTargets.secondary)

        val rearRecorder = SegmentedRecorder(rearVideoCapture.output, "Rear", ::onClipSaved)
        val frontRecorder = SegmentedRecorder(frontVideoCapture.output, "Front", ::onClipSaved)
        rearRecorder.start()
        frontRecorder.start()
        return DualCameraSession(
            rearPreview = rearPreview,
            frontPreview = frontPreview,
            rearVideoCapture = rearVideoCapture,
            frontVideoCapture = frontVideoCapture,
            rearRecorder = rearRecorder,
            frontRecorder = frontRecorder,
            rearOverlay = rearOverlay,
            frontOverlay = frontOverlay,
        )
    }

    // Single-camera recording uses the user-selected quality (720p default keeps the device
    // responsive). Dual mode stays at HD because concurrent cameras are capped on most devices.
    private fun selectedSingleCameraQuality(): Quality {
        return when (DashcamPreferences(this).getQuality()) {
            DashcamQuality.FHD -> Quality.FHD
            DashcamQuality.HD -> Quality.HD
        }
    }

    private fun stabilizationRequested(): Boolean {
        return DashcamPreferences(this).isStabilizationEnabled() &&
            DashcamCapabilities.supportsVideoStabilization(this)
    }

    // CameraX exposes setAudioSource as a library-group restricted API; the prototype
    // intentionally pins the built-in mic. Behaviour is unchanged from the tested build.
    @SuppressLint("RestrictedApi", "WrongConstant")
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun buildVideoCapture(preferredQuality: Quality, stabilize: Boolean): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    preferredQuality,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .build()
        val builder = VideoCapture.Builder(recorder)
            .setTargetRotation(recordingStartRotation)
        if (stabilize) {
            // Hardware EIS/OIS — negligible CPU cost. EIS crops the frame slightly (narrower FOV).
            // Unsupported options are ignored by the camera HAL.
            val extender = Camera2Interop.Extender(builder)
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
            )
            extender.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
            )
        }
        return builder.build()
    }

    private fun createOverlayEffect(
        cameraLabel: String,
        surfaceRotation: Int,
    ): OverlayEffect {
        return OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, mainHandler) { throwable ->
            DashcamStateStore.update {
                it.copy(
                    detailText = "Overlay failed: ${throwable.message ?: "unknown error"}",
                )
            }
        }.apply {
            val timestampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 34f
                setShadowLayer(6f, 0f, 0f, Color.BLACK)
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 26f
                isFakeBoldText = true
            }
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(170, 0, 0, 0)
            }

            // The overlay is drawn on every recorded frame, so keep per-frame work minimal: the
            // burned-in timestamp stays visible on every frame but is only re-formatted/measured
            // once per second, and the transform matrix is reused instead of re-allocated.
            val line1 = "$cameraLabel camera"
            val reusableMatrix = Matrix()
            var cachedSecond = -1L
            var cachedTimestamp = ""
            var cachedBoxWidth = 0f
            var cachedBoxHeight = 0f

            setOnDrawListener { frame ->
                val nowSecond = System.currentTimeMillis() / 1000L
                if (nowSecond != cachedSecond) {
                    cachedSecond = nowSecond
                    cachedTimestamp = overlayDateFormatter.format(Date(nowSecond * 1000L))
                    cachedBoxWidth = maxOf(
                        labelPaint.measureText(line1),
                        timestampPaint.measureText(cachedTimestamp),
                    ) + OVERLAY_PADDING * 2
                    cachedBoxHeight = timestampPaint.fontSpacing + labelPaint.fontSpacing + OVERLAY_PADDING * 2
                }
                val canvas = frame.overlayCanvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawTimestampOverlay(
                    canvas = canvas,
                    line1 = line1,
                    line2 = cachedTimestamp,
                    boxWidth = cachedBoxWidth,
                    boxHeight = cachedBoxHeight,
                    surfaceRotation = surfaceRotation,
                    labelPaint = labelPaint,
                    textPaint = timestampPaint,
                    boxPaint = boxPaint,
                    orientationMatrix = reusableMatrix,
                )
                true
            }
        }
    }

    private fun drawTimestampOverlay(
        canvas: Canvas,
        line1: String,
        line2: String,
        boxWidth: Float,
        boxHeight: Float,
        surfaceRotation: Int,
        labelPaint: Paint,
        textPaint: Paint,
        boxPaint: Paint,
        orientationMatrix: Matrix,
    ) {
        val padding = OVERLAY_PADDING
        val corner = 18f
        val lineHeight = textPaint.fontSpacing

        val logicalWidth: Float
        val logicalHeight: Float
        orientationMatrix.reset()
        canvas.save()
        when (surfaceRotation) {
            Surface.ROTATION_0 -> {
                orientationMatrix.postRotate(-90f)
                orientationMatrix.postTranslate(0f, canvas.height.toFloat())
                canvas.concat(orientationMatrix)
                logicalWidth = canvas.height.toFloat()
                logicalHeight = canvas.width.toFloat()
            }

            Surface.ROTATION_180 -> {
                orientationMatrix.postRotate(90f)
                orientationMatrix.postTranslate(canvas.width.toFloat(), 0f)
                canvas.concat(orientationMatrix)
                logicalWidth = canvas.height.toFloat()
                logicalHeight = canvas.width.toFloat()
            }

            Surface.ROTATION_270 -> {
                orientationMatrix.postRotate(180f)
                orientationMatrix.postTranslate(canvas.width.toFloat(), canvas.height.toFloat())
                canvas.concat(orientationMatrix)
                logicalWidth = canvas.width.toFloat()
                logicalHeight = canvas.height.toFloat()
            }

            else -> {
                logicalWidth = canvas.width.toFloat()
                logicalHeight = canvas.height.toFloat()
            }
        }

        val left = 24f
        val top = logicalHeight - boxHeight - 24f
        val rect = RectF(left, top, left + boxWidth, top + boxHeight)
        canvas.drawRoundRect(rect, corner, corner, boxPaint)
        canvas.drawText(line1, left + padding, top + padding + labelPaint.textSize, labelPaint)
        canvas.drawText(line2, left + padding, top + padding + labelPaint.textSize + lineHeight, textPaint)
        canvas.restore()
    }

    private fun stopRecording(reason: String) {
        activeSession?.stop()
        cameraProvider?.unbindAll()
        activeSession = null
        DashcamNotificationHelper.cancelPowerStartNotification(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        DashcamStateStore.update {
            it.copy(
                activeMode = null,
                isPreparing = false,
                isRecording = false,
                statusText = getString(R.string.status_idle),
                detailText = reason,
                previewAvailable = true,
                backgroundStartPending = false,
            )
        }
        stopSelf()
    }

    private fun promoteToForeground(detailText: String) {
        val notification = DashcamNotificationHelper.buildRecordingNotification(this, detailText)
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfoTypes.cameraAndMicrophone
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            DashcamNotificationHelper.RECORDING_NOTIFICATION_ID,
            notification,
            foregroundType,
        )
    }

    private fun trimStoredClipsToQuota(notifyUser: Boolean) {
        val quotaGb = DashcamPreferences(this).getStorageQuotaGb()
        val result = DashcamClipStorageManager.trimToQuota(this, quotaGb)
        if (notifyUser && result.deletedCount > 0) {
            val cleanupMessage = getString(R.string.storage_trimmed_prefix, result.deletedCount)
            DashcamStateStore.update {
                it.copy(
                    detailText = buildString {
                        append(it.detailText)
                        append(' ')
                        append(cleanupMessage)
                    }.trim(),
                )
            }
        }
    }

    private fun routeAudioToBuiltInMicBestEffort() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        @Suppress("DEPRECATION")
        runCatching { audioManager.stopBluetoothSco() }
        @Suppress("DEPRECATION")
        runCatching { audioManager.isBluetoothScoOn = false }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun supportsFrontRearConcurrent(provider: ProcessCameraProvider): Boolean {
        return provider.availableConcurrentCameraInfos.any { cameraInfos ->
            val lensFacings = cameraInfos.mapNotNull { cameraInfo ->
                Camera2CameraInfo.from(cameraInfo).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            }
            lensFacings.contains(CameraCharacteristics.LENS_FACING_FRONT) &&
                lensFacings.contains(CameraCharacteristics.LENS_FACING_BACK)
        }
    }

    private fun buildRecordingDetail(mode: DashcamMode, trigger: String): String {
        val triggerText = when (trigger) {
            TRIGGER_POWER_CONNECTED -> "Started automatically because charging connected."
            TRIGGER_POWER_NOTIFICATION -> "Started after power-connected confirmation."
            else -> "Started manually from GO Cam."
        }
        return "${mode.label} camera recording is active. $triggerText"
    }

    private fun buildFileName(cameraLabel: String): String {
        val safeLabel = cameraLabel.lowercase(Locale.US).replace(' ', '_')
        return "GOCam_${safeLabel}_${fileDateFormatter.format(Date())}"
    }

    companion object {
        const val ACTION_START = "com.phantom.dashcam.action.START"
        const val ACTION_STOP = "com.phantom.dashcam.action.STOP"

        const val EXTRA_TRIGGER = "extra_trigger"
        const val EXTRA_ROTATION = "extra_rotation"
        const val EXTRA_STOP_REASON = "extra_stop_reason"
        const val EXTRA_USER_INITIATED = "extra_user_initiated"

        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_POWER_CONNECTED = "power_connected"
        const val TRIGGER_POWER_NOTIFICATION = "power_notification"

        // Loop recording is split into short segments so a sudden power loss can only ever
        // damage the segment currently being written; every earlier segment is already closed.
        // 1-minute segments keep the worst-case loss small and make "share last N minutes" precise.
        // Single background thread for the whole recording loop: segment callbacks, clip
        // commit/rename, storage cleanup and quota trimming. Single-threaded so it preserves the
        // original ordering guarantees (cleanup before the first pending file; one segment finalized
        // before the next starts) while keeping that file I/O off the main thread. It lives for the
        // whole process — never shut down — so CameraX's final Finalize callback (which commits the
        // last segment) is always delivered even while the service is being destroyed.
        private val recordingCallbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()

        private const val SEGMENT_DURATION_MS = 60_000L

        // With 1-minute segments this trims roughly every 5 minutes of recording instead of on every
        // segment boundary, so the storage scan no longer rides the per-minute capture cadence. The
        // final stop always trims, so the quota is still enforced when a run ends.
        private const val TRIM_EVERY_N_SEGMENTS = 5
        private const val OVERLAY_PADDING = 18f

        private val overlayDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val fileDateFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        fun createStartIntent(context: Context, trigger: String, rotation: Int): Intent {
            return Intent(context, DashcamRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TRIGGER, trigger)
                putExtra(EXTRA_ROTATION, rotation)
            }
        }

        fun createStopIntent(context: Context, reason: String, userInitiated: Boolean = false): Intent {
            return Intent(context, DashcamRecordingService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_STOP_REASON, reason)
                putExtra(EXTRA_USER_INITIATED, userInitiated)
            }
        }
    }
}

private object ServiceInfoTypes {
    val cameraAndMicrophone: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
}
