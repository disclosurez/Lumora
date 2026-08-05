package com.lumora.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumora.R

/**
 * One row of the Catch Up list. The screen walks channel -> day -> programme and every
 * step is the same shape (a title, an optional second line, and a chevron when the row
 * drills further in), so one adapter serves all three rather than three near-identical ones.
 *
 * [key] is what makes a row identity stable across steps for DiffUtil - the title alone
 * isn't unique (two days can hold a programme of the same name).
 */
data class CatchupRow(
    val key: String,
    val title: String,
    val meta: String? = null,
    /** False on the last step: a programme plays rather than opening another list. */
    val drillsIn: Boolean = true
)

class CatchupAdapter(
    private val onRowClick: (CatchupRow) -> Unit
) : ListAdapter<CatchupRow, CatchupAdapter.ViewHolder>(DiffCallback()) {

    /** Where D-pad UP from the first row goes - the tab row, which is outside this
     *  RecyclerView and so unreachable through nextFocusUpId (see the repo's focus notes
     *  on RecyclerView.focusSearch scoping itself as root). */
    var topRowFocusUpTargetId: Int = View.NO_ID

    /** The column to the right (days from channels, programmes from days) and the one to
     *  the left. Both are sibling RecyclerViews, so the same focus-search scoping applies:
     *  resolve the view and requestFocus() directly rather than trusting nextFocusRightId.
     *  Read live at key-press time, since a column only exists once it has been opened. */
    var focusRightTargetId: Int = View.NO_ID
    var focusLeftTargetId: Int = View.NO_ID

    /** LEFT from the leftmost *visible* column: its neighbour exists but has been slid off
     *  screen by the sliding window, so the press has to step the window back rather than
     *  move focus to a GONE view. Return true if it was handled. */
    var onFocusLeftEdge: (() -> Boolean)? = null

    /** Key of the row this column is currently drilled into, highlighted so the path
     *  through the columns stays readable once focus has moved on to the next one. */
    var selectedKey: String? = null
        private set

    fun setSelected(key: String?) {
        val old = selectedKey
        if (old == key) return
        selectedKey = key
        listOf(old, key).forEach { changed ->
            val idx = currentList.indexOfFirst { it.key == changed }
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    /** Every step of the flow replaces the list wholesale (220 channels -> 3 days -> a
     *  day's programmes), so there is nothing to diff. submitList(null) takes
     *  AsyncListDiffer's remove-all fast path and the next call its insert-all one: both
     *  synchronous, which also means a scrollToPosition(0) *after* this call acts on the
     *  new list. Ordered the other way round - the async commit landing after the scroll -
     *  the layout stayed anchored past the end of the much shorter new list and the
     *  RecyclerView rendered nothing at all. */
    fun replaceAll(rows: List<CatchupRow>) {
        submitList(null)
        submitList(rows)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_catchup_row, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.catchupRowTitle)
        private val meta: TextView = itemView.findViewById(R.id.catchupRowMeta)
        private val chevron: ImageView = itemView.findViewById(R.id.catchupRowChevron)
        private var current: CatchupRow? = null

        init {
            itemView.setOnClickListener { current?.let(onRowClick) }
            itemView.setOnKeyListener { v, keyCode, event ->
                if (event.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val target = when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        if (bindingAdapterPosition == 0) topRowFocusUpTargetId else View.NO_ID
                    // RIGHT opens/enters the next column. On the row that is already the
                    // drilled-into one that column exists; on any other row it does not yet,
                    // so the press doubles as the click that opens it.
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val row = current
                        if (row != null && row.drillsIn && row.key != selectedKey) {
                            onRowClick(row)
                            return@setOnKeyListener true
                        }
                        focusRightTargetId
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (focusLeftTargetId == View.NO_ID && onFocusLeftEdge?.invoke() == true) {
                            return@setOnKeyListener true
                        }
                        focusLeftTargetId
                    }
                    else -> View.NO_ID
                }
                if (target == View.NO_ID) return@setOnKeyListener false
                v.rootView.findViewById<View>(target)?.let { it.requestFocus(); return@setOnKeyListener true }
                false
            }
        }

        fun bind(row: CatchupRow) {
            current = row
            title.text = row.title
            meta.text = row.meta.orEmpty()
            meta.visibility = if (row.meta.isNullOrBlank()) View.GONE else View.VISIBLE
            chevron.visibility = if (row.drillsIn) View.VISIBLE else View.GONE
            // Reassigned on every bind, never left stale from a prior one - a recycled
            // holder would otherwise keep the previous row's selected state.
            itemView.isSelected = row.key == selectedKey
            title.setTextColor(
                itemView.context.getColor(
                    if (row.key == selectedKey) R.color.text_primary else R.color.text_secondary
                )
            )
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CatchupRow>() {
        override fun areItemsTheSame(old: CatchupRow, new: CatchupRow): Boolean = old.key == new.key
        override fun areContentsTheSame(old: CatchupRow, new: CatchupRow): Boolean = old == new
    }
}
