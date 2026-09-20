package com.east.time.ui

import android.graphics.Color

/**
 * 应用 → 颜色。
 *
 * **同一个包名必须永远得到同一个颜色**，否则时间轴和管子上的色块无法阅读 ——
 * 你没法靠位置记忆"这块紫的是微信"，颜色一变就全乱了。
 *
 * 所以用包名哈希取色相，而不是按使用量排名分配。
 */
object AppColors {

    private val cache = HashMap<String, Int>()

    /** 该格没有前台活动、也没有黑屏信息时的底色（可能是切应用瞬间，也可能数据断了） */
    const val EMPTY = 0xFFE8E8E8.toInt()

    /**
     * 黑屏（息屏 / 锁屏）但设备开机。
     *
     * 用红色是为了和"没有信息"的背景色区分开 —— 背景色是**故障信号**（数据没采到），
     * 而黑屏是设备正常关着，两者混在一起会把采集故障伪装成"没在用手机"。
     */
    const val LOCKED = 0xFFC62828.toInt()

    fun of(pkg: String): Int = cache.getOrPut(pkg) {
        val hue = ((pkg.hashCode() % 360) + 360) % 360
        // 固定饱和度/明度：保证在浅色背景上都看得清，且彼此区分度足够
        Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.62f, 0.88f))
    }
}
