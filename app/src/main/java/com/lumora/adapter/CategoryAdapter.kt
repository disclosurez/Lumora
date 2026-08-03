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
import com.lumora.model.CategoryFilter

/** Id prefix MainActivity gives the Live TV dynamic buckets (Sports/News/Music/Cinema). */
const val DYNAMIC_BUCKET_ID_PREFIX = "dynbucket:"

/** Sidebar/shelf rows whose pin star is hidden because pinning them is inert:
 *  Newest (prepended above the pinned block), Jellyfin (always first by construction),
 *  and the classic-layout toggle. Mirrors MainActivity's NEWEST_CATEGORY_ID /
 *  JELLYFIN_CATEGORY_ID / CLASSIC_LAYOUT_TOGGLE_ID - keep in sync if those change. */
val NON_PINNABLE_CATEGORY_IDS = setOf("__newest__", "__jellyfin__", "__classic_layout_toggle__")

class CategoryAdapter(
    private val onCategoryClick: (CategoryFilter) -> Unit,
    private val onCategoryStarClick: (CategoryFilter) -> Unit,
    private val onCategoryLongClick: (CategoryFilter) -> Unit
) : ListAdapter<CategoryFilter, CategoryAdapter.ViewHolder>(DiffCallback()) {

    var selectedId: String? = null
        private set

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
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id == selectedId)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val label: TextView = itemView.findViewById(R.id.categoryLabel)
        private val star: TextView = itemView.findViewById(R.id.categoryStar)
        private val density = itemView.resources.displayMetrics.density
        // Child-step under a parent/dynamic bucket. 24dp read as too deep once the star
        // anchored the row's left edge - a subtler half-step keeps the hierarchy visible
        // without the text drifting halfway across the rail.
        private val childIndentPx = (12 * density).toInt()
        private val chevronDown = ContextCompat.getDrawable(itemView.context, R.drawable.ic_chevron_down)
        private val chevronRight = ContextCompat.getDrawable(itemView.context, R.drawable.ic_chevron_right)
        private var current: CategoryFilter? = null

        init {
            // The row container carries the select/focus chrome; a tap anywhere on it (other
            // than the star, which is clickable and consumes its own press) opens the category.
            itemView.setOnClickListener { current?.let(onCategoryClick) }
            itemView.setOnLongClickListener {
                current?.let {
                    if (it.id != null) onCategoryLongClick(it)
                }
                true
            }
            star.setOnClickListener { current?.let(onCategoryStarClick) }
            // The star surfaces only while the row or the star itself is focused, so any
            // focus change on either re-evaluates the shared condition - flipping row to
            // star keeps it visible, leaving both hides it again.
            val starRevealListener = View.OnFocusChangeListener { _, _ -> updateStarVisibility() }
            itemView.setOnFocusChangeListener(starRevealListener)
            star.setOnFocusChangeListener(starRevealListener)
            // LEFT from the row must land on this row's star (and RIGHT back), handled
            // explicitly - nextFocus ids can silently fail across the RecyclerView's
            // focus-search scope (see the repo's D-pad pattern).
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    // A GONE star can't take focus, so surface it (if pinnable) first; the
                    // focus listeners keep it consistent from here.
                    updateStarVisibility()
                    if (star.visibility == View.VISIBLE) star.requestFocus()
                    true
                } else false
            }
            star.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    itemView.requestFocus()
                    true
                } else false
            }
        }

        fun bind(category: CategoryFilter, selected: Boolean) {
            current = category
            val countSuffix = if (category.count >= 0) " (${category.count})" else ""
            val rawName = if (category.isDynamic) category.name.uppercase() else category.name
            val name = rawName.let { if (it.length > 80) it.take(79) + "…" else it }

            val drawable = when {
                category.isParent && category.expanded -> chevronDown
                category.isParent && !category.expanded -> chevronRight
                else -> null
            }
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
            label.compoundDrawablePadding = (6 * density).toInt()

            val builder = SpannableStringBuilder(name)
            if (countSuffix.isNotEmpty()) {
                val start = builder.length
                builder.append(countSuffix)
                builder.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(label.context, R.color.text_tertiary)),
                    start,
                    builder.length,
                    0
                )
            }
            label.text = builder

            itemView.isSelected = selected
            label.setTextColor(
                label.context.getColor(if (selected) R.color.text_primary else R.color.text_secondary)
            )
            // Child rows indent under their parent; the container's own horizontal padding
            // is the base, so only the extra child step lands here.
            label.setPadding(if (category.isChild) childIndentPx else 0, 0, 0, 0)

            // Star: white when pinned, grey when not, revealed only while the row or the
            // star itself is focused - D-pad users see what they can pin as they land on
            // it. Unpinnable rows ("All", toggles) stay hidden forever: a dead star reads
            // as a broken one. updateStarVisibility() reads live focus state, so a recycled
            // row lands in the correct state - a focused row re-shows the star, an unfocused
            // one never keeps a stale star from a prior bind.
            star.text = if (category.pinned) "★" else "☆"
            star.setTextColor(
                star.context.getColor(if (category.pinned) R.color.text_primary else R.color.text_secondary)
            )
            updateStarVisibility()
        }

        /** Reveals the star only while the row or the star itself has focus. Rows that
         *  can never be pinned never reveal it - the "All" row (id == null) and the
         *  three inert rows (Newest / Jellyfin / classic-layout toggle, see
         *  NON_PINNABLE_CATEGORY_IDS) stay star-free regardless of focus. Called from
         *  both focus-change listeners, the DPAD_LEFT handler (to surface the star
         *  before requestFocus), and the end of every bind (so recycled rows land in
         *  the live-focus state). */
        private fun updateStarVisibility() {
            if (current?.id == null || current?.id in NON_PINNABLE_CATEGORY_IDS) {
                star.visibility = View.GONE
                return
            }
            val show = itemView.hasFocus() || star.hasFocus()
            star.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CategoryFilter>() {
        override fun areItemsTheSame(old: CategoryFilter, new: CategoryFilter): Boolean = old.id == new.id
        override fun areContentsTheSame(old: CategoryFilter, new: CategoryFilter): Boolean = old == new
    }
}
