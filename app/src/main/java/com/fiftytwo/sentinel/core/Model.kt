package com.fiftytwo.sentinel.core

/**
 * 一条监控规则。[pattern] 既可以是完整包名，也可以是 `com.foo.*` 这种子包通配。
 *
 * [label] 是给人看的应用名（选应用时自动带上，手输包名时可选填），只影响显示，不参与匹配。
 */
data class WatchRule(
    val pattern: String,
    val enabled: Boolean = true,
    val addedAt: Long = 0L,
    val label: String = "",
)

/** 事件类型：决定日志那一行的图标和颜色。 */
enum class EventKind {
    /** 扫到命中规则的包 */
    DETECTED,

    /** 卸载成功 */
    UNINSTALLED,

    /** 卸载失败 */
    FAILED,

    /** 命中但因保护名单 / 规则停用 / 只记录模式而没有卸载 */
    SKIPPED,

    /** 普通信息（监控启停、授权变化等） */
    INFO,

    /** 异常 */
    ERROR,
}

/** 一条监控事件，落盘后重启应用还能看到。 */
data class SentinelEvent(
    val at: Long,
    val kind: EventKind,
    val packageName: String,
    val detail: String = "",
)
