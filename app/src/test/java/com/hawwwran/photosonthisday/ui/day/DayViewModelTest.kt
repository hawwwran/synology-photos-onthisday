package com.hawwwran.photosonthisday.ui.day

import com.hawwwran.photosonthisday.api.ItemApi
import com.hawwwran.photosonthisday.api.PhotoItem
import com.hawwwran.photosonthisday.api.SessionCredentials
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.api.SynologyClient
import com.hawwwran.photosonthisday.api.TimelineApi
import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.data.DayIndexRepository
import com.hawwwran.photosonthisday.data.FakeDayIndexStore
import com.hawwwran.photosonthisday.data.db.LikeEntity
import com.hawwwran.photosonthisday.likes.FakeLikeDao
import com.hawwwran.photosonthisday.likes.FakeLikesRemote
import com.hawwwran.photosonthisday.likes.LikeRepository
import com.hawwwran.photosonthisday.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Plan 010 E: on a cold start the liked group is there at once, not after the day reloads. */
@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModelTest {

    private val today = MonthDay(9, 2)
    private val clock = 1_000_000_000_000L
    private val store = FakeDayIndexStore()
    private val likeDao = FakeLikeDao()
    private val session = Session("https://nas.example".toHttpUrl(), "anna", SessionCredentials("S", "T"))

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun photo(id: Int, time: Long) = PhotoItem(Space.PERSONAL, id, id, "${id}_1", time, false, 4000, 3000)

    @Test
    fun `a cold start on a day with liked photos shows the liked group first`() = runTest {
        // A fresh index whose cached day matches its bucket, so no network is touched.
        store.seed(Space.PERSONAL, listOf(DayBucket(2024, today, 2)))
        store.seedRefreshedAt(clock)
        store.replaceDayItems(2024, today, mapOf(Space.PERSONAL to listOf(photo(1, 1_700_000_001L), photo(2, 1_700_000_000L))))
        likeDao.upsertAll(listOf(LikeEntity("PERSONAL", 2, liked = true, updatedAt = 1L)))
        val repository = DayIndexRepository(store, TimelineApi(SynologyClient(OkHttpClient())), ItemApi(SynologyClient(OkHttpClient())), today = { today }, now = { clock })
        val likes = LikeRepository(likeDao, FakeLikesRemote(), folder = { "/x" })

        val viewModel = DayViewModel(repository, likes, session, today, flowOf(false), todayProvider = { today })

        val sections = viewModel.display.first { it.any { s -> s.items.isNotEmpty() } }
        assertEquals(DaySectionHeader.Liked, sections.first().header)
        assertEquals(listOf(2), sections.first().items.map { it.id })
        assertEquals(listOf(1), sections.last().items.map { it.id })
    }
}
