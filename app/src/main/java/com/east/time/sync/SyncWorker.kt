package com.east.time.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val outcome = SyncRepository(applicationContext).sync()

        Log.i(TAG, if (outcome.ok) {
            "同步成功：${outcome.eventCount} 事件 / ${outcome.dailyStatCount} 聚合"
        } else {
            "同步失败：${outcome.message}"
        })

        // 权限没授这类失败重试多少次都一样，直接放过，等下一个周期
        return if (outcome.ok || !outcome.retryable) Result.success() else Result.retry()
    }

    private companion object {
        const val TAG = "UsageSync"
    }
}
