package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.PhotoItem
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.MonthDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [DayIndexStore], so the repository is tested as pure JVM logic. Item rows are keyed by
 * `(namespace, id)` exactly as the Room table is, and a write replaces a row under that key the
 * way the DAO's upsert does, so a photo that moved days behaves here as it does on the device.
 */
class FakeDayIndexStore : DayIndexStore {
    private val buckets = MutableStateFlow<List<NamespacedDayBucket>>(emptyList())
    private val refreshedAt = MutableStateFlow<Long?>(null)
    private var needsRefresh = false
    private val rows = MutableStateFlow<Map<Pair<Space, Int>, Pair<Triple<Int, Int, Int>, PhotoItem>>>(emptyMap())
    var clears = 0
        private set

    /** How many times [replaceBuckets] ran, so a test can assert one write per refresh. */
    var bucketWrites = 0
        private set

    /** How many times [replaceDayItems] ran. */
    var dayWrites = 0
        private set

    /** How many times [markNeedsRefresh] ran. */
    var needsRefreshMarks = 0
        private set

    override fun buckets(): Flow<List<NamespacedDayBucket>> = buckets
    override fun refreshedAt(): Flow<Long?> = refreshedAt

    override suspend fun replaceBuckets(byNamespace: Map<Space, List<DayBucket>>, refreshedAt: Long?) {
        bucketWrites++
        buckets.value = buckets.value.filterNot { it.space in byNamespace } +
            byNamespace.flatMap { (space, days) -> days.map { NamespacedDayBucket(space, it) } }
        if (refreshedAt != null) {
            this.refreshedAt.value = refreshedAt
            needsRefresh = false
        }
    }

    override suspend fun needsRefresh(): Boolean = needsRefresh

    override suspend fun markNeedsRefresh() {
        needsRefreshMarks++
        if (refreshedAt.value != null) needsRefresh = true
    }

    /** Test setup: seed one namespace without counting it as a write. */
    fun seed(space: Space, days: List<DayBucket>) {
        buckets.value = buckets.value.filterNot { it.space == space } + days.map { NamespacedDayBucket(space, it) }
    }

    /** Test setup: pretend the index was refreshed at [epochMillis]. */
    fun seedRefreshedAt(epochMillis: Long) {
        refreshedAt.value = epochMillis
    }

    override fun items(year: Int, monthDay: MonthDay): Flow<List<PhotoItem>> =
        rows.map { all -> itemsOf(all, year, monthDay) }

    override suspend fun cachedCount(year: Int, monthDay: MonthDay): Int = itemsOf(rows.value, year, monthDay).size

    private fun itemsOf(all: Map<Pair<Space, Int>, Pair<Triple<Int, Int, Int>, PhotoItem>>, year: Int, monthDay: MonthDay) =
        all.values
            .filter { it.first == Triple(year, monthDay.month, monthDay.day) }
            .map { it.second }
            .sortedWith(compareByDescending<PhotoItem> { it.takenTimeSeconds }.thenByDescending { it.id })

    override suspend fun replaceDayItems(year: Int, monthDay: MonthDay, byNamespace: Map<Space, List<PhotoItem>>) {
        dayWrites++
        val day = Triple(year, monthDay.month, monthDay.day)
        val kept = rows.value.filterNot { (key, value) -> key.first in byNamespace && value.first == day }.toMutableMap()
        byNamespace.forEach { (space, items) ->
            items.forEach { item -> kept[space to item.id] = day to item }
        }
        rows.value = kept
    }

    override suspend fun clear() {
        buckets.value = emptyList()
        refreshedAt.value = null
        needsRefresh = false
        rows.value = emptyMap()
        clears++
    }
}
