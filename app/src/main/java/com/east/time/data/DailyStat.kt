package com.east.time.data

import androidx.room.Entity

/**
 * 系统自己的**日聚合**统计。
 *
 * 存在的意义有两个：
 *  1. 作为事件流的**交叉校验基准** —— 两路数据独立，吻合才说明采集没错
 *  2. 事件窗口只有 24 小时，而日聚合保留 10 天；万一某天同步失败，
 *     这里还能兜住一部分数据
 */
@Entity(
    tableName = "daily_stat",
    primaryKeys = ["date", "userId", "packageName"]
)
data class DailyStat(
    /** yyyy-MM-dd */
    val date: String,
    val userId: Int,
    val packageName: String,
    val totalTimeUsedMs: Long,
    val totalTimeVisibleMs: Long,
    /** 前台服务时长。`UsageStats.getTotalTimeForegroundServiceUsed()` */
    val totalTimeFsMs: Long,
    val lastTimeUsed: Long?,
)
