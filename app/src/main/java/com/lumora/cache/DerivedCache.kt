package com.lumora.cache

import android.content.Context
import android.util.Log
import com.lumora.model.CategoryFilter
import com.lumora.model.Channel
import java.io.File

/**
 * Disk cache for the *derived* catalogue - the output of the quality-grouping and duplicate
 * -folding passes, not the provider data they run on.
 *
 * [ChannelCache] already makes the raw catalogue cheap to reload, but re-deriving from it was
 * the real cost of a cold start: grouping 30k live channels by normalised name, then folding
 * 28k film titles and 5k series into their duplicate sets, ran to ~9s on a TV stick every
 * single launch to produce a result that is identical until the catalogue or the prefs that
 * shape it change.
 *
 * Only ids and *overrides* are stored, never whole channels: every derived channel IS one of
 * the raw ones with at most one field replaced - live representatives carry a decoration
 * -stripped name, films/series a year recovered from the title - so a derived entry is one
 * line of "id + what changed". Restoring is an id lookup against the raw list, which is a
 * fraction of the cost of re-running the pipeline, and the file stays a couple of MB rather
 * than a second copy of the catalogue.
 *
 * Live and VOD are separate files with their own fingerprints because they're derived on
 * separate coroutines and either may finish (or be cancelled) without the other.
 *
 * ## Invalidation
 * A file is only used when its stored fingerprint matches the one computed now. The
 * fingerprint covers the raw catalogue's identity plus every pref that changes what the
 * derive produces, so any catalogue refresh, provider change or relevant settings toggle
 * misses and re-derives. A miss costs exactly what the app used to do every time.
 */
object DerivedCache {

    private const val TAG = "DerivedCache"
    private const val LIVE_FILE = "derived_live.txt"
    private const val VOD_FILE = "derived_vod.txt"
    private const val SEP = ''
    private fun rowsFile(tab: Int) = "derived_rows_$tab.txt"
    /** Separates ids inside one row's matchIds/channelIds field. */
    private const val ID_SEP = '\u0002'
    /** Stands in for a null category id ("All" rows), which is meaningful and not the same
     *  as an empty string. */
    private const val NULL_ID = "\u0003"
    /** Bump when the line format changes - old files then miss instead of mis-parsing. */
    private const val FORMAT_VERSION = "1"

    class LiveSnapshot(val channels: List<Channel>, val versions: Map<String, List<Channel>>)

    class RowsSnapshot(
        val rows: List<CategoryFilter>,
        val childrenByParent: Map<String, List<CategoryFilter>>
    )

    class VodSnapshot(
        val films: List<Channel>,
        val filmVersions: Map<String, List<Channel>>,
        val series: List<Channel>,
        val seriesVersions: Map<String, List<Channel>>
    )

    /**
     * Identity of the raw catalogue for cache-keying purposes. Folds in the fields the derive
     * passes actually read (id, name, category) rather than hashing whole objects, so a
     * refresh that returns the same catalogue keeps the cache warm even though the Channel
     * instances are new.
     */
    fun catalogFingerprint(channels: List<Channel>, prefsPart: String): String {
        var hash = 1125899906842597L // FNV-ish seed
        for (ch in channels) {
            hash = hash * 31 + ch.id.hashCode()
            hash = hash * 31 + ch.name.hashCode()
            hash = hash * 31 + (ch.categoryId?.hashCode() ?: 0)
            hash = hash * 31 + ch.mediaType.ordinal
        }
        return "$FORMAT_VERSION|${channels.size}|${hash.toULong().toString(16)}|$prefsPart"
    }

    // ── Live ────────────────────────────────────────

    fun saveLive(context: Context, fingerprint: String, snapshot: LiveSnapshot) {
        // A blank id can't be looked up again on the way back in, and silently dropping a
        // channel is worse than re-deriving - skip writing the file at all.
        if (snapshot.channels.any { it.id.isBlank() }) return
        write(context, LIVE_FILE, fingerprint) { out ->
            for (ch in snapshot.channels) {
                out.append("C").append(SEP).append(ch.id).append(SEP).append(clean(ch.name)).append('\n')
            }
            for ((repId, versions) in snapshot.versions) {
                out.append("V").append(SEP).append(repId)
                for (v in versions) out.append(SEP).append(v.id)
                out.append('\n')
            }
        }
    }

