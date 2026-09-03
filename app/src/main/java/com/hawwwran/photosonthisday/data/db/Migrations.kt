package com.hawwwran.photosonthisday.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 4 to 5 (plan 008): an index on `item_row(year, month, day)`, the `needsRefresh` flag on
 * `index_meta`, and `item_like` without `pendingSync`. Versions 1 to 3 were never installed
 * outside development, so 4 is the oldest schema a migration has to start from.
 *
 * The like table is rebuilt rather than altered: `ALTER TABLE ... DROP COLUMN` needs SQLite 3.35,
 * which arrives with API 34, and minSdk is below that. The copy keeps every row, including a like
 * whose push failed, which is the point of migrating instead of dropping.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_row_year_month_day` ON `item_row` (`year`, `month`, `day`)")
        db.execSQL("ALTER TABLE `index_meta` ADD COLUMN `needsRefresh` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `item_like_new` (`namespace` TEXT NOT NULL, `unitId` INTEGER NOT NULL, " +
                "`liked` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`namespace`, `unitId`))",
        )
        db.execSQL("INSERT INTO `item_like_new` (`namespace`, `unitId`, `liked`, `updatedAt`) SELECT `namespace`, `unitId`, `liked`, `updatedAt` FROM `item_like`")
        db.execSQL("DROP TABLE `item_like`")
        db.execSQL("ALTER TABLE `item_like_new` RENAME TO `item_like`")
    }
}

/** Every migration the database builder registers, oldest first. */
val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_4_5)
