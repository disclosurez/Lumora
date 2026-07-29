package com.lumora.data.parser

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

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
        val programmes: List<XmltvProgramme>,
        val sourceEtag: String? = null,
        val sourceLastModified: String? = null
    )

    private val dateFormats = listOf(
        "yyyyMMddHHmmss Z",
        "yyyyMMddHHmm Z",
        "yyyyMMddHHmmss z",
        "yyyyMMddHHmmss",
        "yyyyMMddHHmm"
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
        var currentTag = ""

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when (currentTag) {
                        "channel" -> {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            var displayName = ""
                            var icon: String? = null
                            var done = false

                            while (!done) {
                                when (parser.next()) {
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

    private fun parseXmltvDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        // Remove timezone colon: "20260101120000 +0100" -> "20260101120000 +0100"
        val clean = dateStr
            .replace(Regex("""(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})\s*([+-]\d{2}):?(\d{2})""")) { match ->
                val (y, m, d, h, min, s, tzH, tzM) = match.destructured
                "$y$m${d}${h}$min$s $tzH$tzM"
            }
            .trim()

        for (fmtStr in dateFormats) {
            try {
                val fmt = SimpleDateFormat(fmtStr, Locale.US)
                return fmt.parse(clean)?.time?.div(1000) ?: continue
            } catch (e: Exception) {
                Log.w(TAG, "Date parse failed for format $fmtStr: ${e.message}")
            }
        }
        return 0L
    }
}
