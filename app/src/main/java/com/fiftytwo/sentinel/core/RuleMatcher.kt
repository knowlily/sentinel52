package com.fiftytwo.sentinel.core

/** 规则 pattern 与设备上真实包名的匹配规则。 */
object RuleMatcher {

    private const val WILDCARD = ".*"

    /**
     * 精确包名必须完全相等；`com.foo.*` 形式只匹配**子**包（`com.foo.bar`、`com.foo.bar.baz`），
     * 不匹配 `com.foo` 本身——否则「我只想干掉它的子包」就没法表达。
     */
    fun matches(pattern: String, packageName: String): Boolean {
        val p = pattern.trim()
        val name = packageName.trim()
        if (p.isEmpty() || name.isEmpty()) return false
        if (p.endsWith(WILDCARD)) {
            val prefix = p.dropLast(WILDCARD.length)
            if (prefix.isEmpty()) return false
            return name.startsWith("$prefix.")
        }
        return p == name
    }

    fun isWildcard(pattern: String): Boolean = pattern.trim().endsWith(WILDCARD)

    /** 列出所有命中该 pattern 的已安装包（顺序按 [installed] 给定顺序）。 */
    fun matching(pattern: String, installed: Collection<String>): List<String> =
        installed.filter { matches(pattern, it) }
}
