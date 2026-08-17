package com.lumora.adapter

import android.view.LayoutInflater
import android.graphics.Rect
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

/** A TV-program match from EPG search: the program title is the headline, with the
 *  channel + air time as the meta line. [channel] is what gets played on click. */
data class SearchEpgResult(
    val programTitle: String,
    val metaLine: String,
    val channel: Channel
)

/** One cell in the search results grid: either a catalog [Media] item (poster tile) or
 *  an EPG [Epg] program match (program tile). */
sealed class SearchResultItem {
    data class Media(val channel: Channel) : SearchResultItem()
    data class Epg(val program: SearchEpgResult) : SearchResultItem()
}

/** Search results grid: catalog posters + EPG program tiles in one mixed grid. Replaces
 *  PosterGridAdapter for search because results are no longer all Channels. Focus wiring
 *  mirrors PosterGridAdapter - UP from the top row jumps to [topRowFocusUpTargetId] and
 *  LEFT from the first column to [leftFocusTarget], both via OnKeyListener, since default
 *  focus search can't cross the RecyclerView boundary (this app's documented failure mode:
 *  RecyclerView.focusSearch() scopes FocusFinder to itself, so a target outside the grid
 *  - the keyboard - never resolves through XML nextFocusUpId/nextFocusLeftId). */
