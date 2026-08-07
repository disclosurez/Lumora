package com.lumora.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import com.lumora.R

/**
 * TextView that paints a solid outline behind the glyphs so light text stays readable on top of
 * arbitrary poster artwork. A blurred shadow layer washes out against bright/busy images; a hard
 * stroke does not.
 *
 * Draws twice: a STROKE pass in [outlineColor] at [outlineWidth], then the normal FILL pass. The
 * stroke is centered on the glyph edge, so half of it eats into the letter — the width is doubled
 * internally to keep the visible outline as thick as requested.
 */
class OutlineTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var outlineWidth = 0f
    private var outlineColor = Color.BLACK
    private var drawingOutline = false

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.OutlineTextView)
        outlineWidth = a.getDimension(R.styleable.OutlineTextView_outlineWidth, 0f)
        outlineColor = a.getColor(R.styleable.OutlineTextView_outlineColor, Color.BLACK)
        a.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        if (outlineWidth > 0f) {
            val textColors = textColors
            val shadowRadius = shadowRadius
            val shadowDx = shadowDx
            val shadowDy = shadowDy
            val shadowColor = shadowColor

            drawingOutline = true
            // The stroke pass must not also paint the shadow, or the blur doubles up and smears.
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            setTextColor(outlineColor)
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = outlineWidth * 2f
            super.onDraw(canvas)

            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
            setTextColor(textColors)
            setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
            drawingOutline = false
        }
        super.onDraw(canvas)
    }

    // setTextColor()/setShadowLayer() invalidate; swallowing that mid-draw avoids an invalidate loop.
    override fun invalidate() {
        if (drawingOutline) return
        super.invalidate()
    }
}
