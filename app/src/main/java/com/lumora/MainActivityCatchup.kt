package com.lumora

import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumora.adapter.CatchupRow
import com.lumora.model.Channel
import com.lumora.model.MediaType
import com.lumora.parser.XtreamClient
import com.lumora.player.playback.CatchUpUrlBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ── Catch Up (Xtream tv_archive playback) ──
//
// Extracted from MainActivity.kt; see that file's header.
//
// Columns that fly out left-to-right - category, then its archive channels, then that
// channel's days, then that day's programmes - rather than screens replacing one another.
// The path taken stays on screen, LEFT walks back up it, and picking a different day costs
// one press instead of a back-and-forward.
//
// There are four levels but not the width for four columns, so the window slides: only the
// last catchup_max_columns are on screen (three landscape, two portrait) and the leftmost
// drops off as you drill. Stepping back brings it round again.

/** Marks a Channel built for archive playback rather than one from the catalogue. */
internal const val CATCHUP_ID_PREFIX = "catchup:"

/** How deep the drill currently is. The rightmost open column is what Back closes first. */
internal enum class CatchupStage { CATEGORIES, CHANNELS, DAYS, PROGRAMMES }

/** Archive playback is an Xtream-panel feature: it is the only backend that reports
 *  tv_archive, and the only one with a /timeshift/ endpoint to play back from. */
internal fun MainActivity.catchupChannels(): List<Channel> =
    liveChannels.filter { it.tvArchive && it.tvArchiveDays > 0 && !it.isJellyfin }

/** Archive channels grouped the way the sidebar groups live ones: by the provider's
 *  category name, falling back to the M3U group and then to "Other". A 220-channel archive
 *  is unbrowsable as one flat list. */
internal fun MainActivity.catchupCategories(): List<Pair<String, List<Channel>>> =
    catchupChannels()
        .groupBy { it.categoryName?.takeIf { n -> n.isNotBlank() } ?: it.group?.takeIf { g -> g.isNotBlank() } ?: "Other" }
        .toList()
        .sortedBy { it.first.lowercase() }

/** The tab only exists when there is something behind it. Also re-points Discover's D-pad
 *  LEFT, which has to skip the tab while it is GONE - a nextFocusLeft onto a GONE view
 *  resolves to nothing and the press is silently eaten. */
internal fun MainActivity.updateCatchupTabVisibility() {
    val available = catchupChannels().isNotEmpty()
    binding.tabCatchup.visibility = if (available) View.VISIBLE else View.GONE
    binding.tabDiscover.nextFocusLeftId = if (available) R.id.tabCatchup else R.id.tabHome
    // The tab vanishing under the user (a provider disabled while they are in Catch Up)
    // must not leave the pane on screen with no way back to it.
    if (!available && showingCatchup) selectTab(0)
}

internal fun MainActivity.selectCatchup() {
    activeSettingsOverlay?.dismiss()
    activeSearchOverlay?.dismiss()
    showingCatchup = true
    showingHome = false
    showingDiscover = false
    showingDownloads = false
    releaseLivePreview()
    binding.discoverContent.visibility = View.GONE
    binding.homeContent.visibility = View.GONE
    binding.homeSearchBar.visibility = View.GONE
    binding.downloadsContent.visibility = View.GONE
    binding.downloadsEmptyText.visibility = View.GONE
    binding.contentRow.visibility = View.VISIBLE
    binding.liveRow.visibility = View.GONE
    binding.seriesContent.visibility = View.GONE
    binding.filmsContent.visibility = View.GONE
    // The columns are this screen's navigation; the category rail would be a second,
    // conflicting way to pick what is listed.
    applySidebarVisibility(tabWantsSidebar = false)
    binding.catchupContent.visibility = View.VISIBLE
    ensureCatchupColumnsWired()
    updateTabStyles(binding.tabCatchup)
    showCatchupCategories()
    applyStatus()
}