    fun loadLive(context: Context, fingerprint: String, raw: List<Channel>): LiveSnapshot? {
        val lines = read(context, LIVE_FILE, fingerprint) ?: return null
        val byId = indexById(raw)
        val channels = ArrayList<Channel>(lines.size)
        val versions = HashMap<String, List<Channel>>()
        for (line in lines) {
            val f = line.split(SEP)
            when (f.getOrNull(0)) {
                "C" -> {
                    val base = byId[f.getOrNull(1) ?: return null] ?: return null
                    val name = f.getOrNull(2) ?: return null
                    channels.add(if (name == base.name) base else base.copy(name = name))
                }
                "V" -> {
                    val repId = f.getOrNull(1) ?: return null
                    val members = f.drop(2).mapNotNull { byId[it] }
                    if (members.size > 1) versions[repId] = members
                }
                else -> return null
            }
        }
        if (channels.isEmpty()) return null
        return LiveSnapshot(channels, versions)
    }

    // ── Films / series ──────────────────────────────

    fun saveVod(context: Context, fingerprint: String, snapshot: VodSnapshot) {
        if (snapshot.films.any { it.id.isBlank() } || snapshot.series.any { it.id.isBlank() }) return
        write(context, VOD_FILE, fingerprint) { out ->
            appendVodItems(out, "F", snapshot.films)
            appendVodVersions(out, "FV", snapshot.filmVersions)
            appendVodItems(out, "S", snapshot.series)
            appendVodVersions(out, "SV", snapshot.seriesVersions)
        }
    }

    fun loadVod(context: Context, fingerprint: String, raw: List<Channel>): VodSnapshot? {
        val lines = read(context, VOD_FILE, fingerprint) ?: return null
        val byId = indexById(raw)
        val films = ArrayList<Channel>()
        val series = ArrayList<Channel>()
        val filmVersions = HashMap<String, List<Channel>>()
        val seriesVersions = HashMap<String, List<Channel>>()
        for (line in lines) {
            val f = line.split(SEP)
            val tag = f.getOrNull(0)
            when (tag) {
                "F", "S" -> {
                    val base = byId[f.getOrNull(1) ?: return null] ?: return null
                    // Field 2 is the year the derive resolved out of the title, blank when the
                    // provider's own year already stood.
                    val year = f.getOrNull(2)?.ifEmpty { null }
                    val item = if (year == base.year) base else base.copy(year = year)
                    if (tag == "F") films.add(item) else series.add(item)
                }
                "FV", "SV" -> {
                    val repId = f.getOrNull(1) ?: return null
                    val members = f.drop(2).mapNotNull { byId[it] }
                    if (members.size > 1) {
                        if (tag == "FV") filmVersions[repId] = members else seriesVersions[repId] = members
                    }
                }
                else -> return null
            }
        }
        if (films.isEmpty() && series.isEmpty()) return null
        return VodSnapshot(films, filmVersions, series, seriesVersions)
    }

    // ── Category rows ───────────────────────────────

    /**
     * The sidebar/shelf category rows for one tab. Rebuilding these was the last big cold
     * -start cost after the grouped lists were cached: brand clustering alone walks every
     * live channel's name twice, and the whole pass ran on every launch to produce rows that
     * only change when the catalogue, the pinned/hidden sets, or the layout prefs do.
     *
     * Cached per tab, since the three tabs are built separately and independently.
     */
    fun saveRows(context: Context, tab: Int, fingerprint: String, snapshot: RowsSnapshot) {
        write(context, rowsFile(tab), fingerprint) { out ->
            for (row in snapshot.rows) appendRow(out, "R", null, row)
            for ((parentId, children) in snapshot.childrenByParent) {
                for (child in children) appendRow(out, "K", parentId, child)
            }
        }
    }

    fun loadRows(context: Context, tab: Int, fingerprint: String): RowsSnapshot? {
        val lines = read(context, rowsFile(tab), fingerprint) ?: return null
        val rows = ArrayList<CategoryFilter>()
        val children = LinkedHashMap<String, MutableList<CategoryFilter>>()
        for (line in lines) {
            val f = line.split(SEP)
            // tag, [parentId], id, name, count, flags, matchIds, channelIds
            if (f.size < 8) return null
            val row = CategoryFilter(
                id = f[2].takeIf { it != NULL_ID },
                name = f[3],
                count = f[4].toIntOrNull() ?: return null,
                pinned = f[5].getOrNull(0) == '1',
                isParent = f[5].getOrNull(1) == '1',
                isChild = f[5].getOrNull(2) == '1',
                expanded = f[5].getOrNull(3) == '1',
                isDynamic = f[5].getOrNull(4) == '1',
                matchIds = splitIds(f[6]),
                channelIds = splitIds(f[7])
            )
            when (f[0]) {
                "R" -> rows.add(row)
                "K" -> children.getOrPut(f[1]) { mutableListOf() }.add(row)
                else -> return null
            }
        }
        if (rows.isEmpty()) return null
        return RowsSnapshot(rows, children)
    }

