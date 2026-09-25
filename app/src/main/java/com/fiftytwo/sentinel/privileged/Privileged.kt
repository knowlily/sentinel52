package com.fiftytwo.sentinel.privileged

import android.content.Context
import com.fiftytwo.sentinel.core.BackendKind
import com.fiftytwo.sentinel.core.BackendProbe
import com.fiftytwo.sentinel.core.BackendSelector
import com.fiftytwo.sentinel.core.EventKind
import com.fiftytwo.sentinel.core.PrivilegedState
import com.fiftytwo.sentinel.core.PrivilegedStatus
import com.fiftytwo.sentinel.core.UninstallCommand
import com.fiftytwo.sentinel.data.SentinelStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 三个后端的门面：探测 → 挑一个能用的 → 把卸载请求转过去。
 *
 * - **Root**：`su -c` 拿 uid 0 跑 `pm uninstall`（[RootService]）；
 * - **Dhizuku（设备所有者）**：让代码跑进它进程里调公开 API（[DhizukuService] + [DhizukuUserService]）；
 * - **Stellar（shell）**：起一个 shell 身份的进程跑 `pm uninstall`（[StellarService]）。
 *
 * 优先级 = [BackendKind] 的声明顺序（就绪 > 已装）：**Root > Dhizuku > Stellar**。
 * 授权状态各管各的：没授权的后端只会在界面上提示，绝不会被拿去执行卸载。
 *
 * 注意所有探测都是 **ContentProvider / binder / 起进程** 调用，别在主线程上调。
 */
object Privileged {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val callbacksRegistered = AtomicBoolean(false)

    @Volatile
    private var activeKind: BackendKind? = null

    @Volatile
    private var lastStatus = PrivilegedStatus.UNKNOWN

    /** 后端探测结果的缓存时间（见 [refresh]）。 */
    private const val PROBE_TTL_MS = 60_000L

    @Volatile
    private var probesAt = 0L

    @Volatile
    private var cachedProbes: List<BackendProbe> = emptyList()

    val kind: BackendKind? get() = activeKind

    val status: PrivilegedStatus get() = lastStatus

