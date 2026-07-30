package com.lumora.util

import com.lumora.model.CategoryFilter
import com.lumora.model.Channel
import java.util.Calendar

private val YEAR_PAREN_REGEX = Regex("""\((\d{4})\)""")
// Matches one or more hyphen-joined ALL-CAPS/digit/"+" tokens before " - ", e.g.
// "EN - ", "4K-D+ - ", "EN-TOP - ". Tags are always uppercase in this provider's
// data, which is what keeps this from eating real (mixed-case) title words.
// Uppercase alone isn't enough of a test though - plenty of catalogues list titles
// fully capitalised, so "TROY - THE ODYSSEY" matched this and got stripped down to
// "the odyssey", which then grouped as a duplicate of the actual film "The Odyssey".
// [isSourceTagToken] is the second gate that keeps real title words out.
private val LEADING_TAG_REGEX = Regex("""^(?:[A-Z0-9+]{1,6}-)*[A-Z0-9+]{1,6}\s*-\s*""")

// Longer all-letter tags that are still tags, not title words.
private val KNOWN_TAG_WORDS = setOf(
    "MULTI", "DUAL", "SUB", "SUBS", "DUB", "LAT", "VOSTFR", "HEVC", "H265", "H264",
    "UHD", "FHD", "HDR", "SDR", "VIP", "PLUS", "ORIG", "ORIGINALS", "NEW", "TOP"
)

/** True if one hyphen-joined leading token is a source/quality tag rather than a word of
 *  the title. Tags are short (a language/provider code), carry a digit or "+" ("4K", "D+"),
 *  or are one of the known longer tag words - a 4-to-6 letter all-letter token that is none
 *  of those is far more likely a real title word ("TROY", "ALIEN", "MARVEL"). */
private fun isSourceTagToken(token: String): Boolean =
    token.length <= 3 ||
        token.any { it.isDigit() || it == '+' } ||
        token in KNOWN_TAG_WORDS

/** The leading source tag of [name] ("EN - ", "4K-D+ - "), including its trailing separator,
 *  or null when the title just starts with a capitalised word followed by a dash. */
private fun leadingTagMatch(name: String): String? {
    val match = LEADING_TAG_REGEX.find(name) ?: return null
    val tokens = match.value.trimEnd().trimEnd('-').trim().split('-').filter { it.isNotBlank() }
    if (tokens.isEmpty() || !tokens.all(::isSourceTagToken)) return null
    return match.value
}
private val BRACKET_REGEX = Regex("""\[[^\]]*\]""")
private val WHITESPACE_REGEX = Regex("""\s+""")
// Language tag can show up in either bracket style - "[KR]" or "(KR)" - this provider
// isn't consistent about which.
private val LANGUAGE_BRACKET_REGEX = Regex("""[\[(]([A-Za-z]{2,4})[\])]""")

private val ENGLISH_LANGUAGE_CODES = setOf("IE", "GB", "UK", "EN", "US", "CA", "AU", "NZ")

// Region/quality tag in round parens, the way IPTV catalogues suffix a title after the year:
// "Stuart Fails to Save the Universe (2026) (US)", "Naked and Afraid (US)", "... (FHD)".
// Only the year form was stripped for grouping, so an IPTV copy carrying one of these never
// matched the Jellyfin copy of the same title and both got their own card. Uppercase-only
// (BRACKET_REGEX already handles the square-bracket style) so a real parenthesised title
// word isn't eaten - this only ever feeds the grouping key, never what's displayed.
private val PAREN_TAG_REGEX = Regex("""\([A-Z]{2,5}\)""")

/**
 * Pulls a release year out of a "(YYYY)" suffix in the title. Requires the
 * parens so titles with a bare number in them (e.g. "Blade Runner 2049") don't
 * get misread as a year.
 */
fun extractYearFromName(name: String): String? {
    val match = YEAR_PAREN_REGEX.findAll(name).lastOrNull() ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    return if (year in 1900..(currentYear + 1)) year.toString() else null
}

