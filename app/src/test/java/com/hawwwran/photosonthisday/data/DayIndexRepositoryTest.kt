package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.RoutedPhotosServer
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
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DayIndexRepositoryTest {

    private val nas = RoutedPhotosServer()
    private val store = FakeDayIndexStore()
    private val today = MonthDay(9, 2)
    private var clock = 1_000_000_000_000L

    @Before
    fun start() = nas.start()

    @After
    fun stop() = nas.shutdown()

    private fun repo(onExpired: suspend (String) -> Unit = {}) = DayIndexRepository(
        store = store,
        timelineApi = TimelineApi(SynologyClient(OkHttpClient())),
        itemApi = ItemApi(SynologyClient(OkHttpClient())),
        today = { today },
        now = { clock },
        onSessionExpired = onExpired,
    )

    private fun session() = Session(nas.url(), "anna", SessionCredentials("S", "T"))

    private fun timeline(vararg days: Triple<Triple<Int, Int, Int>, Int, Int>): String {
        val list = days.joinToString(",") { (ymd, count, _) ->
            """{"year":${ymd.first},"month":${ymd.second},"day":${ymd.third},"item_count":$count}"""
        }
        return """{"success":true,"data":{"section":[{"offset":0,"limit":1,"list":[$list]}]}}"""
    }

    private fun count(n: Int) = """{"success":true,"data":{"count":$n}}"""

    private fun dsmError(code: Int) = """{"success":false,"error":{"code":$code}}"""

    private fun items(vararg ids: Pair<Int, Long>): String {
        val list = ids.joinToString(",") { (id, time) ->
            """{"id":$id,"time":$time,"type":"photo","additional":{"thumbnail":{"cache_key":"${id}_1","unit_id":$id},"resolution":{"width":4000,"height":3000}}}"""
        }
        return """{"success":true,"data":{"list":[$list]}}"""
    }

    private fun routeTimeline(space: Space, body: String) = nas.route("api=${space.apiPrefix}.Browse.Timeline", "method=get", body = body)
    private fun routeCount(space: Space, body: String) = nas.route("api=${space.apiPrefix}.Browse.Item", "method=count", body = body)
    private fun routeList(space: Space, body: String, vararg more: String) =
        nas.route("api=${space.apiPrefix}.Browse.Item", "method=list", *more, body = body)

    @Test
    fun `nothing stored and never refreshed is Loading`() = runTest {
        assertEquals(DayIndexState.Loading, repo().observe().first())
    }

    @Test
    fun `refreshed but empty is NoPhotos, not Loading`() = runTest {
        store.seedRefreshedAt(clock)
        assertEquals(DayIndexState.NoPhotos, repo().observe().first())
    }

    @Test
    fun `a populated index names today across years with no network`() = runTest {
        store.seed(Space.PERSONAL, listOf(DayBucket(2019, MonthDay(9, 2), 12), DayBucket(2024, MonthDay(9, 2), 3)))
        store.seed(Space.SHARED, listOf(DayBucket(2024, MonthDay(9, 2), 5)))
        store.seedRefreshedAt(clock)

        val state = repo().observe().first() as DayIndexState.Ready

        assertEquals(MonthDay(9, 2), state.selection.monthDay)
        assertFalse(state.selection.isFallback)
        // 2024 personal 3 + shared 5 merged to one bucket of 8, plus 2019's 12.
        assertEquals(listOf(2024 to 8, 2019 to 12), state.selection.years.map { it.year to it.itemCount })
        assertEquals(20, state.selection.totalItems)
        assertEquals(0, nas.requests.size)
    }

    @Test
    fun `a populated index falls back to the nearest day offline`() = runTest {
        store.seed(Space.PERSONAL, listOf(DayBucket(2021, MonthDay(8, 30), 4)))
        store.seedRefreshedAt(clock)

        val state = repo().observe().first() as DayIndexState.Ready

        assertEquals(MonthDay(8, 30), state.selection.monthDay)
        assertEquals(3, state.selection.daysFromToday)
        assertTrue(state.selection.inThePast)
    }

    @Test
    fun `refresh stores both namespaces in one write and stamps the time`() = runTest {
        routeTimeline(Space.PERSONAL, timeline(Triple(Triple(2024, 9, 2), 3, 0)))
        routeCount(Space.PERSONAL, count(3))
        routeTimeline(Space.SHARED, timeline(Triple(Triple(2023, 1, 5), 2, 0)))
        routeCount(Space.SHARED, count(2))

        val result = repo().refresh(session())

        assertEquals(RefreshResult.Success, result)
        assertEquals(clock, store.refreshedAt().first())
        val spaces = store.buckets().first().map { it.space }.toSet()
        assertEquals(setOf(Space.PERSONAL, Space.SHARED), spaces)
        assertEquals("one histogram write per refresh, not one per namespace", 1, store.bucketWrites)
    }

    @Test
    fun `a count that disagrees with the histogram still refreshes`() = runTest {
        routeTimeline(Space.PERSONAL, timeline(Triple(Triple(2024, 9, 2), 3, 0)))
        routeCount(Space.PERSONAL, count(999)) // upload since the timeline snapshot; not fatal
        routeTimeline(Space.SHARED, timeline())
        routeCount(Space.SHARED, count(0))

        assertEquals(RefreshResult.Success, repo().refresh(session()))
        assertEquals(3, store.buckets().first().sumOf { it.bucket.itemCount })
    }

    @Test
    fun `an expired session on refresh reports it with its sid and writes nothing`() = runTest {
        var expiredSid: String? = null
        routeTimeline(Space.PERSONAL, timeline(Triple(Triple(2024, 9, 2), 3, 0)))
        routeCount(Space.PERSONAL, count(3))
        routeTimeline(Space.SHARED, dsmError(106))

        val result = repo { expiredSid = it }.refresh(session())

        assertEquals(RefreshResult.SessionExpired, result)
        assertEquals("S", expiredSid)
        assertEquals(0, store.bucketWrites)
        assertNull(store.refreshedAt().first())
    }

    /** 105 on the shared space: the account may not read it. The personal histogram is kept and shown. */
    @Test
    fun `a permission error on one namespace keeps the other and reports the error`() = runTest {
        var expired = false
        routeTimeline(Space.PERSONAL, timeline(Triple(Triple(2024, 9, 2), 3, 0)))
        routeCount(Space.PERSONAL, count(3))
        routeTimeline(Space.SHARED, dsmError(105))

        val result = repo { expired = true }.refresh(session())

        assertTrue(result is RefreshResult.Failed)
        assertTrue((result as RefreshResult.Failed).message, "105" in result.message)
        assertFalse("105 is not an expiry", expired)
        assertEquals(listOf(Space.PERSONAL), store.buckets().first().map { it.space })
        assertEquals("a DSM answer is final for now; the index is stamped", clock, store.refreshedAt().first())
    }

    @Test
    fun `a transport failure on one namespace keeps the other but leaves the index stale`() = runTest {
        routeTimeline(Space.PERSONAL, timeline(Triple(Triple(2024, 9, 2), 3, 0)))
        routeCount(Space.PERSONAL, count(3))
        nas.route("api=SYNO.FotoTeam.Browse.Timeline", body = "<html>proxy</html>") // Malformed

        val result = repo().refresh(session())

        assertTrue(result is RefreshResult.Failed)
        assertEquals(listOf(Space.PERSONAL), store.buckets().first().map { it.space })
        assertNull("worth another try on the next open", store.refreshedAt().first())
    }

    @Test
    fun `refreshIfStale skips the network when the index is fresh`() = runTest {
        store.seedRefreshedAt(clock)

        val result = repo().refreshIfStale(session())

        assertEquals(RefreshResult.Success, result)
        assertEquals(0, nas.requests.size)
    }

    @Test
    fun `refreshIfStale fetches when the index is old`() = runTest {
        store.seedRefreshedAt(clock - DayIndexRepository.DEFAULT_STALE_AFTER - 1)
        routeTimeline(Space.PERSONAL, timeline(Triple(Triple(2024, 9, 2), 1, 0)))
        routeCount(Space.PERSONAL, count(1))
        routeTimeline(Space.SHARED, timeline())
        routeCount(Space.SHARED, count(0))

        assertEquals(RefreshResult.Success, repo().refreshIfStale(session()))
        assertTrue(nas.requests.isNotEmpty())
    }

    @Test
    fun `wipe clears the store`() = runTest {
        store.seed(Space.PERSONAL, listOf(DayBucket(2024, MonthDay(9, 2), 3)))
        store.seedRefreshedAt(clock)

        repo().wipe()

        assertEquals(1, store.clears)
        assertTrue(store.buckets().first().isEmpty())
    }

    @Test
    fun `fetchDay fetches both namespaces and caches them newest first`() = runTest {
        routeList(Space.PERSONAL, items(101 to 1_700_000_100L, 100 to 1_700_000_000L))
        routeList(Space.SHARED, items(200 to 1_700_000_050L))

        val result = repo().fetchDay(session(), 2024, MonthDay(9, 2))

        assertEquals(RefreshResult.Success, result)
        val cached = store.items(2024, MonthDay(9, 2)).first()
        assertEquals(listOf(101, 200, 100), cached.map { it.id })
        assertEquals(setOf(Space.PERSONAL, Space.SHARED), cached.map { it.space }.toSet())
    }

    @Test
    fun `fetchDay reports a failed call and caches nothing for it`() = runTest {
        routeList(Space.PERSONAL, dsmError(120))
        routeList(Space.SHARED, dsmError(120))

        val result = repo().fetchDay(session(), 2024, MonthDay(9, 2))

        assertTrue(result is RefreshResult.Failed)
    }

    @Test
    fun `fetchDay on an expired session reports it with its sid`() = runTest {
        var expiredSid: String? = null
        routeList(Space.PERSONAL, dsmError(119))
        routeList(Space.SHARED, dsmError(119))

        val result = repo { expiredSid = it }.fetchDay(session(), 2024, MonthDay(9, 2))

        assertEquals(RefreshResult.SessionExpired, result)
        assertEquals("S", expiredSid)
    }
}
