package com.fiftytwo.sentinel.privileged

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Process

/**
 * 查询/卸载的薄包装层（这一层碰 Android API，所以不写单测；判定逻辑都在 core/ 下，那边有单测）。
 *
 * 卸载不在这里实现：静默卸载需要设备所有者或 shell 身份，交给 [Privileged] 后端
 * （Dhizuku 的 DO 进程 / Stellar 起的 shell 进程）。这里只做两件事——
 * 查包（优先经特权后端查，避免包可见性的干扰）和**复查**（受理 ≠ 删掉）。
 */
class PackageOps(context: Context) {

    private val appContext = context.applicationContext

    data class UninstallResult(val ok: Boolean, val via: String, val detail: String)

    data class AppInfo(val packageName: String, val label: String, val isSystem: Boolean)

    private val ipmClass: Class<*>? by lazy { runCatching { Class.forName(IPM) }.getOrNull() }

    private val ipmStubClass: Class<*>? by lazy { runCatching { Class.forName("$IPM\$Stub") }.getOrNull() }

    // ---------- 查询 ----------

    /**
     * 当前设备上的包名列表。
     *
     * **本地优先，特权服务只作兜底**：清单里声明了 QUERY_ALL_PACKAGES，本地查询在正常设备上就是完整的，
     * 而且不用多走一次 binder；万一某个 ROM 还是裁了可见性（本地返回空），再用特权服务查一遍。
     */
    fun installedPackages(): List<String> = localInstalled().ifEmpty { installedViaPrivileged() ?: emptyList() }

    /**
     * 是否已安装。同样**本地优先**：一次 `getPackageInfo`（binder 到 system_server）比"先问特权服务
     * 再退回本地"便宜，而轮询（默认 3 秒一轮）会反复调它。
     */
    fun isInstalled(packageName: String): Boolean =
        localExists(packageName) || (remotePackageExists(packageName) ?: false)

    /** 给界面用的「已安装应用」列表（带名字），本地查就够。 */
    fun installedApps(): List<AppInfo> {
        val pm = appContext.packageManager
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        return apps.map { info ->
            val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
            AppInfo(
                packageName = info.packageName,
                label = label,
                isSystem = (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }.sortedBy { it.label }
    }

    private fun localInstalled(): List<String> = runCatching {
        appContext.packageManager.getInstalledPackages(0).map { it.packageName }
    }.getOrDefault(emptyList())

    private fun localExists(packageName: String): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    private fun installedViaPrivileged(): List<String>? {
        val pm = remotePackageManager() ?: return null
        val cls = ipmClass ?: return null
        return try {
            val slice = invokeGetInstalledPackages(cls, pm) ?: return null
            val list = slice.javaClass.getMethod("getList").invoke(slice) as? List<*> ?: return null
            list.mapNotNull { (it as? PackageInfo)?.packageName }.ifEmpty { null }
        } catch (t: Throwable) {
            null
        }
    }

    private fun remotePackageExists(packageName: String): Boolean? {
        val pm = remotePackageManager() ?: return null
        val cls = ipmClass ?: return null
        return try {
            invokeGetPackageInfo(cls, pm, packageName) != null
        } catch (t: Throwable) {
            null
        }
    }

    // ---------- 卸载 ----------

    /**
     * 静默卸载。真正的执行交给 [Privileged]（Dhizuku 的设备所有者身份，或 Stellar 的 shell 身份），
     * 这里只负责「受理 ≠ 删掉」这件事：每次都以**再查一次还在不在**为判据。
     */
    fun uninstall(packageName: String, allUsers: Boolean = false): UninstallResult {
        val (accepted, detail) = Privileged.uninstall(appContext, packageName, allUsers)
        val gone = waitUntilGone(packageName, timeoutMs = if (accepted) 6_000L else 1_200L)
        return when {
            gone && accepted -> UninstallResult(true, "已受理", "$detail（已复查：设备上不存在）")
            gone -> UninstallResult(true, "无需卸载", "$detail（已复查：设备上本就无此包）")
            else -> UninstallResult(false, "未受理", detail)
        }
    }

    private fun waitUntilGone(packageName: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val exists = remotePackageExists(packageName) ?: localExists(packageName)
            if (!exists) return true
            if (System.currentTimeMillis() >= deadline) return false
            try {
                Thread.sleep(POLL_STEP_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    // ---------- 反射基础设施 ----------

    /**
     * 拿一个「执行时是 shell uid」的 IPackageManager。
     * 包装器负责把调用转发到特权服务，由它用 shell 身份 transact。
     */
    private fun remotePackageManager(): Any? {
        val stub = ipmStubClass ?: return null
        val binder = StellarService.systemService("package") ?: return null
        val wrapped = StellarService.wrap(binder)
        return try {
            val asInterface = stub.getMethod("asInterface", android.os.IBinder::class.java)
            asInterface.invoke(null, wrapped)
        } catch (t: Throwable) {
            null
        }
    }

    private fun invokeGetInstalledPackages(cls: Class<*>, pm: Any): Any? {
        // Android 13 起 flags 是 long；更早是 int
        val longSig = runCatching {
            cls.getMethod(
                "getInstalledPackages",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()
        if (longSig != null) return longSig.invoke(pm, 0L, currentUserId())

        val intSig = cls.getMethod(
            "getInstalledPackages",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        return intSig.invoke(pm, 0, currentUserId())
    }

    private fun invokeGetPackageInfo(cls: Class<*>, pm: Any, packageName: String): Any? {
        val longSig = runCatching {
            cls.getMethod(
                "getPackageInfo",
                String::class.java,
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
        }.getOrNull()
        if (longSig != null) return longSig.invoke(pm, packageName, 0L, currentUserId())

        val intSig = cls.getMethod(
            "getPackageInfo",
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        return intSig.invoke(pm, packageName, 0, currentUserId())
    }

    /**
     * 当前用户 id（int）。公开 SDK 里没有这个 getter——`Context.getUserId()`、
     * `UserHandle.myUserId()`、`UserHandle.of()` 全是 @hide（javap android.jar 逐个确认过），
     * 所以这里反射取；取不到就按 0（USER_SYSTEM），单用户设备上这就是对的。
     */
    private fun currentUserId(): Int = runCatching {
        val method = Class.forName("android.os.UserHandle").getMethod("myUserId")
        method.invoke(null) as Int
    }.getOrDefault(0)

    companion object {
        private const val IPM = "android.content.pm.IPackageManager"

        private const val POLL_STEP_MS = 250L
    }
}