/** Fills in Channel.year from the title when the provider left it blank. */
fun Channel.withResolvedYear(): Channel =
    if (!year.isNullOrBlank()) this else extractYearFromName(name)?.let { copy(year = it) } ?: this

/**
 * Normalizes a title for duplicate grouping: strips a leading source tag
 * ("TOP - ", "NF - ", "4K-D+ - "), the release year, and any bracketed or parenthesised
 * region/quality tag, so "TOP - The Breadwinner (2026)", "NF - The Breadwinner" and
 * "4K-MAX - The Breadwinner (2026) (US)" all group together.
 */
fun normalizeTitleForGrouping(name: String): String {
    var n = leadingTagMatch(name)?.let { name.removePrefix(it) } ?: name
    n = YEAR_PAREN_REGEX.replace(n, "")
    n = BRACKET_REGEX.replace(n, "")
    n = PAREN_TAG_REGEX.replace(n, "")
    n = WHITESPACE_REGEX.replace(n, " ").trim().lowercase()
    return n
}

/** Pulls just the leading source tag ("4K-D+", "TOP") off a title, for labeling version-picker chips. */
fun extractLeadingTag(name: String): String? =
    leadingTagMatch(name)?.trimEnd('-', ' ')?.trim()

/**
 * Groups movies that are really the same title reposted under different
 * source tags/qualities. Returns one representative Channel per group (for
 * display) plus a map from that representative's id to every version.
 */
fun groupDuplicateMovies(movies: List<Channel>): Pair<List<Channel>, Map<String, List<Channel>>> {
    val groups = LinkedHashMap<String, MutableList<Channel>>()
    for (channel in movies) {
        val key = normalizeTitleForGrouping(channel.name).ifBlank { channel.id }
        groups.getOrPut(key) { mutableListOf() }.add(channel)
    }
    val representatives = mutableListOf<Channel>()
    val versionsById = mutableMapOf<String, List<Channel>>()
    for (group in groups.values) {
        val versions = jellyfinFirst(group)
        val representative = pickRepresentative(versions)
        representatives.add(representative)
        if (versions.size > 1) versionsById[representative.id] = versions
    }
    return representatives to versionsById
}

/** The user's own Jellyfin library always outranks an IPTV copy of the same title: it's the
 *  copy they curated, and it streams off their own server. Ordering the group this way makes
 *  Jellyfin the first (default-played) version chip; ties keep provider order. */
private fun jellyfinFirst(versions: List<Channel>): List<Channel> =
    if (versions.size > 1 && versions.any { it.isJellyfin }) {
        versions.sortedBy { if (it.isJellyfin) 0 else 1 }
    } else {
        versions
    }

/** The copy that gets the card. A poster is what makes the card look right, so it wins among
 *  equally-ranked copies - but never at the cost of demoting the Jellyfin version, which is
 *  already first in [versions]. */
private fun pickRepresentative(versions: List<Channel>): Channel {
    val preferred = versions.firstOrNull()?.takeIf { it.isJellyfin }
    val jellyfinWithPoster = if (preferred != null) {
        versions.firstOrNull { it.isJellyfin && !it.posterUrl.isNullOrBlank() } ?: preferred
    } else {
        null
    }
    return jellyfinWithPoster
        ?: versions.firstOrNull { !it.posterUrl.isNullOrBlank() }
        ?: versions.first()
}

/**
 * Same title-reposted-under-different-source-tags problem as movies: one card per
 * title, plus a map from that representative's id to every duplicate.
 *
 * A series isn't itself a playable stream, so its versions aren't alternate streams
 * of one thing the way a movie's are - each duplicate carries its own episode list,
 * from its own provider. The detail screen still needs them: with several providers
 * merged, the copy that wins the card can be the one with the worse (or missing)
 * episode list, and every other provider's copy was previously dropped outright with
 * no way to reach it.
 */
