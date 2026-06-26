package com.phantom.dashcam

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Browse recorded dashcam clips (newest first) with a thumbnail, tap-to-play, delete, and export
 * flows. Clips stay in app-private storage until the user explicitly downloads or shares them.
 */
class DashcamClipsActivity : AppCompatActivity() {
    private companion object {
        private const val SHARE_DURATION_TOLERANCE_MS = 5_000L
    }

    private enum class ExportDestination {
        DOWNLOAD_TO_GALLERY,
        SHARE_OUT,
    }

    private lateinit var summaryText: TextView
    private lateinit var emptyText: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var shareRecentButton: MaterialButton
    private lateinit var exportRangeButton: MaterialButton
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

        shareRecentButton.setOnClickListener { showShareRecentDialog() }
        exportRangeButton.setOnClickListener { showCustomRangeDialog() }
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

    private fun showCustomRangeDialog() {
        val nowMs = System.currentTimeMillis()
        val latestClipEndMs = loadedClips.maxOfOrNull { it.endedAtEpochMs } ?: nowMs
        val earliestClipStartMs = loadedClips.minOfOrNull { it.startedAtEpochMs } ?: (latestClipEndMs - 10 * 60_000L)
        var startMs = maxOf(earliestClipStartMs, latestClipEndMs - 10 * 60_000L)
        var endMs = latestClipEndMs

        val view = layoutInflater.inflate(R.layout.dialog_dashcam_time_range, null)
        val startValueText: TextView = view.findViewById(R.id.exportRangeStartValue)
        val endValueText: TextView = view.findViewById(R.id.exportRangeEndValue)
        val pickStartButton: MaterialButton = view.findViewById(R.id.pickExportRangeStartButton)
        val pickEndButton: MaterialButton = view.findViewById(R.id.pickExportRangeEndButton)

        fun refreshRangeSummary() {
            startValueText.text = formatDateTime(startMs)
            endValueText.text = formatDateTime(endMs)
        }

        pickStartButton.setOnClickListener {
            pickDateTime(startMs) { pickedMs ->
                startMs = pickedMs
                refreshRangeSummary()
            }
        }
        pickEndButton.setOnClickListener {
            pickDateTime(endMs) { pickedMs ->
                endMs = pickedMs
                refreshRangeSummary()
            }
        }

        refreshRangeSummary()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dashcam_export_range_title)
            .setView(view)
            .setPositiveButton(R.string.dashcam_export_range_continue, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (endMs <= startMs) {
                    Toast.makeText(
                        this,
                        getString(R.string.dashcam_export_invalid_range),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
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
        }
        dialog.show()
    }

    private fun pickDateTime(
        initialMs: Long,
        onPicked: (Long) -> Unit,
    ) {
        val calendar = Calendar.getInstance().apply { timeInMillis = initialMs }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val pickedCalendar = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, calendar.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, calendar.get(Calendar.MINUTE))
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        pickedCalendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        pickedCalendar.set(Calendar.MINUTE, minute)
                        onPicked(pickedCalendar.timeInMillis)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(this),
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
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
                    ExportDestination.DOWNLOAD_TO_GALLERY
                } else {
                    ExportDestination.SHARE_OUT
                }
                exportSelection(selection, destination)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportSelection(
        selection: RecentClipSelection,
        destination: ExportDestination,
    ) {
        setExportButtonsBusy(true)
        lifecycleScope.launch {
            val mergedFile = withContext(Dispatchers.IO) {
                DashcamClipMerger.mergeToCache(
                    this@DashcamClipsActivity,
                    selection.clips,
                    exportStartMs = selection.windowStartMs,
                    exportEndMs = selection.windowEndMs,
                )
            }
            if (mergedFile == null) {
                setExportButtonsBusy(false)
                Toast.makeText(
                    this@DashcamClipsActivity,
                    getString(R.string.dashcam_share_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }

            when (destination) {
                ExportDestination.DOWNLOAD_TO_GALLERY -> {
                    val galleryUri = withContext(Dispatchers.IO) {
                        DashcamClipExporter.saveToGallery(
                            this@DashcamClipsActivity,
                            mergedFile,
                            buildGalleryExportName(selection),
                        )
                    }
                    setExportButtonsBusy(false)
                    if (galleryUri == null) {
                        Toast.makeText(
                            this@DashcamClipsActivity,
                            getString(R.string.dashcam_export_save_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            this@DashcamClipsActivity,
                            getString(R.string.dashcam_export_saved_to_gallery),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }

                ExportDestination.SHARE_OUT -> {
                    setExportButtonsBusy(false)
                    shareMergedFile(mergedFile)
                }
            }
        }
    }

    private fun buildGalleryExportName(selection: RecentClipSelection): String {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "GOCam_export_${formatter.format(Date(selection.windowStartMs))}_${formatter.format(Date(selection.windowEndMs))}.mp4"
    }

    private fun setExportButtonsBusy(isBusy: Boolean) {
        shareRecentButton.isEnabled = !isBusy
        exportRangeButton.isEnabled = !isBusy
        if (isBusy) {
            shareRecentButton.text = getString(R.string.dashcam_share_merging)
            exportRangeButton.text = getString(R.string.dashcam_share_merging)
        } else {
            shareRecentButton.text = getString(R.string.dashcam_share_recent)
            exportRangeButton.text = getString(R.string.dashcam_export_range)
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
