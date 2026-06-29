package com.phantom.dashcam

import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Browse recorded dashcam clips (newest first) with a thumbnail, tap-to-play, delete, and export
 * flows. Clips stay in app-private storage until the user explicitly downloads or shares them.
 */
class DashcamClipsActivity : AppCompatActivity() {
    private companion object {
        private const val SHARE_DURATION_TOLERANCE_MS = 5_000L
    }

    private var exportProgressDialog: AlertDialog? = null
    private var exportObserverJob: Job? = null

    private lateinit var summaryText: TextView
    private lateinit var emptyText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var shareRecentButton: MaterialButton
    private lateinit var exportRangeButton: MaterialButton
    private lateinit var mergeSessionButton: MaterialButton
    private val adapter = ClipAdapter(::openClip, ::confirmDelete)
    private val sharePresetsMinutes = intArrayOf(3, 5, 10)
    private var loadedClips: List<StoredDashcamClip> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashcam_clips)
        summaryText = findViewById(R.id.clipsSummaryText)
        emptyText = findViewById(R.id.clipsEmptyText)
        recyclerView = findViewById(R.id.clipsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        shareRecentButton = findViewById(R.id.shareRecentButton)
        exportRangeButton = findViewById(R.id.exportRangeButton)
        mergeSessionButton = findViewById(R.id.mergeSessionButton)

        shareRecentButton.setOnClickListener { showShareRecentDialog() }
        exportRangeButton.setOnClickListener { showTimelineRangeDialog() }
        mergeSessionButton.setOnClickListener { showSessionPickerDialog() }
    }

    private fun showSessionPickerDialog() {
        setExportButtonsBusy(true)
        lifecycleScope.launch {
            val sessions = withContext(Dispatchers.IO) {
                DashcamClipStorageManager.loadSessions(this@DashcamClipsActivity)
            }
            setExportButtonsBusy(false)
            if (sessions.isEmpty()) {
                Toast.makeText(this@DashcamClipsActivity, getString(R.string.dashcam_share_empty), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = sessions.map { session ->
                getString(
                    R.string.dashcam_session_label_fmt,
                    formatDateTime(session.startedAtEpochMs),
                    formatDateTime(session.endedAtEpochMs),
                    formatElapsed(session.totalDurationMs),
                    session.clips.size,
                )
            }.toTypedArray()
            AlertDialog.Builder(this@DashcamClipsActivity)
                .setTitle(R.string.dashcam_session_pick_title)
                .setItems(labels) { _, which ->
                    val session = sessions[which]
                    val requestLabel = getString(
                        R.string.dashcam_export_range_label,
                        formatDateTime(session.startedAtEpochMs),
                        formatDateTime(session.endedAtEpochMs),
                    )
                    showExportDestinationDialog(requestLabel, sessionToSelection(session))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun sessionToSelection(session: DashcamSession): RecentClipSelection {
        // A session is contiguous, so the whole span is available — no partial-coverage warning.
        return RecentClipSelection(
            clips = session.clips,
            windowStartMs = session.startedAtEpochMs,
            windowEndMs = session.endedAtEpochMs,
            requestedDurationMs = session.totalDurationMs,
            availableDurationMs = session.totalDurationMs,
        )
    }

    private fun showShareRecentDialog() {
        val labels = sharePresetsMinutes
            .map { getString(R.string.dashcam_share_minutes_fmt, it) }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.dashcam_share_recent_title)
            .setItems(labels) { _, which ->
                val minutes = sharePresetsMinutes[which]
                prepareExport(
                    requestLabel = getString(R.string.dashcam_share_minutes_fmt, minutes),
                ) {
                    DashcamClipStorageManager.loadRecentClipSelection(this@DashcamClipsActivity, minutes)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTimelineRangeDialog() {
        if (loadedClips.isEmpty()) {
            Toast.makeText(this, getString(R.string.dashcam_share_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val spanStartMs = loadedClips.minOf { it.startedAtEpochMs }
        val spanEndMs = loadedClips.maxOf { it.endedAtEpochMs }
        val intervals = loadedClips.map { it.startedAtEpochMs..it.endedAtEpochMs }

        val view = layoutInflater.inflate(R.layout.dialog_dashcam_timeline, null)
        val timeline: DashcamTimelineView = view.findViewById(R.id.dashcamTimelineView)
        val startValue: TextView = view.findViewById(R.id.timelineStartValue)
        val endValue: TextView = view.findViewById(R.id.timelineEndValue)
        val durationValue: TextView = view.findViewById(R.id.timelineDurationValue)

        timeline.onSelectionChanged = { startMs, endMs ->
            startValue.text = formatDateTime(startMs)
            endValue.text = formatDateTime(endMs)
            durationValue.text = formatElapsed(endMs - startMs)
        }
        // Default frame = the whole span (both handles at the extremes).
        timeline.setData(spanStartMs, spanEndMs, intervals, spanStartMs, spanEndMs)

        AlertDialog.Builder(this)
            .setTitle(R.string.dashcam_export_range_title)
            .setView(view)
            .setPositiveButton(R.string.dashcam_export_range_continue) { _, _ ->
                val startMs = timeline.getSelectionStartMs()
                val endMs = timeline.getSelectionEndMs()
                val requestLabel = getString(
                    R.string.dashcam_export_range_label,
                    formatDateTime(startMs),
                    formatDateTime(endMs),
                )
                prepareExport(requestLabel) {
                    DashcamClipStorageManager.loadClipSelectionBetween(
                        this@DashcamClipsActivity,
                        startMs,
                        endMs,
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun prepareExport(
        requestLabel: String,
        loadSelection: () -> RecentClipSelection,
    ) {
        setExportButtonsBusy(true)
        lifecycleScope.launch {
            val selection = withContext(Dispatchers.IO) { loadSelection() }
            setExportButtonsBusy(false)
            when {
                selection.clips.isEmpty() -> {
                    Toast.makeText(
                        this@DashcamClipsActivity,
                        getString(R.string.dashcam_share_empty),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                selection.availableDurationMs + SHARE_DURATION_TOLERANCE_MS < selection.requestedDurationMs -> {
                    showPartialExportDialog(requestLabel, selection)
                }
                else -> showExportDestinationDialog(requestLabel, selection)
            }
        }
    }

    private fun showPartialExportDialog(
        requestLabel: String,
        selection: RecentClipSelection,
    ) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dashcam_share_partial_title)
            .setMessage(
                getString(
                    R.string.dashcam_share_partial_message,
                    formatElapsed(selection.availableDurationMs),
                    requestLabel,
                )
            )
            .setPositiveButton(R.string.dashcam_share_partial_continue) { _, _ ->
                showExportDestinationDialog(requestLabel, selection)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showExportDestinationDialog(
        requestLabel: String,
        selection: RecentClipSelection,
    ) {
        val labels = arrayOf(
            getString(R.string.dashcam_export_destination_download),
            getString(R.string.dashcam_export_destination_share),
        )
        AlertDialog.Builder(this)
            .setTitle(
                buildString {
                    append(getString(R.string.dashcam_export_destination_title))
                    append('\n')
                    append(requestLabel)
                }
            )
            .setItems(labels) { _, which ->
                val destination = if (which == 0) {
                    DashcamExportDestination.DOWNLOAD_TO_GALLERY
                } else {
                    DashcamExportDestination.SHARE_OUT
                }
                exportSelection(selection, destination)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportSelection(
        selection: RecentClipSelection,
        destination: DashcamExportDestination,
    ) {
        // Only one export at a time: the service ignores a second ACTION_START, so re-attach the
        // dialog to the running job instead of pretending the new request started.
        if (DashcamExportProgress.state.value is DashcamExportProgress.State.Running) {
            Toast.makeText(this, getString(R.string.dashcam_export_already_running), Toast.LENGTH_SHORT).show()
            showExportProgressDialog()
            return
        }
        // The heavy merge/export runs in a foreground service so it can keep going in the
        // background (notification) after the dialog is dismissed, or be cancelled.
        DashcamExportProgress.reset()
        ContextCompat.startForegroundService(
            this,
            DashcamExportService.startIntent(this, selection.windowStartMs, selection.windowEndMs, destination),
        )
        showExportProgressDialog()
    }

    private fun showExportProgressDialog() {
        exportObserverJob?.cancel()
        val view = layoutInflater.inflate(R.layout.dialog_dashcam_export_progress, null)
        val percentText: TextView = view.findViewById(R.id.exportProgressPercent)
        val progressBar: ProgressBar = view.findViewById(R.id.exportProgressBar)
        val etaText: TextView = view.findViewById(R.id.exportProgressEta)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dashcam_export_dialog_title)
            .setView(view)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> cancelExport() }
            .setNeutralButton(R.string.dashcam_export_background) { d, _ -> d.dismiss() }
            .create()
        dialog.setOnDismissListener {
            exportObserverJob?.cancel()
            exportObserverJob = null
            exportProgressDialog = null
        }
        exportProgressDialog = dialog
        dialog.show()

        exportObserverJob = lifecycleScope.launch {
            DashcamExportProgress.state.collect { state ->
                when (state) {
                    is DashcamExportProgress.State.Running -> {
                        val pct = (state.fraction * 100).toInt().coerceIn(0, 100)
                        percentText.text = getString(R.string.dashcam_export_progress_pct_fmt, pct)
                        progressBar.progress = pct
                        etaText.text = state.etaSeconds?.takeIf { it > 0L }?.let {
                            getString(R.string.dashcam_export_eta_fmt, DateUtils.formatElapsedTime(it))
                        }.orEmpty()
                    }
                    is DashcamExportProgress.State.Done -> {
                        dialog.dismiss()
                        onExportDone(state)
                    }
                    DashcamExportProgress.State.Failed -> {
                        dialog.dismiss()
                        Toast.makeText(this@DashcamClipsActivity, getString(R.string.dashcam_share_failed), Toast.LENGTH_SHORT).show()
                    }
                    DashcamExportProgress.State.Empty -> {
                        dialog.dismiss()
                        Toast.makeText(this@DashcamClipsActivity, getString(R.string.dashcam_share_empty), Toast.LENGTH_SHORT).show()
                    }
                    DashcamExportProgress.State.Cancelled -> {
                        dialog.dismiss()
                        Toast.makeText(this@DashcamClipsActivity, getString(R.string.dashcam_export_cancelled), Toast.LENGTH_SHORT).show()
                    }
                    DashcamExportProgress.State.Idle -> Unit
                }
            }
        }
    }

    private fun cancelExport() {
        startService(DashcamExportService.cancelIntent(this))
    }

    private fun onExportDone(state: DashcamExportProgress.State.Done) {
        // Handled here while the dialog is visible, so cancel the service's fallback notification.
        runCatching { NotificationManagerCompat.from(this).cancel(DashcamNotificationHelper.EXPORT_DONE_NOTIFICATION_ID) }
        when (state.destination) {
            DashcamExportDestination.SHARE_OUT -> state.shareFilePath?.let { shareMergedFile(File(it)) }
            DashcamExportDestination.DOWNLOAD_TO_GALLERY ->
                Toast.makeText(this, getString(R.string.dashcam_export_saved_to_gallery), Toast.LENGTH_SHORT).show()
        }
    }

    private fun setExportButtonsBusy(isBusy: Boolean) {
        shareRecentButton.isEnabled = !isBusy
        exportRangeButton.isEnabled = !isBusy
        mergeSessionButton.isEnabled = !isBusy
        if (isBusy) {
            shareRecentButton.text = getString(R.string.dashcam_share_merging)
            exportRangeButton.text = getString(R.string.dashcam_share_merging)
            mergeSessionButton.text = getString(R.string.dashcam_share_merging)
        } else {
            shareRecentButton.text = getString(R.string.dashcam_share_recent)
            exportRangeButton.text = getString(R.string.dashcam_export_range)
            mergeSessionButton.text = getString(R.string.dashcam_session_merge)
        }
    }

    private fun formatElapsed(durationMs: Long): String {
        val totalSeconds = ((durationMs + 500L) / 1000L).coerceAtLeast(0L)
        return DateUtils.formatElapsedTime(totalSeconds)
    }

    private fun formatDateTime(timeMs: Long): String {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(timeMs))
    }

    private fun shareMergedFile(file: File) {
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.dashcamfileprovider", file)
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, getString(R.string.dashcam_share_failed), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.dashcam_share_chooser)))
        }.onFailure {
            Toast.makeText(this, getString(R.string.dashcam_share_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadClips()
    }

    private fun loadClips() {
        lifecycleScope.launch {
            val clips = withContext(Dispatchers.IO) {
                DashcamClipStorageManager.loadClipsNewestFirst(this@DashcamClipsActivity)
            }
            loadedClips = clips
            adapter.submit(clips)
            emptyText.isVisible = clips.isEmpty()
            recyclerView.isVisible = clips.isNotEmpty()
            val totalBytes = clips.sumOf { it.sizeBytes }
            summaryText.text = getString(
                R.string.dashcam_clips_summary,
                clips.size,
                Formatter.formatShortFileSize(this@DashcamClipsActivity, totalBytes),
            )
        }
    }

    private fun openClip(clip: StoredDashcamClip) {
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.dashcamfileprovider", clip.file)
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, getString(R.string.dashcam_clips_no_player), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(intent)
        }.onFailure {
            Toast.makeText(this, getString(R.string.dashcam_clips_no_player), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(clip: StoredDashcamClip) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dashcam_clip_delete)
            .setMessage(getString(R.string.dashcam_clip_delete_confirm, clip.displayName))
            .setPositiveButton(R.string.dashcam_clip_delete) { _, _ -> deleteClip(clip) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteClip(clip: StoredDashcamClip) {
        val deleted = runCatching { clip.file.delete() }.getOrDefault(false)
        if (deleted) {
            Toast.makeText(this, getString(R.string.dashcam_clip_deleted), Toast.LENGTH_SHORT).show()
            loadClips()
        } else {
            Toast.makeText(this, getString(R.string.dashcam_clip_delete_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private class ClipAdapter(
        private val onClick: (StoredDashcamClip) -> Unit,
        private val onDelete: (StoredDashcamClip) -> Unit,
    ) : RecyclerView.Adapter<ClipAdapter.ClipViewHolder>() {

        private val clips = mutableListOf<StoredDashcamClip>()

        fun submit(newClips: List<StoredDashcamClip>) {
            clips.clear()
            clips.addAll(newClips)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ClipViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dashcam_clip, parent, false)
            return ClipViewHolder(view, onClick, onDelete)
        }

        override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
            holder.bind(clips[position])
        }

        override fun getItemCount(): Int = clips.size

        private class ClipViewHolder(
            itemView: View,
            private val onClick: (StoredDashcamClip) -> Unit,
            private val onDelete: (StoredDashcamClip) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {

            private val thumbnail: ImageView = itemView.findViewById(R.id.clipThumbnail)
            private val nameText: TextView = itemView.findViewById(R.id.clipNameText)
            private val metaText: TextView = itemView.findViewById(R.id.clipMetaText)
            private val deleteButton: ImageButton = itemView.findViewById(R.id.clipDeleteButton)

            fun bind(clip: StoredDashcamClip) {
                val context = itemView.context
                nameText.text = clip.displayName
                val date = DateFormat.getDateTimeInstance()
                    .format(Date(clip.startedAtEpochMs))
                val size = Formatter.formatShortFileSize(context, clip.sizeBytes)
                metaText.text = context.getString(R.string.dashcam_clip_meta_fmt, date, size)
                itemView.setOnClickListener { onClick(clip) }
                deleteButton.setOnClickListener { onDelete(clip) }
                loadThumbnail(clip)
            }

            private fun loadThumbnail(clip: StoredDashcamClip) {
                thumbnail.setImageDrawable(null)
                thumbnail.tag = clip.file.absolutePath
                THUMBNAIL_EXECUTOR.execute {
                    val bitmap = createVideoThumbnail(clip)
                    if (bitmap != null) {
                        thumbnail.post {
                            if (thumbnail.tag == clip.file.absolutePath) {
                                thumbnail.setImageBitmap(bitmap)
                            }
                        }
                    }
                }
            }

            private fun createVideoThumbnail(clip: StoredDashcamClip): Bitmap? {
                return runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ThumbnailUtils.createVideoThumbnail(
                            clip.file,
                            Size(192, 108),
                            null,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        ThumbnailUtils.createVideoThumbnail(
                            clip.file.absolutePath,
                            MediaStore.Video.Thumbnails.MINI_KIND,
                        )
                    }
                }.getOrNull()
            }
        }

        companion object {
            private val THUMBNAIL_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(2)
        }
    }
}
