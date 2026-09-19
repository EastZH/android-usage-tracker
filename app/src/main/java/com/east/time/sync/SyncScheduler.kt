package com.east.time.sync

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {

    private const val PERIODIC_WORK = "usage-sync-periodic"
    private const val ONESHOT_WORK = "usage-sync-now"

    /**
     * 15 分钟是 WorkManager 的周期下限。
     *
     * 之所以敢用这么"慢"的节奏：系统只保留 **24 小时**的原始事件，
     * 而整窗替换让采集变成幂等的 —— 只要一天内成功跑过一次就不丢数据。
     * 被 Doze 推迟几个小时对最终结果毫无影响。
     */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONESHOT_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().build(),
        )
    }
}
