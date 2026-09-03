package com.hawwwran.photosonthisday.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plan 008: v1.0.0 shipped schema 4, so 4 to 5 must carry every row across. Seeds one row per
 * table under the exported 4.json, migrates, validates the schema against 5.json and reads the
 * rows back. Not run in the executing session (no device attached); run on the Vivo before the
 * next release.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate4To5_keepsEveryRow() {
        helper.createDatabase(DB, 4).apply {
            execSQL("INSERT INTO day_bucket (namespace, year, month, day, itemCount) VALUES ('PERSONAL', 2024, 9, 2, 3)")
            execSQL("INSERT INTO index_meta (id, refreshedAt) VALUES (0, 1700000000000)")
            execSQL(
                "INSERT INTO item_row (namespace, id, unitId, cacheKey, takenTime, isVideo, width, height, filename, filesize, folderId, year, month, day) " +
                    "VALUES ('PERSONAL', 7, 7, '7_1', 1700000000, 0, 4000, 3000, 'a.jpg', 100, 1, 2024, 9, 2)",
            )
            execSQL("INSERT INTO item_like (namespace, unitId, liked, updatedAt, pendingSync) VALUES ('SHARED', 9, 1, 1700000001000, 1)")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 5, true, MIGRATION_4_5)

        db.query("SELECT itemCount FROM day_bucket WHERE namespace = 'PERSONAL'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(3, c.getInt(0))
        }
        db.query("SELECT refreshedAt, needsRefresh FROM index_meta WHERE id = 0").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1700000000000L, c.getLong(0))
            assertEquals("the new flag defaults to clear", 0, c.getInt(1))
        }
        db.query("SELECT unitId FROM item_row WHERE year = 2024 AND month = 9 AND day = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(7, c.getInt(0))
        }
        db.query("SELECT liked, updatedAt FROM item_like WHERE namespace = 'SHARED' AND unitId = 9").use { c ->
            assertTrue("a like whose push had failed survives the upgrade", c.moveToFirst())
            assertEquals(1, c.getInt(0))
            assertEquals(1700000001000L, c.getLong(1))
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