fun groupDuplicateSeries(series: List<Channel>): Pair<List<Channel>, Map<String, List<Channel>>> {
    val groups = LinkedHashMap<String, MutableList<Channel>>()
    for (channel in series) {
        val key = normalizeTitleForGrouping(channel.name).ifBlank { channel.id }
        groups.getOrPut(key) { mutableListOf() }.add(channel)
    }
    val representatives = mutableListOf<Channel>()
    val versionsById = mutableMapOf<String, List<Channel>>()
    for (group in groups.values) {
        val versions = jellyfinFirst(group)
        val representative = pickRepresentative(versions)
        representatives.add(representative)
        if (versions.size > 1) versionsById[representative.id] = versions
    }
    return representatives to versionsById
}

/** True if the title carries an explicit non-English bracket language tag, e.g. "[AR]", "[FR]". */
fun isNonEnglishTitle(name: String): Boolean {
    val match = LANGUAGE_BRACKET_REGEX.find(name) ?: return false
    return match.groupValues[1].uppercase() !in ENGLISH_LANGUAGE_CODES
}

// "adults?" (not just "adult") because real provider data files this under "FOR ADULTS"
// (plural) - \b word-boundary matching means the singular-only pattern never matched it.
private val ADULT_KEYWORD_REGEX = Regex("""(?i)\b(xxx|adults?|porn|hentai|erotica?|18\+)\b""")

/** Flags a category/group as adult content, for parental-control filtering. Checks category name first, falls back to the channel's own name/group. */
fun isAdultCategory(categoryName: String?, group: String? = null): Boolean =
    ADULT_KEYWORD_REGEX.containsMatchIn(categoryName ?: "") || ADULT_KEYWORD_REGEX.containsMatchIn(group ?: "")

// ── Live channel quality-version merging ──────────────────────────────────

// This provider spells out quality badges in small-caps/superscript Unicode as often
// as plain ASCII - "ᴿᴬᵂ", "ʰᵉᵛᶜ", "ᴴᴰ", "ⱽᴵᴾ", "⁴ᵏ", "³⁸⁴⁰ᴾ" all show up for the exact
// same real badges as "RAW"/"hevc"/"HD"/"VIP"/"4K"/"3840P" (confirmed against a live
// provider dump). Transliterating those to plain ASCII first means every ASCII-based
// tag regex below (quality words, resolution digits) catches both forms with one pass,
// instead of needing a parallel Unicode-aware copy of each pattern.
private val SUPERSCRIPT_MAP: Map<Char, Char> = mapOf(
    'ᴬ' to 'A', 'ᴮ' to 'B', 'ᴰ' to 'D', 'ᴱ' to 'E', 'ᴳ' to 'G', 'ᴴ' to 'H', 'ᴵ' to 'I', 'ᴶ' to 'J',
    'ᴷ' to 'K', 'ᴸ' to 'L', 'ᴹ' to 'M', 'ᴺ' to 'N', 'ᴼ' to 'O', 'ᴾ' to 'P', 'ᴿ' to 'R', 'ᵀ' to 'T',
    'ᵁ' to 'U', 'ⱽ' to 'V', 'ᵂ' to 'W',
    'ᵃ' to 'a', 'ᵇ' to 'b', 'ᶜ' to 'c', 'ᵈ' to 'd', 'ᵉ' to 'e', 'ᶠ' to 'f', 'ᵍ' to 'g', 'ʰ' to 'h',
    'ⁱ' to 'i', 'ʲ' to 'j', 'ᵏ' to 'k', 'ˡ' to 'l', 'ᵐ' to 'm', 'ⁿ' to 'n', 'ᵒ' to 'o', 'ᵖ' to 'p',
    'ʳ' to 'r', 'ˢ' to 's', 'ᵗ' to 't', 'ᵘ' to 'u', 'ᵛ' to 'v', 'ʷ' to 'w', 'ˣ' to 'x', 'ʸ' to 'y', 'ᶻ' to 'z',
    '⁰' to '0', '¹' to '1', '²' to '2', '³' to '3', '⁴' to '4', '⁵' to '5', '⁶' to '6', '⁷' to '7', '⁸' to '8', '⁹' to '9'
)

