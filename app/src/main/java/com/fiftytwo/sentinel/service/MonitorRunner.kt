package com.fiftytwo.sentinel.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** 界面上启停监控的唯一入口，避免 Activity / 通知 / 开机广播各自拼 Intent 拼出三种写法。 */
object MonitorRunner {

    /**
     * 启动（或叫醒）监控服务。
     *
     * 注意调用时机：Android 12+ 默认**不允许后台启动前台服务**，从后台广播里调这个
     * 会被系统拒掉（异常被下面的 runCatching 吞掉，静默失败）。所以
     * - 开机：`BOOT_COMPLETED` 在豁免名单里，可以起；
     * - 更新 / 被系统杀死之后：只能在界面回到前台时补一次（见 `MainActivity.alignMonitoringState`）。
     */
    fun start(context: Context) {
        val intent = Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    fun stop(context: Context) {
        val intent = Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)
        // 服务已经在前台时用普通 startService 发指令即可（此时界面可见，不受后台启动限制）
        runCatching { context.startService(intent) }
            .onFailure { runCatching { ContextCompat.startForegroundService(context, intent) } }
    }
}
