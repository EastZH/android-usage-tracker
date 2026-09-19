package com.east.time.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * ## 关于 schema 变更的硬性约定
 *
 * **绝对不要用 `fallbackToDestructiveMigration()`。** 它的行为是"版本对不上就把
 * 整个数据库删掉重建"—— 开发期看着方便,但这个 App 的立身之本就是长期留存数据
 * （日聚合要存三年）。一次表结构改动就会把积累的全部记录清空,而且不报错。
 *
 * 每次改 `@Entity` 都必须:
 *  1. 提升 [Database.version]
 *  2. 在 [MIGRATIONS] 里补一条对应的 Migration
 *  3. 在真机上跑一遍升级(装旧版 → 装新版 → 确认数据还在)
 *
 * 开发早期已经吃过一次亏:v1→v2 用了破坏性迁移,数据库被重建,
 * 界面上直接显示"事件 0"。
 */
@Database(
    entities = [UsageEvent::class, DailyStat::class, DailyRollup::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun usageDao(): UsageDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v1 → v2：新增 daily_rollup 表。只建表，不动已有数据。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `daily_rollup` (" +
                        "`date` TEXT NOT NULL, " +
                        "`packageName` TEXT NOT NULL, " +
                        "`foregroundMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`date`, `packageName`))",
                )
            }
        }

        private val MIGRATIONS = arrayOf(MIGRATION_1_2)

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "usage.db",
                ).addMigrations(*MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
    }
}
