package com.hawwwran.photosonthisday.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 1: the day histogram only. Item rows (plan 004) are a cache keyed by opened day and
 * will be their own table. The schema is exported to app/schemas and committed, so a future
 * migration has a baseline to diff against.
 */
@Database(entities = [DayBucketEntity::class, IndexMetaEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dayIndexDao(): DayIndexDao

    companion object {
        const val NAME = "onthisday.db"
    }
}
