package com.fiftytwo.sentinel.privileged

import android.content.Context
import android.content.Intent
import android.os.IBinder
import roro.stellar.Stellar
import roro.stellar.StellarBinderWrapper
import roro.stellar.StellarHelper

/**
 * Stellar 后端（Shizuku 分支），对接见仓库根 `INTEGRATION_GUIDE.md`。
 *
 * Stellar 的特权身份是 shell（2000）/ root，起进程跑 `pm uninstall` 就能静默卸载。
 * 这一层只做「探测 / 授权 / 转发调用」，判定逻辑在 `core/PrivilegedStateResolver`（有单测），
 * 选后端在 [Privileged]。所有调用都包了 runCatching：服务没起时 Stellar 的 API 会抛异常，
 * 这里一律翻译成「不可用」。
 */
object StellarService {

    /** Stellar 管理器的包名，也是拉起来启动服务的那个应用。 */
    const val MANAGER_PACKAGE = "roro.stellar.manager"

    private const val PERMISSION_REQUEST_CODE = 0x52

    // ---------- 探测 ----------

    fun isManagerInstalled(context: Context): Boolean {
        // 库自己的判断是首选；它返回 false 时再自己查一次包名，
        // 免得「包可见性被 ROM 裁掉」被误判成「没装管理器」
        if (runCatching { StellarHelper.isManagerInstalled(context) }.getOrDefault(false)) return true
        return runCatching { context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0); true }.getOrDefault(false)
    }

    fun isBinderAlive(): Boolean = runCatching { Stellar.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = runCatching { Stellar.checkSelfPermission() }.getOrDefault(false)

    /** 服务进程的 uid：0 = Root，2000 = ADB。 */
    fun serviceUid(): Int = runCatching { Stellar.uid }.getOrDefault(-1)

    /**
     * 注册底层回调，任何状态变化（服务连上/断开、授权结果回来）都会通知 [onChange]。
     * 底层三个回调在库那边签名不同，这里收敛成一个无参 lambda，上层不用感知。
     */
    fun registerCallbacks(onChange: () -> Unit) {
        // 三个回调分开包：任意一个注册失败不该把其余两个也带走
        runCatching {
            Stellar.addRequestPermissionResultListener(
                Stellar.OnRequestPermissionResultListener { _, _, _ -> onChange() },
            )
        }
        runCatching {
            Stellar.addBinderReceivedListenerSticky(Stellar.OnBinderReceivedListener { onChange() })
        }
        runCatching {
            Stellar.addBinderDeadListener(Stellar.OnBinderDeadListener { onChange() })
        }
    }

    // ---------- 权限 ----------

    fun requestPermission() {
        runCatching { Stellar.requestPermission(requestCode = PERMISSION_REQUEST_CODE) }
    }

    fun openManager(context: Context): Boolean =
        runCatching { StellarHelper.openManager(context) }.getOrDefault(false)

    // ---------- 特权调用 ----------

    /** 取系统服务 binder（真正执行时用的是服务身份，通常是 shell）。 */
    fun systemService(name: String): IBinder? =
        runCatching { Stellar.getSystemService(name) }.getOrNull()

    /** 包一层转发壳：所有经它发出的 binder 调用都以服务身份执行。 */
    fun wrap(binder: IBinder): IBinder = StellarBinderWrapper(binder)

    /**
     * 起一个特权进程跑命令。`newProcess` 是 Stellar 独有的能力（原版 Shizuku 13 已移除），
     * 卸载就靠它——比反射隐藏 API 稳得多，见 PackageOps 的说明。
     *
     * stderr 并进 stdout：分开读两个流在输出多的时候会互相堵住。
     * 服务没起或权限没给时抛异常，这里返回 null 让调用方走别的路线。
     */
    fun shell(command: String): ShellResult? = runCatching {
        val process = Stellar.newProcess(arrayOf("sh", "-c", "$command 2>&1"), null, null)
        val output = process.inputStream.bufferedReader().readText().trim()
        val exit = process.waitFor()
        ShellResult(exit, output)
    }.getOrNull()
}
