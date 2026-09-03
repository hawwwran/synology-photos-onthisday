package com.hawwwran.photosonthisday.likes

import com.hawwwran.photosonthisday.api.Allowlist
import com.hawwwran.photosonthisday.api.ApiFailure
import com.hawwwran.photosonthisday.api.SessionCredentials
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.data.db.LikeDao
import com.hawwwran.photosonthisday.data.db.LikeEntity
import com.hawwwran.photosonthisday.session.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision 008 as amended 2026-09-03: a like is never lost, never crashes, and an unreadable file is never overwritten. */
class LikeRepositoryTest {

    /** In-memory [LikeDao]; `reconcile` is the interface's own default body, as on the device minus the transaction. */
    private class FakeLikeDao : LikeDao {
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

    private class FakeRemote : LikesRemote {
        var file: List<LikeState>? = emptyList()
        var pullFailure: ApiFailure? = null
        val pushes = ArrayList<List<LikeState>>()
        var pulls = 0
        /** When set, `pull` suspends until it completes, so a test can act mid-sync. */
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

    private val dao = FakeLikeDao()
    private val remote = FakeRemote()
    private var clock = 1_000L
    private val session = Session("https://nas.example".toHttpUrl(), "anna", SessionCredentials("S", "T"))
    private val repo = LikeRepository(dao, remote, folder = { "/home/OnThisDay" }, now = { clock })

    @Test
    fun `a toggle made while the file is being pulled is in the pushed set and stays liked`() = runTest {
        remote.gate = CompletableDeferred()
        val sync = async { repo.sync(session) }
        while (remote.pulls == 0) kotlinx.coroutines.yield()

        repo.toggle(Space.PERSONAL, 5) // lands between the pull and the write
        remote.gate!!.complete(Unit)

        assertEquals(SyncResult.Success(), sync.await())
        assertTrue("pushed", remote.pushes.single().any { it.key == "PERSONAL:5" && it.liked })
        assertEquals(setOf("PERSONAL:5"), repo.likedKeys.first())
    }

    @Test
    fun `overlapping sync requests collapse into one follow-up run`() = runTest {
        remote.gate = CompletableDeferred()
        val first = async { repo.sync(session) }
        while (remote.pulls == 0) kotlinx.coroutines.yield()
        val second = async { repo.sync(session) }
        val third = async { repo.sync(session) }
        kotlinx.coroutines.yield()

        remote.gate!!.complete(Unit)
        remote.gate = null
        first.await(); second.await(); third.await()

        assertEquals("three overlapping calls, two runs", 2, remote.pulls)
    }

    @Test
    fun `a file that exists but cannot be read stops the sync and pushes nothing`() = runTest {
        remote.pullFailure = ApiFailure.Malformed(Allowlist.FS_DOWNLOAD, "likes file is not readable")
        repo.toggle(Space.PERSONAL, 1)

        val result = repo.sync(session)

        assertTrue(result is SyncResult.Failed)
        assertTrue("nothing overwritten", remote.pushes.isEmpty())
        assertEquals("the local like is kept for the next sync", setOf("PERSONAL:1"), repo.likedKeys.first())
    }

    @Test
    fun `a bad key in the file is skipped and counted, never persisted`() = runTest {
        remote.file = listOf(
            LikeState("abc", true, 10),
            LikeState("FOO:5", true, 10),
            LikeState(":5", true, 10),
            LikeState("SHARED:7", true, 10),
        )

        val result = repo.sync(session)

        assertEquals(SyncResult.Success(skippedKeys = 3), result)
        assertEquals(setOf("SHARED:7"), repo.likedKeys.first())
        assertTrue(dao.rows.value.keys.all { (ns, _) -> ns == "SHARED" })
        assertEquals("the pushed file carries only good keys", listOf("SHARED:7"), remote.pushes.single().map { it.key })
    }

    @Test
    fun `a stored row with an unknown namespace is skipped by likedKeys`() = runTest {
        dao.upsertAll(listOf(LikeEntity("FOO", 5, true, 1), LikeEntity("PERSONAL", 6, true, 1)))

        assertEquals(setOf("PERSONAL:6"), repo.likedKeys.first())
    }

    @Test
    fun `a dead session reports the sid it used`() = runTest {
        var expired: String? = null
        val repo = LikeRepository(dao, remote, folder = { "/x" }, onSessionExpired = { expired = it })
        remote.pullFailure = ApiFailure.SessionExpired(Allowlist.FS_DOWNLOAD, 119)

        assertEquals(SyncResult.SessionExpired, repo.sync(session))
        assertEquals("S", expired)
    }

    @Test
    fun `like selected writes one batch with one timestamp`() = runTest {
        repo.setLikedAll(listOf(Space.PERSONAL to 1, Space.SHARED to 2), liked = true)

        assertEquals(setOf("PERSONAL:1", "SHARED:2"), repo.likedKeys.first())
        assertEquals(setOf(1_000L), dao.rows.value.values.map { it.updatedAt }.toSet())
    }
}
