package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.PhotoItem
import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.data.db.DayBucketEntity
import com.hawwwran.photosonthisday.data.db.DayIndexDao
import com.hawwwran.photosonthisday.data.db.IndexMetaEntity
import com.hawwwran.photosonthisday.data.db.ItemRowEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [DayIndexStore] over Room. Thin: the conversion is the only logic, and it is symmetric. */
class RoomDayIndexStore(private val dao: DayIndexDao) : DayIndexStore {

    override fun buckets(): Flow<List<NamespacedDayBucket>> =
        dao.buckets().map { rows -> rows.map(::toDomain) }

    override fun refreshedAt(): Flow<Long?> = dao.refreshedAt()

    override suspend fun replaceBuckets(byNamespace: Map<Space, List<DayBucket>>, refreshedAt: Long?) {
        dao.replaceBuckets(
            namespaces = byNamespace.keys.map { it.name },
            rows = byNamespace.flatMap { (space, days) -> days.map { toEntity(space, it) } },
            meta = refreshedAt?.let { IndexMetaEntity(refreshedAt = it) },
        )
    }

    override fun items(year: Int, monthDay: MonthDay): Flow<List<PhotoItem>> =
        dao.itemsForDay(year, monthDay.month, monthDay.day).map { rows -> rows.map(::toPhoto) }

    override suspend fun replaceDayItems(space: Space, year: Int, monthDay: MonthDay, items: List<PhotoItem>) {
        dao.replaceDayItems(
            namespace = space.name,
            year = year,
            month = monthDay.month,
            day = monthDay.day,
            rows = items.map { toItemRow(it, year, monthDay) },
        )
    }

    override suspend fun clear() = dao.clear()

    private fun toDomain(row: DayBucketEntity) = NamespacedDayBucket(
        space = Space.valueOf(row.namespace),
        bucket = DayBucket(row.year, MonthDay(row.month, row.day), row.itemCount),
    )

    private fun toPhoto(row: ItemRowEntity) = PhotoItem(
        space = Space.valueOf(row.namespace),
        id = row.id,
        unitId = row.unitId,
        cacheKey = row.cacheKey,
        takenTimeSeconds = row.takenTime,
        isVideo = row.isVideo,
        width = row.width,
        height = row.height,
        filename = row.filename,
        folderId = row.folderId,
        filesize = row.filesize,
    )

    private fun toItemRow(item: PhotoItem, year: Int, monthDay: MonthDay) = ItemRowEntity(
        namespace = item.space.name,
        id = item.id,
        unitId = item.unitId,
        cacheKey = item.cacheKey,
        takenTime = item.takenTimeSeconds,
        isVideo = item.isVideo,
        width = item.width,
        height = item.height,
        filename = item.filename,
        folderId = item.folderId,
        filesize = item.filesize,
        year = year,
        month = monthDay.month,
        day = monthDay.day,
    )

    private fun toEntity(space: Space, bucket: DayBucket) = DayBucketEntity(
        namespace = space.name,
        year = bucket.year,
        month = bucket.monthDay.month,
        day = bucket.monthDay.day,
        itemCount = bucket.itemCount,
    )
}
