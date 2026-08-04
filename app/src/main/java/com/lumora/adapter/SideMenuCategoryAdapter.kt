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
        private var current: CategoryFilter? = null

        init {
            itemView.setOnClickListener { current?.let(onCategoryClick) }
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
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CategoryFilter>() {
        override fun areItemsTheSame(old: CategoryFilter, new: CategoryFilter): Boolean = old.id == new.id
        override fun areContentsTheSame(old: CategoryFilter, new: CategoryFilter): Boolean = old == new
    }
}
