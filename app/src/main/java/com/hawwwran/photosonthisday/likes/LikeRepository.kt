package com.hawwwran.photosonthisday.likes

import android.util.Log
import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.data.db.LikeDao
import com.hawwwran.photosonthisday.data.db.LikeEntity
import com.hawwwran.photosonthisday.session.AccountDataWiper
import com.hawwwran.photosonthisday.session.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

sealed interface SyncResult {
    data object Success : SyncResult
    data object SessionExpired : SyncResult
    data class Failed(val message: String) : SyncResult
}

/**
 * Likes, cached in Room for instant offline toggling and reconciled with the NAS file
 * (decision 008). A tap flips the local row at once; sync pushes local changes and pulls the
 * file, merging last-writer-wins. The account-change wipe clears the local cache only; the file
 * is re-pulled on the next sign-in, which is the whole point of keeping likes on the NAS.
 */
class LikeRepository(
    private val dao: LikeDao,
    private val nas: LikesNasStore,
    private val folder: suspend () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    /** Called with the sid that met a dead session, so only that session is signed out. */
    private val onSessionExpired: suspend (sid: String) -> Unit = {},
) : AccountDataWiper {

    /** The keys currently liked, for the like indicator and the liked-first sort. */
    val likedKeys: Flow<Set<String>> =
        dao.liked().map { rows -> rows.map { likeKey(Space.valueOf(it.namespace), it.unitId) }.toSet() }

    suspend fun toggle(space: Space, unitId: Int) {
        val current = dao.find(space.name, unitId)?.liked ?: false
        setLiked(space, unitId, !current)
    }

    /** Set a like to an exact value; used by the batch "like selected" action. Idempotent. */
    suspend fun setLiked(space: Space, unitId: Int, liked: Boolean) {
        dao.upsert(
            LikeEntity(
                namespace = space.name,
                unitId = unitId,
                liked = liked,
                updatedAt = now(),
            ),
        )
    }

    /** Reconcile with the NAS: merge local and remote last-writer-wins, store, and upload the result. */
    suspend fun sync(session: Session): SyncResult {
        val dir = folder()
        Log.i("PhotosLikes", "sync start, folder=$dir")
        return try {
            val remote = nas.pull(session.baseUrl, dir, session.credentials)
            val local = dao.all().map { LikeState(likeKey(Space.valueOf(it.namespace), it.unitId), it.liked, it.updatedAt) }
            val merged = LikesMerge.merge(local, remote).values
            dao.replaceAll(merged.map { it.toEntity() })
            nas.push(session.baseUrl, dir, merged, session.credentials)
            Log.i("PhotosLikes", "sync ok, ${merged.size} entries")
            SyncResult.Success
        } catch (e: ApiFailure.SessionExpired) {
            onSessionExpired(session.credentials.sid)
            Log.w("PhotosLikes", "sync: session expired")
            SyncResult.SessionExpired
        } catch (e: ApiFailure) {
            Log.w("PhotosLikes", "sync failed: ${e.message}")
            SyncResult.Failed(e.message ?: "Synchronizace lajků selhala.")
        }
    }

    override suspend fun wipe() = dao.clear()

    private fun LikeState.toEntity(): LikeEntity {
        val (ns, id) = key.split(":", limit = 2)
        return LikeEntity(namespace = ns, unitId = id.toInt(), liked = liked, updatedAt = updatedAt)
    }
}
