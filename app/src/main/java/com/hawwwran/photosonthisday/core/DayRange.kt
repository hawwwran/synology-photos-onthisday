package com.hawwwran.photosonthisday.core

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The `start_time`/`end_time` window for one calendar day, in epoch seconds (decision 005).
 *
 * `time` is the camera's wall clock stored as if it were UTC (research U7), so the day's bounds
 * are that date at UTC midnight and one second before the next. No device-zone conversion: a
 * photo belongs to the date it was taken on, which is exactly what the timeline reported.
 *
 * The end is `start + 86399`: `end_time` is inclusive, verified on the second run of 2026-09-02
 * (`start_time = end_time = <one item's time>` returned that item; research U1 update), so the
 * last second of the day is inside the range and the next day's first second is not.
 */
fun dayRangeUtc(year: Int, monthDay: MonthDay): LongRange {
    val start = LocalDate.of(year, monthDay.month, monthDay.day)
        .atStartOfDay(ZoneOffset.UTC)
        .toEpochSecond()
    return start..(start + 86_399)
}