private fun deSuperscript(name: String): String {
    if (name.none { it in SUPERSCRIPT_MAP }) return name // fast path - most names have none of these
    return name.map { SUPERSCRIPT_MAP[it] ?: it }.joinToString("")
}

// \b is the wrong boundary for these badges. Providers run them together - "ᵁᴴᴰ³⁸⁴⁰ᴾ"
// transliterates to "UHD3840P", where there is no word boundary between "UHD" and "3840P"
// because D and 3 are both word characters. Every \b-anchored pattern therefore missed the
// whole badge: the channel scored 0 (below RAW and even plain HD) and kept its badge in the
// grouping key, so it never merged with its own siblings either.
//
// Word tags may be followed by a digit (the glued case) but not by another letter, so "SUHD"
// or "4Kids" still won't match; number tags may be preceded by a letter but not a digit, so
// "1080" out of "21080" won't.
private const val WORD_TAG_ALTERNATIVES = """4K|UHD|ULTRA\s?HD|FHD|HD|SD|HEVC|H265|H264|RAW|VIP"""
private const val NUM_TAG_ALTERNATIVES = """\d{3,4}\s?[x×]\s?\d{3,4}|\d{3,4}\s?[PI]"""
private fun wordTagRegex(alternatives: String) = Regex("""(?i)(?<![A-Za-z0-9])(?:$alternatives)(?![A-Za-z])""")
private fun numTagRegex(pattern: String) = Regex("""(?i)(?<!\d)(?:$pattern)(?![A-Za-z0-9])""")

private val DECORATIVE_TOKEN_REGEX = Regex(
    """(?i)(?:(?<![A-Za-z0-9])(?:$WORD_TAG_ALTERNATIVES)(?![A-Za-z])|(?<!\d)(?:$NUM_TAG_ALTERNATIVES)(?![A-Za-z0-9]))"""
)
private val HASH_BORDER_REGEX = Regex("""#+""")
// Provider scatters standalone decorative symbols around badges too - "&" joining
// "4K & 3840P", "◉" bullet markers, etc - that survive DECORATIVE_TOKEN_REGEX because
// they aren't one of the known tag words themselves. Anything left over that isn't a
// letter/digit/space/"+" is just noise for grouping purposes, so strip it outright.
private val SYMBOL_NOISE_REGEX = Regex("""[^\p{L}\p{Nd}\s+]""")
private val QUALITY_4K_REGEX = wordTagRegex("""4K|UHD|ULTRA\s?HD""")
private val QUALITY_RAW_REGEX = wordTagRegex("RAW")
private val QUALITY_FHD_REGEX = wordTagRegex("FHD")
// "UHD" must not read as an HD badge - the leading letter is what rules it out here, and
// the 4K branch is checked first regardless.
private val QUALITY_HD_REGEX = wordTagRegex("HD")
private val QUALITY_SD_REGEX = wordTagRegex("SD")
// Live channels are typically reposted under a source/country tag chain like
// Leading provider/country tags (e.g. "UK| Main Event", "VIP: Main Event", "NOW: Main
// Event") show up as the
// tag delimiter in real catalogs (confirmed against a live provider dump - "NOW:",
// "VIP:", "UK:", "4K:" all precede the exact same channel). Strip that leading
// "TAG| "/"TAG: " chain (case-insensitive, provider casing is inconsistent) so all
// of those collapse into one entry the same way movie titles do.
private val LIVE_LEADING_TAG_REGEX = Regex("""(?i)^(?:[a-z0-9+]{1,8}[|:]\s*)+""")

/** Strips leading source tags ("UK:", "VIP:", "NOW|") and quality/codec noise ("HEVC",
 *  "4K", "UHD", "RAW", pixel-resolution tags...) for display - keeps original casing,
 *  unlike normalizeLiveChannelName/Key which lowercase and light-stem for matching. */
