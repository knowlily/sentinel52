package com.fiftytwo.sentinel.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fiftytwo.sentinel.R
import com.fiftytwo.sentinel.core.DegradedNoticePolicy
import com.fiftytwo.sentinel.core.EventKind
import com.fiftytwo.sentinel.core.PollingPolicy
import com.fiftytwo.sentinel.core.PlanReason
import com.fiftytwo.sentinel.core.PrivilegedState
import com.fiftytwo.sentinel.core.PrivilegedStateResolver
import com.fiftytwo.sentinel.core.RuleMatcher
import com.fiftytwo.sentinel.core.UninstallPlanner
import com.fiftytwo.sentinel.data.SentinelStore
import com.fiftytwo.sentinel.privileged.PackageOps
import com.fiftytwo.sentinel.privileged.Privileged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 监控服务：常驻前台，轮询 + 安装广播双通道。
 *
 * 为什么两条通道都要：
 * - 广播（PACKAGE_INSTALL / PACKAGE_ADDED）反应快，装完的瞬间就动手；
 * - 轮询兜底：广播可能被 ROM 拦掉、或被「先装后立刻禁用广播」的手法躲过，
 *   而轮询是真的去列包名，绕不过去。默认 3 秒一次，开销很小（一次 binder 调用）。
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanLock = Mutex()
    private lateinit var ops: PackageOps

    private var loopJob: Job? = null
    private var uninstalledCount = 0
    private var lastErrorAt = 0L
    private var lastErrorText = ""

    /** 已经报过的包，避免「卸载失败」的包每轮都往日志里塞一条。 */
    private val reportedDetections = HashSet<String>()
    private val reportedSkips = HashSet<String>()

    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.data?.schemeSpecificPart.orEmpty()
            scope.launch { scan(reason = "安装广播 ${intent?.action?.substringAfterLast('.')}（$pkg）") }
        }
    }

    override fun onCreate() {
        super.onCreate()
        SentinelStore.init(this)
        ops = PackageOps(this)
        Notifier.ensureChannel(this)
        registerPackageReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SentinelStore.setMonitoring(false)
                SentinelStore.log(EventKind.INFO, detail = getString(R.string.log_monitor_stopped))
                shutdown()
                return START_NOT_STICKY
            }

            else -> {
                SentinelStore.setMonitoring(true)
                startForegroundSafely()
                if (loopJob == null) {
                    SentinelStore.log(EventKind.INFO, detail = getString(R.string.log_monitor_started))
                    startLoop()
                } else {
                    // 已经在跑了（比如被 START_STICKY 重启后又收到一次 start）：立刻补一次扫描
                    scope.launch { scan(reason = getString(R.string.reason_restart)) }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(installReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    /** 用户没有通知权限时 startForeground 仍要成功，只是不显示——这里不让它拖垮服务。 */
    private fun startForegroundSafely() {
        val notification = Notifier.build(this, getString(R.string.notif_idle))
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, Notifier.NOTIFICATION_ID, notification, type)
        }
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_INSTALL)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching { ContextCompat.registerReceiver(this, installReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onFailure { runCatching { registerReceiver(installReceiver, filter) } }
    }

    private fun startLoop() {
        loopJob = scope.launch {
            while (isActive) {
                runCatching { scan(reason = getString(R.string.reason_poll)) }
                    .onFailure { reportLoopError(it) }
                delay(PollingPolicy.intervalFor(SentinelStore.state.value.intervalMs, isInteractive()))
            }
        }
    }

    /** 屏幕是否亮着。取不到就当成亮着（宁可多扫也别漏）。 */
    private fun isInteractive(): Boolean =
        runCatching { getSystemService(PowerManager::class.java)?.isInteractive ?: true }.getOrDefault(true)

    /** 轮询出错会每轮都来一次，按内容+时间节流，别把日志刷爆。 */
    private fun reportLoopError(t: Throwable) {
        val text = t.message ?: t.javaClass.simpleName
        val now = System.currentTimeMillis()
        if (text == lastErrorText && now - lastErrorAt < ERROR_THROTTLE_MS) return
        lastErrorText = text
        lastErrorAt = now
        SentinelStore.log(EventKind.ERROR, detail = "扫描出错：$text")
    }

    private suspend fun scan(reason: String) {
        if (!scanLock.tryLock()) return
        try {
            val state = SentinelStore.state.value
            val rules = state.rules
            if (rules.isEmpty()) return

            // 探一次后端。里面有 ContentProvider / binder 调用，而 scan 跑在服务自己的后台协程里；
            // Privileged 内部有 60 秒缓存，所以这里每 3 秒来一次也不会每次都真去问三个后端
            val status = Privileged.refresh(this)
            SentinelStore.setPrivilegedStatus(status)

            // 名单全是精确包名时，只查这几条（通常就 1~几个），**不去把整机几百个包拉一遍**；
            // 出现通配规则才需要全量列表，因为 `com.tencent.*` 得靠它枚举子包。
            val all = if (rules.none { RuleMatcher.isWildcard(it.pattern) }) {
                rules.asSequence()
                    .map { it.pattern.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .filter { ops.isInstalled(it) }
                    .toList()
            } else {
                val installed = ops.installedPackages()
                // 本地列表可能因包可见性被裁剪，精确包名的规则再向特权服务单独确认一次
                val extraConfirmed = rules.asSequence()
                    .filterNot { RuleMatcher.isWildcard(it.pattern) }
                    .map { it.pattern.trim() }
                    .filter { it.isNotEmpty() && it !in installed }
                    .filter { ops.isInstalled(it) }
                    .toList()
                (installed + extraConfirmed).distinct()
            }

            // 规则变了就把「已报过」的记忆清掉，用户改完规则应该重新看到提示
            val ruleSignature = rules.joinToString("|") { "${it.pattern}:${it.enabled}" }
            if (ruleSignature != lastRuleSignature) {
                lastRuleSignature = ruleSignature
                reportedDetections.clear()
                reportedSkips.clear()
            }

            val entries = UninstallPlanner.plan(all, rules, packageName)
            val targets = UninstallPlanner.targets(entries)

            reportedDetections.retainAll(targets.toSet())
            reportedSkips.retainAll(entries.map { "${it.reason}|${it.packageName}" }.toSet())

            for (entry in entries) {
                // 只报「命中了但故意不动」的两种情况：保护名单和本应用自己。
                // 停用的规则界面上就是灰的，日志里再刷一遍纯属噪音。
                if (entry.reason != PlanReason.PROTECTED && entry.reason != PlanReason.SELF) continue
                val key = "${entry.reason}|${entry.packageName}"
                if (reportedSkips.add(key)) {
                    SentinelStore.log(EventKind.SKIPPED, entry.packageName, skipDetail(entry.reason, entry.pattern))
                }
            }

            val skippedNoBackend = mutableListOf<String>()
            for (pkg in targets) {
                if (reportedDetections.add(pkg)) {
                    SentinelStore.log(EventKind.DETECTED, pkg, "命中规则（触发：$reason）")
                }

                if (state.dryRun) {
                    if (reportedSkips.add("${PlanReason.TARGET}|$pkg")) {
                        SentinelStore.log(
                            EventKind.SKIPPED,
                            pkg,
                            getString(R.string.log_dry_run),
                        )
                    }
                    continue
                }

                if (!PrivilegedStateResolver.canOperate(status.state)) {
                    // 这一轮命中但没动手。日志每个包只记一次（轮询 3 秒一轮，别刷屏），
                    // 用户可见的提醒交到本轮结束后的 handleDegradedNotice
                    skippedNoBackend.add(pkg)
                    if (reportedNoBackend.add(pkg)) {
                        val who = status.kind?.label ?: "所有特权后端"
                        SentinelStore.log(
                            EventKind.FAILED,
                            pkg,
                            "$who 未就绪（${stateName(status.state)}），本轮跳过",
                        )
                    }
                    continue
                }

                val result = ops.uninstall(pkg, state.allUsers)
                if (result.ok) {
                    uninstalledCount++
                    SentinelStore.recordUninstall(pkg)
                    SentinelStore.log(EventKind.UNINSTALLED, pkg, "${result.via}｜${result.detail}")
                    updateNotification()
                } else {
                    SentinelStore.recordFailure()
                    SentinelStore.log(EventKind.FAILED, pkg, "${result.via}｜${result.detail}")
                    // 卸不动往往意味着后端状态变了（授权被撤 / 服务挂了）：强制重探一次并更新状态，
                    // 别拿着 60 秒的缓存继续撞墙
                    SentinelStore.setPrivilegedStatus(Privileged.refresh(this, force = true))
                }
            }

            // 三个后端都没就绪、命中却一个没卸 → 常驻通知改文案 + 弹一次提醒
            handleDegradedNotice(skippedNoBackend)
        } finally {
            scanLock.unlock()
        }
    }

    private var lastRuleSignature = ""

    /** 降级提示的去抖状态：同一批包多久内不重复提醒 + 已经记过日志的包。 */
    private var degradedFingerprint: String? = null
    private var lastDegradedAlertAt = 0L
    private val reportedNoBackend = mutableSetOf<String>()

    /** 上一次真正推给通知栏的文案，用来避免重复 notify。 */
    private var lastNotificationText: String? = null
    private var lastNotificationTitle: String? = null

    /** 降级提醒当前是不是挂在通知栏上（挂着才需要去 cancel）。 */
    private var alertShown = false

    private fun skipDetail(reason: PlanReason, pattern: String): String = when (reason) {
        PlanReason.PROTECTED -> getString(R.string.log_protected)
        PlanReason.SELF -> getString(R.string.log_self)
        PlanReason.DISABLED -> getString(R.string.log_rule_disabled, pattern)
        PlanReason.TARGET -> ""
    }

    private fun stateName(state: PrivilegedState): String = when (state) {
        PrivilegedState.NOT_INSTALLED -> "未安装"
        PrivilegedState.NOT_RUNNING -> "未激活"
        PrivilegedState.NO_PERMISSION -> "未授权"
        PrivilegedState.READY -> "就绪"
    }

    private fun updateNotification(degraded: Int = 0) {
        val text = if (degraded > 0) {
            getString(R.string.notif_degraded, degraded, uninstalledCount)
        } else {
            getString(R.string.notif_running, uninstalledCount)
        }
        // 文案没变就别 notify：这个函数每轮扫描都会被叫到，3 秒一次地刷通知栏纯属浪费
        val title = if (degraded > 0) getString(R.string.notif_title_degraded) else getString(R.string.notif_title)
        if (text == lastNotificationText && title == lastNotificationTitle) return
        lastNotificationText = text
        lastNotificationTitle = title
        runCatching {
            NotificationManagerCompat.from(this).notify(
                Notifier.NOTIFICATION_ID,
                Notifier.build(this, text, degraded = degraded > 0),
            )
        }
    }

    /**
     * 三个后端都没就绪时的降级提示：常驻通知换文案，并额外弹一次「命中但没能卸载」的提醒。
     *
     * 判定在 [DegradedNoticePolicy]（纯逻辑，有单测）：轮询默认 3 秒一轮，不节流就是刷屏——
     * 同一批包 10 分钟内只提醒一次，集合变了立刻提醒，一个都不跳时把提醒收回并把状态清空。
     */
    private fun handleDegradedNotice(skipped: List<String>) {
        val fingerprint = DegradedNoticePolicy.fingerprint(skipped)
        if (fingerprint.isEmpty()) {
            degradedFingerprint = null
            reportedNoBackend.clear()
            // 只有真的弹过提醒才去 cancel：这是每轮都会走到的分支，无脑 cancel 等于每轮一次 IPC
            if (alertShown) {
                alertShown = false
                Notifier.cancelAlert(this)
            }
            return
        }

        val now = System.currentTimeMillis()
        val shouldAlert = DegradedNoticePolicy.shouldAlert(
            previousFingerprint = degradedFingerprint,
            fingerprint = fingerprint,
            sinceLastAlertMs = now - lastDegradedAlertAt,
        )
        updateNotification(degraded = skipped.size)
        if (!shouldAlert) return

        degradedFingerprint = fingerprint
        lastDegradedAlertAt = now
        val who = Privileged.status.kind?.label?.let { "$it 未就绪" } ?: "三个后端均未安装或未就绪"
        alertShown = true
        Notifier.alert(
            this,
            getString(R.string.notif_alert_title),
            getString(R.string.notif_alert_text, skipped.size, who),
        )
    }

    private fun shutdown() {
        Notifier.cancelAlert(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.fiftytwo.sentinel.action.START"
        const val ACTION_STOP = "com.fiftytwo.sentinel.action.STOP"
        private const val ERROR_THROTTLE_MS = 30_000L
    }
}