class SearchResultsAdapter(
    private val showTypeBadge: Boolean = false,
    /** Long-press on a media poster - favourites the item (see MainActivity.toggleFavoriteVodItem). */
    private val onItemLongClick: ((Channel) -> Unit)? = null,
    private val onItemClick: (SearchResultItem) -> Unit
) : ListAdapter<SearchResultItem, RecyclerView.ViewHolder>(DiffCallback()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Column count of the GridLayoutManager this adapter is bound to. */
    var spanCount: Int = 1
    /** View id resolved via rootView when DPAD_UP is pressed on the top row (the keyboard). */
    var topRowFocusUpTargetId: Int = View.NO_ID
    /** View focused when DPAD_LEFT is pressed from the first column (the keyboard). */
    var leftFocusTarget: View? = null
    /** Poster artwork height override (search gives up most of its width to the keyboard). */
    var posterHeightDimen: Int? = null

    /** Cancel in-flight poster fetches when the adapter is detached or no longer needed.
     *  Children only, never the scope's own Job - a cancelled scope Job stays cancelled
     *  forever, so later launches silently no-op and posters never load again. */
    fun cancelPendingWork() {
        scope.coroutineContext.cancelChildren()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        cancelPendingWork()
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchResultItem.Media -> TYPE_MEDIA
        is SearchResultItem.Epg -> TYPE_EPG
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        return when (viewType) {
            TYPE_EPG -> EpgHolder(
                LayoutInflater.from(context).inflate(R.layout.item_epg_result, parent, false)
            )
            else -> MediaHolder(
                LayoutInflater.from(context).inflate(R.layout.item_poster_grid, parent, false).also { view ->
                    // Same poster-height override the search grid used with PosterGridAdapter:
                    // the card is as wide as the grid column, but a narrow pane (keyboard took
                    // the width) needs a shorter poster or the artwork is stretched.
                    posterHeightDimen?.let { dimen ->
                        val poster = view.findViewById<ImageView>(R.id.itemPoster)
                        poster.layoutParams = poster.layoutParams.also {
                            it.height = view.resources.getDimensionPixelSize(dimen)
                        }
                    }
                }
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SearchResultItem.Media -> (holder as MediaHolder).bind(item.channel)
            is SearchResultItem.Epg -> (holder as EpgHolder).bind(item.program)
        }
    }

    /** Focus handling shared by both tile types: escape the grid at its top/left edges into
     *  the keyboard, bypassing the framework's focus-search (which can't resolve outside the
     *  RecyclerView). [positionProvider] reads the holder's live adapter position - the
     *  listener lambda is created in the adapter's scope, where bindingAdapterPosition isn't
     *  available. */
    private fun setupFocus(itemView: View, positionProvider: () -> Int) {
        itemView.setOnKeyListener { v, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                val pos = positionProvider()
                if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP &&
                    topRowFocusUpTargetId != View.NO_ID && pos in 0 until spanCount
                ) {
                    val target = v.rootView.findViewById<View>(topRowFocusUpTargetId)
                    if (target != null && target.requestFocus()) return@setOnKeyListener true
                } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                    leftFocusTarget != null && isAtLeftEdge(v)
                ) {
                    leftFocusTarget?.let { if (focusLeftTarget(v, it)) return@setOnKeyListener true }
                }
            }
            false
        }
    }

    /**
     * True when [v] has no grid neighbour to its left, i.e. it sits in the first column.
     *
     * Asked of the framework rather than computed as `position % spanCount`: that arithmetic
     * is only right while [spanCount] agrees with the LayoutManager the adapter was actually
     * attached to, and it is assigned by hand at each call site from a resource that changes
     * with screen width.
     *
     * The neighbour has to be checked for grid membership, not merely for being non-null.
     * RecyclerView.focusSearch falls back to `super.focusSearch` - the parent's, window-wide -
     * whenever it finds nothing preferable within itself, so a first-column tile gets a
     * non-null answer that is already the keyboard.
     */
    private fun isAtLeftEdge(v: View): Boolean {
        val grid = v.parent as? RecyclerView ?: return true
        val neighbour = v.focusSearch(View.FOCUS_LEFT) ?: return true
        return neighbour === v || grid.findContainingItemView(neighbour) == null
    }

    /**
     * Move focus from tile [from] leftward onto [target], telling the target where the press
     * came from so it can land somewhere adjacent rather than at some fixed default - the
     * keyboard uses the rect to pick the key beside the tile instead of the middle of the
     * letter block. Returns whether focus actually moved.
     */
    private fun focusLeftTarget(from: View, target: View): Boolean {
        val root = from.rootView as? ViewGroup ?: return target.requestFocus()
        val rect = Rect()
        from.getDrawingRect(rect)
        // The rect has to arrive in the target's own coordinates, which is the contract
        // ViewRootImpl itself follows when it hands a focus rect across the hierarchy.
        root.offsetDescendantRectToMyCoords(from, rect)
        root.offsetRectIntoDescendantCoords(target, rect)
        return target.requestFocus(View.FOCUS_LEFT, rect)
    }

    inner class MediaHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val posterImage: ImageView = itemView.findViewById(R.id.itemPoster)
        private val titleText: TextView = itemView.findViewById(R.id.itemTitle)
        private val typeBadge: TextView = itemView.findViewById(R.id.itemTypeBadge)
        private var current: Channel? = null

        init {
            itemView.setOnClickListener { current?.let { onItemClick(SearchResultItem.Media(it)) } }
            onItemLongClick?.let { handler ->
                itemView.setOnLongClickListener { current?.let(handler); true }
            }
            setupFocus(itemView) { bindingAdapterPosition }
        }

        fun bind(channel: Channel) {
            current = channel
            // VOD titles carry source/quality decoration that reads as noise on a poster;
            // live names keep their country tag.
            titleText.text = if (channel.mediaType == MediaType.MOVIE || channel.mediaType == MediaType.SERIES) {
                cleanVodTitle(channel.name)
            } else channel.name

            if (showTypeBadge) {
                typeBadge.visibility = View.VISIBLE
                val (label, colorRes) = when (channel.mediaType) {
                    MediaType.LIVE -> itemView.context.getString(R.string.live_badge) to R.color.live_red
                    MediaType.MOVIE -> itemView.context.getString(R.string.list_type_film) to R.color.info_cyan
                    MediaType.SERIES -> itemView.context.getString(R.string.series_tab) to R.color.primary
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

    inner class EpgHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val posterImage: ImageView = itemView.findViewById(R.id.epgPoster)
        private val titleText: TextView = itemView.findViewById(R.id.epgProgramTitle)
        private val metaText: TextView = itemView.findViewById(R.id.epgProgramMeta)
        private var current: SearchEpgResult? = null

        init {
            itemView.setOnClickListener { current?.let { onItemClick(SearchResultItem.Epg(it)) } }
            setupFocus(itemView) { bindingAdapterPosition }
        }

        fun bind(result: SearchEpgResult) {
            current = result
            titleText.text = result.programTitle
            metaText.text = result.metaLine
            val url = result.channel.posterUrl ?: result.channel.logoUrl
            posterImage.setImageDrawable(null)
            if (url.isNullOrBlank()) {
                posterImage.setImageResource(R.drawable.ic_launcher_foreground)
                return
            }
            PosterLoader.getCached(url)?.let { posterImage.setImageBitmap(it); return }
            scope.launch {
                val bitmap = PosterLoader.fetch(url)
                if (bitmap != null && current === result) posterImage.setImageBitmap(bitmap)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<SearchResultItem>() {
        override fun areItemsTheSame(old: SearchResultItem, new: SearchResultItem): Boolean = when {
            old is SearchResultItem.Media && new is SearchResultItem.Media ->
                old.channel.url == new.channel.url ||
                    (old.channel.id.isNotBlank() && old.channel.id == new.channel.id)
            old is SearchResultItem.Epg && new is SearchResultItem.Epg ->
                old.program.channel.id == new.program.channel.id &&
                    old.program.programTitle == new.program.programTitle
            else -> false
        }
        override fun areContentsTheSame(old: SearchResultItem, new: SearchResultItem): Boolean = old == new
    }

    private companion object {
        const val TYPE_MEDIA = 0
        const val TYPE_EPG = 1
    }
}
