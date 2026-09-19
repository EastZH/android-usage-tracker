package com.east.time

import android.app.Application
import com.east.time.sync.SyncScheduler

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // 注册周期任务（15 分钟），并立刻补一次 —— 冷启动往往是唯一
        // 能保证执行的时机，不能只依赖 WorkManager。
        SyncScheduler.ensurePeriodic(this)
        SyncScheduler.syncNow(this)
    }
}
