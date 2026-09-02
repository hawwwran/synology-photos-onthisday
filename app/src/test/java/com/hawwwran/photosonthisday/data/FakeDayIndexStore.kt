package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.core.DayBucket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [DayIndexStore], so the repository is tested as pure JVM logic. */
class FakeDayIndexStore : DayIndexStore {
    private val buckets = MutableStateFlow<List<NamespacedDayBucket>>(emptyList())
    private val refreshedAt = MutableStateFlow<Long?>(null)
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

    override suspend fun clear() {
        buckets.value = emptyList()
        refreshedAt.value = null
        clears++
    }
}
