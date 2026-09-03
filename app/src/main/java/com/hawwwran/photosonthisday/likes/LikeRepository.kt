package com.hawwwran.photosonthisday.likes

import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.DsmErrorText
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.data.db.LikeDao
import com.hawwwran.photosonthisday.data.db.LikeEntity
import com.hawwwran.photosonthisday.session.AccountDataWiper
import com.hawwwran.photosonthisday.session.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

sealed interface SyncResult {
    /** [skippedKeys] counts file entries whose key was not a `NAMESPACE:id`; they were left out, not persisted. */
    data class Success(val skippedKeys: Int = 0) : SyncResult
    data object SessionExpired : SyncResult
    data class Failed(val message: String) : SyncResult
}

/**
 * Likes, cached in Room for instant offline toggling and reconciled with the NAS file
 * (decision 008). A tap flips the local row at once; sync pulls the file, merges last-writer-wins
 * inside one database transaction, and pushes the result. The account-change wipe clears the
 * local cache only; the file is re-pulled on the next sign-in, which is the whole point of keeping
 * likes on the NAS.
 *
 * Nothing but an [ApiFailure] is expected out of the remote and the file parser, and every one of
 * them is caught here, so a bad file or a proxy page ends in a [SyncResult], never a crash.
 */
class LikeRepository(
    private val dao: LikeDao,
    private val nas: LikesRemote,
    private val folder: suspend () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Called with the sid that met a dead session, so only that session is signed out. */
    private val onSessionExpired: suspend (sid: String) -> Unit = {},
) : AccountDataWiper {

    /**
     * The keys currently liked, for the like indicator and the liked-first sort. A stored row with
     * a namespace that is not a [Space] (an older build persisted file keys unchecked) is skipped,
     * so an install poisoned that way recovers on update.
     */
    val likedKeys: Flow<Set<String>> =
        dao.liked().map { rows -> rows.mapNotNull { row -> spaceOrNull(row.namespace)?.let { likeKey(it, row.unitId) } }.toSet() }

    suspend fun toggle(space: Space, unitId: Int) {
        val current = dao.find(space.name, unitId)?.liked ?: false
        setLiked(space, unitId, !current)
    }

    /** Set a like to an exact value. Idempotent. */
    suspend fun setLiked(space: Space, unitId: Int, liked: Boolean) = setLikedAll(listOf(space to unitId), liked)

    /** The batch form for "like selected": one write, one invalidation. */
    suspend fun setLikedAll(items: List<Pair<Space, Int>>, liked: Boolean) {
        if (items.isEmpty()) return
        val at = now()
        dao.upsertAll(items.map { (space, unitId) -> LikeEntity(namespace = space.name, unitId = unitId, liked = liked, updatedAt = at) })
    }

    private val syncLock = Mutex()
    private val syncRequested = AtomicBoolean(false)
    private var lastResult: SyncResult = SyncResult.Success()

    /**
     * Reconcile with the NAS. Runs are serialized: a call that arrives while one runs marks it
     * dirty and waits, and the running one goes once more, so any number of overlapping requests
     * collapse into at most one follow-up run and every caller gets the result of a run that saw
     * its change.
     */
    suspend fun sync(session: Session): SyncResult {
        syncRequested.set(true)
        return syncLock.withLock {
            while (syncRequested.compareAndSet(true, false)) lastResult = syncOnce(session)
            lastResult
        }
    }

    private suspend fun syncOnce(session: Session): SyncResult {
        val dir = folder()
        return try {
            val remote = nas.pull(session.baseUrl, dir, session.credentials)
            var skipped = 0
            val merged = dao.reconcile { local ->
                LikesMerge.merge(local.mapNotNull(::toState), remote).values.mapNotNull { state ->
                    toEntity(state).also { if (it == null) skipped++ }
                }
            }
            nas.push(session.baseUrl, dir, merged.mapNotNull(::toState), session.credentials)
            SyncResult.Success(skipped)
        } catch (e: ApiFailure.SessionExpired) {
            onSessionExpired(session.credentials.sid)
            SyncResult.SessionExpired
        } catch (e: ApiFailure) {
            SyncResult.Failed(DsmErrorText.forFailure(e))
        }
    }

    override suspend fun wipe() = dao.clear()

    private fun toState(entity: LikeEntity): LikeState? =
        spaceOrNull(entity.namespace)?.let { LikeState(likeKey(it, entity.unitId), entity.liked, entity.updatedAt) }

    private fun toEntity(state: LikeState): LikeEntity? =
        parseLikeKey(state.key)?.let { (space, unitId) -> LikeEntity(space.name, unitId, state.liked, state.updatedAt) }
}
