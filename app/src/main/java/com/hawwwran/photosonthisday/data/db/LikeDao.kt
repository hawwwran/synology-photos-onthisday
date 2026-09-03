package com.hawwwran.photosonthisday.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LikeDao {
    @Query("SELECT * FROM item_like WHERE liked = 1")
    fun liked(): Flow<List<LikeEntity>>

    @Query("SELECT * FROM item_like")
    suspend fun all(): List<LikeEntity>

    @Query("SELECT * FROM item_like WHERE namespace = :namespace AND unitId = :unitId")
    suspend fun find(namespace: String, unitId: Int): LikeEntity?

    @Upsert
    suspend fun upsertAll(entities: List<LikeEntity>)

    @Query("DELETE FROM item_like")
    suspend fun clear()

    /**
     * Read the local rows, let [merge] fold the remote file into them, write the result, all in
     * one transaction, and return what was written so the caller can push it. No `DELETE` in the
     * middle: a toggle that commits before this transaction is read and merged, one that commits
     * after it survives untouched. The old read-clear-write lost a toggle that landed in between.
     */
    @Transaction
    suspend fun reconcile(merge: (local: List<LikeEntity>) -> List<LikeEntity>): List<LikeEntity> {
        val merged = merge(all())
        upsertAll(merged)
        return merged
    }
}