private fun MainActivity.ensureCatchupColumnsWired() {
    if (binding.catchupCategoryList.layoutManager != null) return
    for ((list, adapter) in listOf(
        binding.catchupCategoryList to catchupCategoryAdapter,
        binding.catchupChannelList to catchupChannelAdapter,
        binding.catchupDayList to catchupDayAdapter,
        binding.catchupProgrammeList to catchupProgrammeAdapter
    )) {
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        // UP out of any column lands on the tab row - it is outside this RecyclerView, so
        // nextFocusUpId can't resolve it (see the repo's focus notes).
        adapter.topRowFocusUpTargetId = R.id.tabCatchup
    }
    // LEFT from the leftmost visible column steps the sliding window back instead of
    // dying against a column that is off screen.
    catchupChannelAdapter.onFocusLeftEdge = { catchupBack() }
    catchupDayAdapter.onFocusLeftEdge = { catchupBack() }
    catchupProgrammeAdapter.onFocusLeftEdge = { catchupBack() }
}

/** Which columns are on screen. Four levels, room for [R.integer.catchup_max_columns], so
 *  the window shows the deepest ones and the leftmost slide off as the drill goes deeper.
 *  Also re-points the sideways focus targets, since "the column to my left" is only a
 *  focus target while it is actually visible. */
private fun MainActivity.updateCatchupColumnWindow(animateDeepest: Boolean) {
    val depth = catchupStage.ordinal
    val maxColumns = resources.getInteger(R.integer.catchup_max_columns)
    val first = (depth - maxColumns + 1).coerceAtLeast(0)
    val columns = listOf<View>(
        binding.catchupCategoryList,
        binding.catchupChannelList,
        binding.catchupDayList,
        binding.catchupProgrammeColumn
    )
    val listIds = listOf(
        R.id.catchupCategoryList, R.id.catchupChannelList, R.id.catchupDayList, R.id.catchupProgrammeList
    )
    val adapters = listOf(
        catchupCategoryAdapter, catchupChannelAdapter, catchupDayAdapter, catchupProgrammeAdapter
    )
    columns.forEachIndexed { index, column ->
        val visible = index in first..depth
        when {
            !visible -> closeCatchupColumn(column)
            // Only the newly-opened deepest column flies out; the ones already on screen
            // must not re-animate every time something below them changes.
            column.visibility != View.VISIBLE ->
                openCatchupColumn(column, animate = animateDeepest && index == depth)
            else -> Unit
        }
        adapters[index].focusLeftTargetId =
            if (visible && index - 1 >= first) listIds[index - 1] else View.NO_ID
        adapters[index].focusRightTargetId =
            if (visible && index + 1 <= depth) listIds[index + 1] else View.NO_ID
    }
}

internal fun MainActivity.hideCatchup() {
    showingCatchup = false
    binding.catchupContent.visibility = View.GONE
    catchupEpgJob?.cancel()
    catchupEpgJob = null
}

/** Column 1: the archive's categories. The reset point for the whole screen. */
internal fun MainActivity.showCatchupCategories() {
    catchupStage = CatchupStage.CATEGORIES
    catchupCategoryName = null
    catchupChannel = null
    catchupDayStart = 0L
    catchupEpgJob?.cancel()
    catchupCategoryAdapter.setSelected(null)
    catchupChannelAdapter.setSelected(null)
    catchupDayAdapter.setSelected(null)
    val categories = catchupCategories()
    binding.catchupCrumb.text = getString(R.string.catchup_crumb_categories, catchupChannels().size)
    setCatchupStatus(null)
    catchupCategoryAdapter.replaceAll(
        categories.map { (name, channels) ->
            CatchupRow(key = "cat:$name", title = name, meta = "${channels.size}")
        }
    )
    binding.catchupCategoryList.scrollToPosition(0)
    updateCatchupColumnWindow(animateDeepest = false)
}

