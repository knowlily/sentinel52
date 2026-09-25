package com.fiftytwo.sentinel.core

/**
 * 事件日志的持久化编解码。行格式：`<at>\t<kind>\t<packageName>\t<detail>`。
 * detail 里的制表符/换行会被压成空格——一行一条的格式不能被内容破坏。
 */
object EventCodec {

    /** 内存与磁盘都只保留最近这么多条。 */
    const val MAX_EVENTS = 200

    private const val SEP = '\t'

    fun sanitize(text: String): String =
        text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    fun encode(events: List<SentinelEvent>): String =
        events.joinToString("\n") { e ->
            listOf(
                e.at.toString(),
                e.kind.name,
                sanitize(e.packageName),
                sanitize(e.detail),
            ).joinToString(SEP.toString())
        }

    fun decode(raw: String?): List<SentinelEvent> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<SentinelEvent>()
        raw.lineSequence().forEach { rawLine ->
            // 只去行尾的 \r（Windows 换行）；不能整行 trim——详情为空的行以制表符结尾，
            // trim 会把结尾的空字段一起吃掉，那一行就被当成「字段不足」丢掉
            val line = rawLine.trimEnd('\r', '\n')
            if (line.isBlank()) return@forEach
            val parts = line.split(SEP)
            if (parts.size < 3) return@forEach
            val at = parts[0].trim().toLongOrNull() ?: return@forEach
            val kind = runCatching { EventKind.valueOf(parts[1].trim()) }.getOrNull() ?: return@forEach
            out.add(
                SentinelEvent(
                    at = at,
                    kind = kind,
                    packageName = parts[2].trim(),
                    detail = parts.getOrNull(3)?.trim().orEmpty(),
                ),
            )
        }
        return out.takeLast(MAX_EVENTS)
    }

    /** 追加一条并裁剪到上限，返回新列表（旧的最先丢）。 */
    fun append(existing: List<SentinelEvent>, event: SentinelEvent): List<SentinelEvent> =
        (existing + event).takeLast(MAX_EVENTS)
}
