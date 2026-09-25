package com.fiftytwo.sentinel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fiftytwo.sentinel.data.SentinelStore

/**
 * 开机后把监控接回去。只有用户上次是「开着」的状态才启——不要替用户改主意。
 *
 * 开机广播是 Android 12+ 允许直接拉起前台服务的少数豁免场景之一，所以这里
 * 用 startForegroundService 是安全的。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        SentinelStore.init(context)
        if (!SentinelStore.state.value.monitoring) return
        MonitorRunner.start(context)
    }
}
