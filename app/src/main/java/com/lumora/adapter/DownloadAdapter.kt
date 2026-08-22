package com.lumora.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumora.R
import com.lumora.download.DownloadRecord
import com.lumora.download.DownloadStatus
import com.lumora.util.PosterLoader

class DownloadAdapter(
    private val onClick: (DownloadRecord) -> Unit,
    private val onDelete: (DownloadRecord) -> Unit
) : ListAdapter<DownloadRecord, DownloadAdapter.ViewHolder>(DiffCallback()) {

    var topRowFocusUpTargetId: Int = View.NO_ID

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.downloadPoster)
        private val title: TextView = itemView.findViewById(R.id.downloadTitle)
        private val status: TextView = itemView.findViewById(R.id.downloadStatus)
        private val progress: ProgressBar = itemView.findViewById(R.id.downloadProgress)
        private val deleteButton: ImageView = itemView.findViewById(R.id.downloadDeleteButton)
        private var current: DownloadRecord? = null

        init {
            itemView.setOnClickListener { current?.let(onClick) }
            deleteButton.setOnClickListener { current?.let(onDelete) }
            val keyHandler = View.OnKeyListener { v, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@OnKeyListener false
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (v === itemView) { deleteButton.requestFocus(); true } else true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (v === deleteButton) { itemView.requestFocus(); true } else false
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        if (bindingAdapterPosition == 0) {
                            val root = v.rootView
                            val candidates = intArrayOf(topRowFocusUpTargetId, R.id.tabDownloads, R.id.tabHome, R.id.btnSettings)
                            for (id in candidates) {
                                if (id == View.NO_ID) continue
                                val t = root.findViewById<View>(id)
                                if (t != null && t.isShown && t.requestFocus()) return@OnKeyListener true
                            }
                        }
                        false
                    }
                    else -> false
                }
            }
            itemView.setOnKeyListener(keyHandler)
            deleteButton.setOnKeyListener(keyHandler)
        }

        fun bind(record: DownloadRecord) {
            current = record
            title.text = record.title
            poster.setImageDrawable(null)
            record.posterUrl?.takeIf { it.isNotBlank() }?.let { url ->
                PosterLoader.getCached(url)?.let { poster.setImageBitmap(it) }
            }
            when (record.status) {
                DownloadStatus.QUEUED -> {
                    status.text = itemView.context.getString(R.string.list_queued)
                    progress.visibility = View.GONE
                }
                DownloadStatus.DOWNLOADING -> {
                    status.text = itemView.context.getString(R.string.list_downloading_percent, record.progressPercent)
                    progress.visibility = View.VISIBLE
                    progress.progress = record.progressPercent
                }
                DownloadStatus.COMPLETE -> {
                    status.text = itemView.context.getString(R.string.list_downloaded, record.subtitle)
                    progress.visibility = View.GONE
                }
                DownloadStatus.FAILED -> {
                    status.text = itemView.context.getString(R.string.list_download_failed)
                    progress.visibility = View.GONE
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<DownloadRecord>() {
        override fun areItemsTheSame(old: DownloadRecord, new: DownloadRecord) = old.id == new.id
        override fun areContentsTheSame(old: DownloadRecord, new: DownloadRecord) = old == new
    }
}
