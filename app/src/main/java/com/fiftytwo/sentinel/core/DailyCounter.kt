package com.fiftytwo.sentinel.core

/**
 * 「今日」计数器的纯逻辑。
 *
 * 读的时候：存的日期戳就是今天 → 沿用原值；跨天 → 当 0。
 * 写的时候：没跨天 → 原值 +1；跨天 → 从 1 开始。
 *
 * 之所以单独拎出来：跨天归零是只有一条边界、出错又不会被立刻发现的地方，
 * 放在这里能用单测钉住，而不是靠人工跨天看。
 */
object DailyCounter {

    fun valueOnRead(storedDay: String?, today: String, stored: Int): Int =
        if (storedDay == today) stored else 0

    fun valueOnBump(storedDay: String?, today: String, stored: Int): Int =
        if (storedDay == today) stored + 1 else 1
}
