package com.hawwwran.photosonthisday.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
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

/**
 * A single row holding when the index was last refreshed, for the stale-index policy, and
 * whether a day fetch found the histogram out of date ([needsRefresh]), which the next open
 * honours once and the next successful refresh clears (decision 005, amended 2026-09-03).
 */
@Entity(tableName = "index_meta")
data class IndexMetaEntity(
    @PrimaryKey val id: Int = SINGLETON,
    val refreshedAt: Long,
    @ColumnInfo(defaultValue = "0") val needsRefresh: Boolean = false,
) {
    companion object {
        const val SINGLETON = 0
    }
}

/**
 * A cached item of one opened day. Keyed by (namespace, id): one photo, one namespace. The day
 * columns let a day be queried and replaced as a unit when it is reopened; they are indexed
 * because every shown year keeps a collector on its day, and Room re-runs all of them on any
 * write to the table. A photo whose taken date is corrected in Photos moves days: the row is
 * upserted under the new day rather than inserted beside the old one.
 */
@Entity(
    tableName = "item_row",
    primaryKeys = ["namespace", "id"],
    indices = [Index("year", "month", "day")],
)
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
 * file (decision 008); a local change reaches it through the next sync, last writer by
 * [updatedAt] winning, so no pending marker is needed. Keyed like the item.
 */
@Entity(tableName = "item_like", primaryKeys = ["namespace", "unitId"])
data class LikeEntity(
    val namespace: String,
    val unitId: Int,
    val liked: Boolean,
    val updatedAt: Long,
)
