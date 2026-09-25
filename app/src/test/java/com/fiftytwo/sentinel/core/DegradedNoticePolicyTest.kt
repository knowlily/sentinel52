package com.fiftytwo.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DegradedNoticePolicyTest {

    // ---------- 指纹 ----------

    @Test
    fun `指纹跟顺序和重复无关`() {
        val a = DegradedNoticePolicy.fingerprint(listOf("com.b", "com.a", "com.b"))
        val b = DegradedNoticePolicy.fingerprint(listOf("com.a", "com.b"))
        assertEquals(a, b)
        assertEquals("com.a|com.b", a)
    }

    @Test
    fun `指纹忽略空包名和空白`() {
        assertEquals("com.a", DegradedNoticePolicy.fingerprint(listOf("", "  ", "com.a", " com.a ")))
    }

    @Test
    fun `一个包都没有时指纹是空的`() {
        assertEquals("", DegradedNoticePolicy.fingerprint(emptyList()))
        assertEquals("", DegradedNoticePolicy.fingerprint(listOf("", "   ")))
    }

    // ---------- 该不该提醒 ----------

    @Test
    fun `没有跳过的包就不提醒`() {
        // 局面恢复了：不提醒，也不该误伤（指纹空 = 无事发生）
        assertFalse(
            DegradedNoticePolicy.shouldAlert(
                previousFingerprint = "com.a",
                fingerprint = "",
                sinceLastAlertMs = 999_999L,
            ),
        )
    }

    @Test
    fun `第一次出现跳过就提醒`() {
        assertTrue(
            DegradedNoticePolicy.shouldAlert(
                previousFingerprint = null,
                fingerprint = "com.a",
                sinceLastAlertMs = 0L,
            ),
        )
    }

    @Test
    fun `跳过的包集合变了立刻提醒`() {
        // 有新包命中却卸不掉，用户该立刻知道，不受冷却限制
        assertTrue(
            DegradedNoticePolicy.shouldAlert(
                previousFingerprint = "com.a",
                fingerprint = "com.a|com.b",
                sinceLastAlertMs = 1_000L,
            ),
        )
    }

    @Test
    fun `同一批包在冷却期内不重复提醒`() {
        // 轮询默认 3 秒一轮，这是最关键的节流：不节流就是刷屏
        assertFalse(
            DegradedNoticePolicy.shouldAlert(
                previousFingerprint = "com.a",
                fingerprint = "com.a",
                sinceLastAlertMs = 3_000L,
            ),
        )
        assertFalse(
            DegradedNoticePolicy.shouldAlert(
                previousFingerprint = "com.a",
                fingerprint = "com.a",
                sinceLastAlertMs = DegradedNoticePolicy.COOLDOWN_MS - 1,
            ),
        )
    }

    @Test
    fun `同一批包过了冷却期再提醒一次`() {
        assertTrue(
            DegradedNoticePolicy.shouldAlert(
                previousFingerprint = "com.a",
                fingerprint = "com.a",
                sinceLastAlertMs = DegradedNoticePolicy.COOLDOWN_MS,
            ),
        )
    }

    @Test
    fun `连续几轮扫描只提醒一次再等冷却`() {
        // 模拟：3 秒一轮，连续 5 轮同一批包 —— 只有第一轮该提醒
        var fingerprint: String? = null
        var lastAt = 0L
        var alerts = 0
        for (round in 0 until 5) {
            val now = round * 3_000L
            val hit = DegradedNoticePolicy.shouldAlert(fingerprint, "com.a", now - lastAt)
            if (hit) {
                alerts++
                fingerprint = "com.a"
                lastAt = now
            }
        }
        assertEquals(1, alerts)
    }
}
