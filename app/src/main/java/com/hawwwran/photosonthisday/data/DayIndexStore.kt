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
    /** Every stored day of both namespaces. Emits again after any [replaceBuckets] or [clear]. */
    fun buckets(): Flow<List<NamespacedDayBucket>>

    /** When the index was last refreshed, or null if it never has been. Distinguishes an empty library from an unfetched one. */
    fun refreshedAt(): Flow<Long?>

    /**
     * Replace the days of every namespace in [byNamespace] wholesale, and stamp [refreshedAt]
     * when it is given (which also clears [needsRefresh]), all in one transaction, so observers
     * see one new histogram rather than a PERSONAL-only one on the way. An upload shifts nothing
     * here: a day changes count or appears.
     */
    suspend fun replaceBuckets(byNamespace: Map<Space, List<DayBucket>>, refreshedAt: Long?)

    /** Set when a day fetch found the histogram out of date; cleared by the next stamped [replaceBuckets]. */
    suspend fun needsRefresh(): Boolean

    suspend fun markNeedsRefresh()

    /** The cached items of one calendar day, both namespaces, newest first. Empty until fetched. */
    fun items(year: Int, monthDay: MonthDay): Flow<List<PhotoItem>>

    /** How many items of one calendar day are cached, both namespaces. */
    suspend fun cachedCount(year: Int, monthDay: MonthDay): Int

    /**
     * Replace the items of one day for every namespace in [byNamespace], in one transaction, so a
     * reopen reflects a changed day exactly and observers see both namespaces land at once. A row
     * already cached under another day is moved, never duplicated.
     */
    suspend fun replaceDayItems(year: Int, monthDay: MonthDay, byNamespace: Map<Space, List<PhotoItem>>)

    /** Decision 006: drop everything so another account cannot be seen through a stale index. */
    suspend fun clear()
}
