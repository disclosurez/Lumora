package com.lumora.cache

import android.content.Context
import android.util.Log
import com.lumora.model.Channel
import com.lumora.model.MediaType
import java.io.BufferedReader
import java.io.File

/** Regex for characters that would corrupt the line/field format; compiled once at class load. */
private val FIELD_CLEAN_REGEX = Regex("[\n\r\u0001]")

private const val TAG = "ChannelCache"
private const val CACHE_FILE = "channels_cache.txt"
private const val LEGACY_JSON_CACHE_FILE = "channels_cache.json"
private const val FIELD_SEP = ''
/** Fields written per line. Append-only: new fields go at the end so older files stay
 *  readable. */
private const val FIELD_COUNT = 27
/** Fields a line must have to be usable. Held at the pre-Catch-Up count on purpose - a
 *  cache written by an older build is still perfectly good, and every field added since
 *  is read with getOrElse. Raising this to FIELD_COUNT dropped every line of an existing
 *  13MB cache on first launch after update, turning an instant cold start into a full
 *  network refetch. */
private const val FIELD_COUNT_MIN = 22

/**
 * Persists the last successfully loaded channel list to disk so re-opening the
 * app shows content instantly instead of re-fetching the whole catalog every time.
 *
 * Plain delimited text, not JSON: org.json's tree parser was the dominant cost in
 * "instant" cached loads once the catalog grew into the tens of thousands of items
 * (a ~20MB JSON file took many seconds just to parse). A flat one-line-per-channel
 * format needs only String.split() per line, no parser/DOM overhead.
 */
object ChannelCache {

