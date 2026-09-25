package com.fiftytwo.sentinel.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fiftytwo.sentinel.core.BackendKind
import com.fiftytwo.sentinel.core.PackageNameValidator
import com.fiftytwo.sentinel.core.PrivilegedStatus
import com.fiftytwo.sentinel.data.SentinelState
import com.fiftytwo.sentinel.data.SentinelStore
import com.fiftytwo.sentinel.privileged.PackageOps
import com.fiftytwo.sentinel.privileged.Privileged
import com.fiftytwo.sentinel.service.BatteryOptimizations
import com.fiftytwo.sentinel.service.MonitorRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<SentinelState> = SentinelStore.state
    val privileged: StateFlow<PrivilegedStatus?> = SentinelStore.privilegedStatus

    private val _apps = MutableStateFlow<List<PackageOps.AppInfo>>(emptyList())
    val apps: StateFlow<List<PackageOps.AppInfo>> = _apps.asStateFlow()

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading.asStateFlow()

    /** 是否已进电池优化白名单（界面在 onResume 时会重读一次）。 */
    private val _batteryExempt = MutableStateFlow(false)
    val batteryExempt: StateFlow<Boolean> = _batteryExempt.asStateFlow()

    /**
     * 后端状态变化（Dhizuku 授权结果 / Stellar 服务连上断开）的回调。
     * 两个库的多个回调已经在 [Privileged] 里收敛成这一个 lambda。
     */
    private val statusListener: () -> Unit = { refreshPrivileged() }

    init {
        Privileged.addListener(statusListener)
        refreshPrivileged()
    }

    override fun onCleared() {
        Privileged.removeListener(statusListener)
        super.onCleared()
    }

    /** 探测两个后端。里面有 ContentProvider / binder 调用，所以放 IO 上跑；用户主动刷新时强制重探。 */
    fun refreshPrivileged() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { Privileged.refresh(getApplication(), force = true) }
            SentinelStore.setPrivilegedStatus(status)
        }
    }

    fun requestPermission(kind: BackendKind? = null) {
        val app = getApplication<Application>()
        // Dhizuku 的授权请求会去调它的 ContentProvider，同样避开主线程
        viewModelScope.launch(Dispatchers.IO) { Privileged.requestPermission(app, kind) }
    }

    fun openManager(kind: BackendKind? = null) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) { Privileged.openManager(app, kind) }
    }

    // ---------- 电池优化 ----------

    fun refreshBattery() {
        _batteryExempt.value = BatteryOptimizations.isIgnoring(getApplication())
    }

    /** 弹系统的「允许忽略电池优化」对话框；结果要等回到前台再刷新。 */
    fun requestIgnoreBattery() {
        BatteryOptimizations.requestIgnore(getApplication())
    }

    // ---------- 监控开关 ----------

    fun setMonitoring(on: Boolean) {
        val context = getApplication<Application>()
        if (on) {
            MonitorRunner.start(context)
        } else {
            MonitorRunner.stop(context)
        }
        SentinelStore.setMonitoring(on)
    }

    /** 手动催一次：服务在跑就补一轮扫描，没跑就顺手把监控打开（它的第一轮就是立即扫描）。 */
    fun checkNow() {
        setMonitoring(true)
    }

    fun setDryRun(value: Boolean) = SentinelStore.setDryRun(value)

    fun setAllUsers(value: Boolean) = SentinelStore.setAllUsers(value)

    fun setIntervalMs(value: Long) = SentinelStore.setIntervalMs(value)

    // ---------- 规则 ----------

    /** 返回 null 表示加成功，否则是要显示给用户的错误/提示文案。[label] 是应用名，可空。 */
    fun addRule(raw: String, label: String = ""): String? {
        val problem = PackageNameValidator.describeProblem(raw)
        if (problem != null) return problem
        val added = SentinelStore.addRule(raw, label)
        return if (added) null else "「${raw.trim()}」已在名单中"
    }

    /** 编辑已有规则（包名 + 应用名）。返回 null 表示改好了，否则是要显示的提示文案。 */
    fun updateRule(oldPattern: String, newPattern: String, label: String): String? {
        val problem = PackageNameValidator.describeProblem(newPattern)
        if (problem != null) return problem
        val ok = SentinelStore.updateRule(oldPattern, newPattern, label)
        return if (ok) null else "「${newPattern.trim()}」已在名单中"
    }

    fun removeRule(pattern: String) = SentinelStore.removeRule(pattern)

    fun setRuleEnabled(pattern: String, enabled: Boolean) = SentinelStore.setRuleEnabled(pattern, enabled)

    fun clearLog() = SentinelStore.clearLog()

    fun loadApps(force: Boolean = false) {
        if (_appsLoading.value) return
        if (!force && _apps.value.isNotEmpty()) return
        viewModelScope.launch {
            _appsLoading.value = true
            _apps.value = withContext(Dispatchers.IO) {
                runCatching { PackageOps(getApplication()).installedApps() }.getOrDefault(emptyList())
            }
            _appsLoading.value = false
        }
    }
}
