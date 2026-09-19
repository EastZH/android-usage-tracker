package com.east.time.export

import android.content.Context
import android.util.Log
import com.east.time.data.DailyRollup
import com.east.time.data.UsageEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把数据导出成文件，供 PC 端用 adb 拉走。
 *
 * 写在 App 自己的外部目录（`/sdcard/Android/data/com.east.time/files/export/`）：
 * 不需要任何存储权限，`adb pull` 也能直接读到。刻意**不**写 Download —— 那里
 * 用户点得到，之前就是因为往那儿丢 APK 导致误装降级闪退。
 *
 * ## 两类文件，两种语义
 *
 * - `rollup.csv` —— **全量快照**，每次导出覆盖。日聚合只有几万行（三年 ~3MB），
 *   全量写比增量同步简单得多，而且天然幂等：PC 端整表替换即可，不需要记状态。
 *
 * - `events-YYYY-MM-DD.csv` —— **按天归档，写完不再变**。
 *   这一天结束后才写，因为那时它的事件才完整。一旦生成就不再重写，
 *   PC 端只要判断"这个文件导过没有"就能决定是否导入 ——
 *   完全绕开了事件去重问题（时间戳只到秒，同一秒内可能有多条同包同类型记录，
 *   靠内容做唯一键会误删）。
 *
 * ⚠️ 归档有**时效性**：手机上原始事件只留 30 天，某天没来得及归档就永久丢失。
 * 所以每次同步都会补写所有缺失的日期。
 */
class ExportWriter(private val context: Context) {

    private val dir: File
        get() = File(context.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }

    fun rollupFile(): File = File(dir, "rollup.csv")
    fun metaFile(): File = File(dir, "meta.json")
    fun dayFile(date: String): File = File(dir, "events-$date.csv")

    /**
     * 写一个"请来拉取"的信号文件。
     *
     * PC 端的监听脚本轮询这个文件，发现内容变了就立刻执行一次拉取。
     * 内容用时间戳，所以每次点击都是一个不同的值 —— 监听端靠"值变了"来触发，
     * 而不是靠"文件存在"（那样只会触发一次）。
     *
     * 这不是多此一举：USB 传输里 PC 是主机端，手机上的 App 没有能力发起，
     * 只能留个信号让对端来取。
     */
    fun writeRequest(timestamp: Long) {
        atomicWrite(File(dir, "REQUEST"), "$timestamp\n")
    }

