package com.hawwwran.photosonthisday.likes

import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.SessionCredentials
import com.hawwwran.photosonthisday.data.db.LikeDao
import com.hawwwran.photosonthisday.data.db.LikeEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl

/** In-memory [LikeDao]; `reconcile` is the interface's own default body, as on the device minus the transaction. */
class FakeLikeDao : LikeDao {
    val rows = MutableStateFlow<Map<Pair<String, Int>, LikeEntity>>(emptyMap())

    override fun liked(): Flow<List<LikeEntity>> = rows.map { it.values.filter { e -> e.liked } }
    override suspend fun all(): List<LikeEntity> = rows.value.values.toList()
    override suspend fun find(namespace: String, unitId: Int): LikeEntity? = rows.value[namespace to unitId]
    override suspend fun upsertAll(entities: List<LikeEntity>) {
        rows.value = rows.value + entities.associateBy { it.namespace to it.unitId }
    }
    override suspend fun clear() {
        rows.value = emptyMap()
    }
}

/** A likes file in memory, with a gate so a test can act in the middle of a pull. */
class FakeLikesRemote : LikesRemote {
    var file: List<LikeState>? = emptyList()
    var pullFailure: ApiFailure? = null
    val pushes = ArrayList<List<LikeState>>()
    var pulls = 0
    /** When set, `pull` suspends until it completes. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun pull(baseUrl: HttpUrl, folder: String, credentials: SessionCredentials): List<LikeState> {
        pulls++
        gate?.await()
        pullFailure?.let { throw it }
        return file ?: emptyList()
    }

    override suspend fun push(baseUrl: HttpUrl, folder: String, states: Collection<LikeState>, credentials: SessionCredentials) {
        pushes += states.toList()
        file = states.toList()
    }
}
