package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.DsmErrorText
import com.hawwwran.photosonthisday.api.ItemApi
import com.hawwwran.photosonthisday.api.PhotoItem
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.api.TimelineApi
import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.DaySelection
import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.core.dayRangeUtc
import com.hawwwran.photosonthisday.core.selectDay
import com.hawwwran.photosonthisday.session.AccountDataWiper
import com.hawwwran.photosonthisday.session.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** What the day screen shows before any photo is fetched (plan 004 fills [DaySelection]). */
sealed interface DayIndexState {
    /** The index has never been fetched and nothing is cached yet. */
    data object Loading : DayIndexState

    /** The index has been fetched and the whole library is empty. Distinct from "nothing today". */
    data object NoPhotos : DayIndexState

    /** A day to show: today across the years, or the nearest day that has anything. */
    data class Ready(val selection: DaySelection) : DayIndexState
}

sealed interface RefreshResult {
    data object Success : RefreshResult
    data object SessionExpired : RefreshResult
    data class Failed(val message: String) : RefreshResult
}

/** The stored histogram, merged across namespaces, and when it was last refreshed (null if never). */
data class DayIndexData(val days: List<DayBucket>, val refreshedAt: Long?)

/**
 * The day index: cache first, network after (decision 005). Selection runs on the stored
 * histogram with no network, which is what makes the nearest-day fallback instant and offline.
 * A refresh replaces each namespace wholesale, so a stale index yields a missing new photo, not
 * a wrong day.
 */
