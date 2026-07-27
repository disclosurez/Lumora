package com.lumora.adapter

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumora.R
import com.lumora.cache.EpgListCache
import com.lumora.model.Channel
import com.lumora.parser.XtreamClient
import com.lumora.util.PosterLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FILLER_MINUTES = 30
// Fast D-pad/fling scrolling flies a row past in well under this time; waiting this
// long before firing its network fetch means rows that never settle never cost a
// request at all - this is what keeps scrolling through thousands of channels smooth.
private const val LOAD_DEBOUNCE_MS = 200L

private data class RenderBlock(
    val title: String,
    val widthPx: Int,
    val isCurrent: Boolean,
    val program: XtreamClient.EpgProgram?
)

/** TiviMate-style TV guide: fixed channel column + horizontally-scrolling EPG timeline per row. */
class LiveGuideAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelFocused: ((Channel) -> Unit)? = null,
    private val onChannelLongPress: ((Channel) -> Unit)? = null,
    private val onProgramLongPress: ((Channel, XtreamClient.EpgProgram) -> Unit)? = null,
    private val isReminderSet: (String) -> Boolean = { false },
    private val fetchPrograms: suspend (String) -> List<XtreamClient.EpgProgram>?
) : ListAdapter<Channel, LiveGuideAdapter.RowViewHolder>(DiffCallback()) {

    companion object {
        /** Timeline scale (px per programme minute, in dp). Also used by MainActivity's
         *  buildGuideHeader() so the time ruler's 30-minute slots stay in step with the
         *  program blocks below them. */
        const val MINUTE_WIDTH_DP = 2.6f
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private val avatarColors = intArrayOf(
        R.color.channel_1, R.color.channel_2, R.color.channel_3,
        R.color.channel_4, R.color.channel_5, R.color.channel_6
    )

    // Shared horizontal scroll offset across every bound row + the time header, so
    // scrolling one (by D-pad focus or drag) keeps every other row in sync.
    private var sharedScrollX: Int = 0
    private val boundScrollViews = mutableSetOf<HorizontalScrollView>()
    private var headerScrollView: HorizontalScrollView? = null
    private var broadcasting = false

    fun attachHeader(header: HorizontalScrollView) {
        headerScrollView = header
        // The header is itself touch-draggable; without broadcasting its scrolls back
        // out, dragging it desyncs the time ruler from every program row below it.
        header.setOnScrollChangeListener { v, scrollX, _, _, _ -> broadcastScroll(scrollX, v as HorizontalScrollView) }
        if (header.scrollX != sharedScrollX) header.scrollTo(sharedScrollX, 0)
    }

    private fun broadcastScroll(x: Int, source: HorizontalScrollView?) {
        if (sharedScrollX == x) return
        // Re-entrancy guard: rows with less programme content than sharedScrollX clamp
        // their scrollTo below, which fires their own scroll listener mid-loop and would
        // re-broadcast the clamped offset - yanking the row the user is actually
        // scrolling (and every other row) back with it, as a visible jitter fight.
        if (broadcasting) return
        broadcasting = true
        try {
            sharedScrollX = x
            headerScrollView?.takeIf { it !== source && it.scrollX != x }?.scrollTo(x, 0)
            for (sv in boundScrollViews) {
                if (sv !== source && sv.scrollX != x) sv.scrollTo(x, 0)
            }
        } finally {
            broadcasting = false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_guide_channel_row, parent, false)
        return RowViewHolder(view)
    }

    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewAttachedToWindow(holder: RowViewHolder) {
        super.onViewAttachedToWindow(holder)
        boundScrollViews.add(holder.scrollView)
        if (holder.scrollView.scrollX != sharedScrollX) holder.scrollView.scrollTo(sharedScrollX, 0)
    }

    override fun onViewDetachedFromWindow(holder: RowViewHolder) {
        super.onViewDetachedFromWindow(holder)
        boundScrollViews.remove(holder.scrollView)
        holder.cancelPendingLoad()
    }

    inner class RowViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val channelInfo: LinearLayout = itemView.findViewById(R.id.guideChannelInfo)
        private val numberText: TextView = itemView.findViewById(R.id.guideChannelNumber)
        private val initialText: TextView = itemView.findViewById(R.id.guideChannelInitial)
        private val logoImage: ImageView = itemView.findViewById(R.id.guideChannelLogo)
        private val nameText: TextView = itemView.findViewById(R.id.guideChannelName)
        val scrollView: HorizontalScrollView = itemView.findViewById(R.id.guideProgramScroll)
        private val programRow: LinearLayout = itemView.findViewById(R.id.guideProgramRow)
        private val density = itemView.resources.displayMetrics.density

        private var current: Channel? = null
        private var loadJob: Job? = null
        private var logoJob: Job? = null

        init {
            channelInfo.setOnClickListener { current?.let(onChannelClick) }
            channelInfo.setOnLongClickListener { current?.let { onChannelLongPress?.invoke(it) }; true }
            channelInfo.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) current?.let { onChannelFocused?.invoke(it) } }
            scrollView.setOnScrollChangeListener { v, scrollX, _, _, _ -> broadcastScroll(scrollX, v as HorizontalScrollView) }
        }

        fun cancelPendingLoad() {
            loadJob?.cancel()
            loadJob = null
            logoJob?.cancel()
            logoJob = null
        }

        /** Focuses this row's channel column - used to land D-pad focus (and thus the
         *  preview pane) on the first channel as soon as a category is opened, instead of
         *  leaving the guide sitting there unfocused until the user presses something. */
        fun requestChannelFocus() {
            channelInfo.requestFocus()
        }

        /** Reserves [px] of end margin so this row's content clears the floating preview
         *  pane - 0 once the row is no longer behind it. Driven by MainActivity's
         *  updateGuideRowWrap(), which knows the preview's on-screen position. */
        fun setReservedEnd(px: Int) {
            val lp = itemView.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (lp.marginEnd != px) {
                lp.marginEnd = px
                itemView.layoutParams = lp
            }
        }

        fun bind(channel: Channel) {
            current = channel
            // guideChannelInfo's focus_scale stateListAnimator (scaleX/scaleY/translationZ)
            // can be interrupted mid-transition by fast RecyclerView recycling (this view
            // getting rebound to a different channel before its unfocus animation finishes),
            // leaving a leftover transform on the (reused) View object that bind() would
            // otherwise never touch - the channel name then visually renders shifted/scaled
            // over the program row next to it, even though layout bounds are correct. Forcing
            // a clean baseline on every bind is the standard defense for this.
            channelInfo.scaleX = 1f
            channelInfo.scaleY = 1f
            channelInfo.translationX = 0f
            channelInfo.translationY = 0f
            channelInfo.translationZ = 0f
            numberText.text = channel.tvgChno?.takeIf { it.isNotBlank() } ?: ""
            nameText.text = channel.name.let { if (it.length > 50) it.take(49) + "…" else it }

            val initial = channel.name.firstOrNull()?.uppercase() ?: "?"
            initialText.text = initial
            // Keyed on the name, not the adapter position: position-keyed colors made the
            // same channel's avatar change color every time a filter shifted the list.
            val colorIndex = (channel.name.hashCode() and 0x7fffffff) % avatarColors.size
            val color = ContextCompat.getColor(itemView.context, avatarColors[colorIndex])
            initialText.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

            logoImage.setImageDrawable(null)
            val logoUrl = channel.logoUrl
            val cachedLogo = logoUrl?.takeIf { it.isNotBlank() }?.let { PosterLoader.getCached(it) }
            if (cachedLogo != null) {
                logoImage.setImageBitmap(cachedLogo)
                logoImage.visibility = View.VISIBLE
                initialText.visibility = View.GONE
            } else {
                logoImage.visibility = View.GONE
                initialText.visibility = View.VISIBLE
            }

            scrollView.scrollTo(sharedScrollX, 0)
            cancelPendingLoad()

            val epgReady = channel.id.isNotBlank() && EpgListCache.has(channel.id)
            if (epgReady) {
                renderPrograms(channel, EpgListCache.get(channel.id))
            } else {
                // Render the placeholder immediately: this View is recycled and still holds
                // the previous channel's program blocks - leaving them up through the
                // debounce+fetch window pairs the wrong schedule with the new channel
                // while scrolling.
                renderPrograms(channel, null, pending = channel.id.isNotBlank())
            }

            // Debounce both the logo and EPG network fetches: a row that's scrolled
            // past within LOAD_DEBOUNCE_MS never issues a request at all.
            if (cachedLogo == null && !logoUrl.isNullOrBlank()) {
                logoJob = scope.launch {
                    delay(LOAD_DEBOUNCE_MS)
                    val bitmap = PosterLoader.fetch(logoUrl)
                    if (bitmap != null && current === channel) {
                        logoImage.setImageBitmap(bitmap)
                        logoImage.visibility = View.VISIBLE
                        initialText.visibility = View.GONE
                    }
                }
            }
            if (!epgReady && channel.id.isNotBlank()) {
                loadJob = scope.launch {
                    delay(LOAD_DEBOUNCE_MS)
                    while (current === channel) {
                        if (EpgListCache.has(channel.id)) {
                            renderPrograms(channel, EpgListCache.get(channel.id))
                            return@launch
                        }
                        if (EpgListCache.markInFlight(channel.id)) {
                            val programs = try {
                                fetchPrograms(channel.id)
                            } catch (e: CancellationException) {
                                // Cancelled mid-fetch (row recycled): release the claim
                                // without caching. Swallowing this (the old runCatching did)
                                // cached null as "no EPG", freezing the channel at
                                // "No programme info" for the rest of the session.
                                EpgListCache.clearInFlight(channel.id)
                                throw e
                            } catch (_: Exception) {
                                null
                            }
                            EpgListCache.put(channel.id, programs)
                            if (current === channel) renderPrograms(channel, programs)
                            return@launch
                        }
                        // Another row's fetch for this same id is in flight - poll until it
                        // lands (or claim it ourselves if that fetch gets cancelled) instead
                        // of bailing, which left this row stuck on the placeholder.
                        delay(200)
                    }
                }
            }
        }

        private fun renderPrograms(channel: Channel, programs: List<XtreamClient.EpgProgram>?, pending: Boolean = false) {
            val blocks: List<RenderBlock> = if (programs.isNullOrEmpty()) {
                // No EPG data for this channel - a single filler block keeps the row
                // clickable and visually aligned with rows that do have data. Repeating
                // the channel's own name here just reads as duplicated text right next
                // to the name column, so use a neutral label instead. While a fetch is
                // still pending the block stays blank - flashing "No programme info" for
                // 200ms before data lands reads as the guide glitching.
                listOf(RenderBlock(if (pending) "" else "No programme info", (FILLER_MINUTES * MINUTE_WIDTH_DP * density).toInt(), false, null))
            } else {
                val nowSeconds = System.currentTimeMillis() / 1000
                programs.map { program ->
                    val isCurrent = nowSeconds in program.startTimestamp until program.stopTimestamp
                    // The currently-airing block is truncated to its remaining time so every
                    // row's timeline starts flush at "now", keeping columns aligned without
                    // needing a separate absolute-position/overlay system.
                    val effectiveStart = if (isCurrent) nowSeconds else program.startTimestamp
                    val minutes = ((program.stopTimestamp - effectiveStart) / 60).coerceAtLeast(1)
                    RenderBlock(program.title, (minutes * MINUTE_WIDTH_DP * density).toInt(), isCurrent, program)
                }
            }

            // Reuse existing block Views where possible instead of tearing every child
            // down and rebuilding on every rebind - this was the main source of GC
            // churn/jank scrolling through a large guide.
            for (i in blocks.indices) {
                val (title, widthPx, isCurrent, program) = blocks[i]
                val block = programRow.getChildAt(i) as? TextView ?: makeBlock().also { programRow.addView(it) }
                // Same leftover-transform defense as channelInfo above - these blocks are
                // reused across binds too (see the comment below), so a stuck transform from
                // a previous focus state would otherwise persist onto whatever program now
                // occupies this recycled block.
                block.scaleX = 1f
                block.scaleY = 1f
                block.translationX = 0f
                block.translationY = 0f
                block.translationZ = 0f
                block.text = title
                val nowSeconds = System.currentTimeMillis() / 1000
                val reminderSet = program != null && program.startTimestamp > nowSeconds && isReminderSet("${channel.id}:${program.startTimestamp}")
                block.background = ContextCompat.getDrawable(
                    itemView.context,
                    when {
                        reminderSet -> R.drawable.bg_guide_block_reminder
                        isCurrent -> R.drawable.bg_guide_block_current
                        else -> R.drawable.bg_guide_block
                    }
                )
                val lp = block.layoutParams as LinearLayout.LayoutParams
                lp.width = widthPx
                block.layoutParams = lp
                block.setOnClickListener { onChannelClick(channel) }
                // D-pad RIGHT off the channel column moves focus into these program blocks -
                // without this, the preview pane only ever updated while focus sat on the
                // channel name itself and otherwise went stale/blank while browsing the guide.
                block.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onChannelFocused?.invoke(channel) }
                block.setOnLongClickListener {
                    if (program != null && program.startTimestamp > nowSeconds) {
                        onProgramLongPress?.invoke(channel, program)
                    }
                    true
                }
            }
            while (programRow.childCount > blocks.size) {
                programRow.removeViewAt(programRow.childCount - 1)
            }
        }

        private fun makeBlock(): TextView {
            val context = itemView.context
            return TextView(context).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                // From resources rather than a literal so the TV override in
                // values-television/dimens.xml applies here too - these blocks are built in
                // code, so they'd otherwise stay phone-sized while the rest of the guide row
                // (inflated from XML) grew around them.
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    context.resources.getDimension(R.dimen.guide_program_text)
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                isFocusable = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    marginEnd = (2 * density).toInt()
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem.url == newItem.url
        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem == newItem
    }
}
