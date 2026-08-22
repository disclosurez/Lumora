package com.lumora.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * daysUntilAirDate's day arithmetic.
 *
 * The count must be of calendar days, not 24-hour periods: the US spring-forward night is 23
 * hours long, so subtracting millis and dividing-truncating dropped a day from any gap that
 * spans it (2026-03-07 -> 2026-03-14 computed 6, "tomorrow" on transition night computed 0).
 * Both ends are built explicitly in America/New_York - the zone whose transition exposes the
 * bug - rather than depending on whatever zone the machine running the test sits in.
 */
class EpisodeReleaseDaysUntilAirTest {

    private val newYork: TimeZone = TimeZone.getTimeZone("America/New_York")

    /** A moment on the given local date in New York. Noon, to prove the time of day plays no part. */
    private fun nyDay(year: Int, month: Int, day: Int): Calendar =
        Calendar.getInstance(newYork).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }

    @Test
    fun `normal gap counts whole days`() {
        // Saturday to the following Friday - no transition in between.
        assertEquals(5, daysUntilAirDate("2026-03-06", nyDay(2026, 3, 1)))
    }

    @Test
    fun `gap spanning the US spring-forward still counts seven`() {
        // 2026-03-08 is the 23-hour day (DST starts 02:00 ET). The old millis division
        // truncated this very gap to 6.
        assertEquals(7, daysUntilAirDate("2026-03-14", nyDay(2026, 3, 7)))
    }

    @Test
    fun `same date is zero`() {
        assertEquals(0, daysUntilAirDate("2026-03-10", nyDay(2026, 3, 10)))
    }

    @Test
    fun `tomorrow on the transition weekend is one`() {
        // Air date IS the spring-forward day: only 23 hours of wall-clock separate it from
        // today, which used to truncate down to 0.
        assertEquals(1, daysUntilAirDate("2026-03-08", nyDay(2026, 3, 7)))
    }

    @Test
    fun `past dates are negative`() {
        assertEquals(-3, daysUntilAirDate("2026-03-04", nyDay(2026, 3, 7)))
    }

    @Test
    fun `unparseable or blank fields give null`() {
        assertNull(daysUntilAirDate(null))
        assertNull(daysUntilAirDate(""))
        assertNull(daysUntilAirDate("   "))
        assertNull(daysUntilAirDate("coming soon"))
    }
}
