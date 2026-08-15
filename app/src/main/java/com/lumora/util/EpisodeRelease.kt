package com.lumora.util

import com.lumora.model.Channel
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Leading ISO date of an air/release date field, as every source states it: Xtream sends a
 *  bare "2021-05-14", Jellyfin a full "2021-05-14T00:00:00.0000000Z", TMDB a bare date again.
 *  Anything not starting with an ISO date is left alone rather than guessed at. */
private val ISO_DATE_PREFIX = Regex("""^(\d{4})-(\d{2})-(\d{2})""")

/**
 * Whole days from today to the ISO date leading [raw]: 0 is today, 1 tomorrow, negative is
 * already past. Null when the field states no parseable date.
 *
 * Both ends are normalised to local midnight before subtracting, so the answer is a count of
 * calendar days rather than of 24-hour periods - an episode airing later today is "0", not "1"
 * because the clock has not passed its hour yet.
 */
fun daysUntilAirDate(raw: String?): Int? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val (year, month, day) = (ISO_DATE_PREFIX.find(value) ?: return null).destructured
    val air = Calendar.getInstance().apply {
        clear()
        set(year.toInt(), month.toInt() - 1, day.toInt())
    }
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return TimeUnit.MILLISECONDS.toDays(air.timeInMillis - today.timeInMillis).toInt()
}

/**
 * True when an episode row is a title that has not aired yet and that nothing can play.
 *
 * Both halves are required. A blank `url` is the TMDB-built placeholder from
 * `tmdbSeasonsFor`/`mergeMissingEpisodesFromTmdb` - i.e. no configured IPTV provider and no
 * Jellyfin server carries this episode; a provider copy always has a URL, and if one exists the
 * episode is playable whatever TMDB thinks its air date is (panels routinely carry a title
 * before, or with a different date than, its official air date). The future air date is what
 * separates "nobody has it yet because it does not exist" from "nobody has it, go find a
 * stream" - only the former is a dead row.
 */
fun isUnreleasedEpisode(channel: Channel): Boolean =
    channel.url.isBlank() && (daysUntilAirDate(channel.releaseDate) ?: -1) > 0
