package com.hawwwran.photosonthisday.api

import kotlinx.serialization.Serializable

/** The `data` of a `Browse.Timeline` `get` v6 response. Only the fields this app reads. */
@Serializable
data class TimelineData(val section: List<TimelineSection> = emptyList())

@Serializable
data class TimelineSection(val list: List<TimelineDay> = emptyList())

@Serializable
data class TimelineDay(val year: Int, val month: Int, val day: Int, val item_count: Int)
