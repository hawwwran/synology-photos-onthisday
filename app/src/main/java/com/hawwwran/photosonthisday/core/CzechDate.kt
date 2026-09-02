package com.hawwwran.photosonthisday.core

/**
 * The day formatted the Czech way: the day number, a dot, and the month in the genitive
 * ("9. září", "1. ledna"). Hardcoded rather than locale-derived so the form is deterministic
 * and testable, and does not shift with the platform's CLDR data.
 */
private val CZECH_MONTHS_GENITIVE = arrayOf(
    "ledna", "února", "března", "dubna", "května", "června",
    "července", "srpna", "září", "října", "listopadu", "prosince",
)

fun MonthDay.czech(): String = "$day. ${CZECH_MONTHS_GENITIVE[month - 1]}"
