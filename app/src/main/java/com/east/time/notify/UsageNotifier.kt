package com.east.time.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 亮屏累计到一定时长时的提醒。
 *
 * 走系统通知而不是应用内弹窗：提醒要在**用户没打开 App 的时候**出现，
 * 应用内 UI 做不到这件事。
 */
object UsageNotifier {

    private const val CHANNEL_ID = "screen_time"
    private const val NOTIFICATION_ID = 1001

    /** Android 13+ 通知需要运行时权限，不申请的话 post 会静默失败 */
    fun canPost(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "亮屏提醒",
                // 用 DEFAULT 而不是 HIGH：这是提示性的信息，不该响铃震动打扰
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "屏幕亮着的累计时长达到设定值时提醒"
            },
        )
    }

    /** @return 是否真的发出去了。没权限时返回 false，调用方据此决定要不要提示用户 */
    fun post(context: Context, title: String, text: String): Boolean {
        if (!canPost(context)) return false
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val n = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, n)
        return true
    }
}
