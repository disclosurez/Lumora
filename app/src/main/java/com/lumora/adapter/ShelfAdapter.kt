package com.lumora.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.RecycledViewPool
import com.lumora.R
import com.lumora.model.Channel
import com.lumora.model.ContentShelf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lumora.util.PosterLoader

/** Netflix-style vertical stack of horizontally-scrolling category shelves. */
class ShelfAdapter(
    private val onItemClick: (Channel) -> Unit,
    private val onPinClick: (ContentShelf) -> Unit = {},
    private val onHideClick: (ContentShelf) -> Unit = {},
    private val onSeeAllClick: (ContentShelf) -> Unit = {},
    // Pinning only means something for real provider categories (Series/Films) - the
    // synthetic Home shelves (Continue Watching/Recently Played/Favorites) already have
    // a fixed, meaningful order, so the star has nothing to do there.
    private val showPinButton: Boolean = true
) : ListAdapter<ContentShelf, ShelfAdapter.ShelfViewHolder>(DiffCallback()) {

    // Shared across every shelf row so scrolling vertically past a shelf and
    // back doesn't re-inflate its poster views from scratch every time.
    private val sharedPosterPool = RecycledViewPool()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShelfViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shelf, parent, false)
        return ShelfViewHolder(view)
    }

    override fun onBindViewHolder(holder: ShelfViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ShelfViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.shelfTitle)
        private val seeAllButton: TextView = itemView.findViewById(R.id.shelfSeeAllButton)
        private val pinButton: TextView = itemView.findViewById(R.id.shelfPinButton)
        private val hideButton: TextView = itemView.findViewById(R.id.shelfHideButton)
        private val itemsList: RecyclerView = itemView.findViewById(R.id.shelfItems)
        private val posterAdapter = ShelfPosterAdapter(onItemClick)
        private var current: ContentShelf? = null

        init {
            itemsList.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            itemsList.setRecycledViewPool(sharedPosterPool)
            itemsList.adapter = posterAdapter
            seeAllButton.setOnClickListener { current?.let(onSeeAllClick) }
            pinButton.setOnClickListener { current?.let(onPinClick) }
            hideButton.setOnClickListener { current?.let(onHideClick) }
        }

        fun bind(shelf: ContentShelf) {
            current = shelf
            titleText.text = "${shelf.title} (${shelf.items.size})"
            if (showPinButton) {
                pinButton.visibility = View.VISIBLE
                pinButton.text = if (shelf.pinned) "★" else "☆"
                pinButton.setTextColor(pinButton.context.getColor(if (shelf.pinned) R.color.primary else R.color.text_secondary))
            } else {
                pinButton.visibility = View.GONE
            }
            posterAdapter.submitList(shelf.items)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ContentShelf>() {
        override fun areItemsTheSame(old: ContentShelf, new: ContentShelf): Boolean = old.title == new.title
        override fun areContentsTheSame(old: ContentShelf, new: ContentShelf): Boolean = old == new
    }
}

private class ShelfPosterAdapter(
    private val onItemClick: (Channel) -> Unit
) : ListAdapter<Channel, ShelfPosterAdapter.ViewHolder>(DiffCallback()) {

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shelf_poster, parent, false)
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
