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
import com.lumora.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import com.lumora.util.PosterLoader
import com.lumora.util.cleanVodTitle

/** Vertical stack of horizontally-scrolling category shelves. D-pad UP/DOWN
 *  between shelf rows and OUT to the tab bar above is handled entirely by Android's default
 *  focus search - no manual key interception needed here (confirmed on-device; an earlier,
 *  much more involved manual-jump implementation turned out to be solving a problem that
 *  didn't actually exist, and was itself the source of several real navigation regressions). */
class ShelfAdapter(
    private val onItemClick: (Channel) -> Unit,
    /** Long-press on a poster - favourites the item (see MainActivity.toggleFavoriteVodItem).
     *  Same gesture the live guide already uses on a channel row, so one hold means
     *  "favourite this" everywhere in the app. */
    private val onItemLongClick: ((Channel) -> Unit)? = null,
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
        val itemsList: RecyclerView = itemView.findViewById(R.id.shelfItems)
        private val posterAdapter = ShelfPosterAdapter(onItemClick, onItemLongClick)
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
            // Pin star hidden for shelves whose category is inert (Newest / Jellyfin /
            // classic toggle) - pinning them does nothing, so a star would read as broken.
            if (showPinButton && shelf.categoryId !in NON_PINNABLE_CATEGORY_IDS) {
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
    private val onItemClick: (Channel) -> Unit,
    private val onItemLongClick: ((Channel) -> Unit)?
) : ListAdapter<Channel, ShelfPosterAdapter.ViewHolder>(DiffCallback()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Cancel in-flight poster fetches when the adapter is detached or no longer needed.
     *  Children only, never the scope's own Job: these adapters are long-lived and get
     *  re-attached (Films/Series swap between shelf and grid on the same RecyclerView, and
     *  shelf rows are recycled), and a cancelled scope Job stays cancelled forever - every
     *  later launch{} silently no-ops, so posters simply never loaded again after the first
     *  detach and only already-cached ones showed. */
    fun cancelPendingWork() {
        scope.coroutineContext.cancelChildren()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        cancelPendingWork()
    }

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
            onItemLongClick?.let { handler ->
                itemView.setOnLongClickListener { current?.let(handler); true }
            }
        }

        fun bind(channel: Channel) {
            current = channel
            // See PosterGridAdapter.bind - the id is what returns focus here after the
            // detail screen closes.
            itemView.tag = channel.id
            // VOD titles carry source/quality decoration ("4K-AMZ - ", "(US)") that reads
            // as noise on a poster - strip it for display; live names keep their country tag.
            titleText.text = if (channel.mediaType == MediaType.MOVIE || channel.mediaType == MediaType.SERIES) {
                cleanVodTitle(channel.name)
            } else channel.name

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
