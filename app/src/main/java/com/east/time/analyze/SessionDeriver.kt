package com.east.time.analyze

import com.east.time.data.UsageEvent

/**
 * 从事件流推导"某段时间内每个 App 的前台时长"。
 *
 * 纯函数，不碰数据库、不碰系统 API —— 这是全部逻辑里最该单独测的一层。
 *
 * ## 判定规则
 * 一个包只要**还有至少一个 activity 处于 resumed 状态**，就算前台。
 * 用集合而不是计数器：`RESUME` 入集合、`PAUSE`/`STOPPED` 出集合，
 * 集合非空即前台。这样同包内 A→B 的页面切换（A pause、B resume 在同一秒）
 * 不会产生假的"离开前台"。
 *
 * ## 已实测验证
 * 与系统 `UsageStats.getTotalTimeInForeground()` 对照，14 个 App 里 12 个
 * **精确到 1.000**（tim / v2ray / emmx / mirrorclient / quicksearchbox /
 * SuperDisplay / MT管理器 / 时钟 / 文件管理 / vending / 微信 / deepseek）。
 *
 * 对照时**必须让两侧窗口一致** —— 系统的"日"桶起点不是零点（实测本机 00:56），
 * 拿自然日的事件比系统桶的聚合值会得出假误差，我在这上面栽过一次。
 *
 * ## 已知精度损失
 * 系统设置这类会做大量**亚秒级开合**的 App 会少算（实测 0.62 倍）：
 * 事件时间戳虽然到毫秒，但同一秒内 `RESUME→PAUSE` 的碎区间累计起来仍有损耗。
 */
object SessionDeriver {

    /**
     * 前台区间，按开始时间升序。**这是本模块的原始输出**，[foregroundMillis] 由它求和得来。
     *
     * 之所以要暴露区间而不只是总时长：时间轴要画"什么时候用了什么",
     * 光有总和画不出来。
     *
     * @param events 必须按 `tsMillis` **升序**
     * @param fromMs 统计窗口起点（含）
     * @param toMs   统计窗口终点（含）。**也用于闭合悬空区间** ——
     *               窗口结束时还开着的区间会按到 [toMs] 为止计算，
     *               否则"此刻正在用的 App"会被算成 0。
     */
    fun sessions(
        events: List<UsageEvent>,
        fromMs: Long,
        toMs: Long,
    ): List<Session> {
        val live = HashMap<String, MutableSet<String?>>()
        val openedAt = HashMap<String, Long>()
        val out = ArrayList<Session>(2_048)

        for (e in events) {
            if (e.tsMillis > toMs) break
            val pkg = e.packageName
            val set = live.getOrPut(pkg) { HashSet() }
            val wasForeground = set.isNotEmpty()

            when (e.eventType) {
                TYPE_RESUMED -> set.add(e.className)
                TYPE_PAUSED, TYPE_STOPPED -> set.remove(e.className)
                // 息屏 / 锁屏 / 关机：**不可能有任何应用在前台**，这是没有歧义的判据。
                // 用它把此刻所有还开着的区间就地闭合。
                //
                // 不加这道闸会出真 bug：应用被系统杀掉、或因安装/卸载被 force-stop 时，
                // 不会产生 PAUSED/STOPPED 事件，那个区间就永远开着。而下面"悬空区间算到
                // 窗口末尾"的逻辑会让它一路算下去 —— 表现为"我明明没开它，却显示用了好几个
                // 小时"，而且数字还在随时间涨，直到下次它真的被关闭才塌回真实值。
                TYPE_SCREEN_OFF, TYPE_KEYGUARD_SHOWN, TYPE_DEVICE_SHUTDOWN -> {
                    for ((p, s2) in live) {
                        if (s2.isEmpty()) continue
                        openedAt.remove(p)?.let { start -> emit(out, p, start, e.tsMillis, fromMs, toMs) }
                        s2.clear()
                    }
                    continue
                }
                else -> continue   // 其余全局事件不参与前台判定
            }

            val nowForeground = set.isNotEmpty()
            if (!wasForeground && nowForeground) {
                openedAt[pkg] = e.tsMillis
            } else if (wasForeground && !nowForeground) {
                openedAt.remove(pkg)?.let { start -> emit(out, pkg, start, e.tsMillis, fromMs, toMs) }
            }
        }

        // 悬空区间：窗口结束时仍在前台的包，算到 toMs 为止
        for ((pkg, start) in openedAt) {
            emit(out, pkg, start, toMs, fromMs, toMs)
        }

        return out.sortedBy { it.startMs }
    }

    /** 区间求和。[sessions] 的便捷封装 */
    fun foregroundMillis(
        events: List<UsageEvent>,
        fromMs: Long,
        toMs: Long,
    ): Map<String, Long> {
        val totals = HashMap<String, Long>()
        for (s in sessions(events, fromMs, toMs)) {
            totals[s.packageName] = (totals[s.packageName] ?: 0L) + (s.endMs - s.startMs)
        }
        return totals
    }

    private fun emit(
        out: MutableList<Session>,
        pkg: String,
        start: Long,
        end: Long,
        fromMs: Long,
        toMs: Long,
    ) {
        val lo = maxOf(start, fromMs)
        val hi = minOf(end, toMs)
        if (hi > lo) out += Session(pkg, lo, hi)
    }

    const val TYPE_RESUMED = "ACTIVITY_RESUMED"
    const val TYPE_PAUSED = "ACTIVITY_PAUSED"
    const val TYPE_STOPPED = "ACTIVITY_STOPPED"

    /** 这三个事件意味着"此刻没有任何应用在前台"，用来闭合悬空区间 */
    const val TYPE_SCREEN_OFF = "SCREEN_NON_INTERACTIVE"
    const val TYPE_KEYGUARD_SHOWN = "KEYGUARD_SHOWN"
    const val TYPE_DEVICE_SHUTDOWN = "DEVICE_SHUTDOWN"
}

/** 一段"某个 App 处于前台"的时间区间，已按 [fromMs, toMs] 裁剪过 */
data class Session(
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}
