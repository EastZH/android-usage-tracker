package com.east.time.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UsageDao {

    // ---------- 原始事件 ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<UsageEvent>)

    /**
     * 清掉窗口内的全部事件，配合随后的全量写回实现幂等。
     * 窗口之外（更早）的事件早就落库且无法再取到，必须保留。
     */
    @Query("DELETE FROM usage_event WHERE tsMillis >= :windowStart")
    suspend fun deleteEventsFrom(windowStart: Long)

    /** 保留策略：只留有界窗口，否则三年下来会到千万行级别 */
    @Query("DELETE FROM usage_event WHERE tsMillis < :before")
    suspend fun deleteEventsBefore(before: Long)

    @Query("SELECT COUNT(*) FROM usage_event")
    suspend fun eventCount(): Long

    @Query("SELECT MAX(tsMillis) FROM usage_event")
    suspend fun latestEventTs(): Long?

    @Query("SELECT * FROM usage_event WHERE tsMillis >= :from ORDER BY tsMillis")
    suspend fun eventsSince(from: Long): List<UsageEvent>

    /** 重建日聚合时用；依赖保留策略把结果限制在有界规模内 */
    @Query("SELECT * FROM usage_event ORDER BY tsMillis")
    suspend fun allEventsOrdered(): List<UsageEvent>

    /**
     * 重建某一天的聚合时用。区间要**前后各放宽一天**：
     * 跨零点的会话（昨晚 23:50 用到今天 00:10）起点在区间之外，
     * 不放宽就会找不到 `RESUMED` 而整段丢掉。
     */
    @Query("SELECT * FROM usage_event WHERE tsMillis >= :from AND tsMillis < :to ORDER BY tsMillis")
    suspend fun eventsBetween(from: Long, to: Long): List<UsageEvent>

    @Query("SELECT MIN(tsMillis) FROM usage_event")
    suspend fun earliestEventTs(): Long?

    // ---------- 系统日聚合（对照 / 兜底） ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDailyStats(stats: List<DailyStat>)

    @Query("SELECT COUNT(*) FROM daily_stat")
    suspend fun dailyStatCount(): Long

    /** 回填历史用：系统只给最近 10 天，所以要趁它还没滚出窗口时抄进我们自己的表 */
    @Query("SELECT * FROM daily_stat")
    suspend fun allDailyStats(): List<DailyStat>

    // ---------- 自建日聚合（永久保留） ----------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRollups(rollups: List<DailyRollup>)

    @Query("DELETE FROM daily_rollup WHERE date >= :fromDate AND date <= :toDate")
    suspend fun deleteRollupsBetween(fromDate: String, toDate: String)

    @Query("SELECT * FROM daily_rollup WHERE date >= :fromDate AND date <= :toDate")
    suspend fun rollupsBetween(fromDate: String, toDate: String): List<DailyRollup>

    @Query("SELECT COUNT(*) FROM daily_rollup")
    suspend fun rollupCount(): Long

    @Query("SELECT MAX(date) FROM daily_rollup")
    suspend fun latestRollupDate(): String?

    @Query("SELECT MIN(date) FROM daily_rollup")
    suspend fun minRollupDate(): String?

    /** 导出全量快照用。三年约 5.5 万行，一次性读出没问题 */
    @Query("SELECT * FROM daily_rollup ORDER BY date, packageName")
    suspend fun allRollups(): List<DailyRollup>

    /** 用于在界面上标注"这些天是系统回填的、边界有误差"。date 是 yyyy-MM-dd，字典序即时间序 */
    @Query("SELECT COUNT(DISTINCT date) FROM daily_rollup WHERE date < :beforeDate")
    suspend fun countRollupDaysBefore(beforeDate: String): Long
}
