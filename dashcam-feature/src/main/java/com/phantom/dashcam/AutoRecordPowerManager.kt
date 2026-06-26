package com.phantom.dashcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner

object AutoRecordPowerManager {

    private const val TAG = "GO_CAM_AUTO_POWER"

    fun setAutoRecordEnabled(context: Context, enabled: Boolean) {
        val prefs = DashcamPreferences(context)
        prefs.setAutoRecordOnPowerEnabled(enabled)
        if (enabled) {
            reconcileCurrentPowerState(
                context = context,
                source = "settings-enabled",
                forceStartWhenConnected = true,
            )
            startMonitoring(context)
        } else {
            DashcamNotificationHelper.cancelPowerStartNotification(context)
            prefs.clearLastKnownPowerConnected()
            stopMonitoring(context)
            DashcamStateStore.syncPreferences(context)
        }
    }

    fun syncMonitoring(context: Context) {
        if (DashcamPreferences(context).isAutoRecordOnPowerEnabled()) {
            startMonitoring(context)
        } else {
            stopMonitoring(context)
        }
    }

    fun reconcileCurrentPowerState(
        context: Context,
        source: String,
        forceStartWhenConnected: Boolean = false,
    ) {
        handlePluggedState(
            context = context,
            isPlugged = PowerStateUtils.isPluggedIn(context),
            source = source,
            forceStartWhenConnected = forceStartWhenConnected,
        )
    }

    fun handleBatteryChanged(
        context: Context,
        intent: Intent,
        source: String,
    ) {
        handlePluggedState(
            context = context,
            isPlugged = PowerStateUtils.isPluggedIn(intent),
            source = source,
            forceStartWhenConnected = false,
        )
    }

    fun handlePowerBroadcast(
        context: Context,
        action: String,
        source: String,
    ) {
        when (action) {
            Intent.ACTION_POWER_CONNECTED -> {
                handlePluggedState(context, true, source, forceStartWhenConnected = false)
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                handlePluggedState(context, false, source, forceStartWhenConnected = false)
            }
        }
    }

    fun maybeStartIfChargingAndReady(context: Context, source: String) {
        val state = DashcamStateStore.state.value
        if (!DashcamPreferences(context).isAutoRecordOnPowerEnabled()) return
        if (!PowerStateUtils.isPluggedIn(context)) return
        if (state.isRecording || state.isPreparing) return

        reconcileCurrentPowerState(
            context = context,
            source = source,
            forceStartWhenConnected = true,
        )
    }

    private fun handlePluggedState(
        context: Context,
        isPlugged: Boolean,
        source: String,
        forceStartWhenConnected: Boolean,
    ) {
        DashcamStateStore.bootstrap(context)

        val prefs = DashcamPreferences(context)
        val previous = prefs.getLastKnownPowerConnected()
        prefs.setLastKnownPowerConnected(isPlugged)

        DashcamStateStore.update {
            it.copy(
                isCharging = isPlugged,
            )
        }

        if (!prefs.isAutoRecordOnPowerEnabled()) {
            Log.i(TAG, "Power state updated while auto-record is disabled: plugged=$isPlugged source=$source")
            return
        }

        if (previous == isPlugged && !(forceStartWhenConnected && isPlugged)) {
            Log.i(TAG, "Power edge ignored because state is unchanged: plugged=$isPlugged source=$source")
            return
        }

        if (isPlugged) {
            handlePowerConnected(context, source)
        } else {
            handlePowerDisconnected(context, source)
        }
    }

    private fun handlePowerConnected(context: Context, source: String) {
        DashcamStateStore.update {
            it.copy(
                isCharging = true,
                backgroundStartPending = false,
                detailText = "Charging connected. GO Cam is preparing to auto-start recording.",
            )
        }

        if (!hasCameraAndAudioPermissions(context)) {
            DashcamStateStore.update {
                it.copy(
                    detailText = "Camera or microphone permission is still missing, so automatic recording cannot start.",
                )
            }
            DashcamNotificationHelper.showPowerStartNotification(context)
            Log.w(TAG, "Auto-start blocked by missing runtime permissions source=$source")
            return
        }

        val state = DashcamStateStore.state.value
        if (state.isRecording || state.isPreparing) {
            Log.i(TAG, "Recording already active, skipping duplicate start source=$source")
            return
        }

        val appVisible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (appVisible || canUseBackgroundLaunch(context)) {
            launchAutoRecordActivity(context, source)
            return
        }

        DashcamStateStore.update {
            it.copy(
                backgroundStartPending = true,
                detailText = context.getString(R.string.auto_record_overlay_detail),
            )
        }
        DashcamNotificationHelper.showOverlayPermissionNotification(context)
        Log.w(TAG, "Auto-start needs overlay permission for background launch source=$source")
    }

    private fun handlePowerDisconnected(context: Context, source: String) {
        DashcamStateStore.update {
            it.copy(
                isCharging = false,
                backgroundStartPending = false,
                detailText = "Charging disconnected. GO Cam is stopping auto-recording.",
            )
        }
        DashcamNotificationHelper.cancelPowerStartNotification(context)

        val state = DashcamStateStore.state.value
        if (state.isRecording || state.isPreparing) {
            context.startService(
                DashcamRecordingService.createStopIntent(
                    context,
                    "Stopped because charger was disconnected",
                ),
            )
            Log.i(TAG, "Sent stop request after charger disconnect source=$source")
        } else {
            Log.i(TAG, "Disconnect observed while recording was idle source=$source")
        }
    }

    private fun canUseBackgroundLaunch(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    }

    private fun launchAutoRecordActivity(context: Context, source: String) {
        DashcamNotificationHelper.cancelPowerStartNotification(context)
        val rotation = SurfaceRotationStore.getRotationHint().takeIf { it in Surface.ROTATION_0..Surface.ROTATION_270 }
            ?: Surface.ROTATION_0
        val intent = AutoRecordLaunchActivity.createIntent(
            context = context,
            trigger = DashcamRecordingService.TRIGGER_POWER_CONNECTED,
            rotation = rotation,
        )
        runCatching {
            context.startActivity(intent)
            DashcamStateStore.update {
                it.copy(
                    detailText = "Charging connected. GO Cam is waking briefly to begin recording.",
                    backgroundStartPending = false,
                )
            }
            Log.i(TAG, "AutoRecordLaunchActivity requested source=$source rotation=$rotation")
        }.onFailure { error ->
            DashcamStateStore.update {
                it.copy(
                    backgroundStartPending = true,
                    detailText = "Charging connected, but GO Cam could not wake itself to start recording automatically.",
                )
            }
            DashcamNotificationHelper.showPowerStartNotification(context)
            Log.e(TAG, "Failed to launch AutoRecord activity", error)
        }
    }

    private fun startMonitoring(context: Context) {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                PowerMonitoringService.createStartIntent(context),
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to start power monitoring service", error)
        }
    }

    private fun stopMonitoring(context: Context) {
        runCatching {
            context.startService(PowerMonitoringService.createStopIntent(context))
        }.onFailure { error ->
            Log.w(TAG, "Unable to request power monitoring service stop", error)
        }
    }

    private fun hasCameraAndAudioPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
    }
}
