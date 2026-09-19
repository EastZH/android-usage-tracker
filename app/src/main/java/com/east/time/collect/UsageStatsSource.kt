package com.east.time.collect

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.Calendar

/**
 * 从 `UsageStatsManager` 读取使用数据。
 *
 * 权限 `PACKAGE_USAGE_STATS` 是 appop 型权限，不在运行时弹窗。两种授予方式：
 *  1. 一次性 adb：`adb shell appops set com.east.time GET_USAGE_STATS allow`
 *  2. 用户在 Settings → Special app access → Usage access 里手动开
 *
 * **appops 授予是持久化的，重启后依然有效。**
 *
 * 数据源选择上踩过的坑：最初打算用 `dumpsys usagestats`（shell 身份零权限门槛，
 * 而且字段更全），但 Shizuku 在 Android 14 上启动不了 —— v13 已移除 `start.sh`，
 * 只剩无线调试一条路，而本机是热点模式开不了无线调试。详见项目 plan。
 *
 * ## 与 dumpsys 相比少了什么
 * 下面这些字段 dumpsys 有、公开 API 没有（都是 @hide），已确认无法获取：
 *   - `instanceId`、`taskRootPackage`（多任务/多窗口归属）
 *   - `NOTIFICATION_INTERRUPTION`、`NOTIFICATION_SEEN` 两种事件类型
 *   - `appLaunchCount`（启动次数，可用事件流自行推导，v1 未做）
 *
 * ## 已知限制
 * 只能看到当前用户（user 0）。应用分身/第二空间（user 999）的记录读不到 ——
 * 那需要 INTERACT_ACROSS_USERS，普通应用拿不到。
 */
object UsageStatsSource {

    /**
     * 事件类型是 int，这里映射成与 `dumpsys usagestats` 输出一致的名字，
     * 方便和之前那轮探测结果对照排查。
     *
     * 注意：`ACTIVITY_RESUMED` 与已废弃的 `MOVE_TO_FOREGROUND` 是同一个 int 值(1)，
     * 两个都写会因 `when` 分支重复而编译报错，写一个即可。
     */
    private fun typeName(eventType: Int): String? = when (eventType) {
        UsageEvents.Event.ACTIVITY_RESUMED -> "ACTIVITY_RESUMED"
        UsageEvents.Event.ACTIVITY_PAUSED -> "ACTIVITY_PAUSED"
        UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
        UsageEvents.Event.SCREEN_INTERACTIVE -> "SCREEN_INTERACTIVE"
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> "SCREEN_NON_INTERACTIVE"
        UsageEvents.Event.KEYGUARD_SHOWN -> "KEYGUARD_SHOWN"
        UsageEvents.Event.KEYGUARD_HIDDEN -> "KEYGUARD_HIDDEN"
        UsageEvents.Event.FOREGROUND_SERVICE_START -> "FOREGROUND_SERVICE_START"
        UsageEvents.Event.FOREGROUND_SERVICE_STOP -> "FOREGROUND_SERVICE_STOP"
        UsageEvents.Event.STANDBY_BUCKET_CHANGED -> "STANDBY_BUCKET_CHANGED"
        UsageEvents.Event.CONFIGURATION_CHANGE -> "CONFIGURATION_CHANGE"
        UsageEvents.Event.SHORTCUT_INVOCATION -> "SHORTCUT_INVOCATION"
        UsageEvents.Event.USER_INTERACTION -> "USER_INTERACTION"
        // 这两个能用来判断手机什么时候重启过
        UsageEvents.Event.DEVICE_STARTUP -> "DEVICE_STARTUP"
        UsageEvents.Event.DEVICE_SHUTDOWN -> "DEVICE_SHUTDOWN"
        else -> null   // 含 NONE(0) 及所有 @hide 类型
    }

    /**
     * 权限检查。appops 读数为 MODE_DEFAULT 时无法判定（可能没授，也可能授了但读数如此），
     * 所以退回实测一次查询 —— 没权限时 `queryEvents` 会立刻返回空。
     */
    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        if (mode == AppOpsManager.MODE_ALLOWED) return true
        if (mode == AppOpsManager.MODE_ERRORED) return false