    /**
     * Written a channel at a time straight to the file, not built up in memory first.
     *
     * The whole catalogue used to go into one StringBuilder and then `toString()` - so at the
     * moment of writing, the text existed twice: once in the builder's char[] and once in the
     * copy handed to writeText, each ~2 bytes per character. A merged multi-provider catalogue
     * reached tens of MB of text, which is where a TV stick died with
     * `Failed to allocate a 175786432 byte allocation` while the builder was still doubling.
     * A BufferedWriter holds only its buffer, whatever the catalogue's size.
     */
    fun save(context: Context, channels: List<Channel>) {
        try {
            val target = File(context.filesDir, CACHE_FILE)
            val tempFile = File(target.absolutePath + ".tmp")
            tempFile.bufferedWriter().use { out ->
            val sb = StringBuilder(256)
            for (ch in channels) {
                sb.setLength(0)
                sb.append(clean(ch.id)).append(FIELD_SEP)
                sb.append(clean(ch.name)).append(FIELD_SEP)
                sb.append(clean(ch.url)).append(FIELD_SEP)
                sb.append(clean(ch.logoUrl)).append(FIELD_SEP)
                sb.append(clean(ch.posterUrl)).append(FIELD_SEP)
                sb.append(clean(ch.backdropUrl)).append(FIELD_SEP)
                sb.append(clean(ch.group)).append(FIELD_SEP)
                sb.append(clean(ch.tvgId)).append(FIELD_SEP)
                sb.append(clean(ch.tvgName)).append(FIELD_SEP)
                sb.append(clean(ch.tvgChno)).append(FIELD_SEP)
                sb.append(ch.mediaType.name).append(FIELD_SEP)
                sb.append(clean(ch.categoryId)).append(FIELD_SEP)
                sb.append(clean(ch.categoryName)).append(FIELD_SEP)
                sb.append(clean(ch.description)).append(FIELD_SEP)
                sb.append(clean(ch.year)).append(FIELD_SEP)
                sb.append(clean(ch.rating)).append(FIELD_SEP)
                sb.append(clean(ch.releaseDate)).append(FIELD_SEP)
                sb.append(if (ch.isJellyfin) "1" else "0").append(FIELD_SEP)
                sb.append(clean(ch.streamUserAgent)).append(FIELD_SEP)
                sb.append(clean(ch.sourceProviderId)).append(FIELD_SEP)
                sb.append(ch.avOffsetMs.toString()).append(FIELD_SEP)
                // Stalker VOD/series play command - without it a cached film reloads with a
                // blank url and nothing to create_link, so playback opens "" as a local file
                // and dies with ENOENT.
                sb.append(clean(ch.stalkerCmd)).append(FIELD_SEP)
                // Catch Up needs these on cold start - the tab is built from the cached
                // catalogue, before any provider refetch has run.
                sb.append(if (ch.tvArchive) "1" else "0").append(FIELD_SEP)
                sb.append(ch.tvArchiveDays.toString()).append(FIELD_SEP)
                // TMDB id / trailer key from the panel - the detail screen's TMDB fill and the
                // Trailer button both run off the cached catalogue on a cold start, and without
                // these they fall back to guessing the title.
                sb.append(clean(ch.tmdbId)).append(FIELD_SEP)
                sb.append(clean(ch.trailerKey)).append(FIELD_SEP)
                // Plex, like Jellyfin, needs its provenance on cold start: the catalogue is
                // browsable from cache before any provider fetch runs, and a Plex item played
                // without this flag would take the plain-URL path instead of negotiating a
                // stream with the server.
                sb.append(if (ch.isPlex) "1" else "0").append('\n')
                out.append(sb)
            }
            }
            // Same atomic swap writeToFile did: a half-written cache must never replace a
            // good one, and a torn file would fail FIELD_COUNT on the next load anyway.
            tempFile.renameTo(target)
            runCatching { File(context.filesDir, LEGACY_JSON_CACHE_FILE).delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cache: ${e.message}")
        }
    }

    /** True when the file just loaded was written before the archive fields existed, so
     *  every channel came back with tvArchive=false regardless of what the panel reports.
     *  The catalogue is still perfectly usable - it just cannot answer "does this channel
     *  have catch-up" until a refetch, which the caller schedules off the back of this. */
    @Volatile
    var lastLoadWasLegacyFormat: Boolean = false
        private set

    fun load(context: Context): List<Channel>? {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return null
        val startedAt = System.currentTimeMillis()
        return try {
            val result = ArrayList<Channel>(50_000)
            var sawLegacyLine = false
            file.bufferedReader().use { reader: BufferedReader ->
                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val f = line.split(FIELD_SEP)
                    if (f.size < FIELD_COUNT_MIN) return@forEachLine
                    if (f.size < FIELD_COUNT) sawLegacyLine = true
                    result.add(
                        Channel(
                            // Falls back to the url, matching what M3uParser now assigns.
                            // Caches written before that fix hold a blank id for every M3U
                            // channel, and this file is read on cold start before any
                            // refetch - without the backfill the id-collision bug would
                            // survive the update until the user manually refreshed.
                            id = f[0].ifEmpty { f[2] },
                            name = f[1],
                            url = f[2],
                            logoUrl = f[3].ifEmpty { null },
                            posterUrl = f[4].ifEmpty { null },
                            backdropUrl = f[5].ifEmpty { null },
                            group = f[6].ifEmpty { null },
                            tvgId = f[7].ifEmpty { null },
                            tvgName = f[8].ifEmpty { null },
                            tvgChno = f[9].ifEmpty { null },
                            mediaType = when (f[10]) {
                                // Direct dispatch avoids a valueOf call + runCatching
                                // allocation per line (tens of thousands on every load).
                                "LIVE" -> MediaType.LIVE
                                "MOVIE" -> MediaType.MOVIE
                                "SERIES" -> MediaType.SERIES
                                else -> MediaType.LIVE // same fallback as the old valueOf(...).getOrDefault
                            },
                            categoryId = f[11].ifEmpty { null },
                            categoryName = f[12].ifEmpty { null },
                            description = f[13].ifEmpty { null },
                            year = f[14].ifEmpty { null },
                            rating = f[15].ifEmpty { null },
                            releaseDate = f[16].ifEmpty { null },
                            isJellyfin = f[17] == "1",
                            streamUserAgent = f[18].ifEmpty { null },
                            sourceProviderId = f[19].ifEmpty { null },
                            avOffsetMs = f.getOrElse(20) { "0" }.toIntOrNull() ?: 0,
                            stalkerCmd = f.getOrElse(21) { "" }.ifEmpty { null },
                            tvArchive = f.getOrElse(22) { "0" } == "1",
                            tvArchiveDays = f.getOrElse(23) { "0" }.toIntOrNull() ?: 0,
                            tmdbId = f.getOrElse(24) { "" }.ifEmpty { null },
                            trailerKey = f.getOrElse(25) { "" }.ifEmpty { null },
                            isPlex = f.getOrElse(26) { "0" } == "1"
                        )
                    )
                }
            }
            // Cold-start budget line: this parse and the derive passes that follow it are
            // what "instant cached start" is spent on - keep them measurable on-device
            // (adb logcat -s LumoraPerf) rather than guessed at.
            lastLoadWasLegacyFormat = sawLegacyLine
            Log.i(
                "LumoraPerf",
                "cache load: ${result.size} items in ${System.currentTimeMillis() - startedAt}ms " +
                    "(${file.length() / 1024}KB)" + if (sawLegacyLine) " [legacy format]" else ""
            )
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cache: ${e.message}")
            null
        }
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, CACHE_FILE).delete() }
        runCatching { File(context.filesDir, LEGACY_JSON_CACHE_FILE).delete() }
    }

    /** Atomically writes text to file: write to a temp file, then rename to prevent corruption on process death. */
    /** Strips characters that would corrupt the line/field format; real channel data never needs them. */
    private fun clean(value: String?): String {
        if (value == null) return ""
        // Fast path: the overwhelming majority of fields are already clean - three char
        // scans are cheaper than a regex pass, and the regex can only match these three.
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf(FIELD_SEP) < 0) return value
        return value.replace(FIELD_CLEAN_REGEX, " ")
    }
}
