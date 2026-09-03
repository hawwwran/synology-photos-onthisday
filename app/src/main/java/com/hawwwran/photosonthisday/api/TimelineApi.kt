package com.hawwwran.photosonthisday.api

import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.MonthDay
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl

/**
 * `Browse.Timeline` `get` v6. The response is `data.section[]`, one entry per page of the item
 * list, each carrying the days on that page; a day larger than a page repeats across
 * consecutive sections with its full `item_count` (`documents/research/photos-web-api.md`, U2).
 *
 * [fetch] returns the histogram flattened and deduplicated: each `(year, month, day)` once, its
 * `item_count` intact. Flattened this way it sums to `Browse.Item` `count`, which is the
 * cross-check plan 003 asserts.
 */
class TimelineApi(
    private val client: SynologyClient,
    private val json: Json = AppJson,
) {
    suspend fun fetch(baseUrl: HttpUrl, space: Space, credentials: SessionCredentials): List<DayBucket> {
        val call = Allowlist.timeline(space)
        val data = client.call(baseUrl, call, credentials = credentials)
        return flatten(json.decodeOrMalformed(call, TimelineData.serializer(), data, "timeline"))
    }

    companion object {
        /** Days in response order, first occurrence of each calendar day wins. Pure, so it is tested directly. */
        fun flatten(data: TimelineData): List<DayBucket> {
            val seen = HashSet<Int>()
            val out = ArrayList<DayBucket>()
            for (section in data.section) {
                for (day in section.list) {
                    val monthDay = MonthDay(day.month, day.day)
                    // The key is unique per (year, calendar day); leapOrdinal separates 29 Feb.
                    val key = day.year * 400 + monthDay.leapOrdinal
                    if (seen.add(key)) out += DayBucket(day.year, monthDay, day.item_count)
                }
            }
            return out
        }
    }
}