    private fun appendRow(out: Appendable, tag: String, parentId: String?, row: CategoryFilter) {
        out.append(tag).append(SEP)
            .append(parentId?.let(::clean) ?: "").append(SEP)
            .append(row.id?.let(::clean) ?: NULL_ID).append(SEP)
            .append(clean(row.name)).append(SEP)
            .append(row.count.toString()).append(SEP)
            .append(if (row.pinned) "1" else "0")
            .append(if (row.isParent) "1" else "0")
            .append(if (row.isChild) "1" else "0")
            .append(if (row.expanded) "1" else "0")
            .append(if (row.isDynamic) "1" else "0").append(SEP)
            .append(joinIds(row.matchIds)).append(SEP)
            .append(joinIds(row.channelIds)).append('\n')
    }

    private fun joinIds(ids: Set<String>): String =
        if (ids.isEmpty()) "" else ids.joinToString(ID_SEP.toString()) { clean(it) }

    private fun splitIds(field: String): Set<String> =
        if (field.isEmpty()) emptySet() else field.split(ID_SEP).toSet()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, LIVE_FILE).delete() }
        runCatching { File(context.filesDir, VOD_FILE).delete() }
        for (tab in 0..2) runCatching { File(context.filesDir, rowsFile(tab)).delete() }
    }

    // ── Shared plumbing ─────────────────────────────

    private fun appendVodItems(out: Appendable, tag: String, items: List<Channel>) {
        for (ch in items) {
            out.append(tag).append(SEP).append(ch.id).append(SEP).append(clean(ch.year ?: "")).append('\n')
        }
    }

    private fun appendVodVersions(out: Appendable, tag: String, versions: Map<String, List<Channel>>) {
        for ((repId, members) in versions) {
            out.append(tag).append(SEP).append(repId)
            for (m in members) out.append(SEP).append(m.id)
            out.append('\n')
        }
    }

    private fun indexById(raw: List<Channel>): Map<String, Channel> {
        val byId = HashMap<String, Channel>(raw.size)
        // First writer wins, matching the derive passes, which see the list in this order.
        for (ch in raw) if (ch.id.isNotEmpty()) byId.putIfAbsent(ch.id, ch)
        return byId
    }

    /** Writes atomically (temp file + rename) so a killed process can't leave a torn cache
     *  that the fingerprint would still accept. */
    private inline fun write(context: Context, fileName: String, fingerprint: String, body: (Appendable) -> Unit) {
        try {
            val target = File(context.filesDir, fileName)
            val temp = File(target.absolutePath + ".tmp")
            temp.bufferedWriter().use { out ->
                out.append(fingerprint).append('\n')
                body(out)
            }
            temp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "save $fileName failed: ${e.message}")
        }
    }

    /** Returns the body lines when the file's first line matches [fingerprint], else null. */
    private fun read(context: Context, fileName: String, fingerprint: String): List<String>? {
        val file = File(context.filesDir, fileName)
        if (!file.exists()) return null
        return try {
            file.bufferedReader().use { reader ->
                if (reader.readLine() != fingerprint) return null
                reader.readLines().filter { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "load $fileName failed: ${e.message}")
            null
        }
    }

    /** The separators, sentinel and newlines are the format: a value carrying one would tear
     *  the file, and one carrying the null-id sentinel would read back as a null id. */
    private fun clean(value: String): String {
        if (value.indexOf(SEP) < 0 && value.indexOf(ID_SEP) < 0 && value.indexOf(NULL_ID) < 0 &&
            value.indexOf('\n') < 0 && value.indexOf('\r') < 0
        ) return value
        return value.replace(SEP, ' ').replace(ID_SEP, ' ').replace(NULL_ID, " ")
            .replace('\n', ' ').replace('\r', ' ')
    }
}
