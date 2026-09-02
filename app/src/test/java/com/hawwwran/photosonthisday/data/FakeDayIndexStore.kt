package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.PhotoItem
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.MonthDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [DayIndexStore], so the repository is tested as pure JVM logic. */
class FakeDayIndexStore : DayIndexStore {
    private val buckets = MutableStateFlow<List<NamespacedDayBucket>>(emptyList())
    private val refreshedAt = MutableStateFlow<Long?>(null)
    private val dayItems = MutableStateFlow<List<Pair<Triple<Int, Int, Int>, PhotoItem>>>(emptyList())
    var clears = 0
        private set

    override fun buckets(): Flow<List<NamespacedDayBucket>> = buckets
    override fun refreshedAt(): Flow<Long?> = refreshedAt

    override suspend fun replace(space: Space, days: List<DayBucket>) {
        buckets.value = buckets.value.filterNot { it.space == space } +
            days.map { NamespacedDayBucket(space, it) }
    }

    override suspend fun setRefreshedAt(epochMillis: Long) {
        refreshedAt.value = epochMillis
    }

    override fun items(year: Int, monthDay: MonthDay): Flow<List<PhotoItem>> =
        kotlinx.coroutines.flow.MutableStateFlow(
            dayItems.value
                .filter { it.first == Triple(year, monthDay.month, monthDay.day) }
                .map { it.second }
                .sortedByDescending { it.takenTimeSeconds },
        )

    override suspend fun replaceDayItems(space: Space, year: Int, monthDay: MonthDay, items: List<PhotoItem>) {
        val key = Triple(year, monthDay.month, monthDay.day)
        dayItems.value = dayItems.value.filterNot { it.first == key && it.second.space == space } +
            items.map { key to it }
    }

    override suspend fun clear() {
        buckets.value = emptyList()
        refreshedAt.value = null
        dayItems.value = emptyList()
        clears++
    }
}
