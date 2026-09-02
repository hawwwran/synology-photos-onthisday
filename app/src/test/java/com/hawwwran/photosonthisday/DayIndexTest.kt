package com.hawwwran.photosonthisday

import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.core.selectDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayIndexTest {

    private fun bucket(year: Int, month: Int, day: Int, count: Int = 1) =
        DayBucket(year, MonthDay(month, day), count)

    @Test
    fun `collects every year of today and reports no fallback`() {
        val index = listOf(
            bucket(2019, 9, 2, count = 12),
            bucket(2024, 9, 2, count = 3),
            bucket(2024, 9, 3, count = 99),
        )

        val selection = selectDay(index, MonthDay(9, 2))!!

        assertEquals(MonthDay(9, 2), selection.monthDay)
        assertEquals(listOf(2024, 2019), selection.years.map { it.year })
        assertEquals(15, selection.totalItems)
        assertFalse(selection.isFallback)
    }

    @Test
    fun `falls back to the nearest day and says how far it is`() {
        val selection = selectDay(listOf(bucket(2021, 8, 30)), MonthDay(9, 2))!!

        assertEquals(MonthDay(8, 30), selection.monthDay)
        assertEquals(3, selection.daysFromToday)
        assertTrue(selection.inThePast)
    }

    @Test
    fun `an equidistant past day beats an equidistant future one`() {
        val index = listOf(bucket(2021, 8, 30), bucket(2022, 9, 5))

        val selection = selectDay(index, MonthDay(9, 2))!!

        assertEquals(MonthDay(8, 30), selection.monthDay)
        assertTrue(selection.inThePast)
    }

    @Test
    fun `distance wraps at the year boundary`() {
        val selection = selectDay(listOf(bucket(2020, 12, 30)), MonthDay(1, 2))!!

        assertEquals(3, selection.daysFromToday)
        assertTrue(selection.inThePast)
    }

    @Test
    fun `29 February is its own day and only leap years hold it`() {
        val index = listOf(bucket(2020, 2, 29), bucket(2021, 2, 28))

        val selection = selectDay(index, MonthDay(2, 29))!!

        assertEquals(listOf(2020), selection.years.map { it.year })
        assertFalse(selection.isFallback)
    }

    @Test
    fun `empty days are not candidates`() {
        assertNull(selectDay(listOf(bucket(2020, 5, 5, count = 0)), MonthDay(9, 2)))
        assertNull(selectDay(emptyList(), MonthDay(9, 2)))
    }
}
