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
 * Not run offline (no device this session). Exercises the real Room path: a namespace is
 * replaced wholesale, the two namespaces coexist, and clear empties both tables.
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

    @Test
    fun replaceNamespace_swaps_only_that_namespace() = runBlocking {
        dao.replaceNamespace("PERSONAL", listOf(row("PERSONAL", 2024, 9, 2, 3)))
        dao.replaceNamespace("SHARED", listOf(row("SHARED", 2024, 9, 2, 5)))

        dao.replaceNamespace("PERSONAL", listOf(row("PERSONAL", 2024, 9, 2, 4)))

        val all = dao.buckets().first().associate { it.namespace to it.itemCount }
        assertEquals(mapOf("PERSONAL" to 4, "SHARED" to 5), all)
    }

    @Test
    fun clear_empties_both_tables() = runBlocking {
        dao.replaceNamespace("PERSONAL", listOf(row("PERSONAL", 2024, 9, 2, 3)))
        dao.setMeta(IndexMetaEntity(refreshedAt = 123L))

        dao.clear()

        assertTrue(dao.buckets().first().isEmpty())
        assertNull(dao.refreshedAt().first())
    }
}
