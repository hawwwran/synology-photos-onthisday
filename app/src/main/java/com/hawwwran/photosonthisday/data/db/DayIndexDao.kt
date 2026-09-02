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
    }

    @Query("DELETE FROM day_bucket")
    suspend fun clearBuckets()

    @Query("DELETE FROM index_meta")
    suspend fun clearMeta()
}
