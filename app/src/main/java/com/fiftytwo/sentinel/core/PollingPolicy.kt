package com.fiftytwo.sentinel.core

/**
 * 轮询间隔策略（纯逻辑，有单测）。
 *
 * 屏幕关着的时候把兜底轮询拉长：[SCREEN_OFF_FLOOR_MS]。理由是那时候没人看着日志，
 * 而「装上就被摘掉」的实时性主要靠**安装广播**（那条通道不受这里影响，装完立刻扫），
 * 定时轮询只是广播被 ROM 拦掉时的兜底。省下来的是关屏期间几十次唤醒 + 全机包名遍历。
 *
 * 屏幕亮着时保持用户设定的间隔——用户可能正盯着日志看反应。
 */
object PollingPolicy {

    /** 关屏时的最小间隔（比它更短的一律抬到它）。 */
    const val SCREEN_OFF_FLOOR_MS = 30_000L

    fun intervalFor(baseMs: Long, interactive: Boolean, screenOffFloorMs: Long = SCREEN_OFF_FLOOR_MS): Long =
        if (interactive) baseMs else maxOf(baseMs, screenOffFloorMs)
}
