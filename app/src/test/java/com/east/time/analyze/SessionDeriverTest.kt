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

    // ---------- 悬空区间必须被息屏/锁屏/关机截断 ----------
    //
    // 应用被系统杀掉、或因安装卸载被 force-stop 时不会产生 PAUSED/STOPPED 事件，
    // 那个区间就永远开着。若不截断，"悬空区间算到窗口末尾"的逻辑会让它一路算下去
    // —— 表现为"我明明没开它，却显示用了好几个小时"，且数字随时间增长。

    @Test
    fun `息屏截断悬空区间`() {
        val events = listOf(
            resume(100, "p"),                                  // 打开
            ev(200, "android", "SCREEN_NON_INTERACTIVE"),      // 息屏 —— 不可能还在前台
        )
        // 窗口拉到 1000s。没有截断的话会被算成 900s。
        val r = SessionDeriver.foregroundMillis(events, 0, 1_000_000)
        assertEquals(100_000L, r["p"])
    }

    @Test
    fun `锁屏也能截断悬空区间`() {
        val events = listOf(
            resume(100, "p"),
            ev(150, "android", "KEYGUARD_SHOWN"),
        )
        val r = SessionDeriver.foregroundMillis(events, 0, 1_000_000)
        assertEquals(50_000L, r["p"])
    }

    @Test
    fun `关机也能截断悬空区间`() {
        val events = listOf(
            resume(100, "p"),
            ev(120, "android", "DEVICE_SHUTDOWN"),
        )
        val r = SessionDeriver.foregroundMillis(events, 0, 1_000_000)
        assertEquals(20_000L, r["p"])
    }

    @Test
    fun `息屏一次截断所有还开着的应用`() {
        val events = listOf(
            resume(100, "p"),
            resume(110, "q"),
            ev(200, "android", "SCREEN_NON_INTERACTIVE"),
        )
        val r = SessionDeriver.foregroundMillis(events, 0, 1_000_000)
        assertEquals(100_000L, r["p"])
        assertEquals(90_000L, r["q"])
    }

    @Test
    fun `息屏之后重新亮屏开启的新区间不受影响`() {
        val events = listOf(
            resume(100, "p"),
            ev(200, "android", "SCREEN_NON_INTERACTIVE"),   // 截断 → p 得 100s
            resume(300, "p"),
            pause(350, "p"),                                 // 正常闭合 → 再得 50s
        )
        val r = SessionDeriver.foregroundMillis(events, 0, 1_000_000)
        assertEquals(150_000L, r["p"])
    }

    @Test
    fun `普通全局事件不应截断区间`() {
        // STANDBY_BUCKET_CHANGED / CONFIGURATION_CHANGE 之类不代表离开前台，
        // 如果把它们也当成截断信号，正常使用会被切得七零八落。
        val events = listOf(
            resume(100, "p"),
            ev(150, "p", "STANDBY_BUCKET_CHANGED"),
            ev(200, "p", "CONFIGURATION_CHANGE"),
            pause(300, "p"),
        )
        val r = SessionDeriver.foregroundMillis(events, 0, 1_000_000)
        assertEquals(200_000L, r["p"])
    }
}
