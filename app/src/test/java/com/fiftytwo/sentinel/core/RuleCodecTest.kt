package com.fiftytwo.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleCodecTest {

    @Test
    fun `编码后解码应还原规则`() {
        val rules = listOf(
            WatchRule("com.evil.one", enabled = true, addedAt = 1_700_000_000_000L),
            WatchRule("com.evil.*", enabled = false, addedAt = 0L),
        )
        assertEquals(rules, RuleCodec.decode(RuleCodec.encode(rules)))
    }

    @Test
    fun `编出来的行是 0 1 + 制表符分隔`() {
        val text = RuleCodec.encode(
            listOf(
                WatchRule("com.a.b", enabled = true, addedAt = 5L),
                WatchRule("com.c.d", enabled = false, addedAt = 6L),
            ),
        )
        assertEquals("1\tcom.a.b\t5\n0\tcom.c.d\t6", text)
    }

    @Test
    fun `首字段不是 0 或 1 的坏行整行跳过`() {
        val decoded = RuleCodec.decode("2\tcom.a.b\t0\n1\tcom.c.d\t0")
        assertEquals(listOf(WatchRule("com.c.d", true, 0L)), decoded)
    }

    @Test
    fun `字段不足的行跳过`() {
        val decoded = RuleCodec.decode("garbage\n1\n1\tcom.a.b")
        assertEquals(listOf(WatchRule("com.a.b", true, 0L)), decoded)
    }

    @Test
    fun `包名为空的行跳过`() {
        val decoded = RuleCodec.decode("1\t\t0\n1\t   \t0\n1\tcom.a.b\t0")
        assertEquals(listOf(WatchRule("com.a.b", true, 0L)), decoded)
    }

    @Test
    fun `重复包名只保留第一条`() {
        val decoded = RuleCodec.decode("0\tcom.a.b\t1\n1\tcom.a.b\t2")
        assertEquals(1, decoded.size)
        assertFalse(decoded[0].enabled)
        assertEquals(1L, decoded[0].addedAt)
    }

    @Test
    fun `时间戳缺失或非法时按 0 处理`() {
        assertEquals(listOf(WatchRule("com.a.b", true, 0L)), RuleCodec.decode("1\tcom.a.b"))
        assertEquals(listOf(WatchRule("com.a.b", true, 0L)), RuleCodec.decode("1\tcom.a.b\tnot-a-number"))
    }

    @Test
    fun `空输入与 null 都得到空列表`() {
        assertTrue(RuleCodec.decode(null).isEmpty())
        assertTrue(RuleCodec.decode("").isEmpty())
        assertTrue(RuleCodec.decode("\n\n  \n").isEmpty())
    }

    @Test
    fun `包名两边的空格会被去掉`() {
        assertEquals(listOf(WatchRule("com.a.b", true, 0L)), RuleCodec.decode("1\t  com.a.b  \t0"))
    }

    // ---------- 应用名（第 4 列） ----------

    @Test
    fun `带应用名的规则能往返`() {
        val rules = listOf(
            WatchRule("com.evil.one", true, 7L, "某个爱拉活的兄弟"),
            WatchRule("com.evil.*", false, 0L, "全家桶"),
        )
        assertEquals(rules, RuleCodec.decode(RuleCodec.encode(rules)))
    }

    @Test
    fun `没应用名的规则还是老格式（不写第 4 列）`() {
        val text = RuleCodec.encode(listOf(WatchRule("com.a.b", true, 5L)))
        assertEquals("1\tcom.a.b\t5", text)
    }

    @Test
    fun `老数据（只有 3 列）解出来应用名为空`() {
        val decoded = RuleCodec.decode("1\tcom.a.b\t5")
        assertEquals(1, decoded.size)
        assertEquals("", decoded[0].label)
    }

    @Test
    fun `应用名里的制表符和换行被洗掉`() {
        val text = RuleCodec.encode(listOf(WatchRule("com.a.b", true, 5L, "微\t信\n正式版")))
        assertEquals("1\tcom.a.b\t5\t微 信 正式版", text)
        assertEquals("微 信 正式版", RuleCodec.decode(text)[0].label)
    }

    @Test
    fun `应用名只写空白等于没写`() {
        assertEquals("1\tcom.a.b\t5", RuleCodec.encode(listOf(WatchRule("com.a.b", true, 5L, "   "))))
        assertEquals("", RuleCodec.decode("1\tcom.a.b\t5\t   ")[0].label)
    }

    @Test
    fun `应用名过长会被截断`() {
        val long = "很长的名字".repeat(30)
        assertEquals(60, RuleCodec.sanitizeLabel(long).length)
    }
}
