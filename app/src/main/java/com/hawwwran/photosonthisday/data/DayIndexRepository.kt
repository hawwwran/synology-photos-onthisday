package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.DsmErrorText
import com.hawwwran.photosonthisday.api.ItemApi
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.api.TimelineApi
import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.DaySelection
import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.core.selectDay
import com.hawwwran.photosonthisday.session.AccountDataWiper
import com.hawwwran.photosonthisday.session.Session
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
    /** Called when a refresh meets a dead session, so the app re-prompts (closes plan 002's last box). */
    private val onSessionExpired: suspend () -> Unit = {},
    private val staleAfterMillis: Long = DEFAULT_STALE_AFTER,
) : AccountDataWiper {

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

    /**
     * Fetch both namespaces and replace the stored index. The item count is fetched too and
     * compared with the flattened total: a mismatch is logged, not fatal, and does not block the
     * refresh, because the histogram is still the best answer available.
     */
    suspend fun refresh(session: Session): RefreshResult {
        try {
            for (space in Space.entries) {
                val days = timelineApi.fetch(session.baseUrl, space, session.credentials)
                val reported = itemApi.count(session.baseUrl, space, session.credentials)
                val summed = days.sumOf { it.itemCount }
                if (summed != reported) IndexLog.countMismatch(space, summed, reported)
                store.replace(space, days)
            }
            store.setRefreshedAt(now())
            return RefreshResult.Success
        } catch (e: ApiFailure.SessionExpired) {
            onSessionExpired()
            return RefreshResult.SessionExpired
        } catch (e: ApiFailure) {
            return RefreshResult.Failed(DsmErrorText.forFailure(e))
        }
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
    }
}
