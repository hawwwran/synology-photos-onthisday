package com.hawwwran.photosonthisday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DayIndexDao {
    @Query("SELECT * FROM day_bucket")
    fun buckets(): Flow<List<DayBucketEntity>>

    @Query("SELECT refreshedAt FROM index_meta WHERE id = 0")
    fun refreshedAt(): Flow<Long?>

    @Query("SELECT needsRefresh FROM index_meta WHERE id = 0")
    suspend fun needsRefresh(): Boolean?

    /** Set the flag without touching the stamp; a no-op when the index was never refreshed. */
    @Query("UPDATE index_meta SET needsRefresh = 1 WHERE id = 0")
    suspend fun markNeedsRefresh()

    @Query("SELECT * FROM item_row WHERE year = :year AND month = :month AND day = :day ORDER BY takenTime DESC, id DESC")
    fun itemsForDay(year: Int, month: Int, day: Int): Flow<List<ItemRowEntity>>

    @Query("SELECT COUNT(*) FROM item_row WHERE year = :year AND month = :month AND day = :day")
    suspend fun countForDay(year: Int, month: Int, day: Int): Int

    /**
     * The named namespaces' rows of one day are replaced as a unit. Upsert, not insert: a photo
     * cached under another day (its date corrected in Photos) is moved, not a constraint failure.
     */
    @Transaction
    suspend fun replaceDayItems(namespaces: List<String>, year: Int, month: Int, day: Int, rows: List<ItemRowEntity>) {
        namespaces.forEach { deleteDayItems(it, year, month, day) }
        upsertItems(rows)
    }

    @Upsert
    suspend fun upsertItems(rows: List<ItemRowEntity>)

    @Query("DELETE FROM item_row WHERE namespace = :namespace AND year = :year AND month = :month AND day = :day")
    suspend fun deleteDayItems(namespace: String, year: Int, month: Int, day: Int)

    /**
     * The named namespaces' rows are replaced as a unit, so a vanished day leaves no stale row
     * behind, and the refresh stamp lands in the same transaction so observers see one histogram.
     */
    @Transaction
    suspend fun replaceBuckets(namespaces: List<String>, rows: List<DayBucketEntity>, meta: IndexMetaEntity?) {
        namespaces.forEach { deleteNamespace(it) }
        insert(rows)
        if (meta != null) setMeta(meta)
    }

    @Insert
    suspend fun insert(rows: List<DayBucketEntity>)

    @Query("DELETE FROM day_bucket WHERE namespace = :namespace")
    suspend fun deleteNamespace(namespace: String)

    @Upsert
    suspend fun setMeta(meta: IndexMetaEntity)

    @Transaction
    suspend fun clear() {
        clearBuckets()
        clearMeta()
        clearItems()
    }

    @Query("DELETE FROM day_bucket")
    suspend fun clearBuckets()

    @Query("DELETE FROM index_meta")
    suspend fun clearMeta()

    @Query("DELETE FROM item_row")
    suspend fun clearItems()
}