fun stripDecorativeTags(name: String): String {
    var n = LIVE_LEADING_TAG_REGEX.replace(deSuperscript(name), "")
    n = DECORATIVE_TOKEN_REGEX.replace(n, " ")
    n = HASH_BORDER_REGEX.replace(n, " ")
    n = BRACKET_REGEX.replace(n, " ")
    n = SYMBOL_NOISE_REGEX.replace(n, " ")
    return WHITESPACE_REGEX.replace(n, " ").trim()
}

// Singular/plural provider drift ("Main Event" vs "Main Events") fractures what's
// really one channel into separate groups. Length>4 guard
// keeps short unrelated words ("News", "Plus") from getting mangled - but it still
// mangles some real words ("Tennis" -> "Tenni"), which is fine for a dedup *key*
// nobody sees, but would look broken as a displayed label, so this only ever feeds
// normalizeLiveChannelKey below, never normalizeLiveChannelName (which stays
// display-safe and is what cleanGroupLabel shows the user).
private fun lightStem(word: String): String =
    if (word.length > 4 && word.endsWith("s", ignoreCase = true)) word.dropLast(1) else word

fun normalizeLiveChannelName(name: String): String = stripDecorativeTags(name).lowercase()

/** Same as [normalizeLiveChannelName] but additionally singular-stems each word, for use as a dedup/grouping key (never for display - see [lightStem]). */
fun normalizeLiveChannelKey(name: String): String =
    normalizeLiveChannelName(name)
        .split(WHITESPACE_REGEX)
        .filter { it.isNotBlank() }
        .joinToString(" ") { lightStem(it) }

// Raw pixel resolution tags map onto the same tiers as their named equivalent
// (3840x2160 = 4K/UHD, 1920x1080 = FHD, 1280x720 = HD). Both shapes appear: a scanline
// count ("2160p", "1080i", "3840 P") and a full dimension pair ("3840x2160"), the latter
// of which used to score 0 - so a "Sport 3840x2160" feed ranked *below* the RAW copy of
// the same channel instead of above it.
private val RES_TAG_REGEX = numTagRegex("""(\d{3,4})\s?[PI]""")
private val RES_DIMENSION_REGEX = numTagRegex("""(\d{3,4})\s?[x×]\s?(\d{3,4})""")

/** Scanline count of whatever resolution tag [name] carries, in either shape. A dimension
 *  pair is reported by its smaller side, so 3840x2160 and 2160p score identically. */
private fun resolutionLines(name: String): Int? {
    RES_DIMENSION_REGEX.find(name)?.let { m ->
        val a = m.groupValues[1].toIntOrNull()
        val b = m.groupValues[2].toIntOrNull()
        if (a != null && b != null) return minOf(a, b)
    }
    return RES_TAG_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
}

/** Higher is better; used to auto-pick the best version and order fallbacks. */
fun liveQualityScore(rawName: String): Int {
    val name = deSuperscript(rawName)
    val resWidth = resolutionLines(name)
    return when {
        QUALITY_4K_REGEX.containsMatchIn(name) -> 5
        resWidth != null && resWidth >= 2160 -> 5
        // RAW = unencoded/unprocessed master feed - no resolution tag of its own, but
        // outranks named HD/FHD since it's the highest-bitrate feed short of an explicit
        // 4K/UHD tag.
        QUALITY_RAW_REGEX.containsMatchIn(name) -> 4
        QUALITY_FHD_REGEX.containsMatchIn(name) -> 3
        resWidth != null && resWidth >= 1080 -> 3
        QUALITY_HD_REGEX.containsMatchIn(name) -> 2
        resWidth != null && resWidth >= 720 -> 2
        QUALITY_SD_REGEX.containsMatchIn(name) -> 1
        resWidth != null -> 1
        else -> 0
    }
}

/**
 * Groups live channels that are the same channel repeated at different
 * qualities. Returns one representative (best-quality) Channel per group for
 * display, plus a map from that representative's id to every version sorted
 * best-quality-first, so playback can fall back to the next one on error.
 */
