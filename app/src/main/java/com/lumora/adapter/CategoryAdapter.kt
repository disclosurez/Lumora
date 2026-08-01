package com.lumora.adapter

import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
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
        private val chevronDown = ContextCompat.getDrawable(textView.context, R.drawable.ic_chevron_down)
        private val chevronRight = ContextCompat.getDrawable(textView.context, R.drawable.ic_chevron_right)

        init {
            textView.setOnClickListener { current?.let(onCategoryClick) }
            textView.setOnLongClickListener {
                current?.let {
                    if (it.id != null) onCategoryLongClick(it)
                }
                true
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
            textView.setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
            textView.compoundDrawablePadding = (6 * textView.resources.displayMetrics.density).toInt()

            val builder = SpannableStringBuilder(name)
            if (countSuffix.isNotEmpty()) {
                val start = builder.length
                builder.append(countSuffix)
                builder.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(textView.context, R.color.text_tertiary)),
                    start,
                    builder.length,
                    0
                )
            }
            textView.text = builder

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
