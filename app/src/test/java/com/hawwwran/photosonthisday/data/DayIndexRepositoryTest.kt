package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.ItemApi
import com.hawwwran.photosonthisday.api.SessionCredentials
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.api.SynologyClient
import com.hawwwran.photosonthisday.api.TimelineApi
import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.session.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DayIndexRepositoryTest {

    private val server = MockWebServer()
    private val store = FakeDayIndexStore()
    private val today = MonthDay(9, 2)
    private var clock = 1_000_000_000_000L

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private fun repo(onExpired: suspend () -> Unit = {}) = DayIndexRepository(
        store = store,
        timelineApi = TimelineApi(SynologyClient(OkHttpClient())),
        itemApi = ItemApi(SynologyClient(OkHttpClient())),
        today = { today },
        now = { clock },
        onSessionExpired = onExpired,
    )

    private fun session() = Session(server.url("/"), "anna", SessionCredentials("S", "T"))

    private fun enqueue(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    private fun timeline(vararg days: Triple<Triple<Int, Int, Int>, Int, Int>): String {
        val list = days.joinToString(",") { (ymd, count, _) ->
            """{"year":${ymd.first},"month":${ymd.second},"day":${ymd.third},"item_count":$count}"""
        }
        return """{"success":true,"data":{"section":[{"offset":0,"limit":1,"list":[$list]}]}}"""
    }

    private fun count(n: Int) = """{"success":true,"data":{"count":$n}}"""

    private fun items(vararg ids: Pair<Int, Long>): String {
        val list = ids.joinToString(",") { (id, time) ->
            """{"id":$id,"time":$time,"type":"photo","additional":{"thumbnail":{"cache_key":"${id}_1","unit_id":$id},"resolution":{"width":4000,"height":3000}}}"""
        }
        return """{"success":true,"data":{"list":[$list]}}"""
    }

    @Test
    fun `nothing stored and never refreshed is Loading`() = runTest {
        assertEquals(DayIndexState.Loading, repo().observe().first())
    }

    @Test
    fun `refreshed but empty is NoPhotos, not Loading`() = runTest {
        store.setRefreshedAt(clock)
        assertEquals(DayIndexState.NoPhotos, repo().observe().first())
    }

    @Test
    fun `a populated index names today across years with no network`() = runTest {
        store.replace(Space.PERSONAL, listOf(DayBucket(2019, MonthDay(9, 2), 12), DayBucket(2024, MonthDay(9, 2), 3)))
        store.replace(Space.SHARED, listOf(DayBucket(2024, MonthDay(9, 2), 5)))
        store.setRefreshedAt(clock)

        val state = repo().observe().first() as DayIndexState.Ready

        assertEquals(MonthDay(9, 2), state.selection.monthDay)
        assertFalse(state.selection.isFallback)
        // 2024 personal 3 + shared 5 merged to one bucket of 8, plus 2019's 12.
        assertEquals(listOf(2024 to 8, 2019 to 12), state.selection.years.map { it.year to it.itemCount })
        assertEquals(20, state.selection.totalItems)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a populated index falls back to the nearest day offline`() = runTest {
        store.replace(Space.PERSONAL, listOf(DayBucket(2021, MonthDay(8, 30), 4)))
        store.setRefreshedAt(clock)

        val state = repo().observe().first() as DayIndexState.Ready

        assertEquals(MonthDay(8, 30), state.selection.monthDay)
        assertEquals(3, state.selection.daysFromToday)
        assertTrue(state.selection.inThePast)
    }

    @Test
    fun `refresh stores both namespaces and stamps the time`() = runTest {
        // Order: timeline PERSONAL, count PERSONAL, timeline SHARED, count SHARED.
        enqueue(timeline(Triple(Triple(2024, 9, 2), 3, 0)))
        enqueue(count(3))
        enqueue(timeline(Triple(Triple(2023, 1, 5), 2, 0)))
        enqueue(count(2))

        val result = repo().refresh(session())

        assertEquals(RefreshResult.Success, result)
        assertEquals(clock, store.refreshedAt().first())
        val spaces = store.buckets().first().map { it.space }.toSet()
        assertEquals(setOf(Space.PERSONAL, Space.SHARED), spaces)
    }

    @Test
    fun `a count that disagrees with the histogram still refreshes`() = runTest {
        enqueue(timeline(Triple(Triple(2024, 9, 2), 3, 0)))
        enqueue(count(999)) // upload since the timeline snapshot; not fatal
        enqueue(timeline())
        enqueue(count(0))

        assertEquals(RefreshResult.Success, repo().refresh(session()))
        assertEquals(3, store.buckets().first().sumOf { it.bucket.itemCount })
    }

    @Test
    fun `an expired session on refresh reports it and calls back`() = runTest {
        var expired = false
        enqueue("""{"success":false,"error":{"code":106}}""")

        val result = repo { expired = true }.refresh(session())

        assertEquals(RefreshResult.SessionExpired, result)
        assertTrue(expired)
    }

    @Test
    fun `refreshIfStale skips the network when the index is fresh`() = runTest {
        store.setRefreshedAt(clock)

        val result = repo().refreshIfStale(session())

        assertEquals(RefreshResult.Success, result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refreshIfStale fetches when the index is old`() = runTest {
        store.setRefreshedAt(clock - DayIndexRepository.DEFAULT_STALE_AFTER - 1)
        enqueue(timeline(Triple(Triple(2024, 9, 2), 1, 0)))
        enqueue(count(1))
        enqueue(timeline())
        enqueue(count(0))

        assertEquals(RefreshResult.Success, repo().refreshIfStale(session()))
        assertTrue(server.requestCount > 0)
    }

    @Test
    fun `wipe clears the store`() = runTest {
        store.replace(Space.PERSONAL, listOf(DayBucket(2024, MonthDay(9, 2), 3)))
        store.setRefreshedAt(clock)

        repo().wipe()

        assertEquals(1, store.clears)
        assertTrue(store.buckets().first().isEmpty())
    }

    @Test
    fun `fetchDay fetches both namespaces and caches them newest first`() = runTest {
        // fetchDay loops Space.entries = [PERSONAL, SHARED]; each is one page under PAGE_SIZE.
        enqueue(items(101 to 1_700_000_100L, 100 to 1_700_000_000L)) // personal
        enqueue(items(200 to 1_700_000_050L)) // shared

        val result = repo().fetchDay(session(), 2024, MonthDay(9, 2))

        assertEquals(RefreshResult.Success, result)
        val cached = store.items(2024, MonthDay(9, 2)).first()
        assertEquals(listOf(101, 200, 100), cached.map { it.id })
        assertEquals(setOf(Space.PERSONAL, Space.SHARED), cached.map { it.space }.toSet())
    }

    @Test
    fun `fetchDay reports a failed call and caches nothing for it`() = runTest {
        enqueue("""{"success":false,"error":{"code":120}}""")

        val result = repo().fetchDay(session(), 2024, MonthDay(9, 2))

        assertTrue(result is RefreshResult.Failed)
    }

    @Test
    fun `fetchDay on an expired session reports it and calls back`() = runTest {
        var expired = false
        enqueue("""{"success":false,"error":{"code":119}}""")

        val result = repo { expired = true }.fetchDay(session(), 2024, MonthDay(9, 2))

        assertEquals(RefreshResult.SessionExpired, result)
        assertTrue(expired)
    }
}
