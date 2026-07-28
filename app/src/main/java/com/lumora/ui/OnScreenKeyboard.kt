package com.lumora.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.lumora.R

/**
 * D-pad driven on-screen keyboard, used by search instead of the system IME.
 *
 * The platform IME on a Fire TV stick opens as a full-screen overlay that covers whatever
 * it's typing into, takes focus away from the app entirely, and can't be laid out beside
 * the results - so search could never show the query and its matches at the same time.
 * Drawing the keys ourselves keeps everything inside one focus tree: the keys are ordinary
 * focusable views, so the remote moves between them, and out of them into the results, with
 * no IME involved.
 *
 * Keys are laid out as rows of equally-weighted views, which is what makes D-pad navigation
 * work without any explicit nextFocus wiring: the default focus search resolves up/down
 * geometrically, and equal weights keep the columns aligned enough for that to feel right.
 */
class OnScreenKeyboard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** A character was typed. */
    var onKey: ((String) -> Unit)? = null
    /** Delete one character. */
    var onBackspace: (() -> Unit)? = null
    /** Clear the whole query. */
    var onClear: (() -> Unit)? = null

    private val letterRows = listOf(
        "1234567890",
        "QWERTYUIOP",
        "ASDFGHJKL",
        "ZXCVBNM"
    )

    init {
        orientation = VERTICAL
        // The keys scale to 1.06x on focus; without these the outermost columns and the top
        // and bottom rows get clipped by this view's own bounds on the side they grow into.
        clipChildren = false
        clipToPadding = false
        build()
    }

    private fun build() {
        for (row in letterRows) addView(keyRow(row.map { it.toString() }))
        // Space is worth a wide key of its own - it's the most-used key in a search query
        // that spans two words, and hunting for a narrow one costs several D-pad presses.
        addView(
            keyRow(
                labels = listOf("SPACE", "DEL", "CLEAR"),
                weights = listOf(2f, 1f, 1f)
            )
        )
    }

    private fun keyRow(labels: List<String>, weights: List<Float>? = null): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            clipChildren = false
            clipToPadding = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        labels.forEachIndexed { index, label ->
            row.addView(key(label, weights?.getOrNull(index) ?: 1f))
        }
        return row
    }

    private fun key(label: String, weight: Float): TextView {
        val gap = resources.getDimensionPixelSize(R.dimen.keyboard_key_gap)
        return TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            setTextColor(context.getColor(R.color.text_primary))
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                resources.getDimension(
                    if (label.length > 1) R.dimen.keyboard_action_text else R.dimen.keyboard_key_text
                )
            )
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_keyboard_key)
            stateListAnimator = android.animation.AnimatorInflater
                .loadStateListAnimator(context, R.animator.focus_scale_flat)
            minHeight = resources.getDimensionPixelSize(R.dimen.keyboard_key_height)
            // 0dp + weight, so a row always fills the keyboard's width exactly and the
            // columns line up between rows - which is what the D-pad navigates by.
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weight).apply {
                setMargins(gap, gap, gap, gap)
            }
            setOnClickListener { emit(label) }
        }
    }

    private fun emit(label: String) {
        when (label) {
            "SPACE" -> onKey?.invoke(" ")
            "DEL" -> onBackspace?.invoke()
            "CLEAR" -> onClear?.invoke()
            else -> onKey?.invoke(label)
        }
    }

    /** The top-left key - where focus should land when search opens, so typing can start
     *  without first navigating into the keyboard. */
    fun firstKey(): View? = (getChildAt(0) as? LinearLayout)?.getChildAt(0)
}
