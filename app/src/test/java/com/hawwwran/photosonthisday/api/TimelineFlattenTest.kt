package com.hawwwran.photosonthisday.api

import com.hawwwran.photosonthisday.core.MonthDay
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineFlattenTest {

    private fun day(y: Int, m: Int, d: Int, n: Int) = TimelineDay(y, m, d, n)

    @Test
    fun `a day split across sections is taken once with its full count`() {
        // The real shape: a big day (2019-05-08, 664) fills several sections, each repeating it.
        val data = TimelineData(
            listOf(
                TimelineSection(listOf(day(2026, 9, 2, 4), day(2026, 9, 1, 7))),
                TimelineSection(listOf(day(2019, 5, 8, 664))),
                TimelineSection(listOf(day(2019, 5, 8, 664))),
                TimelineSection(listOf(day(2019, 5, 8, 664), day(2019, 5, 7, 3))),
            ),
        )

        val flat = TimelineApi.flatten(data)

        assertEquals(listOf(4, 7, 664, 3), flat.map { it.itemCount })
        assertEquals(
            listOf(MonthDay(9, 2), MonthDay(9, 1), MonthDay(5, 8), MonthDay(5, 7)),
            flat.map { it.monthDay },
        )
        assertEquals(678, flat.sumOf { it.itemCount })
    }

    @Test
    fun `29 February and 1 March of the same year are distinct days`() {
        val data = TimelineData(listOf(TimelineSection(listOf(day(2020, 2, 29, 5), day(2020, 3, 1, 2)))))

        val flat = TimelineApi.flatten(data)

        assertEquals(2, flat.size)
        assertEquals(7, flat.sumOf { it.itemCount })
    }

    @Test
    fun `an empty timeline flattens to nothing`() {
        assertEquals(emptyList<Any>(), TimelineApi.flatten(TimelineData()))
    }
}
