package com.lumora.data.parser

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.TimeZone

/**
 * XMLTV EPG parser.
 * Parses standard XMLTV format for program guide data.
 * Handles multiple channels and their program listings.
 */
object XmltvParser {

    private const val MAX_CHANNELS = 10_000
    private const val MAX_PROGRAMMES = 50_000
    private const val TAG = "XmltvParser"

    data class XmltvChannel(
        val id: String,
        val displayName: String,
        val icon: String? = null
    )

    data class XmltvProgramme(
        val channel: String,
        val title: String,
        val subTitle: String? = null,
        val description: String? = null,
        val startTimestamp: Long,
        val stopTimestamp: Long,
        val category: String? = null,
        val episodeNumber: String? = null,
        val icon: String? = null,
        val date: String? = null,
        val starRating: String? = null,
        val credits: List<Pair<String, String>> = emptyList()
    )

    data class XmltvResult(
        val channels: List<XmltvChannel>,
        val programmes: List<XmltvProgramme>
    )

    /**
     * Parse an XMLTV feed from an input stream.
     */
    fun parse(inputStream: InputStream): XmltvResult {
        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<XmltvProgramme>()

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            var displayName = ""
                            var icon: String? = null
                            var done = false

                            while (!done) {
                                when (parser.next()) {
                                    // Truncated/closed feed: stop spinning instead of looping
                                    // forever on END_DOCUMENT with a null name.
                                    XmlPullParser.END_DOCUMENT -> done = true
                                    XmlPullParser.START_TAG -> {
                                        when (parser.name) {
                                            "display-name" -> {
                                                parser.next()
                                                displayName = parser.text ?: ""
                                            }
                                            "icon" -> {
                                                icon = parser.getAttributeValue(null, "src")
                                            }
                                        }
                                    }
                                    XmlPullParser.END_TAG -> {
                                        if (parser.name == "channel") done = true
                                    }
                                }
                            }
                            if (channels.size < MAX_CHANNELS) {
                                channels.add(XmltvChannel(id, displayName, icon))
                            } else {
                                Log.w(TAG, "Reached max channel limit ($MAX_CHANNELS), truncating")
                            }
                        }
                        "programme" -> {
                            val channel = parser.getAttributeValue(null, "channel") ?: ""
                            val start = parser.getAttributeValue(null, "start") ?: ""
                            val stop = parser.getAttributeValue(null, "stop") ?: ""
                            var title = ""
                            var subTitle: String? = null
                            var desc: String? = null
                            var category: String? = null
                            var episodeNum: String? = null
                            var icon: String? = null
                            var date: String? = null
                            var rating: String? = null
                            val credits = mutableListOf<Pair<String, String>>()
                            var done = false

                            while (!done) {
                                when (parser.next()) {
                                    // Truncated/closed feed: stop spinning instead of looping
                                    // forever on END_DOCUMENT with a null name.
                                    XmlPullParser.END_DOCUMENT -> done = true
                                    XmlPullParser.START_TAG -> {
                                        when (parser.name) {
                                            "title" -> {
                                                parser.next()
                                                title = parser.text ?: ""
                                            }
                                            "sub-title" -> {
                                                parser.next()
                                                subTitle = parser.text
                                            }
                                            "desc" -> {
                                                parser.next()
                                                desc = parser.text
                                            }
                                            "category" -> {
                                                parser.next()
                                                category = parser.text
                                            }
                                            "episode-num" -> {
                                                parser.next()
                                                episodeNum = parser.text
                                            }
                                            "icon" -> {
                                                icon = parser.getAttributeValue(null, "src")
                                            }
                                            "date" -> {
                                                parser.next()
                                                date = parser.text
                                            }
                                            "star-rating" -> {
                                                // Parse value child using depth tracking
                                                var depth = 1
                                                while (depth > 0) {
                                                    when (parser.next()) {
                                                        // Truncated/closed feed: bail out of the
                                                        // depth walk instead of spinning on
                                                        // END_DOCUMENT forever.
                                                        XmlPullParser.END_DOCUMENT -> break
                                                        XmlPullParser.START_TAG -> {
                                                            if (parser.name == "value") {
                                                                if (parser.next() == XmlPullParser.TEXT) {
                                                                    rating = parser.text
                                                                }
                                                            }
                                                            depth++
                                                        }
                                                        XmlPullParser.END_TAG -> depth--
                                                    }
                                                }
                                            }
                                            "actor" -> {
                                                // Parse using depth tracking to handle nested elements
                                                var depth = 1
                                                while (depth > 0) {
                                                    when (parser.next()) {
                                                        XmlPullParser.END_DOCUMENT -> break
                                                        XmlPullParser.START_TAG -> {
                                                            depth++
                                                        }
                                                        XmlPullParser.TEXT -> {
                                                            credits.add("actor" to parser.text)
                                                        }
                                                        XmlPullParser.END_TAG -> depth--
                                                    }
                                                }
                                            }
                                            "director" -> {
                                                var depth = 1
                                                while (depth > 0) {
                                                    when (parser.next()) {
                                                        XmlPullParser.END_DOCUMENT -> break
                                                        XmlPullParser.START_TAG -> depth++
                                                        XmlPullParser.TEXT -> {
                                                            credits.add("director" to parser.text)
                                                        }
                                                        XmlPullParser.END_TAG -> depth--
                                                    }
                                                }
                                            }
                                            "presenter" -> {
                                                var depth = 1
                                                while (depth > 0) {
                                                    when (parser.next()) {
                                                        XmlPullParser.END_DOCUMENT -> break
                                                        XmlPullParser.START_TAG -> depth++
                                                        XmlPullParser.TEXT -> {
                                                            credits.add("presenter" to parser.text)
                                                        }
                                                        XmlPullParser.END_TAG -> depth--
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    XmlPullParser.END_TAG -> {
                                        if (parser.name == "programme") done = true
                                    }
                                }
                            }

                            if (programmes.size < MAX_PROGRAMMES) {
                                programmes.add(
                                    XmltvProgramme(
                                        channel = channel,
                                        title = title,
                                        subTitle = subTitle,
                                        description = desc,
                                        startTimestamp = parseXmltvDate(start),
                                        stopTimestamp = parseXmltvDate(stop),
                                        category = category,
                                        episodeNumber = episodeNum,
                                        icon = icon,
                                        date = date,
                                        starRating = rating,
                                        credits = credits
                                    )
                                )
                            } else {
                                Log.w(TAG, "Reached max programme limit ($MAX_PROGRAMMES), truncating")
                            }
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        return XmltvResult(channels, programmes)
    }

    /**
     * Parses an XMLTV timestamp to unix seconds, or 0 if it isn't one.
     *
     * Hand-parsed rather than run through [java.text.SimpleDateFormat]. This is called twice
     * per programme - up to 2 * [MAX_PROGRAMMES] times for a single source - and the previous
     * implementation compiled a [Regex] and constructed up to five SimpleDateFormats (each of
     * which builds a Calendar and loads DateFormatSymbols) on every one of those calls, then
     * threw and logged a ParseException for each format that didn't match. On a slow device
     * that was the dominant cost of an EPG sync. The arithmetic below allocates nothing on the
     * common path.
     *
     * The format is `YYYYMMDDHHMMSS ±HHMM`, where any trailing component may be omitted and
     * the offset may instead be a zone name or absent entirely (meaning local time). `±HH:MM`
     * is not XMLTV but real feeds emit it, so it stays accepted - that is what the old regex
     * pass existed to normalise.
     *
     * Stricter than the old path in one way: a nonsense field (month 13, hour 25) now returns
     * 0 instead of being rolled forward by a lenient Calendar into a plausible-looking wrong
     * date.
     *
     * `internal` only so XmltvDateParsingTest can reach it. [parse] itself is not callable
     * from a JVM unit test - `XmlPullParserFactory` is one of the stubbed-out classes in the
     * mockable android.jar - so this is the seam the date handling can actually be pinned at.
     */
    internal fun parseXmltvDate(dateStr: String): Long {
        val s = dateStr.trim()
        if (s.isEmpty()) return 0L

        var end = 0
        while (end < s.length && s[end] in '0'..'9') end++
        // Below yyyyMMdd there is no day to place the programme on, so there is nothing the
        // guide could do with the result.
        if (end < 8) return 0L
        val n = if (end > 14) 14 else end

        val year = s.digitsAt(0, 4)
        val month = s.digitsAt(4, 6)
        val day = s.digitsAt(6, 8)
        val hour = if (n >= 10) s.digitsAt(8, 10) else 0
        val minute = if (n >= 12) s.digitsAt(10, 12) else 0
        val second = if (n >= 14) s.digitsAt(12, 14) else 0
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 59) return 0L

        // Seconds the reading would be at if the wall clock were UTC; the zone suffix then
        // shifts it onto the real instant.
        val wallSeconds =
            daysFromCivil(year, month, day) * 86_400L + hour * 3600L + minute * 60L + second

        var i = end
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        // No zone at all: XMLTV says the stamp is local time, which is also what the old bare
        // "yyyyMMddHHmmss" pattern did with it.
        if (i >= s.length) return wallSeconds - zoneOffsetSeconds(TimeZone.getDefault(), wallSeconds)

        val sign = when (s[i]) {
            '+' -> 1
            '-' -> -1
            else -> 0
        }
        if (sign != 0) {
            i++
            if (i + 2 > s.length) return 0L
            val offsetHours = s.digitsAtOrNull(i, i + 2) ?: return 0L
            i += 2
            if (i < s.length && s[i] == ':') i++
            val offsetMinutes = if (i + 2 <= s.length) s.digitsAtOrNull(i, i + 2) ?: 0 else 0
            return wallSeconds - sign * (offsetHours * 3600L + offsetMinutes * 60L)
        }

        // A zone name. TimeZone.getTimeZone silently answers GMT for anything it doesn't
        // recognise, which would be a real (wrong) timestamp rather than a rejection - so only
        // trust an id the platform echoes back, and otherwise read the stamp as local time,
        // which is where the old format list fell through to as well.
        val id = s.substring(i)
        val tz = TimeZone.getTimeZone(id)
        val resolved = if (tz.id == id) tz else TimeZone.getDefault()
        return wallSeconds - zoneOffsetSeconds(resolved, wallSeconds)
    }

    /**
     * The offset [tz] is at for a wall-clock reading of [wallSeconds].
     *
     * [TimeZone.getOffset] wants the instant, which isn't known until the offset is - so probe
     * with the reading treated as UTC and correct once. Exact except for wall-clock times that
     * a DST transition makes ambiguous or nonexistent, where every answer is arbitrary and
     * Calendar's was too.
     */
    private fun zoneOffsetSeconds(tz: TimeZone, wallSeconds: Long): Long {
        val probe = tz.getOffset(wallSeconds * 1000L).toLong()
        return tz.getOffset(wallSeconds * 1000L - probe) / 1000L
    }

    /** Days from 1970-01-01 to the given proleptic Gregorian date (Howard Hinnant's
     *  days_from_civil): integer-only, so no Calendar/TimeZone work on the common path. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400                                              // [0, 399]
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    /** Digits in `[from, to)`, which the caller has already established are digits. */
    private fun String.digitsAt(from: Int, to: Int): Int {
        var value = 0
        for (k in from until to) value = value * 10 + (this[k] - '0')
        return value
    }

    /** [digitsAt] for a range that has not been checked yet - null if it isn't all digits. */
    private fun String.digitsAtOrNull(from: Int, to: Int): Int? {
        if (to > length) return null
        var value = 0
        for (k in from until to) {
            val c = this[k]
            if (c !in '0'..'9') return null
            value = value * 10 + (c - '0')
        }
        return value
    }
}
