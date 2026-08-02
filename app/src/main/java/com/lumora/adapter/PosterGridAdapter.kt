package com.lumora.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumora.R
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.util.PosterLoader
import com.lumora.util.cleanVodTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/** Flat, vertically-scrolling poster grid - used for a single selected category
 *  (Films/Series), where a horizontal shelf strip isn't enough room to browse in, and
 *  for global search results (which mix Live/Film/Series, hence [showTypeBadge]). */
class PosterGridAdapter(
    private val showTypeBadge: Boolean = false,
    /** Long-press on a poster - favourites the item (see MainActivity.toggleFavoriteVodItem).
     *  Declared before [onItemClick] so the click handler stays the trailing lambda every
     *  call site passes it as. */
    private val onItemLongClick: ((Channel) -> Unit)? = null,
    private val onItemClick: (Channel) -> Unit
) : ListAdapter<Channel, PosterGridAdapter.ViewHolder>(DiffCallback()) {

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

    /** Column count of the GridLayoutManager this adapter is currently bound to, and where
     *  D-pad UP from the top row should go - a poster's default UP focus-search has to find
     *  something reasonably column-aligned above it, and depending which column a poster's
     *  in, nothing up there (tab bar/search box) may be close enough to win, especially
     *  since the tab bar centered itself instead of spanning full width. Set both together
     *  whenever the grid is (re)built, since span count changes with screen width/rotation.
     *  Handled via OnKeyListener rather than nextFocusUpId - RecyclerView.focusSearch()
     *  scopes its own findNextFocus() to itself as root, so a target outside the RecyclerView
     *  (the tab bar always is) never actually resolves that way; UP just silently does
     *  nothing instead of erroring, which is what made this easy to miss. */
    var spanCount: Int = 1
    var topRowFocusUpTargetId: Int = View.NO_ID

    /** Poster artwork height, as a dimen resource. The card is as wide as the grid's column,
     *  but its height comes from the layout - so a grid in a narrow pane (search, which
     *  gives up most of its width to the keyboard) needs a shorter poster or the artwork is
     *  stretched well past the 2:3 it was cropped for. Null keeps the layout's own value. */
    var posterHeightDimen: Int? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster_grid, parent, false)
        posterHeightDimen?.let { dimen ->
            val poster = view.findViewById<ImageView>(R.id.itemPoster)
            poster.layoutParams = poster.layoutParams.also {
                it.height = view.resources.getDimensionPixelSize(dimen)
            }
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val posterImage: ImageView = itemView.findViewById(R.id.itemPoster)
        private val titleText: TextView = itemView.findViewById(R.id.itemTitle)
        private val typeBadge: TextView = itemView.findViewById(R.id.itemTypeBadge)
        private var current: Channel? = null

        init {
            itemView.setOnClickListener { current?.let(onItemClick) }
            onItemLongClick?.let { handler ->
                itemView.setOnLongClickListener { current?.let(handler); true }
            }
            itemView.setOnKeyListener { v, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP && event.action == android.view.KeyEvent.ACTION_DOWN &&
                    topRowFocusUpTargetId != View.NO_ID && bindingAdapterPosition in 0 until spanCount
                ) {
                    v.rootView.findViewById<View>(topRowFocusUpTargetId)?.let { it.requestFocus(); return@setOnKeyListener true }
                }
                false
            }
        }

        fun bind(channel: Channel) {
            current = channel
            // VOD titles carry source/quality decoration ("4K-AMZ - ", "(US)") that reads
            // as noise on a poster - strip it for display; live names keep their country tag.
            titleText.text = if (channel.mediaType == MediaType.MOVIE || channel.mediaType == MediaType.SERIES) {
                cleanVodTitle(channel.name)
            } else channel.name

            if (showTypeBadge) {
                typeBadge.visibility = View.VISIBLE
                val (label, colorRes) = when (channel.mediaType) {
                    MediaType.LIVE -> "Live" to R.color.live_red
                    MediaType.MOVIE -> "Film" to R.color.info_cyan
                    MediaType.SERIES -> "Series" to R.color.primary
                }
                typeBadge.text = label
                typeBadge.backgroundTintList = ContextCompat.getColorStateList(itemView.context, colorRes)
            } else {
                typeBadge.visibility = View.GONE
            }

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
