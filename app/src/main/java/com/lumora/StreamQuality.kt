package com.lumora

/**
 * Ranks release quality so the best source can be played without asking.
 *
 * Torrent results advertise quality as free text, and it is the single most important thing
 * about them: a 200-seeder CAM is a worse watch than a 3-seeder BluRay, so seeders can only be
 * the tie-break *within* a quality tier, never the primary sort. The tiers below are the standard
 * release ladder, coarse on purpose - the difference between BDRip and BluRay is not worth
 * modelling, the difference between BluRay and a camcorder recording very much is.
 *
 * Matching runs over the title as well as the quality field, because indexers put the release
 * type in the release name (`Movie.2024.1080p.WEB-DL.x265`) at least as often as in a separate
 * column.
 */
object StreamQuality {

    /** Ordered worst to best; the first pattern that matches the text decides the tier. */
    private val TIERS = listOf(
        // Camcorder recordings and screen captures - watchable only if nothing else exists.
        30 to Regex("""\b(cam[\-. ]?rip|hd[\-. ]?cam|\bcam\b|ts\b|tele[\-. ]?sync|hd[\-. ]?ts)""", RegexOption.IGNORE_CASE),
        // Analogue/early digital sources: better than a camcorder, still visibly compromised.
        40 to Regex("""\b(tc\b|tele[\-. ]?cine|scr\b|screener|dvd[\-. ]?scr|r5\b|workprint)""", RegexOption.IGNORE_CASE),
        50 to Regex("""\b(dvd[\-. ]?rip|dvd[\-. ]?r\b|xvid)""", RegexOption.IGNORE_CASE),
        60 to Regex("""\b(hd[\-. ]?tv|pd[\-. ]?tv|sat[\-. ]?rip|tv[\-. ]?rip)""", RegexOption.IGNORE_CASE),
        70 to Regex("""\b(web[\-. ]?rip|hd[\-. ]?rip|br[\-. ]?rip)""", RegexOption.IGNORE_CASE),
        80 to Regex("""\b(web[\-. ]?dl|web[\-. ]?dlrip|amzn|nf\b|dsnp|hmax)""", RegexOption.IGNORE_CASE),
        90 to Regex("""\b(blu[\-. ]?ray|bd[\-. ]?rip|bd[\-. ]?remux|remux|uhd)""", RegexOption.IGNORE_CASE),
    )

    private val RESOLUTIONS = listOf(
        8 to Regex("""\b(2160p?|4k|uhd)\b""", RegexOption.IGNORE_CASE),
        6 to Regex("""\b1080p?\b""", RegexOption.IGNORE_CASE),
        4 to Regex("""\b720p?\b""", RegexOption.IGNORE_CASE),
        2 to Regex("""\b(480p?|576p?|sd)\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * Baseline for a source that advertises nothing recognisable.
     *
     * Sits just under WEB-DL deliberately. An unlabelled source is usually a streaming site's
     * direct link, which in practice is a web rip - so this ranks it above the camcorder tiers
     * and below anything that has actually declared itself as good.
     */
    const val UNKNOWN = 75

    /**
     * A score for sorting, higher is better. Combines the release tier with a small resolution
     * bonus, so 1080p WEB-DL beats 720p WEB-DL but never beats a BluRay.
     */
    fun score(vararg text: String?): Int {
        val haystack = text.filterNotNull().joinToString(" ")
        if (haystack.isBlank()) return UNKNOWN
        val tier = TIERS.lastOrNull { it.second.containsMatchIn(haystack) }?.first ?: UNKNOWN
        val resolution = RESOLUTIONS.firstOrNull { it.second.containsMatchIn(haystack) }?.first ?: 0
        return tier + resolution
    }

    /** Human-readable tier name for the picker, or null when nothing was recognised. */
    fun label(vararg text: String?): String? {
        val haystack = text.filterNotNull().joinToString(" ")
        return when (TIERS.lastOrNull { it.second.containsMatchIn(haystack) }?.first) {
            30 -> "CAM"
            40 -> "TELESYNC"
            50 -> "DVD"
            60 -> "HDTV"
            70 -> "WEBRip"
            80 -> "WEB-DL"
            90 -> "BluRay"
            else -> null
        }
    }
}
