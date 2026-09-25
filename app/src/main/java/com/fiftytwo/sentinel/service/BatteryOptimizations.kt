package com.fiftytwo.sentinel.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 「忽略电池优化」的申请与查询。
 *
 * 为什么需要它：监控是前台服务 + 定时轮询，系统一旦把本应用放进电池优化名单，
 * Doze 下轮询会被压到几十分钟一次，装上来的东西就迟迟不被摘掉。
 *
 * 用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 直接弹系统白名单对话框
 * （清单里要有 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限）；
 * 少数 ROM 不认这个 Intent，就退回到电池优化列表页让用户自己选。
 */
object BatteryOptimizations {

    fun isIgnoring(context: Context): Boolean = runCatching {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** 返回是否成功把用户送到了系统界面（真正的结果是回来之后再查一次）。 */
    fun requestIgnore(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isSuccess) return true

        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(list) }.isSuccess
    }
}
