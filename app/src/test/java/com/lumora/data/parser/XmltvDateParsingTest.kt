package com.lumora.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

/**
 * XMLTV timestamp parsing.
 *
 * Driven through [XmltvParser.parseXmltvDate] rather than [XmltvParser.parse], because
 * `XmlPullParserFactory` is one of the classes stubbed out in the mockable android.jar and
 * throws "not mocked" the moment a JVM test touches it - so the surrounding pull-parse can't
 * run here at all.
 *
 * These stamps used to go through a list of five [java.text.SimpleDateFormat] patterns tried
 * in order. That was replaced with integer arithmetic for speed (it runs twice per programme,
 * up to 100k times for a single source), which makes the exact forms real feeds emit worth
 * writing down: an offset, an offset with the non-standard colon, a named zone, no zone at
 * all, and the truncated-to-minutes variant.
 */
class XmltvDateParsingTest {

    private fun parse(stamp: String): Long = XmltvParser.parseXmltvDate(stamp)

    /** 2026-01-01T12:00:00Z. */
    private val noonUtc = 1767268800L

    /** What [noonUtc] reads as when a stamp names no zone and is therefore local time. */
    private val noonAsLocalWallClock: Long
        get() = noonUtc - TimeZone.getDefault().getOffset(noonUtc * 1000L) / 1000L

    @Test
    fun `numeric offset is applied`() {
        assertEquals(noonUtc, parse("20260101130000 +0100"))
        assertEquals(noonUtc, parse("20260101110000 -0100"))
        assertEquals(noonUtc, parse("20260101120000 +0000"))
    }

    @Test
    fun `offset with a colon is accepted`() {
        // Not XMLTV, but real feeds emit it - normalising it away is the whole reason the
        // regex pass that the hand parser replaced existed.
        assertEquals(noonUtc, parse("20260101130000 +01:00"))
    }

    @Test
    fun `half hour offset is applied`() {
        assertEquals(noonUtc, parse("20260101173000 +0530"))
    }

    @Test
    fun `seconds may be omitted`() {
        assertEquals(noonUtc, parse("202601011300 +0100"))
    }

    @Test
    fun `no zone reads as local time`() {
        assertEquals(noonAsLocalWallClock, parse("20260101120000"))
    }

    @Test
    fun `named zone is resolved`() {
        assertEquals(noonUtc, parse("20260101130000 CET"))
    }

    @Test
    fun `unknown zone name falls back to local time rather than GMT`() {
        // TimeZone.getTimeZone answers GMT for anything it doesn't know, which would be a
        // confidently wrong instant; local time is where the old format list fell through to.
        assertEquals(noonAsLocalWallClock, parse("20260101120000 NOTAZONE"))
    }

    @Test
    fun `leap day is placed correctly`() {
        // 2024-02-29T00:00:00Z - the obvious edge of the civil-date arithmetic.
        assertEquals(1709164800L, parse("20240229000000 +0000"))
    }

    @Test
    fun `dates before the epoch parse negative`() {
        // 1969-12-31T00:00:00Z. Nothing schedules programmes here, but the era arithmetic
        // branches on the sign and a wrong branch would be silent.
        assertEquals(-86400L, parse("19691231000000 +0000"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(noonUtc, parse("  20260101130000 +0100  "))
    }

    @Test
    fun `unparseable stamps yield zero`() {
        assertEquals(0L, parse(""))
        assertEquals(0L, parse("   "))
        assertEquals(0L, parse("not a date"))
        assertEquals(0L, parse("2026"))                   // no day to place it on
        assertEquals(0L, parse("20261301000000 +0000"))   // month 13
        assertEquals(0L, parse("20260101250000 +0000"))   // hour 25
        assertEquals(0L, parse("20260101120000 +"))       // truncated offset
    }
}
