package com.lumora.player

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Sizes/positions its single video child (SurfaceView) inside its own always-full-size
 * bounds according to the video's real aspect ratio, instead of Media3's default of just
 * stretching the decoded frame to fill whatever surface bounds it's given. Hand-rolled
 * instead of pulling in media3-ui (PlayerView, subtitle view, etc.) for this one widget -
 * keeps this app's footprint minimal, which matters on the underpowered Fire TV Stick
 * this app targets.
 */
class VideoAspectFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class Mode { FIT, ZOOM, FILL }

    var resizeMode: Mode = Mode.FIT
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** width / height of the video's natural frame. 0 or less = unknown, don't constrain yet. */
    var videoAspectRatio: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        val child = getChildAt(0) ?: return
        val viewWidth = measuredWidth
        val viewHeight = measuredHeight
        if (viewWidth == 0 || viewHeight == 0 || videoAspectRatio <= 0f || resizeMode == Mode.FILL) {
            child.measure(
                MeasureSpec.makeMeasureSpec(viewWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(viewHeight, MeasureSpec.EXACTLY)
            )
            return
        }

        val containerAspect = viewWidth.toFloat() / viewHeight
        // FIT picks whichever constraint keeps the video fully inside the bounds
        // (letterbox/pillarbox bars). ZOOM picks the opposite constraint so the video
        // overflows and covers the bounds completely (cropped edges) instead.
        val heightConstrained = if (resizeMode == Mode.ZOOM) containerAspect <= videoAspectRatio else containerAspect > videoAspectRatio
        val childWidth: Int
        val childHeight: Int
        if (heightConstrained) {
            childHeight = viewHeight
            childWidth = (viewHeight * videoAspectRatio).roundToInt()
        } else {
            childWidth = viewWidth
            childHeight = (viewWidth / videoAspectRatio).roundToInt()
        }
        child.measure(
            MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        val viewWidth = r - l
        val viewHeight = b - t
        val childWidth = child.measuredWidth
        val childHeight = child.measuredHeight
        val left = (viewWidth - childWidth) / 2
        val top = (viewHeight - childHeight) / 2
        child.layout(left, top, left + childWidth, top + childHeight)
    }
}
