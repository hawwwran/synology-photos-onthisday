package com.hawwwran.photosonthisday.data

import com.hawwwran.photosonthisday.api.Space
import com.hawwwran.photosonthisday.core.DayBucket
import com.hawwwran.photosonthisday.core.MonthDay
import com.hawwwran.photosonthisday.data.db.DayBucketEntity
import com.hawwwran.photosonthisday.data.db.DayIndexDao
import com.hawwwran.photosonthisday.data.db.IndexMetaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** [DayIndexStore] over Room. Thin: the conversion is the only logic, and it is symmetric. */
class RoomDayIndexStore(private val dao: DayIndexDao) : DayIndexStore {

    override fun buckets(): Flow<List<NamespacedDayBucket>> =
        dao.buckets().map { rows -> rows.map(::toDomain) }

    override fun refreshedAt(): Flow<Long?> = dao.refreshedAt()

    override suspend fun replace(space: Space, days: List<DayBucket>) {
        dao.replaceNamespace(space.name, days.map { toEntity(space, it) })
    }

    override suspend fun setRefreshedAt(epochMillis: Long) {
        dao.setMeta(IndexMetaEntity(refreshedAt = epochMillis))
    }

    override suspend fun clear() = dao.clear()

    private fun toDomain(row: DayBucketEntity) = NamespacedDayBucket(
        space = Space.valueOf(row.namespace),
        bucket = DayBucket(row.year, MonthDay(row.month, row.day), row.itemCount),
    )

    private fun toEntity(space: Space, bucket: DayBucket) = DayBucketEntity(
        namespace = space.name,
        year = bucket.year,
        month = bucket.monthDay.month,
        day = bucket.monthDay.day,
        itemCount = bucket.itemCount,
    )
}
