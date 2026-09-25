package com.fiftytwo.sentinel.privileged

/**
 * 特权侧跑一条命令的结果。**必须分开拿退出码和输出**：`pm uninstall` 失败时照样有输出
 * （`Failure [not installed for 0]`），只看「有没有输出」会把失败当成功。
 */
data class ShellResult(val exit: Int, val output: String) {
    val ok: Boolean get() = exit == 0

    override fun toString(): String = "exit=$exit ${output}".trim()
}