fun groupLiveQualityVersions(channels: List<Channel>): Pair<List<Channel>, Map<String, List<Channel>>> {
    val groups = LinkedHashMap<String, MutableList<Channel>>()
    for (channel in channels) {
        val key = normalizeLiveChannelKey(channel.name).ifBlank { channel.id }
        groups.getOrPut(key) { mutableListOf() }.add(channel)
    }
    val representatives = mutableListOf<Channel>()
    val versionsById = mutableMapOf<String, List<Channel>>()
    for (versions in groups.values) {
        // Quality first, Jellyfin only as the tie-break. Live TV is the one place where
        // "prefer Jellyfin" and "prefer the best feed" genuinely conflict: a Jellyfin server
        // fed by the same IPTV sources carries its own RAW/HD copies of a channel, and
        // ranking those above everything buried the provider's 4K feed several entries down
        // and auto-tuned a lower-quality stream. Films and series have no quality ladder, so
        // they keep the plain Jellyfin-first rule.
        val ranked = versions.sortedWith(
            compareByDescending<Channel> { liveQualityScore(it.name) }.thenBy { if (it.isJellyfin) 0 else 1 }
        )
        val best = ranked.first()
        // Version list (picker/failover) keeps full raw names, since "NOW:" vs "VIP:"
        // is a real distinguishing detail there - only the row people actually browse
        // shows the cleaned name.
        val cleanedName = stripDecorativeTags(best.name).ifBlank { best.name }
        representatives.add(if (cleanedName != best.name) best.copy(name = cleanedName) else best)
        // Blank ids would all land on the same key and hand every channel the same version
        // list - a provider that doesn't supply ids should get no version grouping at all,
        // not one shared group. (M3U was exactly this until it started keying off the url.)
        if (ranked.size > 1 && best.id.isNotBlank()) versionsById[best.id] = ranked
    }
    return representatives to versionsById
}

// ── Brand/franchise clustering (Live TV) ───────────────────────────────────

// A prefix only becomes its own virtual category once "lots" of channels share it -
// a one-off match isn't worth a dedicated section, it just stays in its provider category.
private const val MIN_BRAND_CLUSTER_SIZE_MULTI_WORD = 3
private const val MIN_BRAND_CLUSTER_SIZE_SINGLE_WORD = 4

/**
 * Clusters live channels that share a common name prefix — e.g. "Main Event",
 * "Main Event F1", "Main Event News" -> a virtual "Main Event" category — on top
 * of whatever provider category they actually live in (a channel's real category
 * is often just a generic "Sport" bucket with hundreds of unrelated channels in it).
 * Tries a 2-word prefix first, since that's how most multi-channel sports franchises
 * name themselves; whatever's left over falls back to a 1-word prefix with a higher
 * bar to cluster, since a single common word is much more likely to be a false-positive match.
 */
