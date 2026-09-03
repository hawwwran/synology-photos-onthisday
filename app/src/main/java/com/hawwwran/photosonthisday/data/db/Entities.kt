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

/**
 * A cached item of one opened day. Keyed by (namespace, id): one photo, one namespace. The day
 * columns let a day be queried and replaced as a unit when it is reopened.
 */
@Entity(tableName = "item_row", primaryKeys = ["namespace", "id"])
data class ItemRowEntity(
    val namespace: String,
    val id: Int,
    val unitId: Int,
    val cacheKey: String,
    val takenTime: Long,
    val isVideo: Boolean,
    val width: Int,
    val height: Int,
    val filename: String = "",
    val filesize: Long = 0,
    val folderId: Int = 0,
    val year: Int,
    val month: Int,
    val day: Int,
)

/**
 * One item's like, cached locally for instant, offline toggling. The durable copy is the NAS
 * file (decision 008); `pendingSync` marks a local change not yet pushed. Keyed like the item.
 */
@Entity(tableName = "item_like", primaryKeys = ["namespace", "unitId"])
data class LikeEntity(
    val namespace: String,
    val unitId: Int,
    val liked: Boolean,
    val updatedAt: Long,
    val pendingSync: Boolean,
)
