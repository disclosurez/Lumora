package com.lumora.adapter

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lumora.R
import com.lumora.cache.PlaybackPositionStore
import com.lumora.model.Channel
import com.lumora.util.PosterLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class EpisodeAdapter(
    private val onEpisodeClick: (Channel) -> Unit,
    private val showDownloadButton: Boolean = false,
    private val onDownloadClick: ((Channel) -> Unit)? = null,
    private val isDownloaded: ((Channel) -> Boolean)? = null,
    // Some providers bake the whole "Series Name - S01E21 - " straight into the raw
    // per-episode title (on top of the "S01E21 · " this app's own parsers already
    // prepend), so after stripping our own prefix the row still reads e.g. "Two and a
    // Half Men (2003) - S01E21 - No Sniffing, No Wowing" instead of just the title. Only
    // safe to strip when it's an exact match against *this* series' own name - trimming
    // any arbitrary leading "Word - S01E21 - " would also eat titles that legitimately
    // start that way.
    private val seriesName: String? = null,
    // Watched-state check toggle: fired after this row's checkmark flips (save/clear
    // already done, row already repainted) so the host can refresh dependent UI - the
    // season chips' all-watched state and the detail screen's Play button target.
    // Boolean = the new watched state.
    private val onWatchedToggle: ((Channel, Boolean) -> Unit)? = null
) : ListAdapter<Channel, EpisodeAdapter.ViewHolder>(DiffCallback()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val seriesPrefixRegex = seriesName?.takeIf { it.isNotBlank() }?.let {
        Regex("^" + Regex.escape(it) + """\s*-\s*S\d+E\d+\s*-\s*""", RegexOption.IGNORE_CASE)
    }

    /** Cancel in-flight poster fetches when the adapter is detached or no longer needed.
     *  Children only, never the scope's own Job: these adapters are long-lived and get
     *  re-attached (Films/Series swap between shelf and grid on the same RecyclerView, and
     *  shelf rows are recycled), and a cancelled scope Job stays cancelled forever - every
     *  later launch{} silently no-ops, so posters simply never loaded again after the first
     *  detach and only already-cached ones showed. */
    fun cancelPendingWork() {
        scope.coroutineContext.cancelChildren()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        cancelPendingWork()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.episodeTitle)
        private val dateText: TextView = itemView.findViewById(R.id.episodeDate)
        private val plotText: TextView = itemView.findViewById(R.id.episodePlot)
        private val resumeLabel: TextView = itemView.findViewById(R.id.episodeResumeLabel)
        private val numberBadge: TextView = itemView.findViewById(R.id.episodeNumberBadge)
        private val watchedBadge: TextView = itemView.findViewById(R.id.episodeWatchedBadge)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.episodeProgress)
        private val thumbImage: ImageView = itemView.findViewById(R.id.episodeThumb)
        private val downloadButton: ImageButton = itemView.findViewById(R.id.episodeDownloadButton)
        private val checkButton: TextView = itemView.findViewById(R.id.episodeCheckButton)
        private var current: Channel? = null

        init {
            itemView.setOnClickListener { current?.let(onEpisodeClick) }
            downloadButton.setOnClickListener { current?.let { onDownloadClick?.invoke(it) } }

            // The checkmark IS the control: OK toggles watched/unwatched directly - no
            // long-press, no dialog. The store updates synchronously in memory, so the
            // notifyItemChanged below repaints this row with the new state immediately.
            checkButton.setOnClickListener {
                val episode = current ?: return@setOnClickListener
                val key = episode.id.ifBlank { episode.url }
                if (key.isBlank()) return@setOnClickListener
                val wasWatched = PlaybackPositionStore.get(itemView.context, key)?.isNearComplete == true
                if (wasWatched) {
                    PlaybackPositionStore.clear(itemView.context, key)
                } else {
                    // Duration unknown for an unwatched-from-scratch episode; 1ms of 1ms
                    // clears the 0.95 completion threshold, i.e. counts as watched.
                    PlaybackPositionStore.save(itemView.context, key, 1L, 1L, episode)
                }
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
                onWatchedToggle?.invoke(episode, !wasWatched)
            }

            // LEFT/RIGHT inside the row: default focus search won't cross to a focusable
            // child sitting at the row's right edge (FocusFinder's isCandidate requires the
            // candidate to extend past the source's edge), so wire the row <-> check pair
            // explicitly - same reason the category sidebar rows carry nextFocusLeft/Right.
            // On phones the download button sits between them, so the chain goes through it
            // when visible; on TV it's gone and row <-> check are direct neighbours.
            itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    val target = if (downloadButton.visibility == View.VISIBLE) downloadButton else checkButton
                    target.requestFocus()
                    true
                } else {
                    false
                }
            }
            downloadButton.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    itemView.requestFocus()
                    true
                } else {
                    false
                }
            }
            checkButton.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    val target = if (downloadButton.visibility == View.VISIBLE) downloadButton else itemView
                    target.requestFocus()
                    true
                } else {
                    false
                }
            }
        }

        fun bind(episode: Channel) {
            current = episode
            // A row recycled mid-focus-animation carries the previous item's scale and
            // elevation with it, so switching season left visibly enlarged/raised leftover
            // rows scattered through the new list. The focus_scale stateListAnimator only
            // drives these on an actual focus change, so nothing restores them on rebind -
            // reset to rest here, on every bind. A *focused* row rebinding in place (the
            // watched-toggle repaint keeps the same ViewHolder and focus) must keep its
            // pop instead - it was never recycled, and snapping it flat while it still
            // holds focus reads as a glitch. Same guard on the check button below.
            if (!itemView.isFocused) {
                itemView.animate().cancel()
                itemView.scaleX = 1f
                itemView.scaleY = 1f
                itemView.translationZ = 0f
            }

            // Episode number as its own token leading the title line rather than buried
            // inside a long "S01E04 · Actual Episode Title" string - that string still
            // exists in `name` (other UI - Up Next, search, watch history - all key off it,
            // so it can't just be reformatted here), but reading a number out of running
            // text isn't how episode lists read anywhere else.
            if (episode.episodeNum != null) {
                numberBadge.text = episode.episodeNum.toString()
                numberBadge.visibility = View.VISIBLE
            } else {
                numberBadge.visibility = View.GONE
            }
            var title = stripEpisodePrefix(episode.name)
            seriesPrefixRegex?.let { title = title.replaceFirst(it, "") }
            titleText.text = title

            val aired = formatAirDate(episode.releaseDate)
            dateText.text = aired.orEmpty()
            dateText.visibility = if (aired != null) View.VISIBLE else View.GONE

            val plot = episode.description?.takeIf { it.isNotBlank() }
            if (plot != null) {
                plotText.text = plot
                plotText.visibility = View.VISIBLE
            } else {
                plotText.visibility = View.GONE
            }

            val key = episode.id.ifBlank { episode.url }
            val saved = if (key.isNotBlank()) PlaybackPositionStore.get(itemView.context, key) else null
            val isWatched = saved?.isNearComplete == true
            when {
                isWatched -> {
                    watchedBadge.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    resumeLabel.visibility = View.GONE
                }
                saved != null && saved.positionMs > 0 && saved.durationMs > 0 -> {
                    watchedBadge.visibility = View.GONE
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = (saved.positionMs * 1000 / saved.durationMs).toInt().coerceIn(0, 1000)
                    val remainingMin = TimeUnit.MILLISECONDS.toMinutes(saved.durationMs - saved.positionMs).coerceAtLeast(1)
                    resumeLabel.text = itemView.context.getString(R.string.list_resume_remaining, remainingMin)
                    resumeLabel.visibility = View.VISIBLE
                }
                else -> {
                    watchedBadge.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    resumeLabel.visibility = View.GONE
                }
            }
            // A tiny corner badge alone doesn't read at TV viewing distance when scanning
            // a long episode list for "what's left to watch" - dim the thumbnail and drop
            // the title to secondary emphasis so watched rows visibly recede, same pattern
            // The badge itself stays full-strength as the explicit signal.
            thumbImage.alpha = if (isWatched) 0.5f else 1f
            titleText.setTextColor(
                itemView.context.getColor(if (isWatched) R.color.text_tertiary else R.color.text_primary)
            )
            numberBadge.alpha = if (isWatched) 0.6f else 1f

            // Watched-state toggle, always visible. State must be re-applied on every bind
            // (recycled rows carry the previous episode's check), along with the
            // content description announcing what a press would now do. Scale reset guard
            // mirrors the row's above: a focused check rebinding in place keeps its pop.
            checkButton.isSelected = isWatched
            checkButton.contentDescription = itemView.context.getString(
                if (isWatched) R.string.episode_mark_unwatched else R.string.episode_mark_watched
            )
            if (!checkButton.isFocused) {
                checkButton.animate().cancel()
                checkButton.scaleX = 1f
                checkButton.scaleY = 1f
            }

            if (showDownloadButton) {
                downloadButton.visibility = View.VISIBLE
                downloadButton.isEnabled = isDownloaded?.invoke(episode) != true
                downloadButton.alpha = if (downloadButton.isEnabled) 1f else 0.4f
            } else {
                downloadButton.visibility = View.GONE
            }

            // Episode "preview" is the still/thumbnail the provider sends back for that
            // specific episode (movie_image in get_series_info) - not every provider or
            // every episode has one. The play-button overlay always shows either way;
            // without an image it just sits on the plain rounded card background.
            thumbImage.setImageDrawable(null)
            val url = episode.posterUrl
            if (!url.isNullOrBlank()) {
                PosterLoader.getCached(url)?.let { thumbImage.setImageBitmap(it) } ?: run {
                    scope.launch {
                        val bitmap = PosterLoader.fetch(url)
                        if (bitmap != null && current === episode) thumbImage.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(old: Channel, new: Channel): Boolean = old.id == new.id
        override fun areContentsTheSame(old: Channel, new: Channel): Boolean = old == new
    }
}

private val EPISODE_PREFIX_REGEX = Regex("""^S\d+E\d+ · """)

/** Both Xtream and Jellyfin bake "S01E04 · " onto the front of episode names (see
 *  XtreamClient.parseEpisode / JellyfinProvider.parseMediaItem) - stripped here purely
 *  for display since the episode number now has its own badge on the thumbnail, but
 *  `name` itself stays untouched since Up Next/search/watch history all key off it. */
private fun stripEpisodePrefix(name: String): String = name.replaceFirst(EPISODE_PREFIX_REGEX, "")

/** Leading ISO date of a release/air date field. Every source that states one states it
 *  this way, but with different amounts after it: Xtream sends a bare "2021-05-14",
 *  Jellyfin a full "2021-05-14T00:00:00.0000000Z". Anything that doesn't start with an
 *  ISO date is left out rather than guessed at - a mis-parsed date reads as fact. */
private val ISO_DATE_PREFIX_REGEX = Regex("""^(\d{4})-(\d{2})-(\d{2})""")

/** The air date as DD-MM-YY, or null when the source didn't state one. */
internal fun formatAirDate(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val (year, month, day) = (ISO_DATE_PREFIX_REGEX.find(value) ?: return null).destructured
    return "$day-$month-${year.takeLast(2)}"
}
