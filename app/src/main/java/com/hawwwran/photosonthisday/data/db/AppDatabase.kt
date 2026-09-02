package com.hawwwran.photosonthisday.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 2: the day histogram, the per-opened-day item cache (item_row), and the local
 * like cache (item_like), whose durable copy is the NAS file (decision 008). The schema is exported to app/schemas and committed, so a future
 * migration has a baseline to diff against.
 */
@Database(entities = [DayBucketEntity::class, IndexMetaEntity::class, ItemRowEntity::class, LikeEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dayIndexDao(): DayIndexDao
    abstract fun likeDao(): LikeDao

    companion object {
        const val NAME = "onthisday.db"
    }
}