fun deriveBrandCategories(channels: List<Channel>): List<Pair<String, List<Channel>>> {
    // A "+" premium-tier suffix and singular/plural drift are both real provider
    // inconsistencies confirmed against live catalogs that would otherwise fracture
    // one brand into several near-duplicate categories.
    fun normalizeToken(word: String): String = lightStem(word.trimEnd('+')).lowercase()

    fun rawPrefix(name: String, wordCount: Int): String? {
        val words = stripDecorativeTags(name).split(WHITESPACE_REGEX).filter { it.isNotBlank() }
        if (words.size <= wordCount) return null
        val prefix = words.take(wordCount)
        if (prefix.any { it.trimEnd('+').length <= 2 }) return null // too short to be a meaningful brand token
        return prefix.joinToString(" ")
    }

    val claimed = mutableSetOf<String>()
    val members = LinkedHashMap<String, MutableList<Channel>>()
    // Several raw spellings collapse to one key — pick whichever spelling is most
    // common among the cluster as the display label,
    // rather than deriving it mechanically from the (stemmed, so slightly mangled) key.
    val labelVotes = LinkedHashMap<String, MutableMap<String, Int>>()

    for ((wordCount, minSize) in listOf(2 to MIN_BRAND_CLUSTER_SIZE_MULTI_WORD, 1 to MIN_BRAND_CLUSTER_SIZE_SINGLE_WORD)) {
        val remaining = channels.filter { it.id.isNotBlank() && it.id !in claimed }
        val grouped = remaining.groupBy { ch -> rawPrefix(ch.name, wordCount)?.split(" ")?.joinToString(" ") { normalizeToken(it) } }
        for ((key, group) in grouped) {
            if (key == null || group.size < minSize) continue
            for (ch in group) {
                val raw = rawPrefix(ch.name, wordCount) ?: continue
                labelVotes.getOrPut(key) { mutableMapOf() }.merge(raw, 1, Int::plus)
            }
            members.getOrPut(key) { mutableListOf() }.addAll(group)
            claimed.addAll(group.map { it.id })
        }
    }

    return members.entries.map { (key, chs) ->
        val bestRaw = labelVotes[key]?.maxByOrNull { it.value }?.key ?: key
        val label = bestRaw.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar(Char::uppercase) }
        label to chs
    }
}

// ── Category merging (Live TV) ─────────────────────────────────────────────

/** Groups that share a common category prefix. Callers treat these differently:
 *  a cluster keeps its label even with one member, and Series/Films floats them
 *  to the top of the sidebar. */
data class CategoryGroup(val label: String, val members: List<CategoryFilter>, val isCluster: Boolean = false)

/** "Newest" shelf (Series/Films): simply sorts by release date descending so the
 *  most recent content appears first, regardless of category or source. */
fun newestByDate(items: List<Channel>, limit: Int = 30): List<Channel> {
    fun dateKey(ch: Channel): String = ch.releaseDate?.takeIf { it.isNotBlank() } ?: ch.year?.let { "$it-01-01" } ?: ""
    return items.sortedByDescending { dateKey(it) }.take(limit)
}

private fun cleanGroupLabel(raw: String): String =
    normalizeLiveChannelName(raw)
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifBlank { raw }

/**
 * Clusters raw leaf categories (id must be non-null, "All" excluded) into groups
 * by their normalised name (quality/format tags stripped), so "UK| Sport HD"/"UK|
 * Sport SD"/"UK| Sport RAW" collapse into one "Sport" group. A group with a single
 * member is returned as-is (no merging needed).
 */
fun groupCategories(leaves: List<CategoryFilter>): List<CategoryGroup> {
    val normGroups = LinkedHashMap<String, MutableList<CategoryFilter>>()
    for (leaf in leaves) {
        val id = leaf.id ?: continue
        val key = normalizeLiveChannelKey(leaf.name).ifBlank { id }
        normGroups.getOrPut(key) { mutableListOf() }.add(leaf)
    }

    val result = mutableListOf<CategoryGroup>()
    for (members in normGroups.values) {
        val label = if (members.size > 1) cleanGroupLabel(members.first().name) else members.first().name
        result.add(CategoryGroup(label, members))
    }
    return result
}

/**
 * Groups Series/Films categories into clusters that share a common prefix.
 * Provider categories often follow a "Brand Type" pattern (e.g. "NETFLIX MOVIES",
 * "NETFLIX KIDS"), so stripping recognised type suffixes and clustering by the
 * remaining prefix naturally groups content by service without any hardcoded names.
 *
 * Categories that don't share a prefix with anything else are returned as-is.
 * Clusters are marked with [isCluster] = true so callers can float them to the top.
 */
