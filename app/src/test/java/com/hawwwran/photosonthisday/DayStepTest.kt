package com.hawwwran.photosonthisday

import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.core.czech
import com.hawwwran.photosonthisday.core.monthDayFromLeapOrdinal
import com.hawwwran.photosonthisday.core.nextDay
import com.hawwwran.photosonthisday.core.previousDay
import org.junit.Assert.assertEquals
import org.junit.Test

class DayStepTest {

    @Test
    fun `leap ordinal round-trips, 29 February included`() {
        for (ordinal in 1..366) {
            assertEquals(ordinal, monthDayFromLeapOrdinal(ordinal).leapOrdinal)
        }
        assertEquals(MonthDay(2, 29), monthDayFromLeapOrdinal(60))
        assertEquals(MonthDay(3, 1), monthDayFromLeapOrdinal(61))
    }

    @Test
    fun `next day steps through 28 Feb, 29 Feb, 1 March`() {
        assertEquals(MonthDay(2, 29), MonthDay(2, 28).nextDay())
        assertEquals(MonthDay(3, 1), MonthDay(2, 29).nextDay())
    }

    @Test
    fun `next and previous wrap at the year boundary`() {
        assertEquals(MonthDay(1, 1), MonthDay(12, 31).nextDay())
        assertEquals(MonthDay(12, 31), MonthDay(1, 1).previousDay())
    }

    @Test
    fun `previous is the inverse of next everywhere`() {
        for (ordinal in 1..366) {
            val day = monthDayFromLeapOrdinal(ordinal)
            assertEquals(day, day.nextDay().previousDay())
        }
    }

    @Test
    fun `czech format is the day number and the genitive month`() {
        assertEquals("9. září", MonthDay(9, 2 + 7).czech())
        assertEquals("1. ledna", MonthDay(1, 1).czech())
        assertEquals("29. února", MonthDay(2, 29).czech())
        assertEquals("31. prosince", MonthDay(12, 31).czech())
    }
}
