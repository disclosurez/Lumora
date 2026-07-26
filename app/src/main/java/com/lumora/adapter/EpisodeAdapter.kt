package com.lumora.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumora.R
import com.lumora.model.Channel
import com.lumora.util.PosterLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class EpisodeAdapter(
    private val onEpisodeClick: (Channel) -> Unit,
    private val showDownloadButton: Boolean = false,
    private val onDownloadClick: ((Channel) -> Unit)? = null,
    private val isDownloaded: ((Channel) -> Boolean)? = null
) : ListAdapter<Channel, EpisodeAdapter.ViewHolder>(DiffCallback()) {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.episodeTitle)
        private val thumbImage: ImageView = itemView.findViewById(R.id.episodeThumb)
        private val downloadButton: ImageButton = itemView.findViewById(R.id.episodeDownloadButton)
        private var current: Channel? = null

        init {
            itemView.setOnClickListener { current?.let(onEpisodeClick) }
            downloadButton.setOnClickListener { current?.let { onDownloadClick?.invoke(it) } }
        }

        fun bind(episode: Channel) {
            current = episode
            titleText.text = episode.name
            if (showDownloadButton) {
                downloadButton.visibility = View.VISIBLE
                downloadButton.isEnabled = isDownloaded?.invoke(episode) != true
                downloadButton.alpha = if (downloadButton.isEnabled) 1f else 0.4f
            } else {
                downloadButton.visibility = View.GONE
            }

            // Episode "preview" is the still/thumbnail the provider sends back for that
            // specific episode (movie_image in get_series_info) - not every provider or
            // every episode has one. The play-button overlay always shows either way;
            // without an image it just sits on the plain rounded card background.
            thumbImage.setImageDrawable(null)
            val url = episode.posterUrl
            if (!url.isNullOrBlank()) {
                PosterLoader.getCached(url)?.let { thumbImage.setImageBitmap(it) } ?: run {
                    scope.launch {
                        val bitmap = PosterLoader.fetch(url)
                        if (bitmap != null && current === episode) thumbImage.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(old: Channel, new: Channel): Boolean = old.id == new.id
        override fun areContentsTheSame(old: Channel, new: Channel): Boolean = old == new
    }
}
