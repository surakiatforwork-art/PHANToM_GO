package com.phantom.dashcam

import android.content.Context

class DashcamPreferences(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(): DashcamMode {
        return DashcamMode.fromStorageValue(preferences.getString(KEY_MODE, DashcamMode.REAR.storageValue))
    }

    fun setMode(mode: DashcamMode) {
        preferences.edit().putString(KEY_MODE, mode.storageValue).apply()
    }

    fun isFeatureEnabled(): Boolean {
        return preferences.getBoolean(KEY_FEATURE_ENABLED, false)
    }

    fun setFeatureEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FEATURE_ENABLED, enabled).apply()
    }

    fun isAutoRecordOnPowerEnabled(): Boolean {
        return preferences.getBoolean(KEY_AUTO_RECORD_ON_POWER, false)
    }

    fun setAutoRecordOnPowerEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_RECORD_ON_POWER, enabled).apply()
    }

    /**
     * True when the user explicitly stopped recording (vs. a system/charger/crash stop).
     * Used so the watchdog won't fight a manual stop, but still auto-resumes after an OS kill.
     */
    fun isUserStopped(): Boolean {
        return preferences.getBoolean(KEY_USER_STOPPED, false)
    }

    fun setUserStopped(stopped: Boolean) {
        preferences.edit().putBoolean(KEY_USER_STOPPED, stopped).apply()
    }

    fun getLastKnownPowerConnected(): Boolean? {
        if (!preferences.contains(KEY_LAST_KNOWN_POWER_CONNECTED)) {
            return null
        }
        return preferences.getBoolean(KEY_LAST_KNOWN_POWER_CONNECTED, false)
    }

    fun setLastKnownPowerConnected(connected: Boolean) {
        preferences.edit().putBoolean(KEY_LAST_KNOWN_POWER_CONNECTED, connected).apply()
    }

    fun clearLastKnownPowerConnected() {
        preferences.edit().remove(KEY_LAST_KNOWN_POWER_CONNECTED).apply()
    }

    fun getQuality(): DashcamQuality {
        return DashcamQuality.fromStorageValue(preferences.getString(KEY_QUALITY, DashcamQuality.DEFAULT.storageValue))
    }

    fun setQuality(quality: DashcamQuality) {
        preferences.edit().putString(KEY_QUALITY, quality.storageValue).apply()
    }

    fun isStabilizationEnabled(): Boolean {
        return preferences.getBoolean(KEY_STABILIZATION, true)
    }

    fun setStabilizationEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_STABILIZATION, enabled).apply()
    }

    fun isPreviewEnabled(): Boolean {
        return preferences.getBoolean(KEY_PREVIEW_ENABLED, false)
    }

    fun setPreviewEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_PREVIEW_ENABLED, enabled).apply()
    }

    fun getStorageQuotaGb(): Int {
        return normalizeStorageQuotaGb(
            preferences.getInt(KEY_STORAGE_QUOTA_GB, DEFAULT_STORAGE_QUOTA_GB),
        )
    }

    fun setStorageQuotaGb(value: Int) {
        preferences.edit().putInt(KEY_STORAGE_QUOTA_GB, normalizeStorageQuotaGb(value)).apply()
    }

    fun normalizeStorageQuotaGb(value: Int): Int {
        return value.coerceIn(MIN_STORAGE_QUOTA_GB, MAX_STORAGE_QUOTA_GB)
    }

    companion object {
        const val MIN_STORAGE_QUOTA_GB = 8
        const val MAX_STORAGE_QUOTA_GB = 256
        const val DEFAULT_STORAGE_QUOTA_GB = 32

        private const val PREFS_NAME = "go_cam_preferences"
        private const val KEY_FEATURE_ENABLED = "feature_enabled"
        private const val KEY_USER_STOPPED = "user_stopped"
        private const val KEY_MODE = "mode"
        private const val KEY_AUTO_RECORD_ON_POWER = "auto_record_on_power"
        private const val KEY_LAST_KNOWN_POWER_CONNECTED = "last_known_power_connected"
        private const val KEY_PREVIEW_ENABLED = "preview_enabled"
        private const val KEY_STORAGE_QUOTA_GB = "storage_quota_gb"
        private const val KEY_QUALITY = "recording_quality"
        private const val KEY_STABILIZATION = "video_stabilization"
    }
}