        val now = System.currentTimeMillis()
        return queryEvents(context, now - DAY_MS, now).isNotEmpty()
    }

    fun queryEvents(context: Context, fromMillis: Long, toMillis: Long): List<RawEvent> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val iterator = usm.queryEvents(fromMillis, toMillis) ?: return emptyList()

        val out = ArrayList<RawEvent>(16_384)
        val event = UsageEvents.Event()
        while (iterator.hasNextEvent()) {
            iterator.getNextEvent(event)
            val type = typeName(event.eventType) ?: continue
            val pkg = event.packageName ?: continue
            out += RawEvent(
                tsMillis = event.timeStamp,
                userId = 0,
                packageName = pkg,
                className = event.className,
                eventType = type,
            )
        }
        return out
    }

    /**
     * 读最近 [days] 天的日聚合。
     *
     * **坑（踩过）**：Android 的"日"桶边界不对齐零点 —— 实测本机在 00:56 左右轮转
     * （dumpsys 里 `In-memory daily stats timeRange="9/19/2026, 00:56 – 16:26"` 可见）。
     * 而 `queryUsageStats` 返回的是**所有与查询区间有重叠的桶**。
     *
     * 所以早先"逐天查询、拿查询区间端点当日期"的写法是错的：查 `[9/19 00:00, now]`
     * 和查 `[9/18 00:00, 9/19 00:00]` 会命中同一个桶 `[9/18 00:56, 9/19 00:56)`，
     * 于是两天的数值完全相同（Telegram/Twitter 都是这样暴露的）。
     *
     * 正确做法：一次查整个区间，**用每个桶自己的 `firstTimeStamp` 推日期**，
     * 同一 (日期,包名) 来自多个桶时累加而不是覆盖。
     *
     * 单位注意：这里的三个时长返回值都是**毫秒**。
     */
    fun queryDailyStats(context: Context, days: Int = 10): List<RawDailyStat> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = startOfDay(now, days - 1)
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, now)
            ?: return emptyList()

        val merged = HashMap<Pair<String, String>, RawDailyStat>(1_024)
        for (s in stats) {
            val pkg = s.packageName ?: continue
            if (s.totalTimeInForeground <= 0L && s.lastTimeUsed <= 0L) continue

            val date = formatDate(s.firstTimeStamp)
            val key = date to pkg
            val prev = merged[key]
            merged[key] = if (prev == null) {
                RawDailyStat(
                    date = date,
                    userId = 0,
                    packageName = pkg,
                    totalTimeUsedMs = s.totalTimeInForeground,
                    totalTimeVisibleMs = s.totalTimeVisible,
                    totalTimeFsMs = s.totalTimeForegroundServiceUsed,
                    lastTimeUsed = s.lastTimeUsed.takeIf { it > 0L },
                )
            } else {
                prev.copy(
                    totalTimeUsedMs = prev.totalTimeUsedMs + s.totalTimeInForeground,
                    totalTimeVisibleMs = prev.totalTimeVisibleMs + s.totalTimeVisible,
                    totalTimeFsMs = prev.totalTimeFsMs + s.totalTimeForegroundServiceUsed,
                    lastTimeUsed = maxOf(prev.lastTimeUsed ?: 0L, s.lastTimeUsed).takeIf { it > 0L },
                )
            }
        }
        return merged.values.toList()
    }

    private fun startOfDay(now: Long, daysAgo: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun formatDate(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )
    }

    const val DAY_MS = 24L * 60 * 60 * 1000
}

data class RawEvent(
    val tsMillis: Long,
    val userId: Int,
    val packageName: String,
    val className: String?,
    val eventType: String,
)

data class RawDailyStat(
    val date: String,
    val userId: Int,
    val packageName: String,
    val totalTimeUsedMs: Long,
    val totalTimeVisibleMs: Long,
    val totalTimeFsMs: Long,
    val lastTimeUsed: Long?,
)