/** Column 2: the archive channels inside one category. */
internal fun MainActivity.showCatchupChannels(categoryName: String, moveFocus: Boolean) {
    catchupStage = CatchupStage.CHANNELS
    catchupCategoryName = categoryName
    catchupChannel = null
    catchupDayStart = 0L
    catchupEpgJob?.cancel()
    catchupCategoryAdapter.setSelected("cat:$categoryName")
    catchupChannelAdapter.setSelected(null)
    catchupDayAdapter.setSelected(null)
    val channels = catchupCategories().firstOrNull { it.first == categoryName }?.second.orEmpty()
    binding.catchupCrumb.text = getString(R.string.catchup_crumb_channels_in, categoryName, channels.size)
    setCatchupStatus(null)
    catchupChannelAdapter.replaceAll(
        channels.map { ch ->
            CatchupRow(
                key = "ch:${ch.id}",
                title = ch.name,
                meta = resources.getQuantityString(R.plurals.catchup_days_kept, ch.tvArchiveDays, ch.tvArchiveDays)
            )
        }
    )
    binding.catchupChannelList.scrollToPosition(0)
    updateCatchupColumnWindow(animateDeepest = true)
    if (moveFocus) focusFirstItemWhenReady(binding.catchupChannelList)
}

/** Column 3: one row per day the panel still holds, today first. */
internal fun MainActivity.showCatchupDays(channel: Channel, moveFocus: Boolean) {
    catchupStage = CatchupStage.DAYS
    catchupChannel = channel
    catchupDayStart = 0L
    catchupEpgJob?.cancel()
    catchupChannelAdapter.setSelected("ch:${channel.id}")
    catchupDayAdapter.setSelected(null)
    binding.catchupCrumb.text = getString(R.string.catchup_crumb_days, channel.name)
    setCatchupStatus(null)
    val dayLabel = SimpleDateFormat("EEE d MMM", Locale.getDefault())
    catchupDayAdapter.replaceAll(
        (0 until channel.tvArchiveDays.coerceIn(1, 14)).map { back ->
            val start = catchupDayStartMillis(back)
            CatchupRow(
                key = "day:$start",
                title = when (back) {
                    0 -> getString(R.string.catchup_today)
                    1 -> getString(R.string.catchup_yesterday)
                    else -> dayLabel.format(Date(start))
                },
                meta = if (back <= 1) dayLabel.format(Date(start)) else null
            )
        }
    )
    binding.catchupDayList.scrollToPosition(0)
    updateCatchupColumnWindow(animateDeepest = true)
    if (moveFocus) focusFirstItemWhenReady(binding.catchupDayList)
}

/** Column 3: that day's listings. Only programmes that have already finished are playable -
 *  the archive cannot hold what has not been broadcast yet. */
