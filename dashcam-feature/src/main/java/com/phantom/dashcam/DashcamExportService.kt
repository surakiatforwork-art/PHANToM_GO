package com.phantom.dashcam

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs a merge/export job in the background so the user can dismiss the progress dialog ("run in
 * background") and the work keeps going with a status-bar notification, or cancel it. Live progress
 * is published through [DashcamExportProgress] for the dialog to observe while it is visible.
 */
class DashcamExportService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var cancelRequested = false
    private var running = false
    private var lastNotifiedAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelRequested = true
            ACTION_START -> {
                if (!running) {
                    startExport(
                        intent.getLongExtra(EXTRA_WINDOW_START, 0L),
                        intent.getLongExtra(EXTRA_WINDOW_END, 0L),
                        DashcamExportDestination.fromName(intent.getStringExtra(EXTRA_DESTINATION)),
                    )
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startExport(
        windowStartMs: Long,
        windowEndMs: Long,
        destination: DashcamExportDestination,
    ) {
        running = true
        cancelRequested = false
        DashcamNotificationHelper.ensureChannels(this)
        startForegroundCompat(buildProgressNotification(0, null))
        DashcamExportProgress.set(DashcamExportProgress.State.Running(0f, null))
        val startedAtMs = SystemClock.elapsedRealtime()

        scope.launch {
            val selection = DashcamClipStorageManager.loadClipSelectionBetween(
                this@DashcamExportService, windowStartMs, windowEndMs,
            )
            if (selection.clips.isEmpty()) {
                finishWith(DashcamExportProgress.State.Empty)
                return@launch
            }

            // Download reserves the last 10% for the gallery copy; share is merge-only.
            val mergeWeight = if (destination == DashcamExportDestination.DOWNLOAD_TO_GALLERY) 0.9f else 1f
            val merged = DashcamClipMerger.mergeToCache(
                context = this@DashcamExportService,
                clips = selection.clips,
                exportStartMs = selection.windowStartMs,
                exportEndMs = selection.windowEndMs,
                onProgress = { fraction -> publishProgress(fraction * mergeWeight, startedAtMs) },
                isCancelled = { cancelRequested },
            )

            if (cancelRequested) {
                merged?.let { runCatching { it.delete() } }
                finishWith(DashcamExportProgress.State.Cancelled)
                return@launch
            }
            if (merged == null) {
                finishWith(DashcamExportProgress.State.Failed)
                return@launch
            }

            when (destination) {
                DashcamExportDestination.DOWNLOAD_TO_GALLERY -> {
                    val galleryUri = DashcamClipExporter.saveToGallery(
                        this@DashcamExportService, merged, buildGalleryName(selection),
                    ) { fraction -> publishProgress(0.9f + fraction * 0.1f, startedAtMs) }
                    runCatching { merged.delete() }
                    if (galleryUri == null) {
                        finishWith(DashcamExportProgress.State.Failed)
                    } else {
                        showDoneNotification(destination, null)
                        finishWith(DashcamExportProgress.State.Done(destination, null))
                    }
                }
                DashcamExportDestination.SHARE_OUT -> {
                    showDoneNotification(destination, merged)
                    finishWith(DashcamExportProgress.State.Done(destination, merged.absolutePath))
                }
            }
        }
    }

    private fun publishProgress(fraction: Float, startedAtMs: Long) {
        val frac = fraction.coerceIn(0f, 1f)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        val etaSeconds = if (frac > 0.02f) ((elapsedMs * (1f - frac) / frac) / 1000f).toLong() else null
        DashcamExportProgress.set(DashcamExportProgress.State.Running(frac, etaSeconds))
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifiedAtMs >= 500L) {
            lastNotifiedAtMs = now
            notifyProgress(buildProgressNotification((frac * 100).toInt(), etaSeconds))
        }
    }

    private fun finishWith(state: DashcamExportProgress.State) {
        running = false
        DashcamExportProgress.set(state)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildProgressNotification(percent: Int, etaSeconds: Long?): Notification {
        val text = if (etaSeconds != null && etaSeconds > 0L) {
            getString(R.string.dashcam_export_progress_eta_fmt, percent, DateUtils.formatElapsedTime(etaSeconds))
        } else {
            getString(R.string.dashcam_export_progress_pct_fmt, percent)
        }
        return NotificationCompat.Builder(this, DashcamNotificationHelper.EXPORT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.dashcam_export_progress_title))
            .setContentText(text)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.dashcam_export_cancel_action),
                PendingIntent.getService(
                    this, 31, cancelIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun showDoneNotification(destination: DashcamExportDestination, mergedFile: File?) {
        val builder = NotificationCompat.Builder(this, DashcamNotificationHelper.EXPORT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (destination == DashcamExportDestination.SHARE_OUT && mergedFile != null) {
            builder.setContentTitle(getString(R.string.dashcam_export_done_share_title))
                .setContentText(getString(R.string.dashcam_export_done_share_text))
                .setContentIntent(shareChooserPendingIntent(mergedFile))
        } else {
            builder.setContentTitle(getString(R.string.dashcam_export_done_saved))
        }
        androidx.core.app.NotificationManagerCompat.from(this)
            .takeIf { it.areNotificationsEnabled() }
            ?.let { runCatching { it.notify(DashcamNotificationHelper.EXPORT_DONE_NOTIFICATION_ID, builder.build()) } }
    }

    private fun shareChooserPendingIntent(file: File): PendingIntent {
        val uri = FileProvider.getUriForFile(this, "$packageName.dashcamfileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, getString(R.string.dashcam_share_chooser)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return PendingIntent.getActivity(
            this, 32, chooser,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("MissingPermission")
    private fun notifyProgress(notification: Notification) {
        androidx.core.app.NotificationManagerCompat.from(this)
            .takeIf { it.areNotificationsEnabled() }
            ?.let { runCatching { it.notify(DashcamNotificationHelper.EXPORT_PROGRESS_NOTIFICATION_ID, notification) } }
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, DashcamNotificationHelper.EXPORT_PROGRESS_NOTIFICATION_ID, notification, type)
    }

    private fun buildGalleryName(selection: RecentClipSelection): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "GOCam_export_${formatter.format(Date(selection.windowStartMs))}_" +
            "${formatter.format(Date(selection.windowEndMs))}.mp4"
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.phantom.dashcam.action.START_EXPORT"
        const val ACTION_CANCEL = "com.phantom.dashcam.action.CANCEL_EXPORT"
        private const val EXTRA_WINDOW_START = "extra_window_start"
        private const val EXTRA_WINDOW_END = "extra_window_end"
        private const val EXTRA_DESTINATION = "extra_destination"

        fun startIntent(
            context: Context,
            windowStartMs: Long,
            windowEndMs: Long,
            destination: DashcamExportDestination,
        ): Intent {
            return Intent(context, DashcamExportService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_WINDOW_START, windowStartMs)
                putExtra(EXTRA_WINDOW_END, windowEndMs)
                putExtra(EXTRA_DESTINATION, destination.name)
            }
        }

        fun cancelIntent(context: Context): Intent {
            return Intent(context, DashcamExportService::class.java).apply { action = ACTION_CANCEL }
        }
    }
}
