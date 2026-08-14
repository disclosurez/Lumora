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