class DayIndexRepository(
    private val store: DayIndexStore,
    private val timelineApi: TimelineApi,
    private val itemApi: ItemApi,
    private val today: () -> MonthDay,
    private val now: () -> Long = System::currentTimeMillis,
    /** Called with the sid that met a dead session, so the app re-prompts for that session only. */
    private val onSessionExpired: suspend (sid: String) -> Unit = {},
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER,
) : AccountDataWiper {

    /** The merged day list and when it was last refreshed, for callers that pick the day themselves. */
    fun observeDays(): Flow<DayIndexData> =
        combine(store.buckets(), store.refreshedAt()) { buckets, refreshedAt ->
            DayIndexData(merge(buckets), refreshedAt)
        }

    /** The day to show, recomputed whenever the stored index changes. */
    fun observe(): Flow<DayIndexState> =
        combine(store.buckets(), store.refreshedAt()) { buckets, refreshedAt ->
            val selection = selectDay(merge(buckets), today())
            when {
                selection != null -> DayIndexState.Ready(selection)
                refreshedAt != null -> DayIndexState.NoPhotos
                else -> DayIndexState.Loading
            }
        }

    /** True when the index has never been fetched or is older than the threshold. */
    suspend fun isStale(): Boolean {
        val refreshedAt = currentRefreshedAt() ?: return true
        return now() - refreshedAt >= staleAfterMillis
    }

    /** Refresh only when stale; the day screen calls this on open (plan 004). */
    suspend fun refreshIfStale(session: Session): RefreshResult =
        if (isStale()) refresh(session) else RefreshResult.Success

    /** The cached photos of one year's day, both namespaces merged, newest first. Cache first. */
    fun observeDay(year: Int, monthDay: MonthDay): Flow<List<PhotoItem>> = store.items(year, monthDay)

    /**
     * Fetch one year's day from both namespaces and cache it. Pages within the day's time range
     * so a day larger than one page is read in slices. A fetched count that disagrees with the
     * histogram is logged and marks the index stale, so the next open refreshes it (decision 005).
     */
    suspend fun fetchDay(session: Session, year: Int, monthDay: MonthDay): RefreshResult {
        val range = dayRangeUtc(year, monthDay)
        try {
            var total = 0
            for (space in Space.entries) {
                val items = ArrayList<PhotoItem>()
                var offset = 0
                while (offset < MAX_ITEMS_PER_DAY) {
                    val page = itemApi.list(session.baseUrl, space, range, offset, PAGE_SIZE, session.credentials)
                    items += page
                    if (page.size < PAGE_SIZE) break
                    offset += PAGE_SIZE
                }
                store.replaceDayItems(space, year, monthDay, items)
                total += items.size
            }
            val expected = expectedCount(year, monthDay)
            if (expected != null && expected != total) {
                IndexLog.dayCountMismatch(year, monthDay, total, expected)
                store.replaceBuckets(emptyMap(), refreshedAt = 0L) // schedule a histogram refresh on the next open
            }
            return RefreshResult.Success
        } catch (e: ApiFailure.SessionExpired) {
            onSessionExpired(session.credentials.sid)
            return RefreshResult.SessionExpired
        } catch (e: ApiFailure) {
            return RefreshResult.Failed(DsmErrorText.forFailure(e))
        }
    }

    private suspend fun expectedCount(year: Int, monthDay: MonthDay): Int? {
        val forDay = store.buckets().first().filter { it.bucket.year == year && it.bucket.monthDay == monthDay }
        return if (forDay.isEmpty()) null else forDay.sumOf { it.bucket.itemCount }
    }

    /**
     * Fetch both namespaces at once and replace the stored index in one write. The item count is
     * fetched too and compared with the flattened total: a mismatch is logged, not fatal, because
     * the histogram is still the best answer available.
     *
     * One namespace failing does not lose the other. A DSM error is a deterministic answer (105 for
     * a space this account may not read), so the index is stamped and shown; a transport or shape
     * failure leaves the stamp alone so the next open tries again. A dead session stops everything
     * and re-prompts.
     */
    suspend fun refresh(session: Session): RefreshResult = coroutineScope {
        val outcomes = Space.entries.map { space -> async { fetchHistogram(session, space) } }.awaitAll()
        val failures = outcomes.filterIsInstance<HistogramOutcome.Failed>().map { it.failure }
        if (failures.any { it is ApiFailure.SessionExpired }) {
            onSessionExpired(session.credentials.sid)
            return@coroutineScope RefreshResult.SessionExpired
        }
        val fetched = outcomes.filterIsInstance<HistogramOutcome.Fetched>().associate { it.space to it.days }
        val stamp = fetched.isNotEmpty() && failures.all { it is ApiFailure.DsmError }
        store.replaceBuckets(fetched, refreshedAt = if (stamp) now() else null)
        failures.firstOrNull()?.let { return@coroutineScope RefreshResult.Failed(DsmErrorText.forFailure(it)) }
        RefreshResult.Success
    }

    private sealed interface HistogramOutcome {
        data class Fetched(val space: Space, val days: List<DayBucket>) : HistogramOutcome
        data class Failed(val failure: ApiFailure) : HistogramOutcome
    }

    private suspend fun fetchHistogram(session: Session, space: Space): HistogramOutcome = try {
        val days = timelineApi.fetch(session.baseUrl, space, session.credentials)
        val reported = itemApi.count(session.baseUrl, space, session.credentials)
        val summed = days.sumOf { it.itemCount }
        if (summed != reported) IndexLog.countMismatch(space, summed, reported)
        HistogramOutcome.Fetched(space, days)
    } catch (e: ApiFailure) {
        HistogramOutcome.Failed(e)
    }

    /** Decision 006, called by [com.hawwwran.photosonthisday.session.SessionManager] on account change. */
    override suspend fun wipe() = store.clear()

    private suspend fun currentRefreshedAt(): Long? = store.refreshedAt().first()

    /** Merge namespaces: the same calendar day in both spaces becomes one bucket for selection. */
    private fun merge(buckets: List<NamespacedDayBucket>): List<DayBucket> =
        buckets.groupBy { it.bucket.year to it.bucket.monthDay }
            .map { (key, group) -> DayBucket(key.first, key.second, group.sumOf { it.bucket.itemCount }) }

    companion object {
        /** Twelve hours: a household adds photos across a day, not by the minute. */
        const val DEFAULT_STALE_AFTER = 12 * 60 * 60 * 1000L

        /** A page of the item list; a day is read in slices of this size. */
        const val PAGE_SIZE = 200

        /** A stop, so a wrong range can never page forever. The largest observed day is ~1,220. */
        const val MAX_ITEMS_PER_DAY = 20_000
    }
}
