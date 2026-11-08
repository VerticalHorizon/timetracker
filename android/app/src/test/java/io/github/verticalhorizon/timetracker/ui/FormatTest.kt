package io.github.verticalhorizon.timetracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun clock() {
        assertEquals("0:00:00", clock(0))
        assertEquals("1:24:10", clock(5050))
        assertEquals("100:00:00", clock(360_000))
    }

    @Test
    fun compactClock() {
        assertEquals("4:12", compactClock(252))
        assertEquals("59:59", compactClock(3599))
        assertEquals("1:30:00", compactClock(5400))
        assertEquals("0:00", compactClock(-5))
    }

    @Test
    fun hoursMinutes() {
        assertEquals("0m", hoursMinutes(59))
        assertEquals("5m", hoursMinutes(300))
        assertEquals("1h 0m", hoursMinutes(3600))
        assertEquals("1h 24m", hoursMinutes(5050))
    }

    @Test
    fun duration() {
        assertEquals("1 min", duration(60))
        assertEquals("30 min", duration(1800))
        assertEquals("1 h 30 min", duration(5400))
        assertEquals("24 h", duration(86_400))
    }
}
