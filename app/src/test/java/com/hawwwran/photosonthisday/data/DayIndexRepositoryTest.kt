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

    private fun item(id: Int, time: Long, thumbnail: Boolean = true): String {
        val additional = if (thumbnail) ""","additional":{"thumbnail":{"cache_key":"${id}_1","unit_id":$id},"resolution":{"width":4000,"height":3000}}""" else ""
        return """{"id":$id,"time":$time,"type":"photo"$additional}"""
    }

    private fun items(vararg ids: Pair<Int, Long>): String = itemsJson(ids.map { (id, time) -> item(id, time) })

    private fun itemsJson(list: List<String>) = """{"success":true,"data":{"list":[${list.joinToString(",")}]}}"""

    private val emptyPage = itemsJson(emptyList())

    private val day = MonthDay(9, 2)

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
    fun `fetchDay fetches both namespaces at once and caches them newest first in one write`() = runTest {
        routeList(Space.PERSONAL, items(101 to 1_700_000_100L, 100 to 1_700_000_000L))
        routeList(Space.SHARED, items(200 to 1_700_000_050L))

        val result = repo().fetchDay(session(), 2024, day)

        assertEquals(RefreshResult.Success, result)
        val cached = store.items(2024, day).first()
        assertEquals(listOf(101, 200, 100), cached.map { it.id })
        assertEquals(setOf(Space.PERSONAL, Space.SHARED), cached.map { it.space }.toSet())
        assertEquals("both namespaces land in one write", 1, store.dayWrites)
    }

    @Test
    fun `fetchDay reports a failed call and caches nothing for it`() = runTest {
        routeList(Space.PERSONAL, dsmError(120))
        routeList(Space.SHARED, dsmError(120))

        val result = repo().fetchDay(session(), 2024, day)

        assertTrue(result is RefreshResult.Failed)
        assertTrue(store.items(2024, day).first().isEmpty())
    }

    @Test
    fun `fetchDay on an expired session reports it with its sid`() = runTest {
        var expiredSid: String? = null
        routeList(Space.PERSONAL, dsmError(119))
        routeList(Space.SHARED, dsmError(119))

        val result = repo { expiredSid = it }.fetchDay(session(), 2024, day)

        assertEquals(RefreshResult.SessionExpired, result)
        assertEquals("S", expiredSid)
    }

    /** One thumbnail-less item on a full page used to end the paging and truncate the day. */
    @Test
    fun `paging stops on the server page size, not on the filtered one`() = runTest {
        val pageSize = DayIndexRepository.PAGE_SIZE
        val firstPage = (1..pageSize).map { i -> item(id = i, time = 2_000_000_000L - i, thumbnail = i != 7) }
        routeList(Space.PERSONAL, itemsJson(firstPage), "offset=0")
        routeList(Space.PERSONAL, items(5000 to 1_000_000_000L), "offset=$pageSize")
        routeList(Space.SHARED, emptyPage)

        val result = repo().fetchDay(session(), 2024, day, expectedCount = pageSize + 1)

        assertEquals(RefreshResult.Success, result)
        val personalRequests = nas.requests.filter { "SYNO.Foto.Browse.Item" in it && "method=list" in it }
        assertEquals("a full server page is followed by a second request", 2, personalRequests.size)
        assertEquals(pageSize, store.cachedCount(2024, day)) // 200 with thumbnails: 199 from page one, 1 from page two
        assertEquals("the server's total matched the histogram, so no refresh is scheduled", 0, store.needsRefreshMarks)
    }

    @Test
    fun `an item on the edge of two pages is cached once`() = runTest {
        val pageSize = DayIndexRepository.PAGE_SIZE
        val firstPage = (1..pageSize).map { i -> item(id = i, time = 2_000_000_000L) }
        routeList(Space.PERSONAL, itemsJson(firstPage), "offset=0")
        routeList(Space.PERSONAL, items(pageSize to 2_000_000_000L, 9000 to 1_999_999_999L), "offset=$pageSize") // id 200 again
        routeList(Space.SHARED, emptyPage)

        assertEquals(RefreshResult.Success, repo().fetchDay(session(), 2024, day))

        assertEquals(pageSize + 1, store.cachedCount(2024, day))
    }

    /** A photo whose date was corrected in Photos is cached under one day and fetched under another. */
    @Test
    fun `an item that moved to another day is rewritten there, not inserted twice`() = runTest {
        routeList(Space.PERSONAL, items(42 to 1_700_000_000L))
        routeList(Space.SHARED, emptyPage)
        repo().fetchDay(session(), 2024, day)
        val moved = MonthDay(9, 3)
        nas.route("api=SYNO.Foto.Browse.Item", "method=list", "start_time=${com.hawwwran.photosonthisday.core.dayRangeUtc(2024, moved).first}", body = items(42 to 1_700_086_400L))

        val result = repo().fetchDay(session(), 2024, moved)

        assertEquals(RefreshResult.Success, result)
        assertEquals(listOf(42), store.items(2024, moved).first().map { it.id })
        assertEquals("one row per (namespace, id)", 1, store.cachedCount(2024, moved) + store.cachedCount(2024, day))
    }

    @Test
    fun `a count mismatch schedules one refresh, which the next open runs and clears`() = runTest {
        store.seed(Space.PERSONAL, listOf(DayBucket(2024, day, 3)))
        store.seedRefreshedAt(clock)
        routeList(Space.PERSONAL, items(1 to 1_700_000_000L, 2 to 1_700_000_001L)) // histogram says 3
        routeList(Space.SHARED, emptyPage)

        repo().fetchDay(session(), 2024, day, expectedCount = 3)
        repo().fetchDay(session(), 2024, day, expectedCount = 3, force = true) // a second look before any refresh

        assertTrue("flagged", repo().isStale())
        assertEquals("the flag is idempotent", 2, store.needsRefreshMarks)

        routeTimeline(Space.PERSONAL, timeline(Triple(Triple(2024, 9, 2), 2, 0)))
        routeCount(Space.PERSONAL, count(2))
        routeTimeline(Space.SHARED, timeline())
        routeCount(Space.SHARED, count(0))
        assertEquals(RefreshResult.Success, repo().refreshIfStale(session()))
        assertEquals(1, store.bucketWrites)

        assertFalse("cleared by the refresh", repo().isStale())
        assertEquals(RefreshResult.Success, repo().refreshIfStale(session()))
        assertEquals("no second refresh", 1, store.bucketWrites)
    }

    @Test
    fun `fetchDay skips the network when the cache already holds the bucket count and the index is fresh`() = runTest {
        store.seed(Space.PERSONAL, listOf(DayBucket(2024, day, 2)))
        store.seedRefreshedAt(clock)
        routeList(Space.PERSONAL, items(1 to 1_700_000_000L, 2 to 1_700_000_001L))
        routeList(Space.SHARED, emptyPage)
        repo().fetchDay(session(), 2024, day, expectedCount = 2)
        val before = nas.requests.size

        assertEquals(RefreshResult.Success, repo().fetchDay(session(), 2024, day, expectedCount = 2))

        assertEquals("served from the cache", before, nas.requests.size)
    }

    @Test
    fun `pull-to-refresh bypasses the cached-count skip`() = runTest {
        store.seed(Space.PERSONAL, listOf(DayBucket(2024, day, 2)))
        store.seedRefreshedAt(clock)
        routeList(Space.PERSONAL, items(1 to 1_700_000_000L, 2 to 1_700_000_001L))
        routeList(Space.SHARED, emptyPage)
        repo().fetchDay(session(), 2024, day, expectedCount = 2)
        val before = nas.requests.size

        repo().fetchDay(session(), 2024, day, expectedCount = 2, force = true)

        assertTrue("fetched again", nas.requests.size > before)
    }

    @Test
    fun `a stale index never skips the day fetch`() = runTest {
        store.seed(Space.PERSONAL, listOf(DayBucket(2024, day, 1)))
        store.seedRefreshedAt(clock - DayIndexRepository.DEFAULT_STALE_AFTER - 1)
        routeList(Space.PERSONAL, items(1 to 1_700_000_000L))
        routeList(Space.SHARED, emptyPage)
        repo().fetchDay(session(), 2024, day, expectedCount = 1)
        val before = nas.requests.size

        repo().fetchDay(session(), 2024, day, expectedCount = 1)

        assertTrue(nas.requests.size > before)
    }
}
