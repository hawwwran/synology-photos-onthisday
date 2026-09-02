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
    suspend fun upsert(entity: LikeEntity)

    @Upsert
    suspend fun upsertAll(entities: List<LikeEntity>)

    @Query("DELETE FROM item_like")
    suspend fun clear()

    /** Reconciliation writes the merged set as the new local truth. */
    @Transaction
    suspend fun replaceAll(entities: List<LikeEntity>) {
        clear()
        upsertAll(entities)
    }
}
