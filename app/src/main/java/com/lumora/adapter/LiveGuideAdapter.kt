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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MINUTE_WIDTH_DP = 2.6f
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

    fun attachHeader(header: HorizontalScrollView) {
        headerScrollView = header
    }

    private fun broadcastScroll(x: Int, source: HorizontalScrollView?) {
        if (sharedScrollX == x) return
        sharedScrollX = x
        headerScrollView?.takeIf { it !== source && it.scrollX != x }?.scrollTo(x, 0)
        for (sv in boundScrollViews) {
            if (sv !== source && sv.scrollX != x) sv.scrollTo(x, 0)
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

        init {
            channelInfo.setOnClickListener { current?.let(onChannelClick) }
            channelInfo.setOnLongClickListener { current?.let { onChannelLongPress?.invoke(it) }; true }
            channelInfo.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) current?.let { onChannelFocused?.invoke(it) } }
            scrollView.setOnScrollChangeListener { v, scrollX, _, _, _ -> broadcastScroll(scrollX, v as HorizontalScrollView) }
        }

        fun cancelPendingLoad() {
            loadJob?.cancel()
            loadJob = null
        }

        fun bind(channel: Channel) {
            current = channel
            numberText.text = channel.tvgChno?.takeIf { it.isNotBlank() } ?: ""
            nameText.text = channel.name.let { if (it.length > 30) it.take(29) + "…" else it }

            val initial = channel.name.firstOrNull()?.uppercase() ?: "?"
            initialText.text = initial
            val colorIndex = bindingAdapterPosition.coerceAtLeast(0) % avatarColors.size
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
            if (epgReady) renderPrograms(channel, EpgListCache.get(channel.id))

            // Debounce both the logo and EPG network fetches: a row that's scrolled
            // past within LOAD_DEBOUNCE_MS never issues a request at all.
            if (cachedLogo == null && !logoUrl.isNullOrBlank()) {
                loadJob = scope.launch {
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
                val epgJob = scope.launch {
                    delay(LOAD_DEBOUNCE_MS)
                    if (!EpgListCache.markInFlight(channel.id)) return@launch
                    val programs = runCatching { fetchPrograms(channel.id) }.getOrNull()
                    EpgListCache.put(channel.id, programs)
                    if (current === channel) renderPrograms(channel, programs)
                }
                // Only one delayed job is tracked for cancel-on-recycle purposes; logo
                // load is comparatively cheap and near-instant once cached anyway.
                loadJob = epgJob
            } else if (!epgReady) {
                renderPrograms(channel, null)
            }
        }

        private fun renderPrograms(channel: Channel, programs: List<XtreamClient.EpgProgram>?) {
            val blocks: List<RenderBlock> = if (programs.isNullOrEmpty()) {
                // No EPG data for this channel - a single filler block keeps the row
                // clickable and visually aligned with rows that do have data. Repeating
                // the channel's own name here just reads as duplicated text right next
                // to the name column, so use a neutral label instead.
                listOf(RenderBlock("No programme info", (FILLER_MINUTES * MINUTE_WIDTH_DP * density).toInt(), false, null))
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
                textSize = 11f
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