fun groupSeriesFilmCategories(leaves: List<CategoryFilter>): List<CategoryGroup> {
    // Known type/genre/quality words to strip when extracting the prefix.
    // Superscript quality tags (⁴ᴷ→4K, ³⁸⁴⁰ᴾ→3840P) are normalized by deSuperscript.
    val suffixWords = setOf(
        "series", "srs", "movies", "movie", "tv", "vod", "hd", "fhd", "4k",
        "originals", "original", "content", "channel", "channels", "exclusive",
        "films", "film", "shows", "show", "documentaries", "documentary",
        "kids", "anime", "action", "comedy", "drama", "horror",
        "thriller", "sci-fi", "romance", "reality", "variety",
        "musical", "concert", "concerts", "boxing", "ufc", "wwe",
        "workout", "collections", "biblical", "christmas", "westerns",
        "animation", "stand-up", "dolby audio", "dolby", "hevc", "imax",
        "multisubs", "multi-subs", "bluray", "audio",
        "docu-movies", "docu-movie", "sub", "eng",
        // Abbreviations
        "eps", "ep", "min", "mins", "hr", "hrs", "chan", "sec",
        "dub", "dubbed", "subbed", "uncut", "uncensored",
        // Resolution/quality tags
        "3840p", "2160p", "1080p", "720p", "480p", "360p",
        "uhd", "fhd", "sd", "hdr", "sdr", "dts", "atmos",
        // Dolby variations
        "dolbyatmos", "dolbyvision", "dolbydigital",
        // Multi-subs variations
        "multisub", "multi", "subs",
        // Collections/groups
        "imdb", "top"
    )

    data class Entry(val id: String, val rawName: String, val prefix: String)

    // Build entries with cleaned prefixes
    val entries = leaves.mapNotNull { leaf ->
        val id = leaf.id ?: return@mapNotNull null
        val normalized = deSuperscript(leaf.name)
        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        // Strip any suffix words from the end (including unrecognized superscript leftovers)
        val stem = words.toMutableList()
        while (stem.isNotEmpty()) {
            val last = stem.last().lowercase().trimEnd('+', '-')
            if (last in suffixWords) {
                stem.removeAt(stem.lastIndex)
            } else {
                // Check if word is quality-like (all non-alpha, or digits+alpha, or
                // contains characters outside basic Latin after deSuperscript).
                val hasOnlyBasicLatin = last.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '+' }
                val isQualityTag = last.any { it.isDigit() } || last.length <= 3
                if (!hasOnlyBasicLatin || isQualityTag) {
                    stem.removeAt(stem.lastIndex)
                } else {
                    break // real word, stop stripping
                }
            }
        }
        // If stem is empty (all words were suffixes), use first word as prefix
        val prefix = if (stem.isEmpty()) {
            words.firstOrNull()?.replaceFirstChar { it.uppercase() } ?: leaf.name
        } else if (stem.size >= 2) {
            // Multi-word stem: use first 2 words as prefix
            stem.take(2).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        } else {
            // Single-word stem: require 3+ matches to cluster (avoids generic words)
            stem.first().replaceFirstChar { it.uppercase() }
        }
        Entry(id, leaf.name, prefix)
    }

    // Group by prefix, with different cluster-size thresholds:
    // - Single-word prefix → need at least 3 (avoids "COMEDY", "KIDS" etc)
    // - Multi-word prefix → need at least 2
    val prefixGroups = entries.groupBy { it.prefix.lowercase() }
        .filterValues { group ->
            val minSize = if (group.first().prefix.none { it.isWhitespace() }) 3 else 2
            group.size >= minSize
        }

    // Build result: clustered entries first (marked isCluster), then the rest as-is
    val clusteredIds = prefixGroups.values.flatten().map { it.id }.toSet()
    val result = mutableListOf<CategoryGroup>()

    for ((_, groupEntries) in prefixGroups) {
        val label = groupEntries.first().prefix
        val members = groupEntries.mapNotNull { entry ->
            leaves.firstOrNull { it.id == entry.id }
        }
        if (members.isNotEmpty()) {
            result.add(CategoryGroup(label, members, isCluster = true))
        }
    }

    // Remaining leaves that weren't clustered
    for (leaf in leaves) {
        if (leaf.id != null && leaf.id !in clusteredIds) {
            result.add(CategoryGroup(leaf.name, listOf(leaf)))
        }
    }

    return result
}
