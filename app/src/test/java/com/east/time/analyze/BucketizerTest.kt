package com.east.time.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BucketizerTest {

    private fun s(pkg: String, startSec: Long, endSec: Long) =
        Session(pkg, startSec * 1000, endSec * 1000)

    @Test
    fun `每格取占比最大的应用`() {
        // 8 秒区间切 4 格，每格 2 秒
        val sessions = listOf(
            s("a", 0, 3),     // 占格0全部(2s)、格1的1s
            s("b", 3, 6),     // 占格1的1s、格2全部(2s)
            s("c", 6, 8),     // 占格3全部
        )
        val r = Bucketizer.dominantPerBucket(sessions, 0, 8_000, 4)
        assertEquals(listOf("a", "a", "b", "c"), r)
    }

    @Test
    fun `没有活动时该格为 null`() {
        val sessions = listOf(s("a", 0, 2))   // 只占第 0 格(共 4 格，每格 2 秒)
        val r = Bucketizer.dominantPerBucket(sessions, 0, 8_000, 4)
        assertEquals("a", r[0])
        assertNull(r[1])
        assertNull(r[2])
        assertNull(r[3])
    }

    @Test
    fun `完全没数据时全为 null`() {
        val r = Bucketizer.dominantPerBucket(emptyList(), 0, 8_000, 4)
        assertEquals(4, r.size)
        r.forEach { assertNull(it) }
    }

    @Test
    fun `一格里的长应用压过许多短切换`() {
        // 格0 = [0,10s)：a 占了 6s，然后 b/c/d 各 1s 多
        val sessions = listOf(
            s("a", 0, 6),
            s("b", 6, 7), s("c", 7, 8), s("d", 8, 9), s("e", 9, 10),
        )
        val r = Bucketizer.dominantPerBucket(sessions, 0, 10_000, 1)
        assertEquals(listOf("a"), r)
    }

    @Test
    fun `跨格边界的区间在每格里按实际占用竞争`() {
        // a: [0,5)  b: [5,12)  切成 3 格，每格 4 秒
        val sessions = listOf(
            s("a", 0, 5),
            s("b", 5, 12),
        )
        val r = Bucketizer.dominantPerBucket(sessions, 0, 12_000, 3)
        assertEquals("a", r[0])   // [0,4)  只有 a
        assertEquals("b", r[1])   // [4,8)  a 占 1 秒、b 占 3 秒 → b 胜（占比说话，不是起点说话）
        assertEquals("b", r[2])   // [8,12) 只有 b
    }

    @Test
    fun `占用完全相等时按输入顺序先出现的胜出`() {
        // a 覆盖整段，b 也正好覆盖中间那格 —— 这一格两者各占满 4 秒，是真平局。
        // 规则是"先出现的胜出"，不是"更专用的胜出"：后者需要额外猜测，且没法测。
        val sessions = listOf(
            s("a", 0, 12),
            s("b", 4, 8),
        )
        val r = Bucketizer.dominantPerBucket(sessions, 0, 12_000, 3)
        assertEquals("a", r[0])
        assertEquals("a", r[1])   // 平局 → 靠前的 a
        assertEquals("a", r[2])
    }

    @Test
    fun `busyMillis 每个格子分别累加`() {
        val sessions = listOf(s("a", 0, 2), s("b", 4, 6))
        val r = Bucketizer.busyMillisPerBucket(sessions, 0, 8_000, 4)
        assertEquals(listOf(2000L, 0L, 2000L, 0L), r)
    }

    @Test
    fun `busyMillis 跨格区间被正确切开`() {
        // 一个 0..8s 的区间切 4 格，每格应当是 2s
        val r = Bucketizer.busyMillisPerBucket(listOf(s("a", 0, 8)), 0, 8_000, 4)
        assertEquals(listOf(2000L, 2000L, 2000L, 2000L), r)
    }

    @Test
    fun `退化输入不崩`() {
        assertEquals(0, Bucketizer.dominantPerBucket(emptyList(), 0, 0, 0).size)
        assertEquals(3, Bucketizer.dominantPerBucket(emptyList(), 100, 100, 3).size)
        assertEquals(3, Bucketizer.busyMillisPerBucket(emptyList(), 100, 100, 3).size)
    }
}
