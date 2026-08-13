package com.lumora.view

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView

/**
 * A RecyclerView that lays out every row, for use inside a ScrollView.
 *
 * A ScrollView measures its child with an UNSPECIFIED height spec, meaning "you may be as tall
 * as you like". RecyclerView reads that as the opposite. Its auto-measure path runs
 * `defaultOnMeasure` first, which resolves an UNSPECIFIED spec to a height of *zero*, then lays
 * out children into that zero-height viewport - LinearLayoutManager always places at least one
 * row - and finally sizes itself to the children it just laid out. The result is a list exactly
 * one row tall that holds a full adapter, with the rest reachable only by scrolling inside a
 * 156px window. That is what made a season of eight episodes render as one, with `itemCount=8`
 * and `listH=156` sitting side by side in the same log line.
 *
 * Substituting a very large AT_MOST spec gives the layout manager the room the ScrollView meant
 * to offer, so it lays out the whole season and the page scrolls it. Every row is bound up
 * front, which is the price of the fix and a fair one for an episode list; do not reuse this for
 * an unbounded list such as a catalogue grid, where recycling is the entire point.
 */
class FullHeightRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr) {

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Not Int.MAX_VALUE: MeasureSpec packs the mode into the top bits, so the size must
        // stay inside 30 bits or it wraps into a nonsense spec.
        val unbounded = MeasureSpec.makeMeasureSpec(Int.MAX_VALUE shr 2, MeasureSpec.AT_MOST)
        super.onMeasure(widthSpec, unbounded)
    }
}
