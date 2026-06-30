package com.phantom.dashcam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs the (slow) software stabilization of one recorded clip in the background so the user can
 * dismiss the dialog and let it finish with a status-bar notification, or cancel it. The stabilized
 * result is saved straight into the gallery. Mirrors [DashcamExportService]'s lifecycle so it does
 * not disturb recording or the existing export flow.
 */
class DashcamStabilizeService : LifecycleService() {

    @Volatile private var cancelRequested = false
    private var running = false
    private var lastNotifiedAtMs = 0L

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CANCEL -> cancelRequested = true
            ACTION_START -> {
                if (!running) {
                    val path = intent.getStringExtra(EXTRA_SOURCE_PATH)
                    val crop = StabilizationCropStrength.fromStorageValue(intent.getStringExtra(EXTRA_CROP))
                    if (path.isNullOrBlank()) {
                        stopSelf()
                    } else {
                        startStabilize(File(path), crop)
                    }
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startStabilize(source: File, crop: StabilizationCropStrength) {
        running = true
        cancelRequested = false
        DashcamNotificationHelper.ensureChannels(this)
        startForegroundCompat(buildProgressNotification(0, null))
        DashcamStabilizeProgress.set(DashcamStabilizeProgress.State.Running(0f, null))
        val startedAtMs = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (!source.exists()) return@withContext null
                // Stabilize is 0..0.9 of the bar; the gallery copy is the last 10%.
                val stabilized = VideoStabilizer.stabilizeToCache(
                    context = this@DashcamStabilizeService,
                    source = source,
                    crop = crop,
                    onProgress = { fraction -> publishProgress(fraction * 0.9f, startedAtMs) },
                    isCancelled = { cancelRequested },
                ) ?: return@withContext null
                if (cancelRequested) {
                    runCatching { stabilized.delete() }
                    return@withContext null
                }
                val uri = DashcamClipExporter.saveToGallery(
                    context = this@DashcamStabilizeService,
                    sourceFile = stabilized,
                    displayName = buildGalleryName(source),
                    onProgress = { fraction -> publishProgress(0.9f + fraction * 0.1f, startedAtMs) },
                    isCancelled = { cancelRequested },
                )
                runCatching { stabilized.delete() }
                uri
            }

            when {
                cancelRequested -> finishWith(DashcamStabilizeProgress.State.Cancelled)
                result == null -> finishWith(DashcamStabilizeProgress.State.Failed)
                else -> {
                    showDoneNotification()
                    finishWith(DashcamStabilizeProgress.State.Done)
                }
            }
        }
    }

    private fun publishProgress(fraction: Float, startedAtMs: Long) {
        val frac = fraction.coerceIn(0f, 1f)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        val etaSeconds = if (frac > 0.02f) ((elapsedMs * (1f - frac) / frac) / 1000f).toLong() else null
        DashcamStabilizeProgress.set(DashcamStabilizeProgress.State.Running(frac, etaSeconds))
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifiedAtMs >= 500L) {
            lastNotifiedAtMs = now
            notifyProgress(buildProgressNotification((frac * 100).toInt(), etaSeconds))
        }
    }

    private fun finishWith(state: DashcamStabilizeProgress.State) {
        running = false
        DashcamStabilizeProgress.set(state)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildProgressNotification(percent: Int, etaSeconds: Long?): Notification {
        val text = if (etaSeconds != null && etaSeconds > 0L) {
            getString(R.string.dashcam_stabilize_progress_eta_fmt, percent, DateUtils.formatElapsedTime(etaSeconds))
        } else {
            getString(R.string.dashcam_export_progress_pct_fmt, percent)
        }
        return NotificationCompat.Builder(this, DashcamNotificationHelper.EXPORT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.dashcam_stabilize_progress_title))
            .setContentText(text)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.dashcam_export_cancel_action),
                PendingIntent.getService(
                    this, 41, cancelIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun showDoneNotification() {
        val builder = NotificationCompat.Builder(this, DashcamNotificationHelper.EXPORT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.dashcam_stabilize_done_title))
            .setContentText(getString(R.string.dashcam_export_done_saved))
        NotificationManagerCompat.from(this)
            .takeIf { it.areNotificationsEnabled() }
            ?.let { runCatching { it.notify(STABILIZE_DONE_NOTIFICATION_ID, builder.build()) } }
    }

    @SuppressLint("MissingPermission")
    private fun notifyProgress(notification: Notification) {
        NotificationManagerCompat.from(this)
            .takeIf { it.areNotificationsEnabled() }
            ?.let { runCatching { it.notify(STABILIZE_PROGRESS_NOTIFICATION_ID, notification) } }
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, STABILIZE_PROGRESS_NOTIFICATION_ID, notification, type)
    }

    private fun buildGalleryName(source: File): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "GOCam_stabilized_$stamp.mp4"
    }

    companion object {
        const val ACTION_START = "com.phantom.dashcam.action.START_STABILIZE"
        const val ACTION_CANCEL = "com.phantom.dashcam.action.CANCEL_STABILIZE"
        private const val EXTRA_SOURCE_PATH = "extra_source_path"
        private const val EXTRA_CROP = "extra_crop"

        private const val STABILIZE_PROGRESS_NOTIFICATION_ID = 7006
        private const val STABILIZE_DONE_NOTIFICATION_ID = 7007

        fun startIntent(context: Context, sourcePath: String, crop: StabilizationCropStrength): Intent {
            return Intent(context, DashcamStabilizeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE_PATH, sourcePath)
                putExtra(EXTRA_CROP, crop.storageValue)
            }
        }

        fun cancelIntent(context: Context): Intent {
            return Intent(context, DashcamStabilizeService::class.java).apply { action = ACTION_CANCEL }
        }
    }
}
