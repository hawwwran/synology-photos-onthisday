package com.hawwwran.photosonthisday.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version 5: the day histogram (`day_bucket`), its refresh stamp and needs-refresh flag
 * (`index_meta`), the per-opened-day item cache (`item_row`, indexed by day) and the local like
 * cache (`item_like`), whose durable copy is the NAS file (decision 008). Every version is
 * exported to `app/schemas`; upgrades run [MIGRATIONS], never a destructive fallback, because
 * v1.0.0 (schema 4) is installed on real phones and `item_like` may hold a like not yet pushed.
 */
@Database(entities = [DayBucketEntity::class, IndexMetaEntity::class, ItemRowEntity::class, LikeEntity::class], version = 5, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dayIndexDao(): DayIndexDao
    abstract fun likeDao(): LikeDao

    companion object {
        const val NAME = "onthisday.db"
    }
}
