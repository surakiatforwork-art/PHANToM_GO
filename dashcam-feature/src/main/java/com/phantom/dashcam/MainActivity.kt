package com.phantom.dashcam

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Surface
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phantom.dashcam.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: DashcamPreferences

    private var dashcamService: DashcamRecordingService? = null
    private var isServiceBound = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        DashcamStateStore.bootstrap(this)
        AutoRecordPowerManager.maybeStartIfChargingAndReady(this, "permission-result")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            dashcamService = (service as? DashcamRecordingService.LocalBinder)?.service
            isServiceBound = dashcamService != null
            syncPreviewAttachment(DashcamStateStore.state.value)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            dashcamService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferences = DashcamPreferences(this)
        DashcamStateStore.bootstrap(this)
        AutoRecordPowerManager.syncMonitoring(this)
        updateRotationHint()

        configureUi()
        observeState()
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DashcamRecordingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        AutoRecordPowerManager.syncMonitoring(this)
    }

    override fun onResume() {
        super.onResume()
        updateRotationHint()
    }

    override fun onStop() {
        dashcamService?.setPreviewSurfaces(null, null)
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onStop()
    }

    private fun configureUi() {
        binding.rearPreviewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        binding.frontPreviewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE

        binding.permissionButton.setOnClickListener {
            requestMissingPermissions()
        }

        binding.startStopButton.setOnClickListener {
            if (DashcamStateStore.state.value.isRecording || DashcamStateStore.state.value.isPreparing) {
                startService(DashcamRecordingService.createStopIntent(this, "Stopped manually", userInitiated = true))
            } else {
                if (!hasCameraPermission() || !hasAudioPermission()) {
                    requestMissingPermissions()
                    return@setOnClickListener
                }
                updateRotationHint()
                ContextCompat.startForegroundService(
                    this,
                    DashcamRecordingService.createStartIntent(
                        this,
                        DashcamRecordingService.TRIGGER_MANUAL,
                        currentRotation(),
                    ),
                )
            }
        }

        binding.autoRecordSwitch.setOnCheckedChangeListener { _, isChecked ->
            AutoRecordPowerManager.setAutoRecordEnabled(this, isChecked)
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                DashcamStateStore.update {
                    it.copy(
                        detailText = getString(R.string.auto_record_overlay_detail),
                    )
                }
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
        }

        binding.previewSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferences.setPreviewEnabled(isChecked)
            DashcamStateStore.syncPreferences(this)
            syncPreviewAttachment(DashcamStateStore.state.value)
        }

        binding.storageQuotaSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val normalizedValue = preferences.normalizeStorageQuotaGb(value.toInt())
            preferences.setStorageQuotaGb(normalizedValue)
            DashcamStateStore.syncPreferences(this)
        }

        binding.viewClipsButton.setOnClickListener {
            startActivity(Intent(this, DashcamClipsActivity::class.java))
        }

        binding.modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                binding.frontModeButton.id -> DashcamMode.FRONT
                binding.dualModeButton.id -> DashcamMode.DUAL
                else -> DashcamMode.REAR
            }
            preferences.setMode(mode)
            DashcamStateStore.syncPreferences(this)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DashcamStateStore.state.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: DashcamUiState) {
        binding.statusText.text = state.statusText
        binding.detailText.text = state.detailText
        binding.permissionButton.isVisible = !state.permissionGranted || !state.audioPermissionGranted || !hasNotificationPermission()
        binding.startStopButton.isEnabled = !state.isPreparing || state.isRecording
        binding.startStopButton.text = if (state.isRecording || state.isPreparing) {
            getString(R.string.stop_recording)
        } else {
            getString(R.string.start_recording)
        }
        binding.autoRecordSwitch.isChecked = state.autoRecordOnPower
        binding.previewSwitch.isChecked = state.previewEnabled
        binding.previewSwitch.isEnabled = state.previewAvailable
        binding.previewHintText.text = if (!state.previewAvailable) {
            getString(R.string.preview_unavailable_dual)
        } else {
            getString(R.string.preview_hint)
        }
        binding.dualPreviewHintText.isVisible = state.selectedMode == DashcamMode.DUAL
        binding.storageQuotaValueText.text = getString(R.string.storage_quota_value, state.storageQuotaGb)
        if (binding.storageQuotaSlider.value.toInt() != state.storageQuotaGb) {
            binding.storageQuotaSlider.value = state.storageQuotaGb.toFloat()
        }
        binding.rearModeButton.isEnabled = !state.isRecording
        binding.frontModeButton.isEnabled = !state.isRecording
        binding.dualModeButton.isEnabled = !state.isRecording

        when (state.selectedMode) {
            DashcamMode.REAR -> binding.modeToggleGroup.check(binding.rearModeButton.id)
            DashcamMode.FRONT -> binding.modeToggleGroup.check(binding.frontModeButton.id)
            DashcamMode.DUAL -> binding.modeToggleGroup.check(binding.dualModeButton.id)
        }

        val showPreview = state.previewEnabled && state.previewAvailable && state.isRecording
        binding.rearPreviewView.isVisible = showPreview
        binding.frontPreviewView.isVisible = showPreview && state.activeMode == DashcamMode.DUAL
        binding.lastOutputText.text = state.lastOutputLabel ?: ""
        syncPreviewAttachment(state)
    }

    private fun syncPreviewAttachment(state: DashcamUiState) {
        val shouldAttach = state.isRecording && state.previewEnabled && state.previewAvailable
        if (shouldAttach) {
            dashcamService?.setPreviewSurfaces(
                binding.rearPreviewView.surfaceProvider,
                if (state.activeMode == DashcamMode.DUAL) binding.frontPreviewView.surfaceProvider else null,
            )
        } else {
            dashcamService?.setPreviewSurfaces(null, null)
        }
        updateRotationHint()
    }

    private fun requestMissingPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateRotationHint() {
        val rotation = currentRotation()
        SurfaceRotationStore.update(rotation)
        dashcamService?.updateTargetRotation(rotation)
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }
    }
}
