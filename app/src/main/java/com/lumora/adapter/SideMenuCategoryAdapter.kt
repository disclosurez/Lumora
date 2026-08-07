package com.lumora.adapter

import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumora.R
import com.lumora.cache.EpgListCache
import com.lumora.model.CategoryFilter
import com.lumora.parser.XtreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The category drill-down inside the player side menu. Shows the current tab's categories
 *  (a snapshot of the browsing sidebar's categoryAdapter.currentList - no re-fetch) as the
 *  menu's second column, right of the nav rows.
 *
 *  The same adapter renders both of the column's levels - a section's categories, and one
 *  category's channels after drilling further in - because the row shape is identical; the
 *  Activity swaps the list and interprets the click. Channel rows carry `count = -1`, which
 *  drops the trailing "(n)".
 *
 *  D-pad: UP/DOWN inside the column is handled by RecyclerView itself; LEFT walks back out
 *  (to the previous level, or onto the section row that opened the column) and must NOT go
 *  through the framework's focus search, which scopes itself to the RecyclerView and
 *  silently fails to find anything outside it (see the repo's D-pad pattern). It's
 *  intercepted here and handed to [onLeftPressed], which the Activity owns. This listener
 *  also has to win over MainActivity.onKeyDown's "LEFT while the menu is open is a no-op"
 *  rule, which it does: the focused view sees the key first. */
class SideMenuCategoryAdapter(
    private val onCategoryClick: (CategoryFilter) -> Unit
) : ListAdapter<CategoryFilter, SideMenuCategoryAdapter.ViewHolder>(DiffCallback()) {

    var selectedId: String? = null
        private set

    /** True while the column lists channels rather than categories: only then does a row
     *  id name a channel the EPG can be looked up for. Set by the Activity alongside the
     *  list it submits. */
    var showNowPlaying: Boolean = false

    /** Same fetch the guide grid uses, supplied by the Activity. Results land in the shared
     *  [EpgListCache], so a channel already drawn in the guide costs nothing here. */
    var fetchPrograms: (suspend (String) -> List<XtreamClient.EpgProgram>?)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Rows are recycled constantly while the column scrolls; drop in-flight EPG work when
     *  the menu goes away rather than leaving it to finish against dead views. */
    fun cancelPendingWork() {
        scope.coroutineContext.cancelChildren()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        cancelPendingWork()
    }

    /** Back-out handler for LEFT inside the column - see the class comment. */
    var onLeftPressed: (() -> Unit)? = null

    /** RIGHT on a row: drills into it, the same way OK does. The menu opens rightwards the
     *  whole way down, so RIGHT has to keep working at every level rather than dead-ending
     *  at the first column. Also intercepted here so MainActivity.onKeyDown's "RIGHT closes
     *  the menu" rule never sees it. */
    var onRightPressed: ((CategoryFilter) -> Unit)? = null

    fun setSelected(id: String?) {
        val oldId = selectedId
        if (oldId == id) return
        selectedId = id
        val oldIdx = currentList.indexOfFirst { it.id == oldId }
        if (oldIdx >= 0) notifyItemChanged(oldIdx)
        val newIdx = currentList.indexOfFirst { it.id == id }
        if (newIdx >= 0 && newIdx != oldIdx) notifyItemChanged(newIdx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_side_menu_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id == selectedId)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.sideMenuCategoryName)
        private val nowLine: TextView = itemView.findViewById(R.id.sideMenuCategoryNow)
        private var current: CategoryFilter? = null
        private var nowJob: Job? = null

        init {
            itemView.setOnClickListener { current?.let(onCategoryClick) }
            // The guide line fills in for the row the user lands on, which is the one they
            // are reading - and keeps the column from fetching for every row it binds.
            itemView.setOnFocusChangeListener { _, hasFocus ->
                val row = current ?: return@setOnFocusChangeListener
                val id = row.id
                if (hasFocus && showNowPlaying && !id.isNullOrBlank()) requestNowPlaying(row, id)
            }
            // LEFT escapes the column (back a level, or out to the nav rows); RIGHT drills
            // further in. Both are consumed either way: falling through would hand the key
            // to the Activity, which has its own meaning for both while the menu is open.
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { onLeftPressed?.invoke(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { current?.let { onRightPressed?.invoke(it) }; true }
                    else -> false
                }
            }
        }

        fun bind(category: CategoryFilter, selected: Boolean) {
            current = category
            val rawName = if (category.isDynamic) category.name.uppercase() else category.name
            val label = rawName.let { if (it.length > 80) it.take(79) + "…" else it }
            val builder = SpannableStringBuilder(label)
            if (category.count >= 0) {
                val start = builder.length
                builder.append(" (${category.count})")
                builder.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(name.context, R.color.text_tertiary)),
                    start,
                    builder.length,
                    0
                )
            }
            name.text = builder
            itemView.isSelected = selected
            name.setTextColor(
                name.context.getColor(if (selected) R.color.text_primary else R.color.text_secondary)
            )
            bindNowPlaying(category)
        }

        /** "Now: <programme>" under a channel row. Reassigned on every bind, GONE included -
         *  a recycled holder must never keep the previous channel's programme.
         *
         *  Cached guide only; the fetch is deferred to [requestNowPlaying] on focus. Firing
         *  one per bind turned opening a large category into a burst of get_short_epg calls
         *  (a column can bind dozens of rows in a second), the panel started answering them
         *  empty, and those empties were cached as "no EPG" - in the cache the *guide*
         *  reads, so its rows went to "No programme info" too. */
        private fun bindNowPlaying(category: CategoryFilter) {
            nowJob?.cancel()
            nowJob = null
            val channelId = category.id
            if (!showNowPlaying || channelId.isNullOrBlank()) {
                nowLine.text = ""
                nowLine.visibility = View.GONE
                return
            }
            renderNow(EpgListCache.get(channelId))
            if (itemView.isFocused) requestNowPlaying(category, channelId)
        }

        /** Fetches this row's guide, once, for the row the user is actually on. */
        private fun requestNowPlaying(category: CategoryFilter, channelId: String) {
            if (EpgListCache.has(channelId)) {
                renderNow(EpgListCache.get(channelId))
                return
            }
            val fetch = fetchPrograms ?: return
            if (nowJob?.isActive == true) return
            nowJob = scope.launch {
                delay(EPG_LOAD_DEBOUNCE_MS)
                if (current !== category) return@launch
                if (!EpgListCache.markInFlight(channelId)) {
                    // The guide (or another row) is already fetching this channel - wait for
                    // that result rather than issuing a duplicate request. Bounded: a claim
                    // released without anything cached (a cancelled fetch) must not poll
                    // forever, so after the budget the row just falls back to its empty line.
                    var waits = 0
                    while (current === category && !EpgListCache.has(channelId) && waits < EPG_WAIT_MAX_POLLS) {
                        delay(EPG_POLL_DELAY_MS)
                        waits++
                    }
                    if (current === category) renderNow(EpgListCache.get(channelId))
                    return@launch
                }
                val programs = try {
                    fetch(channelId)
                } catch (e: Exception) {
                    EpgListCache.clearInFlight(channelId)
                    throw e
                }
                // Only a real result is cached. Caching an empty/failed fetch would mark the
                // channel "no EPG" for the rest of the session - for the guide as well,
                // since the cache is shared - and a panel that rate-limits one burst would
                // permanently blank rows that do have a schedule.
                if (programs.isNullOrEmpty()) EpgListCache.clearInFlight(channelId)
                else EpgListCache.put(channelId, programs)
                if (current === category) renderNow(programs)
            }
        }

        private fun renderNow(programs: List<XtreamClient.EpgProgram>?) {
            val nowSeconds = System.currentTimeMillis() / 1000
            // Same predicate the guide highlights with (now in [start, stop)), so the menu
            // and the guide never name different programmes for the same channel.
            val title = programs
                ?.firstOrNull { nowSeconds in it.startTimestamp until it.stopTimestamp }
                ?.title
            nowLine.text = title.orEmpty()
            nowLine.visibility = if (title.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CategoryFilter>() {
        override fun areItemsTheSame(old: CategoryFilter, new: CategoryFilter): Boolean = old.id == new.id
        override fun areContentsTheSame(old: CategoryFilter, new: CategoryFilter): Boolean = old == new
    }
}

/** Matches the guide's debounce: a row scrolled past inside this window never fetches. */
private const val EPG_LOAD_DEBOUNCE_MS = 250L

/** Poll interval while waiting on another fetch's in-flight claim. */
private const val EPG_POLL_DELAY_MS = 200L

/** Cap on those polls (~10s total): a claim released without a result must not spin forever. */
private const val EPG_WAIT_MAX_POLLS = 50
