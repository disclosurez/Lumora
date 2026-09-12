package com.lumora.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.widget.EditText

/** The caret for the on-screen-keyboard search fields.
 *
 *  Those fields are deliberately non-focusable - the platform IME has to stay suppressed, and
 *  a focused box with no IME is a dead end for a remote - but a TextView only draws its cursor
 *  while it holds focus, so a typed query otherwise appears with no insertion point at all.
 *  This view stands in: [attachBlinkingCursor] keeps it after the last character, and it
 *  blinks on the platform's usual 500ms half-period, stopping when its window goes away. */
class BlinkingCursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val blink = object : Runnable {
        override fun run() {
            alpha = if (alpha == 0f) 1f else 0f
            postDelayed(this, BLINK_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(blink, BLINK_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blink)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val BLINK_MS = 500L
    }
}

/** Keeps [cursor] sitting after the last character of [input] as the query is typed, replaced
 *  by a recent chip, or scrolled by a long query in the single-line field. */
internal fun attachBlinkingCursor(input: EditText, cursor: View) {
    fun place() {
        val layout = input.layout ?: return
        cursor.translationX =
            input.left + input.paddingLeft + layout.getPrimaryHorizontal(input.text.length) - input.scrollX
    }
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            cursor.post { place() }
        }
    })
    input.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
    input.post { place() }
}
