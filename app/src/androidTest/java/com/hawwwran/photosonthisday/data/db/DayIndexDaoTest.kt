package com.hawwwran.photosonthisday.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real Room path: namespaces are replaced wholesale and coexist, clear empties
 * every table, and an item that moved days is upserted rather than refused. Not run in the
 * executing session (no device attached).
 */
@RunWith(AndroidJUnit4::class)
class DayIndexDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DayIndexDao

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.dayIndexDao()
    }

    @After
    fun close() = db.close()

    private fun row(ns: String, y: Int, m: Int, d: Int, n: Int) = DayBucketEntity(ns, y, m, d, n)

    private fun item(ns: String, id: Int, y: Int, m: Int, d: Int) =
        ItemRowEntity(ns, id, id, "${id}_1", 1_700_000_000L, false, 4000, 3000, year = y, month = m, day = d)

    @Test
    fun replaceBuckets_swaps_only_the_named_namespaces() = runBlocking {
        dao.replaceBuckets(listOf("PERSONAL", "SHARED"), listOf(row("PERSONAL", 2024, 9, 2, 3), row("SHARED", 2024, 9, 2, 5)), null)

        dao.replaceBuckets(listOf("PERSONAL"), listOf(row("PERSONAL", 2024, 9, 2, 4)), IndexMetaEntity(refreshedAt = 5L))

        val all = dao.buckets().first().associate { it.namespace to it.itemCount }
        assertEquals(mapOf("PERSONAL" to 4, "SHARED" to 5), all)
        assertEquals(5L, dao.refreshedAt().first())
    }

    @Test
    fun clear_empties_every_table() = runBlocking {
        dao.replaceBuckets(listOf("PERSONAL"), listOf(row("PERSONAL", 2024, 9, 2, 3)), IndexMetaEntity(refreshedAt = 123L))
        dao.replaceDayItems(listOf("PERSONAL"), 2024, 9, 2, listOf(item("PERSONAL", 1, 2024, 9, 2)))

        dao.clear()

        assertTrue(dao.buckets().first().isEmpty())
        assertNull(dao.refreshedAt().first())
        assertEquals(0, dao.countForDay(2024, 9, 2))
    }

    /** Plan 008 A: the same (namespace, id) fetched under another day used to abort the insert. */
    @Test
    fun an_item_that_moved_days_is_rewritten_under_the_new_day() = runBlocking {
        dao.replaceDayItems(listOf("PERSONAL"), 2024, 9, 2, listOf(item("PERSONAL", 42, 2024, 9, 2)))

        dao.replaceDayItems(listOf("PERSONAL"), 2024, 9, 3, listOf(item("PERSONAL", 42, 2024, 9, 3)))

        assertEquals(0, dao.countForDay(2024, 9, 2))
        assertEquals(listOf(42), dao.itemsForDay(2024, 9, 3).first().map { it.id })
    }

    @Test
    fun needsRefresh_is_set_once_the_index_exists_and_cleared_by_a_stamped_write() = runBlocking {
        dao.markNeedsRefresh()
        assertNull("no meta row yet, nothing to flag", dao.needsRefresh())

        dao.replaceBuckets(emptyList(), emptyList(), IndexMetaEntity(refreshedAt = 1L))
        dao.markNeedsRefresh()
        assertEquals(true, dao.needsRefresh())

        dao.replaceBuckets(emptyList(), emptyList(), IndexMetaEntity(refreshedAt = 2L))
        assertEquals(false, dao.needsRefresh())
    }
}
