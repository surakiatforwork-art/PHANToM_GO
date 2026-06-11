package com.phantom.carnavrelay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    private const val TAG = "PHANTOM_GO"
    private const val CHANNEL_ID = "phantom_go_main"
    private const val NOTIFICATION_ID = 3001
    private const val SECURITY_ALERT_NOTIFICATION_ID = 3101
    private const val NOTIFICATION_TIMEOUT_MS = 4000L

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "PHANToM GO (Main)"
            val descriptionText = "Share status notifications"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return false
            }
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun showSending(context: Context, allowToastFallback: Boolean) {
        showNotification(context, "Sending to car display…", allowToastFallback, forceAlert = false)
    }

    fun showResult(context: Context, message: String, allowToastFallback: Boolean) {
        showNotification(context, message, allowToastFallback, forceAlert = true)
    }

    fun showInfo(context: Context, message: String, allowToastFallback: Boolean) {
        showNotification(context, message, allowToastFallback, forceAlert = true)
    }

    @SuppressLint("MissingPermission")
    fun showAntiTheftAlert(context: Context, alert: FirebaseManager.SecurityAlert) {
        ensureChannel(context)
        val title = buildAntiTheftTitle(alert)
        val body = buildAntiTheftBody(alert)

        if (!canPostNotifications(context)) {
            Log.w(TAG, "🔕 Anti-theft notification blocked")
            PhantomLog.w("ANTI-THEFT NOTIF blocked: ${alert.eventType}")
            showToast(context, "$title: $body")
            return
        }

        val openAppIntent = MainActivity.createIntent(context, BottomNavHelper.Tab.HOME).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            alert.alertId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_main_mode)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(R.drawable.ic_main_mode, "Open app", openAppPendingIntent)

        buildMapsPendingIntent(context, alert)?.let { mapPendingIntent ->
            builder.addAction(android.R.drawable.ic_dialog_map, "Open location", mapPendingIntent)
        }

        try {
            NotificationManagerCompat.from(context).notify(
                "${CHANNEL_ID}_security",
                SECURITY_ALERT_NOTIFICATION_ID + alert.alertId.hashCode(),
                builder.build()
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "🔕 Anti-theft notification blocked by permission", e)
            PhantomLog.w("ANTI-THEFT NOTIF SecurityException: ${alert.eventType}")
            showToast(context, "$title: $body")
        }
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(
        context: Context,
        text: String,
        allowToastFallback: Boolean,
        forceAlert: Boolean
    ) {
        ensureChannel(context)
        if (!canPostNotifications(context)) {
            Log.w(TAG, "🔕 Notification permission missing or blocked")
            PhantomLog.w("NOTIF blocked: $text")
            if (allowToastFallback) {
                showToast(context, text)
            }
            return
        }

        if (forceAlert) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        val openIntent = MainActivity.createIntent(context, BottomNavHelper.Tab.HOME).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_main_mode)
            .setContentTitle("PHANToM GO")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_main_mode, "Open app", openPendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "🔕 Notification blocked by permission", e)
            PhantomLog.w("NOTIF SecurityException: $text")
            if (allowToastFallback) {
                showToast(context, text)
            }
        }
    }

    private fun showToast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildAntiTheftTitle(alert: FirebaseManager.SecurityAlert): String {
        val source = alert.displayName ?: alert.displayDeviceId ?: "Display device"
        return "Anti-theft alert from $source"
    }

    private fun buildAntiTheftBody(alert: FirebaseManager.SecurityAlert): String {
        val eventLabel = when (alert.eventType) {
            DisplayAntiTheftManager.ALERT_TIMEOUT_1 -> "No PIN entered within 15 seconds."
            DisplayAntiTheftManager.ALERT_REPLUG_DURING_CHALLENGE_1 -> "3.5 mm jack was replugged during challenge."
            DisplayAntiTheftManager.ALERT_WRONG_PIN_1 -> "Wrong PIN entered once."
            DisplayAntiTheftManager.ALERT_WRONG_PIN_2 -> "Wrong PIN entered twice. Alarm is starting."
            DisplayAntiTheftManager.ALERT_ALARM_STARTED -> "Alarm is active on the display device."
            DisplayAntiTheftManager.ALERT_ALARM_ESCALATED_FULLSCREEN -> "Alarm escalated to full-screen lock."
            DisplayAntiTheftManager.ALERT_DISARMED_SUCCESS -> "Display device was disarmed with the correct PIN."
            else -> "Display anti-theft event: ${alert.eventType}"
        }
        val locationLabel = if (alert.locationAvailable && alert.lat != null && alert.lng != null) {
            " Location available."
        } else {
            ""
        }
        val speedLabel = alert.speedKmh?.let { " Speed $it km/h." } ?: ""
        return "$eventLabel$locationLabel$speedLabel"
    }

    private fun buildMapsPendingIntent(
        context: Context,
        alert: FirebaseManager.SecurityAlert
    ): PendingIntent? {
        val lat = alert.lat ?: return null
        val lng = alert.lng ?: return null
        val uri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            alert.alertId.hashCode() xor 0x1f1f1f1f,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
