package com.east.time.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条使用事件。
 *
 * 刻意**不加**唯一索引。事件时间戳只精确到秒，同一秒内完全可能出现
 * `RESUME→PAUSE→RESUME` 这样同包名、同类型的多条记录，用
 * `(tsMillis, packageName, eventType)` 做唯一键会误删合法事件。
 * 幂等改用"整窗替换"实现 —— 见 [com.east.time.sync.SyncRepository]。
 */
@Entity(
    tableName = "usage_event",
    indices = [Index("tsMillis"), Index("packageName")]
)
data class UsageEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tsMillis: Long,
    /** 恒为 0。公开 API 读不到第二空间(user 999)的数据 */
    val userId: Int,
    val packageName: String,
    /** 全局事件（亮灭屏、锁屏）没有 class，为 null */
    val className: String?,
    val eventType: String,
)
