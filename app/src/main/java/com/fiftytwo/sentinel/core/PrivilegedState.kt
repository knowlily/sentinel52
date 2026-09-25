package com.fiftytwo.sentinel.core

/**
 * 特权后端。三条路都能静默卸载，区别在「靠什么身份」：
 * - Root：直接 `su -c`，uid 0，权限最高（系统应用也能卸），不需要任何常驻服务；
 * - Dhizuku：它自己是设备所有者，把管理权限借给应用（卸载在它进程里执行）；
 * - Stellar：Shizuku 分支，起一个 shell 身份的进程跑 `pm uninstall`。
 *
 * **枚举顺序 = 优先级**（见 [BackendSelector]）：越靠前的越优先。
 */
enum class BackendKind(val label: String) {
    /** https://github.com/topjohnwu/Magisk —— su 授权后 uid 0，最省事也最强 */
    ROOT("Root"),

    /** https://github.com/iamr0s/Dhizuku —— 设备所有者（Device Owner）委托 */
    DHIZUKU("Dhizuku"),

    /** https://github.com/roro2239/Stellar-API —— Shizuku 分支，shell 身份 */
    STELLAR("Stellar"),
}

/** 某个后端当前的可用状态。 */
enum class PrivilegedState {
    /** 手机里没装对应的管理器 */
    NOT_INSTALLED,

    /** 装了但没激活 / 服务没跑 */
    NOT_RUNNING,

    /** 具备条件了，但本应用还没被授权 */
    NO_PERMISSION,

    /** 就绪，能执行静默卸载 */
    READY,
}

/** 一次探测的结果。判定全在 [PrivilegedStateResolver]，这里是纯数据，方便单测。 */
data class BackendProbe(
    val kind: BackendKind,
    val managerInstalled: Boolean,
    val binderAlive: Boolean,
    val permissionGranted: Boolean,
) {
    val state: PrivilegedState
        get() = PrivilegedStateResolver.resolve(managerInstalled, binderAlive, permissionGranted)
}

/** 最终展示给界面/服务的结果。 */
data class PrivilegedStatus(
    val kind: BackendKind?,
    val state: PrivilegedState,
    val uid: Int = -1,
    /** 两个后端各自的探测结果，界面按它逐个展示（[kind] 只是「当前用哪个」）。 */
    val probes: List<BackendProbe> = emptyList(),
) {
    val kindLabel: String get() = kind?.label ?: "无可用后端"
    val uidMode: String get() = PrivilegedStateResolver.describeUid(uid)

    fun probeOf(kind: BackendKind): BackendProbe? = probes.firstOrNull { it.kind == kind }

    companion object {
        val UNKNOWN = PrivilegedStatus(kind = null, state = PrivilegedState.NOT_INSTALLED)
    }
}

object PrivilegedStateResolver {

    /**
     * 顺序不能换：没装就是没装（哪怕 binder 探测偶发为真）；
     * 装了但没激活时，权限查询会抛异常/返回失败，
     * 报「未授权」会让用户去点一个永远不会弹的授权框。
     */
    fun resolve(managerInstalled: Boolean, binderAlive: Boolean, permissionGranted: Boolean): PrivilegedState =
        when {
            !managerInstalled -> PrivilegedState.NOT_INSTALLED
            !binderAlive -> PrivilegedState.NOT_RUNNING
            !permissionGranted -> PrivilegedState.NO_PERMISSION
            else -> PrivilegedState.READY
        }

    /** 只有「条件具备但没授权」才该去弹授权框。 */
    fun canRequestPermission(state: PrivilegedState): Boolean = state == PrivilegedState.NO_PERMISSION

    /** 只有就绪才允许执行卸载——其余状态一拍下去只会失败。 */
    fun canOperate(state: PrivilegedState): Boolean = state == PrivilegedState.READY

    /** 服务/进程跑在什么身份下（Stellar 用得上；Dhizuku 是它自己的 uid）。 */
    fun describeUid(uid: Int): String = when (uid) {
        0 -> "Root"
        2000 -> "ADB / Shell"
        1000 -> "System"
        -1 -> "未知"
        else -> "UID $uid"
    }
}

/**
 * 挑后端。优先级：**就绪 > 已装**，同级按 [BackendKind] 的声明顺序（Dhizuku 在前）。
 *
 * 为什么 Dhizuku 优先：设备所有者这条路的权限最稳——它不依赖任何常驻服务、重启也不用重新激活，
 * 而 Stellar 的服务重启后要重新授权。两者其实都可用时，选省事的那个。
 */
object BackendSelector {

    /**
     * 优先级：**就绪 > 已装**，同级按 [BackendKind] 的**声明顺序**（Dhizuku 在 Stellar 前面）。
     *
     * 注意别写成「取列表里第一个就绪的」——那样结果会随传参顺序变，等于没有优先级。
     */
    fun select(probes: List<BackendProbe>): BackendKind? {
        if (probes.isEmpty()) return null

        fun pick(predicate: (BackendProbe) -> Boolean): BackendKind? =
            BackendKind.entries.firstOrNull { kind ->
                probes.firstOrNull { it.kind == kind }?.let(predicate) == true
            }

        return pick { it.state == PrivilegedState.READY } ?: pick { it.managerInstalled }
    }

    fun probeOf(probes: List<BackendProbe>, kind: BackendKind?): BackendProbe? =
        probes.firstOrNull { it.kind == kind }
}
