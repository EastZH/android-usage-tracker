package com.east.time.analyze

import kotlin.math.max
import kotlin.math.min

/**
 * 把时间区间切成等分的格子，每格报出**占该格时间最长的那个应用**。
 *
 * 用途是把连续的时间轴离散成可以点选的格子（一天 24 格 = 每格一小时，
 * 或一小时 60 格 = 每格一分钟）。
 *
 * 为什么是"取占比最大的"而不是"取格子起点时的应用"：格子边界是任意切的，
 * 如果按起点取，一个跨了 99% 格子的应用会因为起点差 1 毫秒而被忽略。
 * 按占比取最符合直觉 —— 这一格里你主要在用什么，就显示什么。
 *
 * **平局规则**：用严格大于比较，所以两个应用在该格里占用**完全相等**时，
 * 按 [sessions] 的顺序**先出现的胜出**。不引入"更专用的应用优先"之类的
 * 启发式 —— 那会让结果依赖额外的猜测，且难以测试。
 *
 * 纯函数，不碰数据库也不碰 View，可以单独测。
 */
object Bucketizer {

    /**
     * @param sessions 已按开始时间升序（[SessionDeriver.sessions] 的输出）
     * @param fromMs   整个区间的起点
     * @param toMs     整个区间的终点
     * @param count    切几格
     * @return 每格的包名；该格完全没有前台活动时为 null
     */
    fun dominantPerBucket(
        sessions: List<Session>,
        fromMs: Long,
        toMs: Long,
        count: Int,
    ): List<String?> {
        if (count <= 0) return emptyList()
        val span = toMs - fromMs
        if (span <= 0) return List(count) { null }

        val out = ArrayList<String?>(count)
        // 单调游标：格子从左往右推进，已经结束的区间不会再用到
        var lo = 0

        for (i in 0 until count) {
            val bs = fromMs + span * i / count
            val be = fromMs + span * (i + 1) / count

            while (lo < sessions.size && sessions[lo].endMs <= bs) lo++

            var best: String? = null
            var bestOverlap = 0L
            var k = lo
            while (k < sessions.size) {
                val s = sessions[k]
                if (s.startMs >= be) break
                val overlap = min(s.endMs, be) - max(s.startMs, bs)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    best = s.packageName
                }
                k++
            }
            out += best
        }
        return out
    }

    /**
     * 每格**是否以黑屏为主**。
     *
     * 用来把"设备没在用"（黑屏）和"数据没记到"（背景色）区分开 —— 两者在时间轴上
     * 都是"没有 App 前台"，但含义完全不同：前者正常，后者说明采集断了。
     *
     * 用"过半"而不是"沾边就算"：一小时里屏幕黑了 5 分钟、用了 55 分钟，那这一格
     * 该显示是"在用"，不是"锁屏"。
     */
    fun screenOffPerBucket(
        offIntervals: List<Interval>,
        fromMs: Long,
        toMs: Long,
        count: Int,
    ): List<Boolean> {
        if (count <= 0) return emptyList()
        val span = toMs - fromMs
        if (span <= 0) return List(count) { false }

        // 先累加每格的黑屏毫秒数，最后再和格子宽度的一半比较
        val offMs = LongArray(count)
        for (iv in offIntervals) {
            if (iv.endMs <= fromMs || iv.startMs >= toMs) continue
            val lo = maxOf(iv.startMs, fromMs)
            val hi = minOf(iv.endMs, toMs)
            var i = ((lo - fromMs) * count / span).toInt().coerceIn(0, count - 1)
            var pos = lo
            while (pos < hi && i < count) {
                val bucketEnd = fromMs + span * (i + 1) / count
                val seg = minOf(hi, bucketEnd)
                offMs[i] += seg - pos
                pos = seg
                i++
            }
        }
        return (0 until count).map { offMs[it] * 2 >= span / count }
    }

    /**
     * 每格的总时长（不只是最长的那个应用）。用来判断这一格"有没有东西"、
     * 以及给格子做深浅着色。
     */
    fun busyMillisPerBucket(
        sessions: List<Session>,
        fromMs: Long,
        toMs: Long,
        count: Int,
    ): List<Long> {
        if (count <= 0) return emptyList()
        val span = toMs - fromMs
        if (span <= 0) return List(count) { 0L }

        val out = LongArray(count)
        for (s in sessions) {
            if (s.endMs <= fromMs || s.startMs >= toMs) continue
            val lo = max(s.startMs, fromMs)
            val hi = min(s.endMs, toMs)
            // 区间可能横跨多格，逐格累加它落在格内的部分
            var i = ((lo - fromMs) * count / span).toInt().coerceIn(0, count - 1)
            var pos = lo
            while (pos < hi && i < count) {
                val bucketEnd = fromMs + span * (i + 1) / count
                val seg = min(hi, bucketEnd)
                out[i] += seg - pos
                pos = seg
                i++
            }
        }
        return out.toList()
    }
}
