package com.fiftytwo.sentinel.data

import android.content.Context
import android.content.SharedPreferences
import com.fiftytwo.sentinel.core.DailyCounter
import com.fiftytwo.sentinel.core.EventCodec
import com.fiftytwo.sentinel.core.EventKind
import com.fiftytwo.sentinel.core.RuleCodec
import com.fiftytwo.sentinel.core.SentinelEvent
import com.fiftytwo.sentinel.core.WatchRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 卸载统计。
 *
 * 单独记账、单独持久化，**不去数日志条数**：日志本身有条数上限（见 EventCodec），一超上限统计就失真。
 * 「今日」按本地自然日归零——判断依据是存下来的日期戳，不依赖任何定时任务。
 */
data class UninstallStats(
    val total: Int = 0,
    val today: Int = 0,
    /** 本次监控（上一次把「监控」开关打开之后）的次数。 */
    val session: Int = 0,
    val failed: Int = 0,
    val lastAt: Long = 0L,
    val lastPackage: String = "",
)

/** 界面上能看到的全部可变状态。 */
data class SentinelState(
    val rules: List<WatchRule> = emptyList(),
    val events: List<SentinelEvent> = emptyList(),
    val dryRun: Boolean = false,
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val allUsers: Boolean = false,
    val monitoring: Boolean = false,
    val stats: UninstallStats = UninstallStats(),
) {
    companion object {
        const val DEFAULT_INTERVAL_MS = 3_000L
        const val MIN_INTERVAL_MS = 1_000L
        const val MAX_INTERVAL_MS = 60_000L
    }
}

/**
 * 规则 / 日志 / 开关的唯一数据源。
 *
 * 做成单例是因为同一个进程里有两拨人读写它：界面（Compose）和前台服务（轮询循环）。
 * 让服务也走 StateFlow，界面就不用轮询 SharedPreferences，也不会出现
 * 「服务已经卸载了，界面还显示监控中」这种两套状态打架的情况。
 */
object SentinelStore {

    private const val PREFS = "sentinel"
    private const val KEY_RULES = "rules"
    private const val KEY_EVENTS = "events"
    private const val KEY_DRY_RUN = "dry_run"
    private const val KEY_INTERVAL = "interval_ms"
    private const val KEY_ALL_USERS = "all_users"
    private const val KEY_MONITORING = "monitoring"
    private const val KEY_ROOT_GRANTED = "root_granted"
    private const val KEY_STATS_TOTAL = "stats_total"
    private const val KEY_STATS_TODAY = "stats_today"
    private const val KEY_STATS_DAY = "stats_day"
    private const val KEY_STATS_SESSION = "stats_session"
    private const val KEY_STATS_FAILED = "stats_failed"
    private const val KEY_STATS_LAST_AT = "stats_last_at"
    private const val KEY_STATS_LAST_PKG = "stats_last_pkg"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(SentinelState())
    val state: StateFlow<SentinelState> = _state.asStateFlow()

