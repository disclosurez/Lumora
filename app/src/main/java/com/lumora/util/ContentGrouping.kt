package com.lumora.util

import com.lumora.model.CategoryFilter
import com.lumora.model.Channel
import java.util.Calendar

private val YEAR_PAREN_REGEX = Regex("""\((\d{4})\)""")
// Matches one or more hyphen-joined ALL-CAPS/digit/"+" tokens before " - ", e.g.
// "EN - ", "4K-D+ - ", "EN-TOP - ". Tags are always uppercase in this provider's
// data, which is what keeps this from eating real (mixed-case) title words.
private val LEADING_TAG_REGEX = Regex("""^(?:[A-Z0-9+]{1,6}-)*[A-Z0-9+]{1,6}\s*-\s*""")
private val BRACKET_REGEX = Regex("""\[[^\]]*\]""")
private val WHITESPACE_REGEX = Regex("""\s+""")
// Language tag can show up in either bracket style - "[KR]" or "(KR)" - this provider
// isn't consistent about which.
private val LANGUAGE_BRACKET_REGEX = Regex("""[\[(]([A-Za-z]{2,4})[\])]""")

private val ENGLISH_LANGUAGE_CODES = setOf("IE", "GB", "UK", "EN", "US", "CA", "AU", "NZ")

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
 * ("TOP - ", "NF - ", "4K-D+ - "), the release year, and any bracketed tags, so
 * "TOP - The Breadwinner (2026)" and "NF - The Breadwinner" group together.
 */
fun normalizeTitleForGrouping(name: String): String {
    var n = LEADING_TAG_REGEX.replace(name, "")
    n = YEAR_PAREN_REGEX.replace(n, "")
    n = BRACKET_REGEX.replace(n, "")
    n = WHITESPACE_REGEX.replace(n, " ").trim().lowercase()
    return n
}

/** Pulls just the leading source tag ("4K-D+", "TOP") off a title, for labeling version-picker chips. */
fun extractLeadingTag(name: String): String? {
    val match = LEADING_TAG_REGEX.find(name) ?: return null
    return match.value.trimEnd('-', ' ').trim()
}

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
    for (versions in groups.values) {
        val representative = versions.firstOrNull { !it.posterUrl.isNullOrBlank() } ?: versions.first()
        representatives.add(representative)
        if (versions.size > 1) versionsById[representative.id] = versions
    }
    return representatives to versionsById
}

/**
 * Same title-reposted-under-different-source-tags problem as movies, but a
 * series isn't itself a playable stream - each duplicate has its own episode
 * list, so there's nothing to version-pick. Just keep one card per title.
 */
fun groupDuplicateSeries(series: List<Channel>): List<Channel> {
    val groups = LinkedHashMap<String, MutableList<Channel>>()
    for (channel in series) {
        val key = normalizeTitleForGrouping(channel.name).ifBlank { channel.id }
        groups.getOrPut(key) { mutableListOf() }.add(channel)
    }
    return groups.values.map { versions -> versions.firstOrNull { !it.posterUrl.isNullOrBlank() } ?: versions.first() }
}

/** True if the title carries an explicit non-English bracket language tag, e.g. "[AR]", "[FR]". */
fun isNonEnglishTitle(name: String): Boolean {
    val match = LANGUAGE_BRACKET_REGEX.find(name) ?: return false
    return match.groupValues[1].uppercase() !in ENGLISH_LANGUAGE_CODES
}

private val ADULT_KEYWORD_REGEX = Regex("""(?i)\b(xxx|adult|porn|18\+)\b""")

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

private val DECORATIVE_TOKEN_REGEX = Regex("""(?i)\b(4K|UHD|FHD|HD|SD|HEVC|H265|H264|RAW|VIP|\d{3,4}P)\b""")
private val HASH_BORDER_REGEX = Regex("""#+""")
// Provider scatters standalone decorative symbols around badges too - "&" joining
// "4K & 3840P", "◉" bullet markers, etc - that survive DECORATIVE_TOKEN_REGEX because
// they aren't one of the known tag words themselves. Anything left over that isn't a
// letter/digit/space/"+" is just noise for grouping purposes, so strip it outright.
private val SYMBOL_NOISE_REGEX = Regex("""[^\p{L}\p{Nd}\s+]""")
// Live channels are typically reposted under a source/country tag chain like
// "UK| Sky Sports Main Event", "VIP: Sky Sports Main Event", "NOW: Sky Sports Main
// Event" - same channel, different provider feed. Both "|" and ":" show up as the
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

// Singular/plural provider drift ("Main Event" vs "Main Events", "Sky Sport" vs "Sky
// Sports") fractures what's really one channel into separate groups. Length>4 guard
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

// Raw pixel-width resolution tags map onto the same tiers as their named equivalent
// (3840x2160 = 4K/UHD, 1920x1080 = FHD, 1280x720 = HD).
private val RES_TAG_REGEX = Regex("""(?i)\b(\d{3,4})P\b""")

