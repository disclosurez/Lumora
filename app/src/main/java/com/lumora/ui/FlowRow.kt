package com.lumora.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * A horizontal row that can wrap onto further lines instead of running off the side.
 *
 * Exists for the top chrome row (`tabBarRow`), which holds nine items - six tabs, Search,
 * Settings, Refresh. On a TV that is one comfortable line; on a ~360dp portrait phone it is
 * roughly 800dp of content inside a ~260dp window, so every tab past the second could only be
 * reached by dragging the scroller sideways through three screens. With [wrap] on, the items
 * flow onto a second line and all of them are on screen at once, at full label width - no
 * truncation, no sideways scrolling.
 *
 * With [wrap] off (TV, landscape) it lays out exactly like the horizontal LinearLayout it
 * replaced: one line, natural widths, overflowing its parent so the enclosing
 * HorizontalScrollView scrolls as before.
 *
 * Children are measured at their natural width regardless of the incoming spec's mode, which
 * is what makes this usable inside a HorizontalScrollView: that parent measures its child with
 * an UNSPECIFIED width spec whose *size* is still the viewport width, so the size is read here
 * as the wrap budget rather than the mode being trusted.
 */
class FlowRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    /** Wrap onto further lines when a child doesn't fit; false = single line, natural width. */
    var wrap: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    /** Vertical gap between wrapped lines, in px. Ignored when [wrap] is false. */
    var lineSpacing: Int = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    // Resolved in onMeasure and replayed in onLayout - laying out a wrapped row twice (once to
    // measure, once to place) would otherwise have to repeat the whole line-breaking pass.
    private var childLefts = IntArray(0)
    private var childTops = IntArray(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (childLefts.size < childCount) {
            childLefts = IntArray(childCount)
            childTops = IntArray(childCount)
        }

        val budget = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        // No budget to respect when not wrapping: one line, however wide it comes out.
        val lineLimit = if (wrap) paddingLeft + budget.coerceAtLeast(0) else Int.MAX_VALUE

        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        var widest = paddingLeft

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams

            child.measure(
                MeasureSpec.makeMeasureSpec(budget.coerceAtLeast(0), MeasureSpec.UNSPECIFIED),
                getChildMeasureSpec(
                    heightMeasureSpec,
                    paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin,
                    lp.height
                )
            )

            val outerWidth = lp.leftMargin + child.measuredWidth + lp.rightMargin
            // Never break before the first item on a line: a child wider than the whole budget
            // gets its own line and overhangs rather than being pushed onto an empty one.
            if (x > paddingLeft && x + outerWidth > lineLimit) {
                x = paddingLeft
                y += lineHeight + lineSpacing
                lineHeight = 0
            }

            childLefts[i] = x + lp.leftMargin
            childTops[i] = y + lp.topMargin
            x += outerWidth
            lineHeight = maxOf(lineHeight, lp.topMargin + child.measuredHeight + lp.bottomMargin)
            if (x > widest) widest = x
        }

        val contentWidth = widest + paddingRight
        val contentHeight = y + lineHeight + paddingBottom
        setMeasuredDimension(
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
                MeasureSpec.getSize(widthMeasureSpec)
            } else {
                contentWidth
            },
            resolveSize(contentHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.layout(
                childLefts[i],
                childTops[i],
                childLefts[i] + child.measuredWidth,
                childTops[i] + child.measuredHeight
            )
        }
    }

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams
}
