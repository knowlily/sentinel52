package com.fiftytwo.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyCounterTest {

    private val today = "2026-09-23"

    @Test
    fun `同一天读原值`() {
        assertEquals(7, DailyCounter.valueOnRead(today, today, 7))
    }

    @Test
    fun `跨天读为零`() {
        assertEquals(0, DailyCounter.valueOnRead("2026-09-22", today, 7))
    }

    @Test
    fun `从没记过日期戳时读为零`() {
        assertEquals(0, DailyCounter.valueOnRead(null, today, 7))
    }

    @Test
    fun `同一天累加`() {
        assertEquals(8, DailyCounter.valueOnBump(today, today, 7))
    }

    @Test
    fun `跨天从一起算`() {
        assertEquals(1, DailyCounter.valueOnBump("2026-09-22", today, 7))
        assertEquals(1, DailyCounter.valueOnBump(null, today, 0))
    }

    @Test
    fun `归零后第一天再加就是二`() {
        val afterMidnight = DailyCounter.valueOnBump("2026-09-22", today, 7)
        assertEquals(2, DailyCounter.valueOnBump(today, today, afterMidnight))
    }
}
