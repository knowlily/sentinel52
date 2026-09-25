package com.fiftytwo.sentinel.core

/**
 * 「三个后端都没就绪，这一轮命中也没动手」该不该提醒（纯逻辑，有单测）。
 *
 * 为什么需要策略：轮询默认 3 秒一轮，同一个包会被反复命中，每次都弹通知就是骚扰。
 * 规则是——
 * - 这一轮没有被跳过的包 → 不提醒（局面恢复，指纹清空，下次再出问题能立刻提醒）；
 * - 跳过的包集合和上次不一样 → 提醒（新情况，用户该知道）；
 * - 集合一样 → 只有距上次提醒超过 [COOLDOWN_MS] 才再提醒一次（同一件事别刷屏）。
 */
object DegradedNoticePolicy {

    /** 同一批包的最短重提醒间隔。 */
    const val COOLDOWN_MS = 10 * 60_000L

    /** 受影响的包名归一化成指纹：去空、去重、排序（顺序不同不算不同情况）。 */
    fun fingerprint(packages: List<String>): String =
        packages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString("|")

    fun shouldAlert(
        previousFingerprint: String?,
        fingerprint: String,
        sinceLastAlertMs: Long,
        cooldownMs: Long = COOLDOWN_MS,
    ): Boolean {
        if (fingerprint.isEmpty()) return false
        if (fingerprint != previousFingerprint) return true
        return sinceLastAlertMs >= cooldownMs
    }
}
