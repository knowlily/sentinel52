package com.fiftytwo.sentinel.core

/**
 * 卸载命令的**拼装**（纯逻辑，有单测）。Root 与 Stellar 两个后端都跑 `pm`，命令形状一模一样，
 * 所以放一处：谁也别自己拼字符串，免得两边的引号规则慢慢漂移。
 *
 * 为什么必须引号：命令是交给特权侧的 `sh -c` 解释的，包名虽然已经被 [PackageNameValidator]
 * 挡过一道，但这里是最后一道防线——带空格/引号的输入不能变成「多跑一条命令」。
 */
object UninstallCommand {

    /** `pm uninstall --user 0 'com.example.app'`（[allUsers] 时为 `--user all`）。 */
    fun build(packageName: String, allUsers: Boolean): String =
        "pm uninstall ${userFlag(allUsers)} ${quote(packageName)}"

    /** `pm uninstall` 的 user 参数。 */
    fun userFlag(allUsers: Boolean): String = if (allUsers) "--user all" else "--user 0"

    /** POSIX 单引号转义：`'` 自己写成 `'\''`，其余原样。 */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
