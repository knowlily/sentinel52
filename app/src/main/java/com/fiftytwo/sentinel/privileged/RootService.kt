package com.fiftytwo.sentinel.privileged

import android.content.Context
import com.fiftytwo.sentinel.core.UninstallCommand
import com.fiftytwo.sentinel.data.SentinelStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 后端：`su -c` 拿 uid 0，然后跑 `pm uninstall`。
 *
 * 三处和其它两个后端不一样，都是踩出来的：
 *
 * 1. **「装没装」不能只看文件**。Magisk 会把真的 `su` 放在 `/system/bin/su`，但 KernelSU 打开
 *    「传统 SU 命令支持」后是由**内核拦 `execve("/system/bin/su")`** 提供的——磁盘上根本没有这个文件，
 *    `ls` / `which su` 一律找不到。所以这里把「装了 su 管理器应用」也算作「这条路存在」
 *    （KernelSU / Magisk / APatch 三个管理器包名任一存在即可）。
 * 2. **授权状态查不到，只能跑一次**。Dhizuku / Stellar 的授权可以随时用 binder 查询，
 *    su 的授权只有真跑一次才知道，而且第一次跑会弹 su 管理器的授权框。所以拆成两件事：
 *    **探测**（[isSuInstalled]，不执行任何东西）和**申请授权**（[requestRoot]，用户点按钮才做）。
 * 3. **读输出要另起线程**：授权框没被处理时进程不退出，主线程 `readText()` 会连超时都用不上。
 *
 * uid 0 意味着 `PackageInstallerService` 那串判定直接走第一条（root 持有 DELETE_PACKAGES），
 * 而且系统应用也卸得掉——三个后端里权限最高的一个，也不需要任何常驻服务。
 */
object RootService {

    /** 常见的 su 落点。顺序不重要，取第一个能执行的。 */
    private val SU_PATHS = listOf(
        "/system/bin/su",       // Magisk / 多数 ROM；也是 KernelSU 内核虚拟出来的那个路径
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/vendor/bin/su",
        "/system/sbin/su",
        "/debug_ramdisk/su",    // Magisk 的 ramdisk
    )

    /**
     * su 管理器应用。装着任意一个就说明「这台机器有 root，只是本应用还没拿到」——
     * 探测时据此把状态报成「待授权」而不是「没装」，用户才知道该点哪个按钮。
     */
    private val SU_MANAGERS = listOf(
        "me.weishu.kernelsu" to "KernelSU",
        "com.topjohnwu.magisk" to "Magisk",
        "me.bmax.apatch" to "APatch",
    )

    /** KernelSU「传统 SU 命令支持」约定的路径：文件不存在，但内核会拦这个 execve。 */
    private const val KSU_SU_PATH = "/system/bin/su"

    private const val REQUEST_TIMEOUT_MS = 20_000L
    private const val VERIFY_TIMEOUT_MS = 5_000L

    /**
     * 已授权之后，多久复核一次。
     *
     * **别每次探测都 fork 一次 `su`**：起一个 root 进程（哪怕只跑 `id`）要过内核 / su 管理器那一套，
     * 是整套探测里最贵的一步，而「授权被撤」这种事很罕见。所以平时用缓存，5 分钟才复核一次；
     * 需要立刻知道的地方（用户点刷新、真要卸载前）会显式 [isGranted] force=true。
     */
    private const val VERIFY_TTL_MS = 5 * 60_000L

    @Volatile
    private var verifiedAt = 0L

    @Volatile
    private var verifiedResult = false

    // ---------- 探测 ----------

    /** 装了哪个 su 管理器（没装返回 null）。 */
    fun managerLabel(context: Context): String? = SU_MANAGERS.firstOrNull { (pkg, _) ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }?.second

    /** 文件系统上真实的 su。 */
    fun suPath(): String? = SU_PATHS.firstOrNull { path ->
        runCatching { File(path).let { it.exists() && it.canExecute() } }.getOrDefault(false)
    }

    /**
     * 真正拿来做 exec 的路径：存在的那个优先；都没有但装着 su 管理器时，用 KernelSU 约定的
     * `/system/bin/su`（内核拦 execve，文件在不在无所谓）。两者都没有才算「没 root 条件」。
     */
    fun suExecutable(context: Context): String? =
        suPath() ?: if (managerLabel(context) != null) KSU_SU_PATH else null

