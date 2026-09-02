package com.hawwwran.photosonthisday.core

import java.time.LocalDate
import java.time.ZoneId

/**
 * "What is today", in the device's zone. This is the only calendar question answered locally;
 * a photo's day comes from Photos itself (decision 005). 29 February stays itself, because
 * [MonthDay] keeps it a day of its own and only leap years hold it.
 */
fun currentMonthDay(zone: ZoneId = ZoneId.systemDefault()): MonthDay {
    val date = LocalDate.now(zone)
    return MonthDay(date.monthValue, date.dayOfMonth)
}
