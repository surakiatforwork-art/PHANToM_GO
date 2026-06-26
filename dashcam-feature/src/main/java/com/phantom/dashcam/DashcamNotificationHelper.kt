package com.phantom.dashcam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object DashcamNotificationHelper {

    const val RECORDING_NOTIFICATION_ID = 7001
    const val POWER_NOTIFICATION_ID = 7002
    const val POWER_MONITOR_NOTIFICATION_ID = 7003

    private const val RECORDING_CHANNEL_ID = "gocam_recording"
    private const val ALERT_CHANNEL_ID = "gocam_alerts"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        val recordingChannel = NotificationChannel(
            RECORDING_CHANNEL_ID,
            context.getString(R.string.notification_channel_recording),
            NotificationManager.IMPORTANCE_LOW,
        )
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            context.getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(recordingChannel)
        manager.createNotificationChannel(alertChannel)
    }

    fun buildRecordingNotification(context: Context, detailText: String): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, RECORDING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(context.getString(R.string.notification_recording_title))
            .setContentText(detailText.ifBlank { context.getString(R.string.notification_recording_text) })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent(context))
            .addAction(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.notification_action_stop),
                stopServicePendingIntent(context),
            )
            .build()
    }

    fun buildPowerMonitorNotification(context: Context): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, RECORDING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(context.getString(R.string.notification_monitor_title))
            .setContentText(context.getString(R.string.notification_monitor_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPendingIntent(context))
            .build()
    }

    @SuppressLint("MissingPermission")
    fun showPowerStartNotification(context: Context) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(context.getString(R.string.notification_needs_action_title))
            .setContentText(context.getString(R.string.notification_needs_action_text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppPendingIntent(context))
            .addAction(
                android.R.drawable.ic_media_play,
                context.getString(R.string.notification_action_start_now),
                startServicePendingIntent(context, DashcamRecordingService.TRIGGER_POWER_NOTIFICATION),
            )
            .build()
        NotificationManagerCompat.from(context).notify(POWER_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun showOverlayPermissionNotification(context: Context) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.notification_needs_action_title))
            .setContentText(context.getString(R.string.notification_overlay_needed_text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openOverlaySettingsPendingIntent(context))
            .addAction(
                android.R.drawable.ic_menu_manage,
                context.getString(R.string.notification_action_open_settings),
                openOverlaySettingsPendingIntent(context),
            )
            .build()
        NotificationManagerCompat.from(context).notify(POWER_NOTIFICATION_ID, notification)
    }

    fun cancelPowerStartNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(POWER_NOTIFICATION_ID)
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        // Open the host app's launcher activity so this helper works for both the
        // standalone GO Cam shell and the main PHANToM GO app.
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context,
            10,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun startServicePendingIntent(context: Context, trigger: String): PendingIntent {
        val intent = DashcamRecordingService.createStartIntent(context, trigger, SurfaceRotationStore.getRotationHint())
        return PendingIntent.getService(
            context,
            11,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopServicePendingIntent(context: Context): PendingIntent {
        val intent = DashcamRecordingService.createStopIntent(context, "Stopped from notification")
        return PendingIntent.getService(
            context,
            12,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openOverlaySettingsPendingIntent(context: Context): PendingIntent {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            13,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
