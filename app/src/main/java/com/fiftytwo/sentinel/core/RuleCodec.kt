package com.fiftytwo.sentinel.core

/**
 * 规则的持久化编解码。
 *
 * 用「一行一条、字段用制表符分隔」的纯文本，是为了两件事：
 * 1. 不引入序列化库（也就不需要编译器插件）；
 * 2. 编解码是纯 Kotlin，能直接在 JVM 单测里钉住「坏行怎么处理」——出问题时
 *    用户手里的规则不会整份丢掉，只会丢那一条坏行。
 *
 * 行格式：`<0|1>\t<pattern>\t<addedAt>[\t<label>]`。
 * addedAt 可缺省（当 0）；label 也可以是空的——**空的就不写这一列**，
 * 这样没改名的一堆规则存出来还是老格式，两个方向都兼容。
 */
object RuleCodec {

    private const val SEP = '\t'
    private const val MAX_LABEL = 60

    /** 应用名是用户手输的自由文本，可能夹制表符/换行——会把行格式撑坏，先洗一遍。 */
    fun sanitizeLabel(raw: String): String = raw
        .replace('\t', ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(MAX_LABEL)

    fun encode(rules: List<WatchRule>): String =
        rules.joinToString("\n") { rule ->
            buildString {
                append(if (rule.enabled) "1" else "0")
                append(SEP)
                append(rule.pattern.trim())
                append(SEP)
                append(rule.addedAt)
                val label = sanitizeLabel(rule.label)
                if (label.isNotEmpty()) {
                    append(SEP)
                    append(label)
                }
            }
        }

    /**
     * 解析规则文本。坏行跳过而不是抛异常：用户存过的文件里出现一条垃圾，
     * 不应该导致其余规则全部失效。重复的 pattern 只保留第一条（顺序即优先级）。
     */
    fun decode(raw: String?): List<WatchRule> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<WatchRule>()
        raw.lineSequence().forEach { rawLine ->
            // 只去行尾的 \r；各字段自己再 trim，别让整行 trim 把空字段吃掉
            val line = rawLine.trimEnd('\r', '\n')
            if (line.isBlank()) return@forEach
            val parts = line.split(SEP)
            if (parts.size < 2) return@forEach
            val enabled = when (parts[0].trim()) {
                "1" -> true
                "0" -> false
                else -> return@forEach
            }
            val pattern = parts[1].trim()
            if (pattern.isEmpty()) return@forEach
            val addedAt = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
            // 第 4 列（应用名）可有可无；多余的列一概忽略，不让脏数据污染显示
            val label = sanitizeLabel(parts.getOrNull(3).orEmpty())
            if (seen.add(pattern)) out.add(WatchRule(pattern, enabled, addedAt, label))
        }
        return out
    }
}
