package com.fiftytwo.sentinel.core

/** 一条命中记录为什么会被（或不会被）卸载。 */
enum class PlanReason {
    /** 要卸载 */
    TARGET,

    /** 规则被停用 */
    DISABLED,

    /** 系统关键包 / 特权服务本体，禁止卸载 */
    PROTECTED,

    /** 就是本应用自己 */
    SELF,
}

data class PlanEntry(
    val packageName: String,
    val pattern: String,
    val reason: PlanReason,
)

/**
 * 「谁该被卸载」的判定，纯函数。
 *
 * 拆出来单测的原因很直接：这段逻辑决定用户的手机被删掉什么，
 * 而它又完全不需要 Android 环境——能测就必须测。
 */
object UninstallPlanner {

    /**
     * 硬保护名单：删了它们，手机或本工具本身会不可用。
     * `com.android.shell` 是 adb(Shizuku) 与 Stellar 的服务宿主 uid，`roro.stellar.manager`
     * 是本应用的授权来源——删掉任何一个，这个工具就再也卸不了东西了。
     * 顺带把原版 Shizuku 的管理器也挡上：本应用不用它，但误删会让别的依赖它的工具一起失效。
     */
    val PROTECTED: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.shell",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.providers.settings",
        "com.android.externalstorage",
        "moe.shizuku.privileged.api",
        "roro.stellar.manager",
        // Dhizuku 是设备所有者：卸掉它等于把设备管理权限交出去，而且系统本来也不允许
        "com.rosan.dhizuku",
        // su 管理器：卸掉它 = 本应用的 root 后端立刻失效，用户也很难再装回来
        "me.weishu.kernelsu",
        "com.topjohnwu.magisk",
        "me.bmax.apatch",
    )

    fun isProtected(packageName: String): Boolean = packageName in PROTECTED

    /**
     * 逐条规则按给定顺序处理，同一个包名只会被第一条命中的规则认领。
     * 停用的规则也会产出 DISABLED 条目，界面才能告诉用户「它命中了但你没开」。
     */
    fun plan(
        installed: Collection<String>,
        rules: List<WatchRule>,
        selfPackage: String,
    ): List<PlanEntry> {
        val installedSet = LinkedHashSet(installed)
        val claimed = HashSet<String>()
        val out = ArrayList<PlanEntry>()
        for (rule in rules) {
            for (name in installedSet) {
                if (!RuleMatcher.matches(rule.pattern, name)) continue
                if (!claimed.add(name)) continue
                val reason = when {
                    name == selfPackage -> PlanReason.SELF
                    isProtected(name) -> PlanReason.PROTECTED
                    !rule.enabled -> PlanReason.DISABLED
                    else -> PlanReason.TARGET
                }
                out.add(PlanEntry(name, rule.pattern, reason))
            }
        }
        return out.sortedWith(compareBy({ it.packageName }, { it.pattern }))
    }

    /** 真正要执行卸载的包名，去重后按字母序，保证行为可预期。 */
    fun targets(entries: List<PlanEntry>): List<String> =
        entries.filter { it.reason == PlanReason.TARGET }
            .map { it.packageName }
            .distinct()
            .sorted()
}
