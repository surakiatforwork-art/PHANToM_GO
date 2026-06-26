package com.phantom.dashcam

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Browse recorded dashcam clips (newest first). Tapping a clip opens it in the
 * system video player. Reachable from both the GO Cam shell and the main app.
 */
class DashcamClipsActivity : AppCompatActivity() {

    private lateinit var summaryText: TextView
    private lateinit var emptyText: TextView
    private lateinit var recyclerView: RecyclerView
    private val adapter = ClipAdapter(::openClip)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashcam_clips)
        summaryText = findViewById(R.id.clipsSummaryText)
        emptyText = findViewById(R.id.clipsEmptyText)
        recyclerView = findViewById(R.id.clipsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
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
        val uri = DashcamClipStorageManager.contentUriFor(clip.id)
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

    private class ClipAdapter(
        private val onClick: (StoredDashcamClip) -> Unit,
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
            return ClipViewHolder(view, onClick)
        }

        override fun onBindViewHolder(holder: ClipViewHolder, position: Int) {
            holder.bind(clips[position])
        }

        override fun getItemCount(): Int = clips.size

        private class ClipViewHolder(
            itemView: View,
            private val onClick: (StoredDashcamClip) -> Unit,
        ) : RecyclerView.ViewHolder(itemView) {

            private val nameText: TextView = itemView.findViewById(R.id.clipNameText)
            private val metaText: TextView = itemView.findViewById(R.id.clipMetaText)

            fun bind(clip: StoredDashcamClip) {
                val context = itemView.context
                nameText.text = clip.displayName
                val date = DateFormat.getDateTimeInstance()
                    .format(Date(clip.createdAtEpochSeconds * 1000L))
                val size = Formatter.formatShortFileSize(context, clip.sizeBytes)
                metaText.text = context.getString(R.string.dashcam_clip_meta_fmt, date, size)
                itemView.setOnClickListener { onClick(clip) }
            }
        }
    }
}
