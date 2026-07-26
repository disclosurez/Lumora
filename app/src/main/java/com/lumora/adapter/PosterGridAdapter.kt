package com.lumora.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

/** Flat, vertically-scrolling poster grid - used for a single selected category
 *  (Films/Series), where a horizontal shelf strip isn't enough room to browse in. */
class PosterGridAdapter(
    private val onItemClick: (Channel) -> Unit
) : ListAdapter<Channel, PosterGridAdapter.ViewHolder>(DiffCallback()) {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val posterImage: ImageView = itemView.findViewById(R.id.itemPoster)
        private val titleText: TextView = itemView.findViewById(R.id.itemTitle)
        private var current: Channel? = null

        init {
            itemView.setOnClickListener { current?.let(onItemClick) }
        }

        fun bind(channel: Channel) {
            current = channel
            titleText.text = channel.name

            val url = channel.posterUrl ?: channel.logoUrl
            posterImage.setImageDrawable(null)
            if (url.isNullOrBlank()) {
                posterImage.setImageResource(R.drawable.ic_launcher_foreground)
                return
            }
            PosterLoader.getCached(url)?.let { posterImage.setImageBitmap(it); return }

            scope.launch {
                val bitmap = PosterLoader.fetch(url)
                if (bitmap != null && current === channel) posterImage.setImageBitmap(bitmap)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(old: Channel, new: Channel): Boolean = old.url == new.url || (old.id.isNotBlank() && old.id == new.id)
        override fun areContentsTheSame(old: Channel, new: Channel): Boolean = old == new
    }
}
