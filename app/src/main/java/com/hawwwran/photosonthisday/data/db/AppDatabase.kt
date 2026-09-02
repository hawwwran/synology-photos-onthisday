package com.hawwwran.photosonthisday.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 1: the day histogram plus a per-opened-day item cache (item_row). The schema is exported to app/schemas and committed, so a future
 * migration has a baseline to diff against.
 */
@Database(entities = [DayBucketEntity::class, IndexMetaEntity::class, ItemRowEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dayIndexDao(): DayIndexDao

    companion object {
        const val NAME = "onthisday.db"
    }
}
