package com.east.time.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.east.time.analyze.Bucketizer
import com.east.time.analyze.Session
import com.east.time.analyze.SessionDeriver
import com.east.time.collect.UsageStatsSource
import com.east.time.data.AppDatabase
import com.east.time.data.DailyRollup
import com.east.time.data.DailyStat
import com.east.time.data.UsageEvent
import com.east.time.export.ExportWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

class SyncRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val dao = db.usageDao()
    private val prefs = appContext.getSharedPreferences("sync", Context.MODE_PRIVATE)
    private val exportWriter by lazy { ExportWriter(appContext) }

    val lastSyncAt: Long get() = prefs.getLong(KEY_LAST_SYNC, 0L)
    val lastError: String? get() = prefs.getString(KEY_LAST_ERROR, null)

    /** 系统回填出来的最早日期（yyyy-MM-dd）。从未回填过则为 null */
    val backfillEarliest: String? get() = prefs.getString(KEY_BACKFILL_EARLIEST, null)

    /** 最近一次点「导出」的时间。0 = 从未 */
    val lastExportAt: Long get() = prefs.getLong(KEY_LAST_EXPORT, 0L)

    /** PC 端已处理到的请求值。>= [lastExportAt] 说明这一次电脑确实拉走了 */
    val pulledByPcUpTo: Long get() = exportWriter.readAck() ?: 0L

    suspend fun eventCount(): Long = dao.eventCount()
    suspend fun dailyStatCount(): Long = dao.dailyStatCount()
    suspend fun rollupCount(): Long = dao.rollupCount()

    /**
     * 拉一次最近 24 小时的事件 + 最近 10 天的系统日聚合，落库；
     * 然后重建自建日聚合、按保留策略裁剪原始事件。
     *
     * 幂等靠**整窗替换**：先删掉窗口内的全部事件，再全量写回。
     * 不用唯一键去重，是因为事件时间戳只精确到秒，同一秒内完全可能出现
     * 同包名同类型的多条记录（`RESUME→PAUSE→RESUME`），唯一键会误删合法数据。
     */
    suspend fun sync(): SyncOutcome = withContext(Dispatchers.IO) {
        if (!UsageStatsSource.hasPermission(appContext)) {
            // 授权是用户侧动作，重试无用 —— 交给状态页提示
            return@withContext fail("未授予「使用情况访问」权限", retryable = false)
        }

        try {
            val now = System.currentTimeMillis()
            val windowStart = now - UsageStatsSource.DAY_MS

            val rawEvents = UsageStatsSource.queryEvents(appContext, windowStart, now)
            if (rawEvents.isEmpty()) {
                // 有权限却查不到任何事件。可能确实是新设备，也可能是 ROM 阉割。
                // 此时**不能**继续走"先删后插"，否则会把已有数据清空。
                return@withContext fail("查询到 0 条事件（权限已授但无数据，疑似 ROM 限制）")
            }

            val rawStats = UsageStatsSource.queryDailyStats(appContext)

            val events = rawEvents.map {
                UsageEvent(
                    tsMillis = it.tsMillis,
                    userId = it.userId,
                    packageName = it.packageName,
                    className = it.className,
                    eventType = it.eventType,
                )
            }
            val stats = rawStats.map {
                DailyStat(
                    date = it.date,
                    userId = it.userId,
                    packageName = it.packageName,
                    totalTimeUsedMs = it.totalTimeUsedMs,
                    totalTimeVisibleMs = it.totalTimeVisibleMs,
                    totalTimeFsMs = it.totalTimeFsMs,
                    lastTimeUsed = it.lastTimeUsed,
                )
            }

            db.withTransaction {
                dao.deleteEventsFrom(windowStart)
                dao.insertEvents(events)
                if (stats.isNotEmpty()) dao.upsertDailyStats(stats)
            }

            // 顺序要紧：
            //   1. 先把历史天汇总进永久表
            //   2. 回填系统给的历史
            //   3. 导出（含把已完成的那天的事件归档成不可变文件）
            //   4. 最后才裁剪原始事件
            // 3 和 4 不能对调 —— 先裁的话，某天会在归档之前就被删掉，永久丢失。
            rebuildRollups()
            backfillFromSystem()
            exportData()
            pruneEvents(now)

            prefs.edit().putLong(KEY_LAST_SYNC, now).remove(KEY_LAST_ERROR).apply()

            SyncOutcome(
                ok = true,
                message = null,
                eventCount = events.size,
                dailyStatCount = stats.size,
                windowStart = windowStart,
                retryable = false,
            )
        } catch (t: Throwable) {
            fail("同步异常：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 把"今天之前"的每一天汇总进 [DailyRollup]（永久保留）。
     *
     * 为什么要靠事件重算而不是读系统日聚合：系统"日"桶边界在 00:56 而非零点，
     * 拿它当"今天"会和直觉差近一小时。用自然日切才符合预期。
     *
     * **今天不写**：今天还在进行中，每次算都是不完整的。今天的数值由 UI
     * 从事件实时推导 —— 那样"此刻正在用的 App"才算得准（悬空区间会算到当前时刻）。
     *
     * 代价是每次同步都重算最多 [EVENT_RETENTION_DAYS] 天，但事件已被保留策略
     * 限制在有界规模内，这点开销远小于出错的代价。
     */
    private suspend fun rebuildRollups() {
        val todayStart = startOfTodayMillis()
        val earliest = dao.earliestEventTs() ?: return

        var dayStart = startOfDay(earliest)
        if (dayStart >= todayStart) return   // 还没有完整的历史天

        val rollups = ArrayList<DailyRollup>(64 * 32)
        var from = dayStart

        while (dayStart < todayStart) {
            val dayEnd = nextDay(dayStart)

            // 前后各放宽一天，避免跨零点的会话因为找不到起点而整段丢失
            val events = dao.eventsBetween(dayStart - UsageStatsSource.DAY_MS, dayEnd + UsageStatsSource.DAY_MS)
            val totals = SessionDeriver.foregroundMillis(events, dayStart, dayEnd)
            val date = formatDate(dayStart)
            for ((pkg, ms) in totals) {
                rollups += DailyRollup(date = date, packageName = pkg, foregroundMs = ms)
            }
            dayStart = dayEnd
        }

        db.withTransaction {
            dao.deleteRollupsBetween(formatDate(from), formatDate(todayStart - UsageStatsSource.DAY_MS))
            if (rollups.isNotEmpty()) dao.insertRollups(rollups)
        }
    }

    /**
     * 用系统的 10 天日聚合**回填**我们开始记录之前的历史。
     *
     * 规则：**只回填严格早于 `daily_rollup` 里最早那天的日期。**
     * 因为 `min(date)` 是最小值，所以待插入的日期必然都不存在，
     * 自然不可能覆盖我们自己推导出来的数据 —— 不需要额外加"来源"字段。
     *
     * 反过来做（按日期覆盖）会把我们按自然日算的准确值，换成系统按 00:56 分桶的
     * 近似值，越修越错。
     *
     * 系统只保留 10 天，所以要**每次同步都尝试**，趁它还没滚出窗口抄进我们自己的表。
     *
     * ⚠️ 回填来的数值边界有误差：系统"日"桶从 00:56 起算，不是零点。
     * 界面上会标注出来，别把它和我们自己算的混为一谈。
     */
    private suspend fun backfillFromSystem() {
        val earliest = dao.minRollupDate() ?: return
        val today = formatDate(startOfTodayMillis())

        val rows = dao.allDailyStats().filter {
            it.date < earliest && it.date < today && it.totalTimeUsedMs > 0L
        }
        if (rows.isEmpty()) return

        dao.insertRollups(rows.map { DailyRollup(it.date, it.packageName, it.totalTimeUsedMs) })

        val boundary = rows.minOf { it.date }
        val prev = backfillEarliest
        if (prev == null || boundary < prev) {
            prefs.edit().putString(KEY_BACKFILL_EARLIEST, boundary).apply()
        }
    }

    /** 只留最近 [EVENT_RETENTION_DAYS] 天的原始事件。日聚合已永久保留，不受影响。 */
    private suspend fun pruneEvents(now: Long) {
        dao.deleteEventsBefore(startOfDay(now) - EVENT_RETENTION_DAYS * UsageStatsSource.DAY_MS)
    }

    /**
     * 导出给 PC 拉取。
     *
     * 导出失败**不能**让整个同步失败 —— 数据已经安全落库了，导出只是搬运。
     * 但也不能默默吞掉：PC 端脚本靠 `meta.json` 的 `exportedAt` 判断数据是否新鲜，
     * 导出停了它就会发现。
     */
    /**
     * 界面上「导出到电脑」按钮走的路径：先采一次保证导出的是最新数据，再导出，
     * 最后把结果报回去。
     *
     * 注意这个按钮**不能**替用户完成传输 —— USB 连接的主机端是 PC，
     * App 作为设备端无法主动推送。它能把数据备好并明确告知备好了什么。
     */
    suspend fun exportNow(): ExportSummary = withContext(Dispatchers.IO) {
        val outcome = sync()
        exportData()

        val now = System.currentTimeMillis()
        // 留一个"请来拉取"的信号。USB 传输只能由 PC 发起，App 能做的就是把
        // 数据备好 + 告诉对端"现在来取"。PC 上的 watch.py 轮询这个文件。
        val notified = runCatching { exportWriter.writeRequest(now) }.isSuccess
        prefs.edit().putLong(KEY_LAST_EXPORT, now).apply()

        ExportSummary(
            syncOk = outcome.ok,
            syncMessage = outcome.message,
            files = exportWriter.currentFiles(),
            exportedAt = now,
            notified = notified,
        )
    }

    private suspend fun exportData() {
        runCatching {
            val archived = exportWriter.archivedDays().toMutableSet()
            var changed = false

            // 补写所有缺失的日期归档。只处理**还在事件表里**的日期 ——
            // 更早的已经被裁剪，补不回来了（这也是 retention 只有 30 天的代价）。
            val earliest = dao.earliestEventTs()
            if (earliest != null) {
                val todayStart = startOfTodayMillis()
                var dayStart = startOfDay(earliest)
                while (dayStart < todayStart) {
                    val date = formatDate(dayStart)
                    if (date !in archived) {
                        val dayEnd = nextDay(dayStart)
                        val events = dao.eventsBetween(dayStart, dayEnd)
                        if (events.isNotEmpty()) {
                            exportWriter.writeDay(date, events)
                            archived += date
                            changed = true
                        }
                    }
                    dayStart = nextDay(dayStart)
                }
            }

            // 今天的明细单独写一份可变的。按天归档的文件必须等那天结束才写
            // （PC 端靠"导过没有"决定是否导入，文件可变这个模型就崩了），
            // 但那样 PC 上永远看不到今天。
            val todayStart = startOfTodayMillis()
            val todayDate = formatDate(todayStart)
            val todayEvents = dao.eventsBetween(todayStart, System.currentTimeMillis())
            exportWriter.writeToday(todayDate, todayStart, todayEvents)
            exportWriter.removeStaleTodayFiles(todayDate)

            // 日聚合全量快照。内容没变就不重写 —— 三年后这文件有 2MB，
            // 每 15 分钟重写一次纯属磨闪存。
            val rollups = dao.allRollups()
            val fingerprint = fingerprintOf(rollups)
            if (fingerprint != prefs.getLong(KEY_EXPORT_FP, -1L)) {
                exportWriter.writeRollup(rollups)
                prefs.edit().putLong(KEY_EXPORT_FP, fingerprint).apply()
            }

            // meta.json **每次都要写**：它的 exportedAt 是 PC 端判断新鲜度的依据。
            // 之前跟着"内容变了才写"一起跳过，时间戳停在上次内容变化时，
            // PC 端看到几小时前的旧时间，没法区分"刚同步过"和"同步卡住了"。
            exportWriter.writeMeta(rollups.size, archived.size, lastSyncAt, backfillEarliest)
        }.onFailure {
            Log.w(TAG, "导出失败（不影响采集）：${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** 内容指纹。行数 + 各行时长，够用且比哈希便宜 */
    private fun fingerprintOf(rows: List<DailyRollup>): Long {
        var h = rows.size.toLong() * 1_000_003L
        for (r in rows) h = h * 31L + r.foregroundMs
        return h
    }

    /**
     * 某段时间内每个 App 的前台时长，按降序。
     *
     * 历史天读 [DailyRollup]，今天从事件实时推导 —— 两者相加。
     * 不用 [DailyStat]（系统聚合），因为它的桶边界是 00:56 不是零点。
     */
    suspend fun rangeUsage(fromMillis: Long, toMillis: Long): List<PackageUsage> =
        withContext(Dispatchers.IO) {
            val todayStart = startOfTodayMillis()
            val totals = HashMap<String, Long>()

            // 历史上已完整结束的天
            val historyEnd = minOf(toMillis, todayStart)
            if (historyEnd > fromMillis) {
                for (r in dao.rollupsBetween(formatDate(fromMillis), formatDate(historyEnd - 1))) {
                    totals[r.packageName] = (totals[r.packageName] ?: 0L) + r.foregroundMs
                }
            }

            // 今天（或区间落在今天内的部分）实时推导
            val liveFrom = maxOf(fromMillis, todayStart)
            if (toMillis > liveFrom) {
                val events = dao.eventsBetween(liveFrom - UsageStatsSource.DAY_MS, toMillis)
                for ((pkg, ms) in SessionDeriver.foregroundMillis(events, liveFrom, toMillis)) {
                    totals[pkg] = (totals[pkg] ?: 0L) + ms
                }
            }

            totals.entries
                .filter { it.value > 0L }
                .map { PackageUsage(it.key, it.value) }
                .sortedByDescending { it.foregroundMs }
        }

    suspend fun todayUsage(): List<PackageUsage> =
        usageIn(UsageRange.TODAY, System.currentTimeMillis())

    /**
     * [fromMs] 所在那天起、直到今天的前台区间，用于画时间轴。按日期升序。
     *
     * 传区间起点而不是"天数"，是为了让时间轴和下面的列表**覆盖同一段时间** ——
     * 列表选「本周」是从周一算起，时间轴也必须从周一画起，否则两边对不上。
     *
     * 每天单独推导，且**前后各放宽一天**取事件 —— 跨零点的会话（昨晚 23:50 用到
     * 今天 00:10）起点在当天之外，不放宽就会因为找不到 `RESUMED` 而整段丢掉。
     *
     * 今天的 `endMs` 取当前时刻而不是当天 24 点：时间轴只该画到"现在"，
     * 否则右边会有一大片虚假的空白。
     */
    suspend fun daySessionsFrom(fromMs: Long): List<DaySessions> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val todayStart = startOfTodayMillis()
        var dayStart = startOfDay(fromMs)
        if (dayStart > todayStart) dayStart = todayStart

        val out = ArrayList<DaySessions>(32)
        while (dayStart <= todayStart) {
            val fullDayEnd = nextDay(dayStart)
            val dayEnd = minOf(fullDayEnd, now)
            if (dayEnd > dayStart) {
                val events = dao.eventsBetween(
                    dayStart - UsageStatsSource.DAY_MS,
                    fullDayEnd + UsageStatsSource.DAY_MS,
                )
                out += DaySessions(
                    dayStartMs = dayStart,
                    endMs = dayEnd,
                    sessions = SessionDeriver.sessions(events, dayStart, dayEnd),
                )
            }
            dayStart = fullDayEnd
        }
        out
    }

    /**
     * 今天**按小时聚合**的使用情况，用来回答"我几点在干什么"。
     *
     * 为什么不直接列区间：今天实测有 **1942 段**前台区间（平均 13 秒一段），
     * 即使把同一 App 间隔 60 秒内的合并、再滤掉 15 秒以下，仍有 268 段 ——
     * 列表化完全没法看。按小时聚合后每天约 15~20 行，正好是"几时在干什么"
     * 这个粒度。
     *
     * 每个小时内按 App 占比降序，并丢掉占比过小的项（[MIN_APP_MS]）；
     * 那些零碎加起来会体现在该小时的"总计"里，不会凭空消失。
     */
    suspend fun hourlyFor(dayStartMs: Long): List<HourBucket> = withContext(Dispatchers.IO) {
        val days = daySessionsFrom(dayStartMs)
        val sessions = days.firstOrNull()?.sessions ?: return@withContext emptyList()

        val byHour = HashMap<Int, HashMap<String, Long>>()
        for (s in sessions) {
            // **必须按小时切开**，不能把整段归到"开始时刻所在的小时"。
            // 否则一段 21:50–22:10 的连续使用会整段算进 21 点，22 点显示 0 ——
            // 用户看着手机却被告知"这一小时 0 秒"，是最容易被发现的那种错。
            var pos = s.startMs
            while (pos < s.endMs) {
                val cal = Calendar.getInstance().apply { timeInMillis = pos }
                val hour = cal.get(Calendar.HOUR_OF_DAY)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val hourEnd = cal.timeInMillis + HOUR_MS
                val segEnd = minOf(s.endMs, hourEnd)

                val bucket = byHour.getOrPut(hour) { HashMap() }
                bucket[s.packageName] = (bucket[s.packageName] ?: 0L) + (segEnd - pos)
                pos = segEnd
            }
        }

        byHour.entries.sortedBy { it.key }.map { (hour, apps) ->
            HourBucket(
                hour = hour,
                totalMs = apps.values.sum(),
                apps = apps.entries
                    .filter { it.value >= MIN_APP_MS }
                    .sortedByDescending { it.value }
                    .map { PackageUsage(it.key, it.value) },
            )
        }
    }

    /**
     * 今天的两根管子要的数据：
     *  - [hourCells] 24 格，每格一小时
     *  - [minuteCellsByHour] 24 × 60，每格一分钟（选中的小时用哪一行就取哪一行）
     *
     * 一次性把 1440 格算完（纯内存计算，几十微秒），好过每次点选都重新查库。
     * 格子边界用**完整的小时**（0 点、1 点…），不用"到此刻为止" ——
     * 否则当前小时会被拉伸，格子和实际分钟对不上。
     */
    suspend fun dayGrid(dayStartMs: Long): TodayGrid = withContext(Dispatchers.IO) {
        val dayStart = startOfDay(dayStartMs)
        val sessions = daySessionsFrom(dayStart).firstOrNull()?.sessions ?: emptyList()
        val hourMs = UsageStatsSource.DAY_MS / 24

        TodayGrid(
            // 系统回填的那些天只有"当天总量"，没有时刻信息 —— 管子会整片全灰。
            // 必须把这个状态传给界面，否则用户会以为"那天没碰手机"，
            // 而下面的列表里明明有数字。
            hasEvents = sessions.isNotEmpty(),
            hourCells = Bucketizer.dominantPerBucket(
                sessions, dayStart, dayStart + UsageStatsSource.DAY_MS, 24,
            ),
            minuteCellsByHour = (0 until 24).map { h ->
                Bucketizer.dominantPerBucket(
                    sessions, dayStart + h * hourMs, dayStart + (h + 1) * hourMs, 60,
                )
            },
        )
    }

    /**
     * [anchorMs] 所在周期的起点。
     *
     * 之所以要 anchor 而不是直接取"现在"：用户可以用箭头往回翻看历史，
     * 列表和管子都得跟着锚点走。
     */
    fun periodStart(range: UsageRange, anchorMs: Long): Long = when (range) {
        UsageRange.TODAY -> startOfDay(anchorMs)
        UsageRange.WEEK -> startOfWeek(anchorMs)
        UsageRange.MONTH -> startOfMonth(anchorMs)
    }

    /**
     * 周期的结束时刻。
     * 当前周期只算到"此刻"（否则末段全是虚假空白）；已过去的周期算满。
     */
    fun periodEnd(range: UsageRange, anchorMs: Long): Long {
        val now = System.currentTimeMillis()
        val start = periodStart(range, anchorMs)
        val naturalEnd = Calendar.getInstance().apply {
            timeInMillis = start
            when (range) {
                UsageRange.TODAY -> add(Calendar.DAY_OF_YEAR, 1)
                UsageRange.WEEK -> add(Calendar.DAY_OF_YEAR, 7)
                UsageRange.MONTH -> add(Calendar.MONTH, 1)
            }
        }.timeInMillis
        return minOf(naturalEnd, now)
    }

    /** 按口径把锚点前后移动一格：天 / 周 / 月 */
    fun shiftAnchor(range: UsageRange, anchorMs: Long, steps: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = anchorMs
            when (range) {
                UsageRange.TODAY -> add(Calendar.DAY_OF_YEAR, steps)
                UsageRange.WEEK -> add(Calendar.DAY_OF_YEAR, 7 * steps)
                UsageRange.MONTH -> add(Calendar.MONTH, steps)
            }
        }.timeInMillis

    /** 能往回翻到的最早一天（有记录可查的边界） */
    suspend fun earliestRecordedDay(): Long = withContext(Dispatchers.IO) {
        val rollup = dao.minRollupDate()
        val event = dao.earliestEventTs()
        val candidates = listOfNotNull(
            rollup?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.atStartOfDay()?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(),
            event?.let { startOfDay(it) },
        )
        candidates.minOrNull() ?: startOfTodayMillis()
    }

    suspend fun usageIn(range: UsageRange, anchorMs: Long): List<PackageUsage> =
        rangeUsage(periodStart(range, anchorMs), periodEnd(range, anchorMs))

    // ---------- 日期工具 ----------

    private fun startOfTodayMillis(): Long = startOfDay(System.currentTimeMillis())

    /** 一周从**周一**算起（中文习惯，不是 Calendar 默认的周日） */
    private fun startOfWeek(millis: Long): Long {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        // Calendar: SUNDAY=1 … SATURDAY=7。换算成"距周一几天"
        val daysSinceMonday = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        return startOfDay(c.timeInMillis).let { d ->
            Calendar.getInstance().apply {
                timeInMillis = d
                add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
            }.timeInMillis
        }
    }

    private fun startOfMonth(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nextDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis

    private fun formatDate(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "%04d-%02d-%02d".format(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun fail(message: String, retryable: Boolean = true): SyncOutcome {
        prefs.edit().putString(KEY_LAST_ERROR, message).apply()
        return SyncOutcome(
            ok = false,
            message = message,
            eventCount = 0,
            dailyStatCount = 0,
            windowStart = null,
            retryable = retryable,
        )
    }

    private companion object {
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LAST_ERROR = "last_error"
        const val KEY_BACKFILL_EARLIEST = "backfill_earliest"
        const val KEY_EXPORT_FP = "export_fingerprint"
        const val KEY_LAST_EXPORT = "last_export_at"
        const val TAG = "UsageSync"

        /** 小时明细里，单个 App 占比低于这个值就不单列（并入该小时总计） */
        const val MIN_APP_MS = 60_000L

        const val HOUR_MS = 60L * 60 * 1000

        /** 原始事件的保留天数。日聚合永久保留，不受此影响 */
        const val EVENT_RETENTION_DAYS = 30L
    }
}

data class SyncOutcome(
    val ok: Boolean,
    val message: String?,
    val eventCount: Int,
    val dailyStatCount: Int,
    val windowStart: Long?,
    /** 重试是否有意义。权限没授、ROM 不给数据这类，重试多少次都一样 */
    val retryable: Boolean,
)

data class PackageUsage(
    val packageName: String,
    val foregroundMs: Long,
)

/** 某一天两根管子所需的格子数据 */
data class TodayGrid(
    /** false = 这天没有事件明细（通常是系统回填的历史），管子画不出来 */
    val hasEvents: Boolean,
    /** 24 格，每格一小时。null = 该小时无前台活动 */
    val hourCells: List<String?>,
    /** 24 行 × 60 格，每格一分钟 */
    val minuteCellsByHour: List<List<String?>>,
)

/** 今天某一个小时的使用情况 */
data class HourBucket(
    /** 0..23 */
    val hour: Int,
    val totalMs: Long,
    /** 该小时内各 App 占比，降序；只含占比达到阈值的项 */
    val apps: List<PackageUsage>,
)

/** 某一天的前台区间 */
data class DaySessions(
    val dayStartMs: Long,
    /** 该算到哪为止。过去的日子是当天 24 点，今天是"此刻" */
    val endMs: Long,
    val sessions: List<Session>,
)

/** 「导出到电脑」的结果，用于界面上如实告知备好了什么 */
data class ExportSummary(
    val syncOk: Boolean,
    val syncMessage: String?,
    /** 文件名 → 字节数 */
    val files: List<Pair<String, Long>>,
    val exportedAt: Long,
    /** 是否成功留下"请来拉取"的信号 */
    val notified: Boolean,
) {
    val totalBytes: Long get() = files.sumOf { it.second }
    val dayCount: Int get() = files.count { it.first.startsWith("events-") }
}

/** 统计区间。WEEK 以周一为起点 */
enum class UsageRange { TODAY, WEEK, MONTH }