    /** 导出目录现状，用于在界面上告诉用户"备好了什么"。按名称排序，便于阅读 */
    fun currentFiles(): List<Pair<String, Long>> =
        dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }
            ?.sortedBy { it.name }
            ?.map { it.name to it.length() }
            ?: emptyList()

    /** 已经归档过的日期（从文件名反推），用于跳过重复写入 */
    fun archivedDays(): Set<String> =
        dir.listFiles { f -> f.name.startsWith("events-") && f.name.endsWith(".csv") }
            ?.mapNotNull { it.name.removePrefix("events-").removeSuffix(".csv").takeIf { d -> d.length == 10 } }
            ?.toSet()
            ?: emptySet()

    /**
     * PC 端处理过的最后一个请求值。PC 拉完会把请求值写回 `ACK`。
     *
     * 用途是给用户反馈：点了按钮但电脑没在监听时，手机上也该看得出来，
     * 而不是"点了没反应、不知道为什么"。
     */
    fun readAck(): Long? =
        runCatching { File(dir, "ACK").readText().trim().toLongOrNull() }.getOrNull()

    /** 最近一次导出写入的时间；读 meta.json 里的 exportedAt，读不到就返回 null */
    fun lastExportedAt(): Long? = runCatching {
        val s = metaFile().readText()
        val m = Regex(""""exportedAt"\s*:\s*"([^"]+)"""").find(s) ?: return null
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).parse(m.groupValues[1])?.time
    }.getOrNull()

    /** 全量快照。先写临时文件再改名，避免 PC 端刚好拉到写了一半的文件 */
    fun writeRollup(rows: List<DailyRollup>) {
        val sb = StringBuilder(rows.size * 48 + 64)
        sb.append("date,packageName,foregroundMs\n")
        for (r in rows) {
            sb.append(r.date).append(',')
                .append(csv(r.packageName)).append(',')
                .append(r.foregroundMs).append('\n')
        }
        atomicWrite(rollupFile(), sb.toString())
    }

    /**
     * 今天的事件，**每次导出都覆盖写**。
     *
     * 按天归档的文件（`events-YYYY-MM-DD.csv`）必须等那天结束才写，因为 PC 端靠
     * "这个文件导过没有"来决定是否导入 —— 文件一旦可变，这个模型就崩了。
     * 但那样一来 PC 上永远看不到今天。所以单独给今天开一个可变文件，
     * PC 端按"整块替换"处理（靠 `source_file` 列删掉旧的再插）。
     */
    fun writeToday(date: String, dayStartMs: Long, events: List<UsageEvent>) {
        val sb = StringBuilder(events.size * 96 + 96)
        sb.append("tsMillis,userId,packageName,className,eventType\n")
        for (e in events) {
            if (e.tsMillis < dayStartMs) continue
            sb.append(e.tsMillis).append(',')
                .append(e.userId).append(',')
                .append(csv(e.packageName)).append(',')
                .append(csv(e.className)).append(',')
                .append(csv(e.eventType)).append('\n')
        }
        atomicWrite(File(dir, "today-$date.csv"), sb.toString())
    }

    /** 清掉非今天的 today-*.csv，避免跨天后 PC 端还去导入昨天的 */
    fun removeStaleTodayFiles(keepDate: String) {
        dir.listFiles { f ->
            f.name.startsWith("today-") && f.name != "today-$keepDate.csv"
        }?.forEach { it.delete() }
    }

    /** 某一天的全部事件。只在文件不存在时调用。 */
    fun writeDay(date: String, events: List<UsageEvent>) {
        val sb = StringBuilder(events.size * 96 + 64)
        sb.append("tsMillis,userId,packageName,className,eventType\n")
        for (e in events) {
            sb.append(e.tsMillis).append(',')
                .append(e.userId).append(',')
                .append(csv(e.packageName)).append(',')
                .append(csv(e.className)).append(',')
                .append(csv(e.eventType)).append('\n')
        }
        atomicWrite(dayFile(date), sb.toString())
    }

    fun writeMeta(rollupRows: Int, archivedDays: Int, lastSyncAt: Long, backfillEarliest: String?) {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
        val json = buildString {
            append("{\n")
            append("  \"exportedAt\": \"").append(fmt.format(Date())).append("\",\n")
            append("  \"rollupRows\": ").append(rollupRows).append(",\n")
            append("  \"archivedDays\": ").append(archivedDays).append(",\n")
            append("  \"lastSyncAt\": ").append(lastSyncAt).append(",\n")
            append("  \"backfillEarliest\": ")
                .append(if (backfillEarliest == null) "null" else "\"$backfillEarliest\"").append(",\n")
            append("  \"schemaVersion\": ").append(SCHEMA_VERSION).append("\n")
            append("}\n")
        }
        atomicWrite(metaFile(), json)
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // 某些文件系统上 rename 到已存在的目标会失败，退一步直接覆盖
            target.writeText(content)
            tmp.delete()
        }
        Log.i(TAG, "导出 ${target.name}: ${content.length} 字节")
    }

    /** 包名/类名正常不含逗号引号，但导出格式不该依赖"正常" */
    private fun csv(s: String?): String {
        if (s == null) return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
    }

    private companion object {
        const val TAG = "UsageExport"
        const val DIR_NAME = "export"
        const val SCHEMA_VERSION = 1
    }
}
