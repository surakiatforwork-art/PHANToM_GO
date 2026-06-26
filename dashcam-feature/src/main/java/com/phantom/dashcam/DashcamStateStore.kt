package com.phantom.dashcam

import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object DashcamStateStore {

    private val _state = MutableStateFlow(DashcamUiState())
    val state: StateFlow<DashcamUiState> = _state.asStateFlow()

    fun bootstrap(context: Context) {
        val prefs = DashcamPreferences(context)
        _state.update {
            it.copy(
                selectedMode = prefs.getMode(),
                autoRecordOnPower = prefs.isAutoRecordOnPowerEnabled(),
                previewEnabled = prefs.isPreviewEnabled(),
                previewAvailable = true,
                storageQuotaGb = prefs.getStorageQuotaGb(),
                permissionGranted = hasCameraPermission(context),
                audioPermissionGranted = hasAudioPermission(context),
                isCharging = PowerStateUtils.isCharging(context),
            )
        }
    }

    fun syncPreferences(context: Context) {
        bootstrap(context)
    }

    fun update(transform: (DashcamUiState) -> DashcamUiState) {
        _state.update(transform)
    }

    private fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CAMERA,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