internal fun MainActivity.showCatchupProgrammes(channel: Channel, dayStartMillis: Long, moveFocus: Boolean) {
    catchupStage = CatchupStage.PROGRAMMES
    catchupChannel = channel
    catchupDayStart = dayStartMillis
    catchupDayAdapter.setSelected("day:$dayStartMillis")
    val dayLabel = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(dayStartMillis))
    binding.catchupCrumb.text = getString(R.string.catchup_crumb_programmes, channel.name, dayLabel)
    setCatchupStatus(getString(R.string.catchup_loading))
    catchupProgrammeAdapter.replaceAll(emptyList())
    updateCatchupColumnWindow(animateDeepest = true)
    catchupEpgJob?.cancel()
    catchupEpgJob = scope.launch {
        val provider = xtreamProviderFor(channel)
        val listings = if (provider == null) emptyList() else runCatching {
            XtreamClient(BaseApplication.instance.okHttpClient).getEpgTable(provider, channel.id)
        }.getOrDefault(emptyList())
        // A day that ends in the future is today: nothing past "now" can be in the archive.
        val dayEnd = dayStartMillis + DAY_MS
        val cutoff = minOf(dayEnd, System.currentTimeMillis())
        val programmes = withContext(Dispatchers.Default) {
            listings.filter { p ->
                val startMs = p.startTimestamp * 1000L
                startMs >= dayStartMillis && startMs < dayEnd && p.stopTimestamp * 1000L <= cutoff
            }
        }
        if (!isActive) return@launch
        // A newer day (or channel) was picked while this one loaded - its listings are the
        // ones the user is waiting for, so drop these rather than overwrite them.
        if (catchupStage != CatchupStage.PROGRAMMES || catchupDayStart != dayStartMillis) return@launch
        val rows = if (programmes.isNotEmpty()) {
            setCatchupStatus(null)
            programmes.map { p -> catchupProgrammeRow(p) }
        } else {
            // No guide for this day, but the archive is time-addressed rather than
            // programme-addressed, so it is still playable - offer plain hourly blocks
            // instead of a dead end.
            setCatchupStatus(getString(R.string.catchup_no_guide))
            catchupHourlyBlocks(dayStartMillis, cutoff)
        }
        catchupProgrammeAdapter.replaceAll(rows)
        binding.catchupProgrammeList.scrollToPosition(0)
        if (rows.isEmpty()) setCatchupStatus(getString(R.string.catchup_nothing_aired))
        else if (moveFocus) focusFirstItemWhenReady(binding.catchupProgrammeList)
    }
}

/** Slides a column in from behind the one on its left. Width is fixed by the layout, so
 *  this is a short translate+fade rather than a size change - cheap enough to stay smooth
 *  on the budget TV sticks this has to run on. */
