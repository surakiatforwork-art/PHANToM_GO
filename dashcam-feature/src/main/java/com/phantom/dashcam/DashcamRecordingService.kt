package com.phantom.dashcam

import android.annotation.SuppressLint
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.hardware.camera2.CameraCharacteristics
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
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
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
        private val recording: Recording,
    ) : ActiveSession {
        override fun stop() {
            runCatching { recording.stop() }
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
        private val rearRecording: Recording,
        private val frontRecording: Recording,
        private val rearOverlay: OverlayEffect,
        private val frontOverlay: OverlayEffect,
    ) : ActiveSession {
        override val activeMode: DashcamMode = DashcamMode.DUAL

        override fun stop() {
            runCatching { rearRecording.stop() }
            runCatching { frontRecording.stop() }
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

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appMainExecutor by lazy { ContextCompat.getMainExecutor(this) }

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
                targetRotation = intent.getIntExtra(EXTRA_ROTATION, SurfaceRotationStore.getRotationHint())
                startRecording(intent.getStringExtra(EXTRA_TRIGGER) ?: TRIGGER_MANUAL)
            }
            ACTION_STOP -> {
                stopRecording(intent.getStringExtra(EXTRA_STOP_REASON) ?: "Stopped")
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        activeSession?.stop()
        cameraProvider?.unbindAll()
        activeSession = null
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
        trimStoredClipsToQuota(notifyUser = false)

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
        val videoCapture = buildVideoCapture(Quality.FHD)
        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(videoCapture)
            .addEffect(overlay)
            .build()

        provider.bindToLifecycle(this, selector, useCaseGroup)
        preview.setSurfaceProvider(if (previewEnabled) previewTargets.main else null)

        val recording = startVideoRecording(
            output = videoCapture.output,
            cameraLabel = mode.label,
            onFinalize = { displayName ->
                if (displayName != null) {
                    DashcamStateStore.update {
                        it.copy(
                            lastOutputLabel = getString(R.string.last_output_prefix, displayName),
                        )
                    }
                }
            },
        )
        return SingleCameraSession(mode, preview, videoCapture, overlay, recording)
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
        val rearVideoCapture = buildVideoCapture(Quality.HD)
        val frontVideoCapture = buildVideoCapture(Quality.HD)

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

        val completedFinalizers = AtomicInteger(0)
        val finalizeCallback: (String?) -> Unit = { displayName ->
            if (displayName != null) {
                DashcamStateStore.update {
                    it.copy(
                        lastOutputLabel = getString(R.string.last_output_prefix, displayName),
                    )
                }
            }
            completedFinalizers.incrementAndGet()
        }

        val rearRecording = startVideoRecording(rearVideoCapture.output, "Rear", finalizeCallback)
        val frontRecording = startVideoRecording(frontVideoCapture.output, "Front", finalizeCallback)
        return DualCameraSession(
            rearPreview = rearPreview,
            frontPreview = frontPreview,
            rearVideoCapture = rearVideoCapture,
            frontVideoCapture = frontVideoCapture,
            rearRecording = rearRecording,
            frontRecording = frontRecording,
            rearOverlay = rearOverlay,
            frontOverlay = frontOverlay,
        )
    }

    // CameraX exposes setAudioSource as a library-group restricted API; the prototype
    // intentionally pins the built-in mic. Behaviour is unchanged from the tested build.
    @SuppressLint("RestrictedApi", "WrongConstant")
    private fun buildVideoCapture(preferredQuality: Quality): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    preferredQuality,
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .build()
        return VideoCapture.Builder(recorder)
            .setTargetRotation(recordingStartRotation)
            .build()
    }

    private fun startVideoRecording(
        output: Recorder,
        cameraLabel: String,
        onFinalize: (String?) -> Unit,
    ): Recording {
        val displayName = buildFileName(cameraLabel)
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PHANToM GO Cam")
            }
        }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )
            .setContentValues(contentValues)
            .build()

        val audioPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        if (audioPermissionGranted) {
            routeAudioToBuiltInMicBestEffort()
        }

        val pendingRecording = output.prepareRecording(this, outputOptions).let { prepared ->
            if (audioPermissionGranted) {
                prepared.withAudioEnabled()
            } else {
                DashcamStateStore.update {
                    it.copy(
                        detailText = getString(R.string.audio_missing_detail),
                    )
                }
                prepared
            }
        }

        return pendingRecording
            .start(appMainExecutor) { event ->
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
                        if (event.hasError()) {
                            stopRecording("Recording failed on $cameraLabel camera.")
                        } else {
                            onFinalize(displayName)
                            trimStoredClipsToQuota(notifyUser = true)
                        }
                    }

                    else -> Unit
                }
            }
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

            setOnDrawListener { frame ->
                val canvas = frame.overlayCanvas
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawTimestampOverlay(
                    canvas = canvas,
                    cameraLabel = cameraLabel,
                    surfaceRotation = surfaceRotation,
                    labelPaint = labelPaint,
                    textPaint = timestampPaint,
                    boxPaint = boxPaint,
                )
                true
            }
        }
    }

    private fun drawTimestampOverlay(
        canvas: Canvas,
        cameraLabel: String,
        surfaceRotation: Int,
        labelPaint: Paint,
        textPaint: Paint,
        boxPaint: Paint,
    ) {
        val timestamp = overlayDateFormatter.format(Date())
        val line1 = "$cameraLabel camera"
        val line2 = timestamp
        val padding = 18f
        val corner = 18f
        val lineHeight = textPaint.fontSpacing
        val boxWidth = maxOf(labelPaint.measureText(line1), textPaint.measureText(line2)) + padding * 2
        val boxHeight = lineHeight + labelPaint.fontSpacing + padding * 2

        val logicalWidth: Float
        val logicalHeight: Float
        val orientationMatrix = Matrix()
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

        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_POWER_CONNECTED = "power_connected"
        const val TRIGGER_POWER_NOTIFICATION = "power_notification"

        private val overlayDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val fileDateFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        fun createStartIntent(context: Context, trigger: String, rotation: Int): Intent {
            return Intent(context, DashcamRecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TRIGGER, trigger)
                putExtra(EXTRA_ROTATION, rotation)
            }
        }

        fun createStopIntent(context: Context, reason: String): Intent {
            return Intent(context, DashcamRecordingService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_STOP_REASON, reason)
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
