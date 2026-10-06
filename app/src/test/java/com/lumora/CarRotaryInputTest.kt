package com.lumora

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure mapping tests for [carRotaryDirection] - no Android framework types needed. */
class CarRotaryInputTest {

    @Test
    fun positiveScrollIsNext() {
        assertEquals(1, carRotaryDirection(1f, 0f, 0f))
    }

    @Test
    fun negativeScrollIsPrevious() {
        assertEquals(-1, carRotaryDirection(-1f, 0f, 0f))
    }

    @Test
    fun positiveVscrollFallsBackToNext() {
        assertEquals(1, carRotaryDirection(0f, 1f, 0f))
    }

    @Test
    fun negativeVscrollFallsBackToPrevious() {
        assertEquals(-1, carRotaryDirection(0f, -1f, 0f))
    }

    @Test
    fun positiveHscrollFallsBackToNext() {
        assertEquals(1, carRotaryDirection(0f, 0f, 1f))
    }

    @Test
    fun negativeHscrollFallsBackToPrevious() {
        assertEquals(-1, carRotaryDirection(0f, 0f, -1f))
    }

    @Test
    fun allZeroIsNoDirection() {
        assertEquals(0, carRotaryDirection(0f, 0f, 0f))
    }

    @Test
    fun nonzeroScrollWinsOverVerticalAndHorizontal() {
        assertEquals(1, carRotaryDirection(1f, -1f, -1f))
        assertEquals(-1, carRotaryDirection(-1f, 1f, 1f))
    }

    @Test
    fun zeroScrollDefersToVerticalOverHorizontal() {
        assertEquals(1, carRotaryDirection(0f, 1f, -1f))
        assertEquals(-1, carRotaryDirection(0f, -1f, 1f))
    }
}
