package com.lumora.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumora.R
import com.lumora.model.CategoryFilter

class CategoryAdapter(
    private val onCategoryClick: (CategoryFilter) -> Unit,
    private val onCategoryLongClick: (CategoryFilter) -> Unit
) : ListAdapter<CategoryFilter, CategoryAdapter.ViewHolder>(DiffCallback()) {

    var selectedId: String? = null
        private set

    fun setSelected(id: String?) {
        val oldId = selectedId
        if (oldId == id) return
        selectedId = id
        // Only update the old and new items instead of a full redraw - avoids
        // flashing every visible row when the user taps a category.
        val oldIdx = currentList.indexOfFirst { it.id == oldId }
        if (oldIdx >= 0) notifyItemChanged(oldIdx)
        val newIdx = currentList.indexOfFirst { it.id == id }
        if (newIdx >= 0 && newIdx != oldIdx) notifyItemChanged(newIdx)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id == selectedId)
    }

    inner class ViewHolder(private val textView: TextView) : RecyclerView.ViewHolder(textView) {
        private var current: CategoryFilter? = null
        private val basePaddingStart = textView.paddingStart
        private val basePaddingTop = textView.paddingTop
        private val basePaddingBottom = textView.paddingBottom
        private val basePaddingEnd = textView.paddingEnd
        private val childIndentPx = (24 * textView.resources.displayMetrics.density).toInt()

        init {
            textView.setOnClickListener { current?.let(onCategoryClick) }
            textView.setOnLongClickListener {
                current?.let {
                    // "All" (id == null) can't be pinned/unpinned.
                    if (it.id != null) onCategoryLongClick(it)
                }
                true
            }
        }

        fun bind(category: CategoryFilter, selected: Boolean) {
            current = category
            val pinPrefix = if (category.pinned) "★ " else ""
            val chevron = if (category.isParent) (if (category.expanded) "▾ " else "▸ ") else ""
            // A negative count is a sentinel for non-filtering utility rows (e.g. the
            // classic-layout toggle) that have nothing meaningful to count.
            val countSuffix = if (category.count >= 0) " (${category.count})" else ""
            val name = category.name.let { if (it.length > 40) it.take(39) + "…" else it }
            textView.text = "$chevron$pinPrefix$name$countSuffix"
            textView.isSelected = selected
            textView.setTextColor(
                textView.context.getColor(if (selected) R.color.text_primary else R.color.text_secondary)
            )
            val startPadding = basePaddingStart + if (category.isChild) childIndentPx else 0
            textView.setPadding(startPadding, basePaddingTop, basePaddingEnd, basePaddingBottom)
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CategoryFilter>() {
        override fun areItemsTheSame(old: CategoryFilter, new: CategoryFilter): Boolean = old.id == new.id
        override fun areContentsTheSame(old: CategoryFilter, new: CategoryFilter): Boolean = old == new
    }
}
