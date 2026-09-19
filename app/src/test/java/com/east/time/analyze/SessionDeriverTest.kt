package com.east.time.analyze

import com.east.time.data.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDeriverTest {

    private var seq = 0L

    private fun ev(tsSec: Long, pkg: String, type: String, cls: String = "A"): UsageEvent =
        UsageEvent(
            id = seq++,
            tsMillis = tsSec * 1000,
            userId = 0,
            packageName = pkg,
            className = cls,
            eventType = type,
        )

    private fun resume(ts: Long, pkg: String, cls: String = "A") =
        ev(ts, pkg, SessionDeriver.TYPE_RESUMED, cls)

    private fun pause(ts: Long, pkg: String, cls: String = "A") =
        ev(ts, pkg, SessionDeriver.TYPE_PAUSED, cls)

    private fun stop(ts: Long, pkg: String, cls: String = "A") =
        ev(ts, pkg, SessionDeriver.TYPE_STOPPED, cls)

    @Test
    fun `简单的一开一合`() {
        val events = listOf(resume(100, "p"), pause(110, "p"))
        val r = SessionDeriver.foregroundMillis(events, 0, 200_000)
        assertEquals(10_000L, r["p"])
    }

    @Test
    fun `悬空区间算到窗口末尾`() {
        // 只有 resume，没有 pause —— 对应"此刻正在用这个 App"
        val events = listOf(resume(100, "p"))
        val r = SessionDeriver.foregroundMillis(events, 0, 130_000)
        assertEquals(30_000L, r["p"])
    }

    @Test
    fun `区间被窗口裁掉超出部分`() {
        val events = listOf(resume(50, "p"), pause(150, "p"))
        // 窗口只取 [100s, 120s]
        val r = SessionDeriver.foregroundMillis(events, 100_000, 120_000)
        assertEquals(20_000L, r["p"])
    }

    @Test
    fun `同包内页面切换不丢时间`() {
        // A pause 与 B resume 同一时刻 —— 包始终在前台，应当连续计算
        val events = listOf(
            resume(100, "p", "A"),
            pause(110, "p", "A"),
            resume(110, "p", "B"),
            pause(120, "p", "B"),
        )
        val r = SessionDeriver.foregroundMillis(events, 0, 200_000)
        assertEquals(20_000L, r["p"])
    }

    @Test
    fun `全局事件不参与前台判定`() {
        val events = listOf(
            ev(100, "android", "SCREEN_INTERACTIVE"),
            ev(105, "android", "KEYGUARD_SHOWN"),
        )
        val r = SessionDeriver.foregroundMillis(events, 0, 200_000)
        assertEquals(0, r.size)
    }

    @Test
    fun `多实例同 className 不会提前结束`() {
        // 两个 activity 实例共用同一个 className（公开 API 拿不到 instanceId）。
        // pause 掉其中一个，另一个仍应视为前台。
        val events = listOf(
            resume(100, "p", "A"),
            resume(105, "p", "A"),
            pause(110, "p", "A"),
            pause(120, "p", "A"),
        )
        val r = SessionDeriver.foregroundMillis(events, 0, 200_000)
        // 集合语义下第二个 resume 是幂等的，所以 110s 时集合已空 → 只算 10s
        assertEquals(10_000L, r["p"])
    }

    @Test
    fun `按包分别累计`() {
        val events = listOf(
            resume(100, "p"), pause(110, "p"),
            resume(105, "q"), pause(130, "q"),
        )
        val r = SessionDeriver.foregroundMillis(events, 0, 200_000)
        assertEquals(10_000L, r["p"])
        assertEquals(25_000L, r["q"])
    }

    @Test
    fun `停止事件也能闭合区间`() {
        val events = listOf(resume(100, "p"), stop(115, "p"))
        val r = SessionDeriver.foregroundMillis(events, 0, 200_000)
        assertEquals(15_000L, r["p"])
    }

    // ---------- sessions()：时间轴要用区间，不只是总时长 ----------

    @Test
    fun `sessions 返回区间且按开始时间升序`() {
        val events = listOf(
            resume(200, "b"), pause(210, "b"),
            resume(100, "a"), pause(110, "a"),
        )
        val s = SessionDeriver.sessions(events, 0, 300_000)
        assertEquals(2, s.size)
        assertEquals("a", s[0].packageName)
        assertEquals(100_000L, s[0].startMs)
        assertEquals(110_000L, s[0].endMs)
        assertEquals("b", s[1].packageName)
    }

    @Test
    fun `sessions 与 foregroundMillis 必须一致`() {
        val events = listOf(
            resume(100, "p"), pause(160, "p"),
            resume(180, "p"), pause(200, "p"),
            resume(250, "q"), pause(280, "q"),
        )
        val sessions = SessionDeriver.sessions(events, 120_000, 260_000)
        val fromTotals = SessionDeriver.foregroundMillis(events, 120_000, 260_000)
        val fromSessions = sessions.groupBy { it.packageName }
            .mapValues { (_, v) -> v.sumOf { it.durationMs } }

        assertEquals(fromTotals, fromSessions)
        // 窗口 [120s,260s] 内：p 有 [120,160] 和 [180,200]，q 有 [250,260]
        assertEquals(60_000L, fromSessions["p"])
        assertEquals(10_000L, fromSessions["q"])
    }

    @Test
    fun `跨窗口的区间被裁到窗口边界`() {
        val events = listOf(resume(50, "p"), pause(300, "p"))
        val s = SessionDeriver.sessions(events, 100_000, 200_000)
        assertEquals(1, s.size)
        assertEquals(100_000L, s[0].startMs)
        assertEquals(200_000L, s[0].endMs)
    }

    @Test
    fun `没有前台时 sessions 为空`() {
        val events = listOf(resume(100, "p"), pause(110, "p"))
        // 窗口完全落在区间之外
        assertEquals(0, SessionDeriver.sessions(events, 500_000, 600_000).size)
    }
}
