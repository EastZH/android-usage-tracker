package com.east.time.data

import androidx.room.Entity

/**
 * 我们自己从事件流推导出的**日聚合**，永久保留。
 *
 * 为什么不直接用 [DailyStat]（系统给的日聚合）：系统的"日"桶边界不对齐零点
 * （实测本机 00:56），拿它当"今天"会和直觉差近一小时。这里的日期是自然日。
 *
 * ## 为什么必须单独存一张表
 * `usage_event` 每天约 1 万行，三年就是 1100 万行（约 2GB），手机上撑不住。
 * 而日聚合每天只有约 50 行，三年约 5.5 万行（几 MB）—— 所以策略是：
 * **原始事件只留一个有界窗口，日聚合永久保留。**
 *
 * 代价是无法回溯：系统的原始事件只有 24 小时、日聚合只有 10 天，
 * 装这个 App 之前的使用记录拿不回来，三年是从开始记录那天算起。
 *
 * 注意：**今天不写这张表**。今天还在进行中，每次同步重算都是不完整的；
 * 今天的数值由 UI 从事件实时推导（这样"此刻正在用的 App"才算得准）。
 */
@Entity(
    tableName = "daily_rollup",
    primaryKeys = ["date", "packageName"],
)
data class DailyRollup(
    /** yyyy-MM-dd，自然日 */
    val date: String,
    val packageName: String,
    /** 当天该 App 的前台总时长 */
    val foregroundMs: Long,
)
