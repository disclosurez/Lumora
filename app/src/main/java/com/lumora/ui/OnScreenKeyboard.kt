package com.lumora.ui

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
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
 *
 *  One deliberate exception to "let the framework figure it out":
 *  - the bottom row has nothing reliably below it (the results grid is a sibling, and
 *    crossing out of the keyboard's own tree via default focus search is geometry roulette),
 *    so DOWN from any bottom-row key is routed through [bottomRowDownTarget] when the host
 *    has set one.
 *  The letter rows need no wiring: equal weights align the columns, and the fourth row's
 *  seven wider keys still sit under the right-hand keys of the row above (N under K, M
 *  under L) - default focus search resolves those, which is how the D-pad reaches N and M
 *  at all. (A previous version routed DOWN from K/L straight to the results grid, wrongly
 *  assuming nothing sat below them; on a real remote that made M - and N - unreachable
 *  from above.)
 *
 * The keyboard itself is focusable: hosts point outside focus targets at it (the results
 * grid's top-row UP lands here), and the next D-pad press then enters the keys via normal
 * focus search.
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

    /**
     * Where DOWN from the bottom row should go (typically the results grid). If it's a
     * plain focusable view it's focused directly; if it's a RecyclerView, the first item
     * is scrolled in and focused (a focused RecyclerView is a dead landing pad, and the
     * host shouldn't have to make the grid itself focusable just for this).
     */
    var bottomRowDownTarget: View? = null

    /** Four letter rows, digits on top. Layer 0 is shared between letter and punctuation
     *  modes; rows 1-3 are the ones the ?123/ABC key swaps. */
    private val letterRows = listOf(
        "1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"
    ).map { row -> row.map { it.toString() } }

    /** Same row widths as the letter block (10/9/7) so a layer swap keeps every key in
     *  place - the D-pad geometry never changes, only the labels. */
    private val punctRows: List<List<String>> = listOf(
        listOf("'", "-", "&", ":", ",", "?", "!", "(", ")", "."),
        listOf("@", "#", "$", "%", "^", "*", "_", "=", "+"),
        listOf("\"", ";", "/", "\\", "[", "]", "|")
    )

    private val shiftLabel: String = context.getString(R.string.keyboard_shift)
    private val abcLabel: String = context.getString(R.string.keyboard_abc)

    /** The three rows that swap between letter and punctuation layers (children 1..3;
     *  child 0 is the digits row, which both layers share). */
    private val layerRows = mutableListOf<LinearLayout>()
    private var toggleKey: TextView? = null
    private var punctMode = false

    init {
        orientation = VERTICAL
        // The keys scale to 1.06x on focus; without these the outermost columns and the top
        // and bottom rows get clipped by this view's own bounds on the side they grow into.
        clipChildren = false
        clipToPadding = false
        // Focusable so a host can point an outside focus target at the whole keyboard
        // (e.g. the search grid's top-row UP) and let focus search enter the keys next.
        isFocusable = true
        build()
    }

    private fun build() {
        // Digits + the three swappable letter rows.
        for (row in letterRows) addView(keyRow(row))
        layerRows += getChildAt(1) as LinearLayout
        layerRows += getChildAt(2) as LinearLayout
        layerRows += getChildAt(3) as LinearLayout

        // Bottom row: the shift key toggles the punctuation layer, SPACE keeps its wide
        // key (the most-used key in a multi-word query), then DEL and CLEAR.
        val bottom = keyRow(
            labels = listOf(shiftLabel, "SPACE", "DEL", "CLEAR"),
            weights = listOf(1f, 2f, 1f, 1f)
        )
        addView(bottom)
        toggleKey = bottom.getChildAt(0) as TextView

        // DOWN from anywhere on the bottom row: into the results (via the host's target),
        // never stranded below the keyboard with nothing to land on.
        for (i in 0 until bottom.childCount) {
            bottom.getChildAt(i).setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN) {
                    return@setOnKeyListener routeDown()
                }
                false
            }
        }
    }

    /** DOWN from the bottom row / K / L: focus the host's target, or fall through to the
     *  default focus search if there isn't one worth using. */
    private fun routeDown(): Boolean {
        val target = bottomRowDownTarget ?: return false
        if (!target.isShown) return false
        if (target.requestFocus()) return true
        // Non-focusable container (a RecyclerView): scroll the first row in, then focus its
        // first item on the next frame - same retry pattern the episode list uses.
        if (target is RecyclerView && (target.adapter?.itemCount ?: 0) > 0) {
            target.scrollToPosition(0)
            target.post { target.layoutManager?.findViewByPosition(0)?.requestFocus() }
            return true
        }
        return false
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
            // Inter instead of fake-bold (setTypeface(null, BOLD) synthesizes a bold from the
            // system font; Inter semibold is the app's own face and matches every other label
            // on screen). Letter keys get semibold, the wider action keys medium so SPACE/DEL/
            // CLEAR don't out-weight the letters at their smaller size.
            typeface = ResourcesCompat.getFont(
                context,
                if (label.length > 1) R.font.inter_medium else R.font.inter_semibold
            )
            setBackgroundResource(R.drawable.bg_keyboard_key)
            stateListAnimator = android.animation.AnimatorInflater
                .loadStateListAnimator(context, R.animator.focus_scale_flat)
            minHeight = resources.getDimensionPixelSize(R.dimen.keyboard_key_height)
            // 0dp + weight, so a row always fills the keyboard's width exactly and the
            // columns line up between rows - which is what the D-pad navigates by.
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, weight).apply {
                setMargins(gap, gap, gap, gap)
            }
            // Read the LIVE label, not the build-time one: applyLayer() swaps the text in
            // place (punct mode), and emit() must see what the key currently shows, not
            // what it was inflated with - otherwise ?123 mode displays punctuation but
            // every press types the original letter.
            setOnClickListener { emit(text.toString()) }
        }
    }

    private fun emit(label: String) {
        when (label) {
            "SPACE" -> onKey?.invoke(" ")
            "DEL" -> onBackspace?.invoke()
            "CLEAR" -> onClear?.invoke()
            shiftLabel, abcLabel -> {
                // Swap the letter rows for punctuation (or back) in place - the key views
                // are reused, so whatever holds focus keeps it through the swap.
                punctMode = !punctMode
                applyLayer()
            }
            else -> onKey?.invoke(label)
        }
    }

    /** Re-labels the swappable rows for the current layer without rebuilding any views. */
    private fun applyLayer() {
        val labels = if (punctMode) punctRows else letterRows.drop(1)
        layerRows.forEachIndexed { rowIndex, row ->
            val rowLabels = labels[rowIndex]
            for (col in 0 until row.childCount) {
                (row.getChildAt(col) as? TextView)?.text = rowLabels.getOrNull(col) ?: ""
            }
        }
        toggleKey?.text = if (punctMode) abcLabel else shiftLabel
    }

    /** The G key - middle of the letter block, where focus should land when the keyboard
     *  opens so typing can start without first navigating into the keys. Not the top-left
     *  "1": a letter is the overwhelmingly likely first key, and G is the closest to all
     *  of them (average D-pad distance to any letter is a fraction of the trip from "1"). */
    fun firstKey(): View? = layerRows.getOrNull(1)?.getChildAt(4)
}
