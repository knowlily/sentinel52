package com.fiftytwo.sentinel.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.fiftytwo.sentinel.MainActivity
import com.fiftytwo.sentinel.R

/**
 * 两个通知渠道都在这里建，服务只负责改文案：
 * - [CHANNEL_ID]：监控的常驻通知（LOW，不打扰）；
 * - [ALERT_CHANNEL_ID]：三个后端都没就绪、命中却没卸载时的提醒（DEFAULT，一次性）。
 */
object Notifier {

    const val CHANNEL_ID = "sentinel_monitor"
    const val NOTIFICATION_ID = 0x52

    const val ALERT_CHANNEL_ID = "sentinel_alert"
    const val ALERT_NOTIFICATION_ID = 0x53

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.channel_desc)
                    setShowBadge(false)
                },
            )
        }

        if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    context.getString(R.string.channel_alert_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.channel_alert_desc)
                    setShowBadge(true)
                },
            )
        }
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 监控的常驻通知。[content] 由服务决定（正常时是「已卸载 N 个」，未就绪时是降级文案）。 */
    fun build(context: Context, content: String, degraded: Boolean = false): Notification {
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                if (degraded) context.getString(R.string.notif_title_degraded)
                else context.getString(R.string.notif_title),
            )
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.action_stop_monitor), stop)
            .build()
    }

    /**
     * 一次性的降级提醒：命中了却一个都没卸（特权后端没就绪）。点它打开应用去授权。
     * 用同一个 id 覆盖，所以同一件事反复触发也只在通知栏占一条。
     */
    fun alert(context: Context, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.action_grant), openAppIntent(context))
            .build()
        runCatching { manager.notify(ALERT_NOTIFICATION_ID, notification) }
    }

    /** 后端恢复就绪 / 不再有跳过的包时，把那条提醒收回来。 */
    fun cancelAlert(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(ALERT_NOTIFICATION_ID) }
    }
}
