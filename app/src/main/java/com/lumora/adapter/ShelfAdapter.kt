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

/** Netflix-style vertical stack of horizontally-scrolling category shelves.
 *  [topAnchorViewId] is where D-pad UP from the very first shelf's posters should land
 *  (the tab bar button for whichever tab hosts this adapter) - without it, UP finds
 *  whatever's geometrically nearest, which is that same shelf's own See All/Pin/Hide
 *  row, not the tab bar above it. Every other shelf's posters get wired the same way
 *  to the *previous* shelf's poster row, for the same reason - see wireVerticalFocus(). */
class ShelfAdapter(
    private val onItemClick: (Channel) -> Unit,
    private val onPinClick: (ContentShelf) -> Unit = {},
    private val onHideClick: (ContentShelf) -> Unit = {},
    private val onSeeAllClick: (ContentShelf) -> Unit = {},
    // Pinning only means something for real provider categories (Series/Films) - the
    // synthetic Home shelves (Continue Watching/Recently Played/Favorites) already have
    // a fixed, meaningful order, so the star has nothing to do there.
    private val showPinButton: Boolean = true,
    private val topAnchorViewId: Int = View.NO_ID
) : ListAdapter<ContentShelf, ShelfAdapter.ShelfViewHolder>(DiffCallback()) {

    // Shared across every shelf row so scrolling vertically past a shelf and
    // back doesn't re-inflate its poster views from scratch every time.
    private val sharedPosterPool = RecycledViewPool()
    private var outerRecyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        outerRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        if (outerRecyclerView === recyclerView) outerRecyclerView = null
    }

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
            wireVerticalFocus()
        }

        private fun wireVerticalFocus() {
            val outerRv = outerRecyclerView
            val pos = bindingAdapterPosition
            // Always assign - including View.NO_ID on failure - rather than returning early
            // and leaving whatever was set before. This ShelfViewHolder instance gets
            // recycled to render different shelves as the list scrolls; if a stale target
            // (e.g. the tab bar, from when this same instance was rendering shelf 0) were
            // left in place because the lookup below happened to fail on a later bind, UP
            // would jump to the wrong place - it did, this is why.
            val targetId = when {
                outerRv == null || pos == RecyclerView.NO_POSITION -> View.NO_ID
                pos == 0 -> topAnchorViewId
                else -> (outerRv.findViewHolderForAdapterPosition(pos - 1) as? ShelfViewHolder)
                    ?.let { ensureViewId(it.itemsList) } ?: View.NO_ID
            }
            posterAdapter.nextFocusUpTargetId = targetId
        }

        private fun ensureViewId(view: View): Int {
            if (view.id == View.NO_ID) view.id = View.generateViewId()
            return view.id
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

    /** Where D-pad UP should go from any poster in this row - set by the owning
     *  ShelfViewHolder once it knows its position among sibling shelves, read live by each
     *  poster's OnKeyListener (see ViewHolder init) rather than baked in at bind time, so a
     *  poster scrolled into view later (this row scrolls horizontally, independent of
     *  vertical shelf order) still gets the current value, not a stale one. */
    var nextFocusUpTargetId: Int = View.NO_ID

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
            // RecyclerView.focusSearch() scopes its own findNextFocus() call to itself as
            // root, so nextFocusUpId pointing outside this RecyclerView (let alone outside
            // the *outer* shelf-list RecyclerView this row is nested inside) never actually
            // resolves - the search silently comes up empty and UP does nothing. Handling
            // the key directly and jumping focus manually sidesteps that scoping entirely.
            itemView.setOnKeyListener { v, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && event.action == android.view.KeyEvent.ACTION_DOWN &&
                    nextFocusUpTargetId != View.NO_ID
                ) {
                    v.rootView.findViewById<View>(nextFocusUpTargetId)?.let { it.requestFocus(); return@setOnKeyListener true }
                }
                false
            }
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
