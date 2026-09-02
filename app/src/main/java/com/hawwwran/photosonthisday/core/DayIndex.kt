package com.hawwwran.photosonthisday.core

import kotlin.math.abs
import kotlin.math.min

/**
 * The daily cut through the years, as pure logic over a day histogram.
 *
 * The NAS is asked once for "which days hold photos" (see plan 003); everything
 * here then runs on the device with no network, which is what makes the nearest-day
 * fallback instant and available offline.
 */

const val DAYS_IN_LEAP_YEAR = 366

/** Days before each month in a leap year. Index 0 is January. */
private val LEAP_DAYS_BEFORE_MONTH =
    intArrayOf(0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335)

/** A calendar day without a year: what "the same day in other years" means. */
data class MonthDay(val month: Int, val day: Int) {
    init {
        require(month in 1..12) { "month out of range: $month" }
        require(day in 1..31) { "day out of range: $day" }
    }

    /** Position in a *leap* year, 1..366, so 29 February keeps a slot of its own. */
    val leapOrdinal: Int = LEAP_DAYS_BEFORE_MONTH[month - 1] + day
}

/** One day of one year that holds photos, as the Photos timeline reports it. */
data class DayBucket(val year: Int, val monthDay: MonthDay, val itemCount: Int)

/** The day the app shows, and the years it found photos in. */
data class DaySelection(
    val monthDay: MonthDay,
    /** Newest year first. */
    val years: List<DayBucket>,
    /** 0 when today itself has photos; otherwise how far the fallback day is. */
    val daysFromToday: Int,
    /** Whether the fallback day sits before today in the calendar. */
    val inThePast: Boolean,
) {
    val isFallback: Boolean get() = daysFromToday != 0
    val totalItems: Int get() = years.sumOf { it.itemCount }
}

/**
 * Pick the day to show: today across every year that has it, or the calendar day
 * nearest to today that has anything at all.
 *
 * Distance ignores the year and wraps at the year boundary, so on 2 January a
 * 30 December photo is three days away rather than 362. Ties go to the past,
 * because a day that has already happened reads as a memory and a day that has
 * not reads as a bug.
 */
fun selectDay(index: List<DayBucket>, today: MonthDay): DaySelection? {
    val byDay = index.filter { it.itemCount > 0 }.groupBy { it.monthDay }
    val chosen = byDay.keys.minWithOrNull(nearestTo(today)) ?: return null
    return DaySelection(
        monthDay = chosen,
        years = byDay.getValue(chosen).sortedByDescending { it.year },
        daysFromToday = circularDistance(today.leapOrdinal, chosen.leapOrdinal),
        inThePast = daysBackward(today, chosen) <= daysForward(today, chosen),
    )
}

private fun nearestTo(today: MonthDay) = compareBy<MonthDay>(
    { circularDistance(today.leapOrdinal, it.leapOrdinal) },
    { if (daysBackward(today, it) <= daysForward(today, it)) 0 else 1 },
    { it.leapOrdinal }, // only reachable for equidistant days on the same side; keeps the pick stable
)

private fun daysForward(from: MonthDay, to: MonthDay) =
    (to.leapOrdinal - from.leapOrdinal + DAYS_IN_LEAP_YEAR) % DAYS_IN_LEAP_YEAR

private fun daysBackward(from: MonthDay, to: MonthDay) =
    (from.leapOrdinal - to.leapOrdinal + DAYS_IN_LEAP_YEAR) % DAYS_IN_LEAP_YEAR

private fun circularDistance(a: Int, b: Int): Int {
    val direct = abs(a - b)
    return min(direct, DAYS_IN_LEAP_YEAR - direct)
}
