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
 * The end is `start + 86399`. Whether `end_time` is inclusive was not verified against the NAS;
 * the owner accepted that a photo taken at exactly 23:59:59 may be missed.
 */
fun dayRangeUtc(year: Int, monthDay: MonthDay): LongRange {
    val start = LocalDate.of(year, monthDay.month, monthDay.day)
        .atStartOfDay(ZoneOffset.UTC)
        .toEpochSecond()
    return start..(start + 86_399)
}
