package com.hawwwran.photosonthisday.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One day of one namespace. The key is (namespace, year, month, day): the same calendar day
 * exists independently in the personal and the shared space.
 */
@Entity(tableName = "day_bucket", primaryKeys = ["namespace", "year", "month", "day"])
data class DayBucketEntity(
    val namespace: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val itemCount: Int,
)

/** A single row holding when the index was last refreshed, for the stale-index policy. */
@Entity(tableName = "index_meta")
data class IndexMetaEntity(
    @PrimaryKey val id: Int = SINGLETON,
    val refreshedAt: Long,
) {
    companion object {
        const val SINGLETON = 0
    }
}
