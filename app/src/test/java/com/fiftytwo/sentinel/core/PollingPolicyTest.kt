package com.fiftytwo.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PollingPolicyTest {

    @Test
    fun `屏幕亮着就用用户设定的间隔`() {
        assertEquals(3_000L, PollingPolicy.intervalFor(3_000L, interactive = true))
        assertEquals(1_000L, PollingPolicy.intervalFor(1_000L, interactive = true))
        assertEquals(30_000L, PollingPolicy.intervalFor(30_000L, interactive = true))
    }

    @Test
    fun `屏幕关着时最短抬到 30 秒`() {
        assertEquals(30_000L, PollingPolicy.intervalFor(3_000L, interactive = false))
        assertEquals(30_000L, PollingPolicy.intervalFor(1_000L, interactive = false))
    }

    @Test
    fun `关屏时不会把用户设的更长间隔改短`() {
        // 用户自己设了 60 秒，关屏后不该被"优化"回 30 秒
        assertEquals(60_000L, PollingPolicy.intervalFor(60_000L, interactive = false))
    }

    @Test
    fun `间隔只会被拉长，不会被缩短`() {
        for (base in listOf(1_000L, 3_000L, 5_000L, 10_000L, 30_000L, 60_000L)) {
            val off = PollingPolicy.intervalFor(base, interactive = false)
            assertTrue("base=$base 关屏后不能变短（得到 $off）", off >= base)
        }
    }

    @Test
    fun `可以传自定义的下限`() {
        assertEquals(120_000L, PollingPolicy.intervalFor(3_000L, interactive = false, screenOffFloorMs = 120_000L))
    }
}