private fun MainActivity.openCatchupColumn(column: View, animate: Boolean) {
    column.animate().cancel()
    column.alpha = 1f
    column.translationX = 0f
    column.visibility = View.VISIBLE
    if (!animate) return
    column.alpha = 0f
    column.translationX = -CATCHUP_COLUMN_SLIDE_DP * resources.displayMetrics.density
    column.animate()
        .alpha(1f)
        .translationX(0f)
        .setDuration(160)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

private fun MainActivity.closeCatchupColumn(column: View) {
    column.animate().cancel()
    column.visibility = View.GONE
    column.alpha = 1f
    column.translationX = 0f
}

private fun MainActivity.catchupProgrammeRow(p: XtreamClient.EpgProgram): CatchupRow {
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    val startMs = p.startTimestamp * 1000L
    val endMs = p.stopTimestamp * 1000L
    val minutes = ((endMs - startMs) / 60_000L).toInt().coerceAtLeast(1)
    return CatchupRow(
        key = "prog:${p.startTimestamp}",
        title = p.title,
        meta = "${clock.format(Date(startMs))} – ${clock.format(Date(endMs))}  ·  ${minutes}m",
        drillsIn = false
    )
}

/** Guide-less fallback: whole hours from the start of the day up to [cutoff]. */
private fun MainActivity.catchupHourlyBlocks(dayStartMillis: Long, cutoff: Long): List<CatchupRow> {
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    val rows = mutableListOf<CatchupRow>()
    var slot = dayStartMillis
    while (slot + HOUR_MS <= cutoff) {
        rows.add(
            CatchupRow(
                key = "slot:$slot",
                title = "${clock.format(Date(slot))} – ${clock.format(Date(slot + HOUR_MS))}",
                meta = getString(R.string.catchup_hour_block),
                drillsIn = false
            )
        )
        slot += HOUR_MS
    }
    return rows
}

internal fun MainActivity.onCatchupCategoryClick(row: CatchupRow) {
    showCatchupChannels(row.key.removePrefix("cat:"), moveFocus = true)
}

internal fun MainActivity.onCatchupChannelClick(row: CatchupRow) {
    val id = row.key.removePrefix("ch:")
    catchupChannels().firstOrNull { it.id == id }?.let { showCatchupDays(it, moveFocus = true) }
}

internal fun MainActivity.onCatchupDayClick(row: CatchupRow) {
    val start = row.key.removePrefix("day:").toLongOrNull() ?: return
    catchupChannel?.let { showCatchupProgrammes(it, start, moveFocus = true) }
}

/** Back closes the rightmost open column, so it retraces the way in. Only from the channel
 *  column does it leave the section, which [handleBackNavigation] handles via
 *  isAtSectionTop(). */
internal fun MainActivity.catchupBack(): Boolean = when (catchupStage) {
    CatchupStage.PROGRAMMES -> {
        catchupEpgJob?.cancel()
        catchupChannel?.let { showCatchupDays(it, moveFocus = true) }
        true
    }
    CatchupStage.DAYS -> {
        catchupCategoryName?.let { showCatchupChannels(it, moveFocus = true) }
        true
    }
    CatchupStage.CHANNELS -> {
        showCatchupCategories()
        focusFirstItemWhenReady(binding.catchupCategoryList)
        true
    }
    CatchupStage.CATEGORIES -> false
}

/**
 * Plays an archived programme. The stream is a finite recording rather than a live edge,
 * so it is handed to the player as MOVIE: that is what gives it the seek bar and progress
 * the user expects of "watch what I missed", and it keeps the live-only chrome
 * (channel-surf on UP/DOWN, the preview pane) out of the way.
 *
 * The id is deliberately not the channel's - a resume position saved against the channel
 * id would then be replayed the next time the *live* channel is opened.
 */
internal fun MainActivity.playCatchup(row: CatchupRow) {
    val channel = catchupChannel ?: return
    val provider = xtreamProviderFor(channel) ?: run {
        Toast.makeText(this, R.string.catchup_no_provider, Toast.LENGTH_SHORT).show()
        return
    }
    val startSeconds: Long
    val durationMinutes: Int
    when {
        row.key.startsWith("prog:") -> {
            startSeconds = row.key.removePrefix("prog:").toLongOrNull() ?: return
            // "12:00 – 13:30  ·  90m" - the row already carries the length the panel needs.
            durationMinutes = row.meta?.substringAfterLast("·")?.trim()?.removeSuffix("m")
                ?.toIntOrNull() ?: 60
        }
        row.key.startsWith("slot:") -> {
            val slotMillis = row.key.removePrefix("slot:").toLongOrNull() ?: return
            startSeconds = slotMillis / 1000L
            durationMinutes = 60
        }
        else -> return
    }
    val url = CatchUpUrlBuilder.buildXtreamTimeshiftUrl(
        provider = provider,
        streamId = channel.id,
        startTimestampSeconds = startSeconds,
        // A minute of lead-in absorbs the usual few seconds of clock skew between panel
        // and device, which otherwise clips the opening of the programme.
        durationMinutes = durationMinutes + 1
    ) ?: run {
        Toast.makeText(this, R.string.catchup_no_provider, Toast.LENGTH_SHORT).show()
        return
    }
    showPlayerFor(
        channel.copy(
            id = "$CATCHUP_ID_PREFIX${channel.id}:$startSeconds",
            name = "${row.title} · ${channel.name}",
            url = url,
            mediaType = MediaType.MOVIE,
            tvArchive = false,
            tvArchiveDays = 0
        )
    )
}

private fun MainActivity.setCatchupStatus(text: String?) {
    binding.catchupStatus.text = text.orEmpty()
    binding.catchupStatus.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
}

/** Midnight, [daysBack] days ago, in the device's zone - the archive is addressed by
 *  wall-clock time, so day boundaries have to be local ones. */
private fun catchupDayStartMillis(daysBack: Int): Long {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, -daysBack)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

private const val DAY_MS = 24 * 60 * 60 * 1000L
private const val HOUR_MS = 60 * 60 * 1000L
/** dp the flying-out column starts offset by. Small on purpose - it reads as the column
 *  sliding out from under its neighbour, not a panel travelling across the screen. */
private const val CATCHUP_COLUMN_SLIDE_DP = 24f
