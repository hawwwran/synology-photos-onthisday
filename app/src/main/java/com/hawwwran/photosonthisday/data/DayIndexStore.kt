package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.PhotoItem
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.MonthDay
import kotlinx.coroutines.flow.Flow

/** One day of one namespace, as stored: the namespace is kept so plan 004 knows where to fetch. */
data class NamespacedDayBucket(val space: Space, val bucket: DayBucket)

/**
 * Where the histogram lives. Room implements it on the device; a fake implements it in tests,
 * which keeps [DayIndexRepository] pure JVM logic. Not scoped by account: one account per
 * install, and [clear] wipes on a change (decision 006), so no account column is needed.
 */
interface DayIndexStore {
    /** Every stored day of both namespaces. Emits again after any [replace] or [clear]. */
    fun buckets(): Flow<List<NamespacedDayBucket>>

    /** When the index was last refreshed, or null if it never has been. Distinguishes an empty library from an unfetched one. */
    fun refreshedAt(): Flow<Long?>

    /** Replace one namespace's days wholesale: an upload shifts nothing here, a day just changes count or appears. */
    suspend fun replace(space: Space, days: List<DayBucket>)

    suspend fun setRefreshedAt(epochMillis: Long)

    /** The cached items of one calendar day, both namespaces, newest first. Empty until fetched. */
    fun items(year: Int, monthDay: MonthDay): Flow<List<PhotoItem>>

    /** Replace one namespace's items for one day, so a reopen reflects a changed day exactly. */
    suspend fun replaceDayItems(space: Space, year: Int, monthDay: MonthDay, items: List<PhotoItem>)

    /** Decision 006: drop everything so another account cannot be seen through a stale index. */
    suspend fun clear()
}
