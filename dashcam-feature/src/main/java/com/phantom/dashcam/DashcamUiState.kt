package com.phantom.dashcam

data class DashcamUiState(
    val selectedMode: DashcamMode = DashcamMode.REAR,
    val activeMode: DashcamMode? = null,
    val autoRecordOnPower: Boolean = false,
    val previewEnabled: Boolean = false,
    val previewAvailable: Boolean = true,
    val storageQuotaGb: Int = DashcamPreferences.DEFAULT_STORAGE_QUOTA_GB,
    val permissionGranted: Boolean = false,
    val audioPermissionGranted: Boolean = false,
    val isCharging: Boolean = false,
    val isPreparing: Boolean = false,
    val isRecording: Boolean = false,
    val statusText: String = "Ready",
    val detailText: String = "Grant camera permission, choose a camera mode, and start recording.",
    val lastOutputLabel: String? = null,
    val backgroundStartPending: Boolean = false,
)