    private val _privilegedStatus = MutableStateFlow<com.fiftytwo.sentinel.core.PrivilegedStatus?>(null)
    val privilegedStatus: StateFlow<com.fiftytwo.sentinel.core.PrivilegedStatus?> =
        _privilegedStatus.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val now = System.currentTimeMillis()
        _state.value = SentinelState(
            rules = RuleCodec.decode(p.getString(KEY_RULES, null)),
            events = EventCodec.decode(p.getString(KEY_EVENTS, null)),
            dryRun = p.getBoolean(KEY_DRY_RUN, false),
            intervalMs = p.getLong(KEY_INTERVAL, SentinelState.DEFAULT_INTERVAL_MS)
                .coerceIn(SentinelState.MIN_INTERVAL_MS, SentinelState.MAX_INTERVAL_MS),
            allUsers = p.getBoolean(KEY_ALL_USERS, false),
            monitoring = p.getBoolean(KEY_MONITORING, false),
            stats = UninstallStats(
                total = p.getInt(KEY_STATS_TOTAL, 0),
                // 存的日期戳不是今天 → 「今日」从零开始（跨天第一次读也会重置）
                today = DailyCounter.valueOnRead(
                    storedDay = p.getString(KEY_STATS_DAY, null),
                    today = dayStamp(now),
                    stored = p.getInt(KEY_STATS_TODAY, 0),
                ),
                session = p.getInt(KEY_STATS_SESSION, 0),
                failed = p.getInt(KEY_STATS_FAILED, 0),
                lastAt = p.getLong(KEY_STATS_LAST_AT, 0L),
                lastPackage = p.getString(KEY_STATS_LAST_PKG, "").orEmpty(),
            ),
        )
    }

    /** 本地自然日戳，用来判断「今日」是否需要归零。 */
    private fun dayStamp(now: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))

    private fun edit(block: (SharedPreferences.Editor) -> Unit) {
        val p = prefs ?: return
        p.edit().also(block).apply()
    }

    fun setPrivilegedStatus(value: com.fiftytwo.sentinel.core.PrivilegedStatus) {
        _privilegedStatus.value = value
    }

    // ---------- 规则 ----------

    /** 已存在则返回 false（调用方据此提示「已在名单中」）。 */
    @Synchronized
    fun addRule(pattern: String, label: String = "", now: Long = System.currentTimeMillis()): Boolean {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return false
        val current = _state.value.rules
        if (current.any { it.pattern == trimmed }) return false
        val next = current + WatchRule(
            pattern = trimmed,
            enabled = true,
            addedAt = now,
            label = RuleCodec.sanitizeLabel(label),
        )
        _state.value = _state.value.copy(rules = next)
        persistRules(next)
        return true
    }

    /**
     * 改一条规则（包名和/或应用名）。
     *
     * 改成别的规则已经在用的包名会被拒（返回 false），否则两条规则的优先级就说不清了。
     * 顺序、启用状态、添加时间都不动——用户只改了他想改的那两项。
     */
    @Synchronized
    fun updateRule(oldPattern: String, newPattern: String, newLabel: String): Boolean {
        val target = newPattern.trim()
        if (target.isEmpty()) return false
        val current = _state.value.rules
        val index = current.indexOfFirst { it.pattern == oldPattern }
        if (index < 0) return false
        if (target != oldPattern && current.any { it.pattern == target }) return false
        val next = current.toMutableList()
        next[index] = next[index].copy(pattern = target, label = RuleCodec.sanitizeLabel(newLabel))
        _state.value = _state.value.copy(rules = next)
        persistRules(next)
        return true
    }

    /** 规则上配的应用名（没配就是空串）。界面用它把日志/统计里的包名换成看得懂的名字。 */
    fun labelOf(pattern: String): String =
        _state.value.rules.firstOrNull { it.pattern == pattern }?.label.orEmpty()

    @Synchronized
    fun removeRule(pattern: String) {
        val next = _state.value.rules.filterNot { it.pattern == pattern }
        _state.value = _state.value.copy(rules = next)
        persistRules(next)
    }

    @Synchronized
    fun setRuleEnabled(pattern: String, enabled: Boolean) {
        val next = _state.value.rules.map { if (it.pattern == pattern) it.copy(enabled = enabled) else it }
        _state.value = _state.value.copy(rules = next)
        persistRules(next)
    }

    private fun persistRules(rules: List<WatchRule>) = edit { it.putString(KEY_RULES, RuleCodec.encode(rules)) }

    // ---------- 日志 ----------

    @Synchronized
    fun log(kind: EventKind, packageName: String = "", detail: String = "", now: Long = System.currentTimeMillis()) {
        val next = EventCodec.append(_state.value.events, SentinelEvent(now, kind, packageName, detail))
        _state.value = _state.value.copy(events = next)
        edit { it.putString(KEY_EVENTS, EventCodec.encode(next)) }
    }

    @Synchronized
    fun clearLog() {
        _state.value = _state.value.copy(events = emptyList())
        edit { it.putString(KEY_EVENTS, "") }
    }

    // ---------- 统计 ----------

    /**
     * 记一次成功的卸载。总数 / 今日 / 本次监控 +1，并记下最近一次的包名与时间。
     * 由前台服务在日志落盘的同时调用（成功才算，未就绪跳过的不算）。
     */
    @Synchronized
    fun recordUninstall(packageName: String, now: Long = System.currentTimeMillis()) {
        val day = dayStamp(now)
        val storedDay = prefs?.getString(KEY_STATS_DAY, null)
        val current = _state.value.stats
        val next = current.copy(
            total = current.total + 1,
            today = DailyCounter.valueOnBump(storedDay, day, current.today),
            session = current.session + 1,
            lastAt = now,
            lastPackage = packageName,
        )
        _state.value = _state.value.copy(stats = next)
        edit {
            it.putInt(KEY_STATS_TOTAL, next.total)
            it.putInt(KEY_STATS_TODAY, next.today)
            it.putString(KEY_STATS_DAY, day)
            it.putInt(KEY_STATS_SESSION, next.session)
            it.putLong(KEY_STATS_LAST_AT, next.lastAt)
            it.putString(KEY_STATS_LAST_PKG, next.lastPackage)
        }
    }

    /** 记一次真正动手了但没成功的卸载（后端没就绪、规则停用之类的「没动手」不算）。 */
    @Synchronized
    fun recordFailure() {
        val next = _state.value.stats.let { it.copy(failed = it.failed + 1) }
        _state.value = _state.value.copy(stats = next)
        edit { it.putInt(KEY_STATS_FAILED, next.failed) }
    }

    /** 开启监控时把「本次监控」归零。 */
    @Synchronized
    fun resetSession() {
        val next = _state.value.stats.copy(session = 0)
        _state.value = _state.value.copy(stats = next)
        edit { it.putInt(KEY_STATS_SESSION, 0) }
    }

    // ---------- 开关 ----------

    fun setDryRun(value: Boolean) {
        _state.value = _state.value.copy(dryRun = value)
        edit { it.putBoolean(KEY_DRY_RUN, value) }
    }

    fun setAllUsers(value: Boolean) {
        _state.value = _state.value.copy(allUsers = value)
        edit { it.putBoolean(KEY_ALL_USERS, value) }
    }

    fun setIntervalMs(value: Long) {
        val clamped = value.coerceIn(SentinelState.MIN_INTERVAL_MS, SentinelState.MAX_INTERVAL_MS)
        _state.value = _state.value.copy(intervalMs = clamped)
        edit { it.putLong(KEY_INTERVAL, clamped) }
    }

    fun setMonitoring(value: Boolean) {
        // 「本次监控」的计算起点：从关到开的那一刻
        if (value && !_state.value.monitoring) resetSession()
        _state.value = _state.value.copy(monitoring = value)
        edit { it.putBoolean(KEY_MONITORING, value) }
    }

    // ---------- root 授权 ----------
    //
    // 刻意不放进 SentinelState：它只是 RootService 的一次性结论（su 授权只看得到结果、查不到状态），
    // 界面读的是 PrivilegedStatus 里的探测结论，不需要再多一份状态源。

    fun isRootGranted(): Boolean = prefs?.getBoolean(KEY_ROOT_GRANTED, false) ?: false

    fun setRootGranted(value: Boolean) = edit { it.putBoolean(KEY_ROOT_GRANTED, value) }
}
