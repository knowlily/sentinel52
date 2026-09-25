package com.fiftytwo.sentinel.privileged

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process

/**
 * 这个类**不在本应用的进程里运行**。
 *
 * Dhizuku 会用自己的 Context `createPackageContext(本应用, INCLUDE_CODE)` 把本应用加载进去，
 * 然后反射构造这个类（优先 `(Context)` 构造），注入的 [host] 就是 **Dhizuku 进程自己的 Context**
 * —— 也就是说这段代码的 uid 是 Dhizuku 的 uid，而 Dhizuku 是设备所有者。
 *
 * 于是 `host.packageManager.packageInstaller.uninstall(...)` 在系统侧命中 AOSP 里那条分支：
 * ```
 * if (checkPermission(DELETE_PACKAGES) == GRANTED) ...
 * else if (canSilentlyInstallPackage(callerPackageName, callingUid))   // ← 我们走这条
 *     Binder.clearCallingIdentity(); ... deletePackageVersioned(...)   // 静默
 * else { enforcePermission(REQUEST_DELETE_PACKAGES); ... 弹确认框 }
 * ```
 * `canSilentlyInstallPackage` 只看 uid 对应的包是不是设备所有者，而 bind 前
 * `mAppOps.checkPackage(callingUid, callerPackageName)` 要求 callerPackageName 属于该 uid
 * —— 这两条只有「用 Dhizuku 的 Context 发起调用」才同时满足。
 *
 * 注意：卸载是异步的，这里返回 true 只代表**系统受理了**，删没删由客户端复查。
 */
@Suppress("unused")
class DhizukuUserService(private val host: Context) : ISentinelDeviceAdmin.Stub() {

    override fun uninstall(packageName: String, allUsers: Boolean): Boolean {
        val installer = host.packageManager.packageInstaller
        // statusReceiver 在公开 API 上是 @NonNull：给一个没人接的广播，结果我们靠复查确认
        val sender = PendingIntent.getBroadcast(
            host,
            0,
            Intent(ACTION_UNINSTALL_RESULT).setPackage(host.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).intentSender

        if (allUsers) {
            @Suppress("DEPRECATION")
            val versioned = android.content.pm.VersionedPackage(packageName, VERSION_CODE_HIGHEST)
            installer.uninstall(versioned, FLAG_DELETE_ALL_USERS, sender)
        } else {
            installer.uninstall(packageName, sender)
        }
        return true
    }

    override fun whoAmI(): String =
        "uid=${Process.myUid()} context=${host.packageName}"

    private companion object {
        const val ACTION_UNINSTALL_RESULT = "com.fiftytwo.sentinel.action.UNINSTALL_RESULT"

        /** PackageManager.VERSION_CODE_HIGHEST */
        const val VERSION_CODE_HIGHEST = -1

        /**
         * PackageManager.DELETE_ALL_USERS —— 这个常量在公开 SDK 里没有（javap android.jar 确认过，
         * 只暴露了 DELETE_ARCHIVE），值是 AOSP 里的 1 << 1，由 deletePackageVersioned 读取。
         */
        const val FLAG_DELETE_ALL_USERS = 0x00000002
    }
}
