package com.fiftytwo.sentinel.privileged

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import com.rosan.dhizuku.api.DhizukuUserServiceArgs

/**
 * Dhizuku 客户端（见 `D:\githubs\Dhizuku-API-main` 的 dhizuku-api）。
 *
 * Dhizuku 本身是设备所有者，通过一个 ContentProvider 把 binder 借给别的应用；
 * 真正的管理工作（比如卸载）要**在 Dhizuku 进程里执行**才带 DO 身份，
 * 所以这里除了探测/授权，还负责绑定 [DhizukuUserService]。
 *
 * 所有调用都包 runCatching：Dhizuku 没装/没激活时这些 API 会抛异常，
 * 这里一律翻译成「不可用」。
 */
object DhizukuService {

    /** Dhizuku 管理器的包名。 */
    const val MANAGER_PACKAGE = "com.rosan.dhizuku"

    private const val BIND_TIMEOUT_MS = 4_000L
    private const val POLL_STEP_MS = 50L

    @Volatile
    private var remote: ISentinelDeviceAdmin? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = service?.let { ISentinelDeviceAdmin.Stub.asInterface(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
        }
    }

    // ---------- 探测 ----------

    fun isManagerInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** 能拿到 Dhizuku 的 binder，且它自己确实是设备所有者 / 工作资料所有者。 */
    fun isBinderAlive(context: Context): Boolean =
        runCatching { Dhizuku.init(context) }.getOrDefault(false)

    fun hasPermission(): Boolean =
        runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)

    // ---------- 授权 ----------

    /** 弹 Dhizuku 自己的授权界面；结果异步回来。 */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        runCatching {
            val listener = object : DhizukuRequestPermissionListener() {
                override fun onRequestPermission(grantResult: Int) {
                    runCatching { onResult(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) }
                }
            }
            Dhizuku.requestPermission(listener)
        }
    }

    fun openManager(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(MANAGER_PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    // ---------- 卸载 ----------

    /**
     * 通过用户服务执行卸载。返回 (是否受理, 说明)。
     * 「受理」不等于删掉——客户端会再查一次，见 PackageOps。
     */
    fun uninstall(context: Context, packageName: String, allUsers: Boolean): Pair<Boolean, String> {
        if (!isBinderAlive(context)) return false to "Dhizuku 未激活或未安装"
        if (!hasPermission()) return false to "未获得 Dhizuku 授权"

        val service = ensureBound(context) ?: return false to "绑定 Dhizuku 用户服务失败（$BIND_TIMEOUT_MS ms 超时）"
        return runCatching {
            val accepted = service.uninstall(packageName, allUsers)
            if (accepted) true to "已在 Dhizuku 进程内发起（${service.whoAmI()}）" else false to "用户服务拒绝执行"
        }.getOrElse { t ->
            remote = null
            false to (t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName)
        }
    }

    /** 绑一次用户服务并把 binder 缓存起来；已经绑过就直接返回。 */
    private fun ensureBound(context: Context): ISentinelDeviceAdmin? {
        remote?.let { return it }

        val appContext = context.applicationContext
        val args = DhizukuUserServiceArgs(ComponentName(appContext, DhizukuUserService::class.java))
        val requested = runCatching { Dhizuku.bindUserService(args, connection) }.getOrDefault(false)
        if (!requested) return null

        // Dhizuku 的回调在主线程上回来，而这里通常在后台线程——等一会儿
        val deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            remote?.let { return it }
            try {
                Thread.sleep(POLL_STEP_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return remote
    }
}