/** Higher is better; used to auto-pick the best version and order fallbacks. */
fun liveQualityScore(rawName: String): Int {
    val name = deSuperscript(rawName)
    val resWidth = RES_TAG_REGEX.find(name)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        Regex("(?i)\\b(4K|UHD)\\b").containsMatchIn(name) -> 5
        resWidth != null && resWidth >= 2160 -> 5
        // RAW = unencoded/unprocessed master feed - no resolution tag of its own, but
        // outranks named HD/FHD since it's the highest-bitrate feed short of an explicit
        // 4K/UHD tag.
        Regex("(?i)\\bRAW\\b").containsMatchIn(name) -> 4
        Regex("(?i)\\bFHD\\b").containsMatchIn(name) -> 3
        resWidth != null && resWidth >= 1080 -> 3
        Regex("(?i)\\bHD\\b").containsMatchIn(name) -> 2
        resWidth != null && resWidth >= 720 -> 2
        Regex("(?i)\\bSD\\b").containsMatchIn(name) -> 1
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
        val ranked = versions.sortedByDescending { liveQualityScore(it.name) }
        val best = ranked.first()
        // Version list (picker/failover) keeps full raw names, since "NOW:" vs "VIP:"
        // is a real distinguishing detail there - only the row people actually browse
        // shows the cleaned name.
        val cleanedName = stripDecorativeTags(best.name).ifBlank { best.name }
        representatives.add(if (cleanedName != best.name) best.copy(name = cleanedName) else best)
        if (ranked.size > 1) versionsById[best.id] = ranked
    }
    return representatives to versionsById
}

// ── Brand/franchise clustering (Live TV) ───────────────────────────────────

// A prefix only becomes its own virtual category once "lots" of channels share it -
// a one-off match isn't worth a dedicated section, it just stays in its provider category.
private const val MIN_BRAND_CLUSTER_SIZE_MULTI_WORD = 3
private const val MIN_BRAND_CLUSTER_SIZE_SINGLE_WORD = 4

/**
 * Clusters live channels that share a common name prefix - "Sky Sports Main Event",
 * "Sky Sports F1", "Sky Sports News" -> a virtual "Sky Sports" category - on top of
 * whatever provider category they actually live in (a channel's real category is
 * often just a generic "Sport" bucket with hundreds of unrelated channels in it).
 * Tries a 2-word prefix first ("Sky Sports", "BT Sport"), since that's how most
 * multi-channel sports franchises name themselves; whatever's left over falls back
 * to a 1-word prefix ("ESPN 1"/"ESPN 2" -> "ESPN") with a higher bar to cluster,
 * since a single common word is much more likely to be a false-positive match.
 */
fun deriveBrandCategories(channels: List<Channel>): List<Pair<String, List<Channel>>> {
    // A "+" premium-tier suffix ("Sky Sports+") and singular/plural drift ("Sky
    // Sport" vs "Sky Sports") are both real provider inconsistencies (confirmed
    // against a live catalog - 193/70/70/1 way split on exactly this) that would
    // otherwise fracture one brand into several near-duplicate categories.
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
    // Several raw spellings ("Sky Sports", "Sky Sports+", "Sky Sport") collapse to one
    // key - pick whichever spelling is most common among the cluster as the display label,
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

data class CategoryGroup(val label: String, val members: List<CategoryFilter>)

private val BRAND_KEYWORDS = linkedMapOf(
    "Disney" to listOf("disney"),
    "Netflix" to listOf("netflix"),
    "Apple TV" to listOf("apple tv", "apple+"),
    "Amazon / Prime Video" to listOf("amazon", "prime video"),
    "HBO / Max" to listOf("hbo"),
    "Paramount+" to listOf("paramount"),
    "Peacock" to listOf("peacock"),
    "Discovery+" to listOf("discovery")
)

private fun cleanGroupLabel(raw: String): String =
    normalizeLiveChannelName(raw)
        .split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifBlank { raw }

/**
 * Clusters raw leaf categories (id must be non-null, "All" excluded) into:
 *  - explicit brand groups (Disney/Netflix/Apple/...) matched by keyword, then
 *  - everything else, grouped by name with quality/format tags stripped, so
 *    "UK| Sport HD"/"UK| Sport SD"/"UK| Sport RAW" collapse into one "Sport" group.
 * A group with a single member is returned as-is (no merging needed).
 */
fun groupCategories(leaves: List<CategoryFilter>): List<CategoryGroup> {
    val used = mutableSetOf<String>()
    val brandGroups = LinkedHashMap<String, MutableList<CategoryFilter>>()
    for (leaf in leaves) {
        val id = leaf.id ?: continue
        val lower = leaf.name.lowercase()
        val brand = BRAND_KEYWORDS.entries.firstOrNull { (_, keywords) -> keywords.any { lower.contains(it) } }?.key
        if (brand != null) {
            brandGroups.getOrPut(brand) { mutableListOf() }.add(leaf)
            used.add(id)
        }
    }

    val normGroups = LinkedHashMap<String, MutableList<CategoryFilter>>()
    for (leaf in leaves) {
        val id = leaf.id ?: continue
        if (id in used) continue
        val key = normalizeLiveChannelKey(leaf.name).ifBlank { id }
        normGroups.getOrPut(key) { mutableListOf() }.add(leaf)
    }

    val result = mutableListOf<CategoryGroup>()
    for ((brand, members) in brandGroups) result.add(CategoryGroup(brand, members))
    for (members in normGroups.values) {
        val label = if (members.size > 1) cleanGroupLabel(members.first().name) else members.first().name
        result.add(CategoryGroup(label, members))
    }
    return result
}
