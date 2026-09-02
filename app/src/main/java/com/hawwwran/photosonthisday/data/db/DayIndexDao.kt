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

    @Query("SELECT * FROM item_row WHERE year = :year AND month = :month AND day = :day ORDER BY takenTime DESC, id DESC")
    fun itemsForDay(year: Int, month: Int, day: Int): Flow<List<ItemRowEntity>>

    @Transaction
    suspend fun replaceDayItems(namespace: String, year: Int, month: Int, day: Int, rows: List<ItemRowEntity>) {
        deleteDayItems(namespace, year, month, day)
        insertItems(rows)
    }

    @Insert
    suspend fun insertItems(rows: List<ItemRowEntity>)

    @Query("DELETE FROM item_row WHERE namespace = :namespace AND year = :year AND month = :month AND day = :day")
    suspend fun deleteDayItems(namespace: String, year: Int, month: Int, day: Int)

    /** A namespace's rows are replaced as a unit, so a vanished day leaves no stale row behind. */
    @Transaction
    suspend fun replaceNamespace(namespace: String, rows: List<DayBucketEntity>) {
        deleteNamespace(namespace)
        insert(rows)
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
