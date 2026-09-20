package com.east.time

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import com.east.time.collect.UsageStatsSource
import com.east.time.sync.DaySessions
import com.east.time.sync.HourBucket
import com.east.time.sync.PackageUsage
import com.east.time.sync.SyncRepository
import com.east.time.sync.SyncScheduler
import com.east.time.sync.UsageRange
import com.east.time.notify.UsageNotifier
import com.east.time.ui.TimeGrid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val repo by lazy { SyncRepository(this) }
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val labelCache = HashMap<String, String>()

    private lateinit var statusValue: TextView
    private lateinit var hintValue: TextView
    private lateinit var statsValue: TextView
    private lateinit var rangeLabel: TextView
    private lateinit var totalValue: TextView
    private lateinit var grantButton: Button
    private lateinit var syncButton: Button
    private lateinit var exportButton: Button
    private lateinit var exportResult: TextView
    private lateinit var notifySwitch: Switch
    private lateinit var notifyCount: TextView
    private lateinit var rangeToday: Button
    private lateinit var rangeWeek: Button
    private lateinit var rangeMonth: Button
    private lateinit var prevButton: Button
    private lateinit var nowButton: Button
    private lateinit var nextButton: Button
    private lateinit var periodLabel: TextView
    private lateinit var usageList: ListView
    private lateinit var timeGrid: TimeGrid
    private lateinit var touchedValue: TextView
    private lateinit var adapter: ArrayAdapter<String>

    // 下面这几份数据由 refresh() 填，供点选回调直接取用，不必每次点都重查数据库。
    // 名字里刻意不带 "today" —— 它装的是**当前选中周期**的数据，随「今天/本周/本月」变。
    private var periodUsage: List<PackageUsage> = emptyList()
    private var hourBuckets: List<HourBucket> = emptyList()
    private var minuteCellsByHour: List<List<String?>> = emptyList()
    private var lockedMinuteByHour: List<List<Boolean>> = emptyList()

    /** 适配器的数据源。UI 线程独占，别处不要碰 */
    private val rowItems = mutableListOf<String>()

    /**
     * 刷新序号。onCreate 与 onResume 会各触发一次 refresh，切换区间也会触发，
     * 多个协程并发时先发起的那次可能更晚返回，用旧数据覆盖新数据渲染。
     * 每次 refresh 领一个号，只有最新那次才允许渲染。
     */
    private var refreshSeq = 0

    private var range = UsageRange.TODAY

    /** 当前查看的时间锚点。箭头按 [range] 的口径前后挪它 */
    private var anchorMs = System.currentTimeMillis()

    /** 能往回翻到的最早一天，由 refresh() 查出 */
    private var earliestDayMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusValue = findViewById(R.id.statusValue)
        hintValue = findViewById(R.id.errorValue)
        statsValue = findViewById(R.id.statsValue)
        rangeLabel = findViewById(R.id.rangeLabel)
        totalValue = findViewById(R.id.totalValue)
        grantButton = findViewById(R.id.grantButton)
        syncButton = findViewById(R.id.syncButton)
        exportButton = findViewById(R.id.exportButton)
        exportResult = findViewById(R.id.exportResult)
        notifySwitch = findViewById(R.id.notifySwitch)
        notifyCount = findViewById(R.id.notifyCount)
        prevButton = findViewById(R.id.prevButton)
        nowButton = findViewById(R.id.nowButton)
        nextButton = findViewById(R.id.nextButton)
        periodLabel = findViewById(R.id.periodLabel)
        rangeToday = findViewById(R.id.rangeToday)
        rangeWeek = findViewById(R.id.rangeWeek)
        rangeMonth = findViewById(R.id.rangeMonth)
        usageList = findViewById(R.id.usageList)
        timeGrid = findViewById(R.id.timeGrid)
        touchedValue = findViewById(R.id.touchedValue)

        // 点「时」的某一格 → 切换选中，下面的分钟管和列表跟着变
        timeGrid.onHourSelected = { hour ->
            timeGrid.minuteCells =
                if (hour >= 0) minuteCellsByHour.getOrElse(hour) { emptyList() } else emptyList()
            timeGrid.lockedMinuteCells =
                if (hour >= 0) lockedMinuteByHour.getOrElse(hour) { emptyList() } else emptyList()
            touchedValue.text = ""
            renderBelowList()
        }
        // 手指按在分钟格上（可拖动）→ 实时告诉你是哪个 App
        timeGrid.onMinuteTouched = { minute, pkg ->
            val h = timeGrid.selectedHour
            touchedValue.text = if (h < 0) {
                ""
            } else {
                String.format(
                    Locale.getDefault(), "%02d:%02d  %s", h, minute,
                    pkg?.let { labelOf(it) } ?: "（无前台应用）",
                )
            }
        }

        // 适配器与 rowItems 共用同一个 list，改动后 notifyDataSetChanged 即可。
        // 不要用 adapter.clear()/addAll() —— 那操作的是适配器内部列表，
        // 与这里持有的引用容易脱节，出现"界面显示的旧数据和总计对不上"。
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rowItems)
        usageList.adapter = adapter

        rangeToday.setOnClickListener { setRange(UsageRange.TODAY) }
        rangeWeek.setOnClickListener { setRange(UsageRange.WEEK) }
        rangeMonth.setOnClickListener { setRange(UsageRange.MONTH) }

        prevButton.setOnClickListener { moveAnchor(-1) }
        nextButton.setOnClickListener { moveAnchor(+1) }
        // 圆点：回到当前周期（今天 / 本周 / 本月）
        nowButton.setOnClickListener {
            anchorMs = System.currentTimeMillis()
            timeGrid.selectedHour = -1
            refresh()
        }

        // 「使用情况访问」没有运行时权限弹窗，只能跳系统设置让用户手动开
        grantButton.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                .onFailure { hintValue.text = "打不开系统设置页，请手动到「设置 → 应用 → 特殊应用权限 → 使用情况访问」开启" }
        }

        syncButton.setOnClickListener {
            syncButton.isEnabled = false
            scope.launch {
                val outcome = withContext(Dispatchers.IO) { repo.sync() }
                if (!outcome.ok) hintValue.text = outcome.message
                refresh()
                syncButton.isEnabled = true
            }
        }

        exportButton.setOnClickListener { doExport() }

        notifySwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                repo.notifyEnabled = false
                refresh()
                return@setOnCheckedChangeListener
            }
            // Android 13+ 通知要运行时权限。没权限就先申请，**权限没下来之前不打开开关** ——
            // 否则开关显示"已开启"但一条通知也发不出来，用户完全无从判断。
            if (UsageNotifier.canPost(this)) {
                enableNotify()
            } else {
                notifySwitch.isChecked = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
                }
            }
        }

        updateRangeButtons()
        refresh()
    }

    /**
     * 「导出到电脑」。
     *
     * 这个按钮**不能**替用户完成传输 —— USB 连接的主机端是 PC，App 作为设备端
     * 无法主动推送。它能做的是：立刻采一次保证数据最新，把导出文件写好，
     * 然后如实告诉用户备好了什么、下一步该做什么。
     */
    private fun doExport() {
        exportButton.isEnabled = false
        exportResult.text = "正在采集并导出…"
        scope.launch {
            val s = withContext(Dispatchers.IO) { repo.exportNow() }

            // 等 PC 的回执。监听脚本每 2 秒轮询一次，正常 2~4 秒内到。
            // 主动等这几秒，是为了让反馈即时 —— 否则用户点完什么也看不到，
            // 得重新打开 App 才知道电脑到底拉没拉。
            var pulled = false
            val deadline = System.currentTimeMillis() + 12_000
            while (System.currentTimeMillis() < deadline) {
                if (withContext(Dispatchers.IO) { repo.pulledByPcUpTo } >= s.exportedAt) {
                    pulled = true
                    break
                }
                delay(600)
            }

            exportResult.text = buildString {
                if (!s.syncOk) append("采集失败：").append(s.syncMessage).append('\n')
                append("已备好 ").append(s.files.size).append(" 个文件 / ")
                append(String.format(Locale.US, "%.0f KB", s.totalBytes / 1024.0))
                if (s.dayCount > 0) {
                    append("，含 ").append(s.dayCount).append(" 天事件归档")
                }
                append('\n')
                append(
                    when {
                        pulled -> "✓ 电脑已拉取"
                        !s.notified -> "✗ 通知电脑失败"
                        else -> "⏳ 已发信号，但电脑没响应 —— 检查 watch.bat 是否在运行"
                    }
                )
                append("  ·  ").append(timeFmt.format(Date(s.exportedAt)))
            }
            exportButton.isEnabled = true
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能刚从系统设置页授权回来
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun setRange(r: UsageRange) {
        // 注意不能在这里 `if (range == r) return` —— 用户点已经选中的那个口径，
        // 意图通常是"回到总体视图、取消小时选中"，直接返回会让这个点击毫无反应。
        val changed = range != r
        range = r
        // 换口径时锚点回到"当前"，否则从「今天」切到「本月」会停在一个奇怪的历史月份
        if (changed) anchorMs = System.currentTimeMillis()
        timeGrid.selectedHour = -1
        timeGrid.minuteCells = emptyList()
        touchedValue.text = ""
        updateRangeButtons()
        refresh()
    }

    /**
     * 按当前口径挪一格。往回不能越过有记录的最早一天 —— 再往前翻是一片空白，
     * 只会让人以为"那天没用手机"。
     */
    private fun moveAnchor(steps: Int) {
        val moved = repo.shiftAnchor(range, anchorMs, steps)
        val start = repo.periodStart(range, moved)
        if (start < earliestDayMs) return
        // 不允许翻到未来
        if (start > repo.periodStart(range, System.currentTimeMillis())) return
        anchorMs = moved
        timeGrid.selectedHour = -1
        refresh()
    }

    private fun updateRangeButtons() {
        // 选中项置灰（禁用），是这一屏最省事的选中态表达，不必引入额外样式
        rangeToday.isEnabled = range != UsageRange.TODAY
        rangeWeek.isEnabled = range != UsageRange.WEEK
        rangeMonth.isEnabled = range != UsageRange.MONTH

        rangeLabel.text = getString(
            when (range) {
                UsageRange.TODAY -> R.string.range_label_today
                UsageRange.WEEK -> R.string.range_label_week
                UsageRange.MONTH -> R.string.range_label_month
            }
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIFY) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            enableNotify()
        } else {
            hintValue.text = "没有通知权限，亮屏提醒无法工作。可到系统设置里授予「通知」权限。"
            notifySwitch.isChecked = false
        }
    }

    private fun enableNotify() {
        repo.notifyEnabled = true
        notifySwitch.isChecked = true
        // 立刻补一次：开关刚打开时，今天的亮屏时长可能早就过了好几档
        SyncScheduler.syncNow(this)
        refresh()
    }

    private fun refresh() {
        val seq = ++refreshSeq
        scope.launch {
            val granted = withContext(Dispatchers.IO) { UsageStatsSource.hasPermission(this@MainActivity) }
            val events = withContext(Dispatchers.IO) { repo.eventCount() }
            val rollups = withContext(Dispatchers.IO) { repo.rollupCount() }
            val usage = withContext(Dispatchers.IO) { repo.usageIn(range, anchorMs) }
            val earliest = withContext(Dispatchers.IO) { repo.earliestRecordedDay() }

            // 只有「今天」这个口径才需要分钟级管子 —— 周/月的诉求是"哪些 App 用得多"，
            // 把一个月拆成分钟没人看得下去
            val isDayMode = range == UsageRange.TODAY
            val dayStart = repo.periodStart(range, anchorMs)
            val grid = if (isDayMode) {
                withContext(Dispatchers.IO) { repo.dayGrid(dayStart) }
            } else {
                null
            }
            val buckets = if (isDayMode) {
                withContext(Dispatchers.IO) { repo.hourlyFor(dayStart) }
            } else {
                emptyList()
            }
            val last = repo.lastSyncAt

            // 已经有更新的一次 refresh 发起了，本次结果作废，别覆盖它
            if (seq != refreshSeq) return@launch

            statusValue.text = if (granted) "已授权" else "未授权"
            grantButton.isEnabled = !granted
            syncButton.isEnabled = granted

            // 授权是用户在系统设置里完成的，那一刻本进程可能已经在跑，
            // 而 App.onCreate 里的首次同步早在授权之前就失败了。
            // 不补这一次，用户会看到"已授权"但数据永远是空的，必须强停重开才行。
            if (granted && (repo.lastSyncAt == 0L || repo.lastError != null)) {
                SyncScheduler.syncNow(this@MainActivity)
            }

            hintValue.text = when {
                !granted -> "点「授权」去系统设置开启；或一次性用 adb：appops set com.east.time GET_USAGE_STATS allow"
                else -> repo.lastError ?: ""
            }

            notifySwitch.isChecked = repo.notifyEnabled
            notifyCount.text = if (repo.notifyEnabled) {
                "今天 " + repo.todayNotifyCount + " 次"
            } else {
                ""
            }

            statsValue.text = buildString {
                append("事件 ").append(events).append(" 行（保留 30 天）")
                append(" · 日聚合 ").append(rollups).append(" 行（永久")
                // 回填数据的日界来自系统的 00:56 分桶，有误差 —— 必须标出来，
                // 不能让它和自己按自然日算出来的数字看起来是一回事
                repo.backfillEarliest?.let { append("，含系统回填至 ").append(it) }
                append("）")
                append(" · 上次同步 ").append(if (last == 0L) "从未" else timeFmt.format(Date(last)))
                // 单独显示「上次导出」：它和「上次同步」是两回事 ——
                // 同步每 15 分钟自动跑，导出只在你点按钮时才发生。
                // 有这条，一眼就能看出多久没往电脑搬过了（超过 30 天事件就补不回来了）。
                val lastExport = repo.lastExportAt
                append("\n上次导出 ").append(
                    if (lastExport == 0L) "从未（点「导出」）" else timeFmt.format(Date(lastExport))
                )
            }

            // 导航状态
            earliestDayMs = earliest
            val curStart = repo.periodStart(range, System.currentTimeMillis())
            periodLabel.text = formatPeriod(range, anchorMs, curStart)
            prevButton.isEnabled = dayStart > earliest
            nextButton.isEnabled = dayStart < curStart

            timeGrid.visibility = if (isDayMode) View.VISIBLE else View.GONE
            touchedValue.visibility = if (isDayMode) View.VISIBLE else View.GONE

            // 必须**无条件**更新。之前这行写在 `if (isDayMode)` 里面，
            // 结果切到「本周/本月」时 periodUsage 还留着上一次「今天」的数据 ——
            // 界面就一直是今天的信息，和周期标签对不上。
            periodUsage = usage

            if (isDayMode && grid != null) {
                hourBuckets = buckets
                minuteCellsByHour = grid.minuteCellsByHour
                lockedMinuteByHour = grid.lockedMinuteByHour
                timeGrid.hourCells = grid.hourCells
                timeGrid.lockedHourCells = grid.lockedHourCells
                // 刷新会重设网格，选中状态跟着复位
                timeGrid.selectedHour = -1
                timeGrid.minuteCells = emptyList()
                timeGrid.lockedMinuteCells = emptyList()
                // 没有明细的日子要说清楚，不能留一片全灰的管子让人误读成"那天没碰手机"
                touchedValue.text = if (grid.hasEvents) {
                    ""
                } else {
                    getString(R.string.no_minute_detail)
                }
            }

            renderBelowList()
        }
    }

    /**
     * 下方列表随选中状态切换：
     *  - 选中了某小时 → 该小时各 App 的时长
     *  - 没选中 → 当前周期各 App 的总时长
     */
    private fun renderBelowList() {
        val hour = timeGrid.selectedHour
        if (hour < 0) {
            rangeLabel.text = getString(
                when (range) {
                    UsageRange.TODAY -> R.string.range_label_day
                    UsageRange.WEEK -> R.string.range_label_week
                    UsageRange.MONTH -> R.string.range_label_month
                }
            )
            renderUsage(periodUsage)
        } else {
            rangeLabel.text = getString(R.string.range_label_hour, hour)
            renderUsage(hourBuckets.firstOrNull { it.hour == hour }?.apps ?: emptyList())
        }
    }

    /** 周期标签，例如「09-19 周六（今天）」「09-14 ~ 09-20」「2026-09（本月）」 */
    private fun formatPeriod(r: UsageRange, anchor: Long, currentStart: Long): String {
        val start = repo.periodStart(r, anchor)
        val isCurrent = start == currentStart
        return when (r) {
            UsageRange.TODAY -> {
                val d = Date(start)
                val s = SimpleDateFormat("MM-dd EEE", Locale.getDefault()).format(d)
                if (isCurrent) "$s（今天）" else s
            }
            UsageRange.WEEK -> {
                val f = SimpleDateFormat("MM-dd", Locale.getDefault())
                val first = f.format(Date(start))
                // periodEnd 是"下周一的 00:00"，减 1 毫秒回到本周期最后一天，否则会多显示一天
                val last = f.format(Date(repo.periodEnd(r, anchor) - 1))
                if (isCurrent) "$first–$last（本周）" else "$first–$last"
            }
            UsageRange.MONTH -> {
                val s = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date(start))
                if (isCurrent) "$s（本月）" else s
            }
        }
    }



    private fun renderUsage(usage: List<PackageUsage>) {
        val total = usage.sumOf { it.foregroundMs }
        totalValue.text = formatDuration(total)

        val rows = usage.map { u ->
            val label = labelOf(u.packageName)
            // 固定宽度对齐，比表格布局省事且不需要额外依赖
            String.format(Locale.getDefault(), "%-14s %s", formatDuration(u.foregroundMs), label)
        }

        Log.i(
            TAG,
            "renderUsage: range=$range total=${total / 60000}min items=${usage.size} " +
                "top3=" + usage.take(3).joinToString { "${it.packageName}=${it.foregroundMs / 1000}s" },
        )

        rowItems.clear()
        rowItems.addAll(rows)
        adapter.notifyDataSetChanged()
    }

    private fun labelOf(pkg: String): String = labelCache.getOrPut(pkg) {
        runCatching {
            val pm = packageManager
            @Suppress("DEPRECATION")
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> "${h}h${m}m"
            m > 0 -> "${m}m${s}s"
            else -> "${s}s"
        }
    }

    private companion object {
        const val TAG = "UsageSync"
        const val REQ_NOTIFY = 2002
    }
}