    /** 注册状态变化回调（服务连上/断开、授权结果回来）。 */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        if (callbacksRegistered.compareAndSet(false, true)) {
            val notify = { listeners.forEach { runCatching { it() } } }
            runCatching { StellarService.registerCallbacks(notify) }
            // Dhizuku 只有「请求授权」这一处异步回调，其余靠界面自己刷新
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyListeners() {
        listeners.forEach { runCatching { it() } }
    }

    /**
     * 探测三个后端、挑一个。**必须在后台线程调用**（里面有 ContentProvider / binder / 起进程）。
     *
     * 结果会缓存 [PROBE_TTL_MS]：这套探测里有一次 ContentProvider 往返、几次 binder 调用，
     * 后端状态却变得很慢（授权、服务重启都是人手触发的），轮询时没必要每 3 秒重问一遍。
     * [force] = true 时忽略缓存（用户点刷新、授权流程结束、卸载失败需要重新判断时）。
     */
    fun refresh(context: Context, force: Boolean = false): PrivilegedStatus {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        if (!force && cachedProbes.isNotEmpty() && now - probesAt < PROBE_TTL_MS) {
            lastStatus = buildStatus(cachedProbes, appContext)
            return lastStatus
        }
        val probes = probeAll(appContext, force)
        cachedProbes = probes
        probesAt = now
        lastStatus = buildStatus(probes, appContext)
        return lastStatus
    }

    /** 三个后端各探一次。开销就在这儿，所以调用点要么低频、要么被缓存挡住。 */
    private fun probeAll(appContext: Context, force: Boolean): List<BackendProbe> {
        val suAvailable = attempt { RootService.isSuInstalled(appContext) } ?: false
        return listOf(
            BackendProbe(
                kind = BackendKind.ROOT,
                managerInstalled = suAvailable,
                // su 没有守护进程，「通道存在」就等于有 su（文件或 su 管理器）
                binderAlive = suAvailable,
                permissionGranted = attempt { RootService.isGranted(appContext, force) } ?: false,
            ),
            BackendProbe(
                kind = BackendKind.DHIZUKU,
                managerInstalled = attempt { DhizukuService.isManagerInstalled(appContext) } ?: false,
                binderAlive = attempt { DhizukuService.isBinderAlive(appContext) } ?: false,
                permissionGranted = attempt { DhizukuService.hasPermission() } ?: false,
            ),
            BackendProbe(
                kind = BackendKind.STELLAR,
                managerInstalled = attempt { StellarService.isManagerInstalled(appContext) } ?: false,
                binderAlive = attempt { StellarService.isBinderAlive() } ?: false,
                permissionGranted = attempt { StellarService.hasPermission() } ?: false,
            ),
        )
    }

    /** 从探测结果里挑一个后端，拼出对外的状态。 */
    private fun buildStatus(probes: List<BackendProbe>, appContext: Context): PrivilegedStatus {
        val selected = BackendSelector.select(probes)
        activeKind = selected
        val probe = BackendSelector.probeOf(probes, selected)
        val state = probe?.state ?: PrivilegedState.NOT_INSTALLED
        return PrivilegedStatus(
            kind = selected,
            state = state,
            uid = when {
                state != PrivilegedState.READY -> -1
                selected == BackendKind.ROOT -> 0
                selected == BackendKind.STELLAR -> attempt { StellarService.serviceUid() } ?: -1
                else -> -1
            },
            probes = probes,
        )
    }

    /**
     * [kind] 省略时用当前后端。
     *
     * Root 这条要真跑一次 `su -c id`（会弹 su 管理器的授权框，最长等 20 秒），
     * 所以结果会写进应用日志，用户能在界面上看到「拿到没拿到」。
     */
    fun requestPermission(context: Context, kind: BackendKind? = null) {
        when (kind ?: activeKind ?: kindOfInstalledManager(context)) {
            BackendKind.ROOT -> {
                val (ok, detail) = RootService.requestRoot(context.applicationContext)
                SentinelStore.log(if (ok) EventKind.INFO else EventKind.ERROR, detail = "Root 授权：$detail")
            }

            BackendKind.DHIZUKU -> DhizukuService.requestPermission { notifyListeners() }
            BackendKind.STELLAR -> StellarService.requestPermission()
            null -> Unit
        }
        notifyListeners()
    }

    /**
     * 打开「管理器」。Root 没有管理器应用（su 授权框由 su 管理器自己弹），
     * 所以这条路返回 false，界面据此不显示这个按钮。
     */
    fun openManager(context: Context, kind: BackendKind? = null): Boolean =
        when (kind ?: activeKind ?: kindOfInstalledManager(context)) {
            BackendKind.DHIZUKU -> DhizukuService.openManager(context)
            BackendKind.STELLAR -> StellarService.openManager(context)
            BackendKind.ROOT, null -> false
        }

    /**
     * 执行静默卸载。返回 (是否受理, 走的哪条路 + 说明)。
     * 「受理」不等于删掉——调用方要再查一次（PackageOps 里做了）。
     */
    fun uninstall(context: Context, packageName: String, allUsers: Boolean): Pair<Boolean, String> =
        when (activeKind) {
            BackendKind.ROOT -> {
                val (ok, detail) = RootService.uninstall(context.applicationContext, packageName, allUsers)
                ok to "Root｜$detail"
            }

            BackendKind.DHIZUKU -> {
                val (ok, detail) = DhizukuService.uninstall(context, packageName, allUsers)
                ok to "Dhizuku｜$detail"
            }

            BackendKind.STELLAR -> {
                val command = UninstallCommand.build(packageName, allUsers)
                val result = StellarService.shell(command)
                when {
                    result == null -> false to "Stellar｜不可用（服务未运行，或无法启动特权进程）"
                    result.ok -> true to "Stellar｜$command → ${result}"
                    else -> false to "Stellar｜$command → ${result}"
                }
            }

            null -> false to "无可用后端（Root / Dhizuku / Stellar 均不可用）"
        }

    /** 还没探测过时，用「哪个后端装了」来猜一个目标（授权用），顺序同优先级。 */
    private fun kindOfInstalledManager(context: Context): BackendKind? = when {
        attempt { RootService.isSuInstalled(context.applicationContext) } == true -> BackendKind.ROOT
        attempt { DhizukuService.isManagerInstalled(context.applicationContext) } == true -> BackendKind.DHIZUKU
        attempt { StellarService.isManagerInstalled(context.applicationContext) } == true -> BackendKind.STELLAR
        else -> null
    }

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        null
    }
}