    /** 「这条路在这台机器上是否存在」。**不执行任何东西**，不弹授权框，可以随便调。 */
    fun isSuInstalled(context: Context): Boolean = suExecutable(context) != null

    /**
     * 是否已获 root。
     *
     * 结果会缓存 [VERIFY_TTL_MS]：轮询默认 3 秒一轮，如果每轮都 `su -c id`，光是 fork root 进程
     * 就够把电池啃掉一块。[force] = true 时忽略缓存（用户点刷新、授权流程结束后、真要卸载前）。
     * 缓存为「否」时每次都真查——那种情况下 `SentinelStore.isRootGranted()` 早就 false 了，
     * 根本走不到这里，也就不存在反复 fork 的问题。
     */
    fun isGranted(context: Context, force: Boolean = false): Boolean {
        val su = suExecutable(context) ?: return false
        if (!SentinelStore.isRootGranted()) return false

        val now = System.currentTimeMillis()
        if (!force && verifiedResult && now - verifiedAt < VERIFY_TTL_MS) return true

        val result = exec(su, "id", VERIFY_TIMEOUT_MS)
        verifiedResult = result.ok && result.output.contains("uid=0")
        verifiedAt = now
        if (!verifiedResult) SentinelStore.setRootGranted(false)
        return verifiedResult
    }

    // ---------- 授权 ----------

    /** 真跑一次 `su -c id`；**可能弹 su 管理器的授权框**，所以只在用户明确要求时调。 */
    fun requestRoot(context: Context): Pair<Boolean, String> {
        val su = suExecutable(context)
            ?: return false to "未检测到 su，也未安装 su 管理器（Magisk / KernelSU / APatch）"

        val result = exec(su, "id", REQUEST_TIMEOUT_MS)
        val granted = result.ok && result.output.contains("uid=0")
        SentinelStore.setRootGranted(granted)
        verifiedResult = granted
        verifiedAt = System.currentTimeMillis()

        return if (granted) {
            val who = result.output.lineSequence().firstOrNull()?.trim() ?: "uid=0"
            val via = managerLabel(context)?.let { "$it · " } ?: ""
            true to "已获得 root 权限（$via$who）"
        } else {
            false to "su 未返回 root：${result.toString().ifBlank { "被拒绝或超时" }}"
        }
    }

    // ---------- 卸载 ----------

    /** 卸载。返回 (是否受理, 说明)——「受理」不等于删掉，调用方会再查一次。 */
    fun uninstall(context: Context, packageName: String, allUsers: Boolean): Pair<Boolean, String> {
        val su = suExecutable(context) ?: return false to "缺少 root 条件（既无 su，也未安装 su 管理器）"
        if (!SentinelStore.isRootGranted()) return false to "尚未授权（请点击「申请 Root 授权」）"

        val command = UninstallCommand.build(packageName, allUsers)
        val result = exec(su, command, REQUEST_TIMEOUT_MS)
        return if (result.ok) true to "$command → $result" else false to "$command → $result"
    }

    /**
     * `su -c <command>`。读输出放到另一个线程：授权框没被处理时进程一直不退出，
     * 主线程 `readText()` 会永远等不到 EOF，超时也就用不上。这里先起读线程再 `waitFor(timeout)`，
     * 超时就 `destroyForcibly`。
     *
     * 起不来（su 没了 / 被 SELinux 拒）时返回 exit=-1 并把**异常原文带上**：
     * 这类失败在真机上只能靠这行字诊断（例如 KernelSU 用户态组件没装全时是
     * `error=2, No such file or directory`）。
     */
    private fun exec(suPath: String, command: String, timeoutMs: Long): ShellResult = try {
        val process = ProcessBuilder(suPath, "-c", command)
            .redirectErrorStream(true)
            .start()

        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        }
        reader.isDaemon = true
        reader.start()

        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            reader.join(200)
            ShellResult(-1, "超时 ${timeoutMs}ms（su 授权弹窗可能尚未处理）")
        } else {
            reader.join(500)
            ShellResult(process.exitValue(), output.toString().trim())
        }
    } catch (t: Throwable) {
        ShellResult(-1, "启动进程失败 $suPath：${t.javaClass.simpleName}: ${t.message}")
    }
}
