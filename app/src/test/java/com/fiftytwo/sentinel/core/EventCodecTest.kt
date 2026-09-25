package com.fiftytwo.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCodecTest {

    @Test
    fun `编码后解码应还原事件`() {
        val events = listOf(
            SentinelEvent(1_700_000_000_000L, EventKind.DETECTED, "com.evil.app", "命中规则"),
            SentinelEvent(1_700_000_000_001L, EventKind.UNINSTALLED, "com.evil.app", "deletePackageAsUser｜已卸载"),
        )
        assertEquals(events, EventCodec.decode(EventCodec.encode(events)))
    }

    @Test
    fun `详情里的制表符与换行被压成空格不破坏行结构`() {
        val event = SentinelEvent(1L, EventKind.ERROR, "com.a.b", "第一行\n第二行\t带制表符")
        val text = EventCodec.encode(listOf(event))
        assertEquals(1, text.split("\n").size)

        val decoded = EventCodec.decode(text)
        assertEquals(1, decoded.size)
        assertEquals("第一行 第二行 带制表符", decoded[0].detail)
    }

    @Test
    fun `坏行与未知类型整行跳过`() {
        val raw = buildString {
            append("garbage\n")
            append("not-a-number\tDETECTED\tcom.a.b\tx\n")
            append("1\tNOT_A_KIND\tcom.a.b\tx\n")
            append("1\tDETECTED\n")
            append("2\tDETECTED\tcom.a.b\tok\n")
        }
        assertEquals(listOf(SentinelEvent(2L, EventKind.DETECTED, "com.a.b", "ok")), EventCodec.decode(raw))
    }

    @Test
    fun `detail 缺省时为空串`() {
        assertEquals(listOf(SentinelEvent(3L, EventKind.INFO, "", "")), EventCodec.decode("3\tINFO\t\t"))
    }

    @Test
    fun `空输入得到空列表`() {
        assertTrue(EventCodec.decode(null).isEmpty())
        assertTrue(EventCodec.decode("").isEmpty())
    }

    @Test
    fun `append 超过上限时丢最旧的`() {
        var events = emptyList<SentinelEvent>()
        val total = EventCodec.MAX_EVENTS + 25
        for (i in 0 until total) {
            events = EventCodec.append(events, SentinelEvent(i.toLong(), EventKind.INFO, "com.p.$i"))
        }
        assertEquals(EventCodec.MAX_EVENTS, events.size)
        assertEquals(25L, events.first().at)
        assertEquals((total - 1).toLong(), events.last().at)
    }

    @Test
    fun `解码时同样截断到上限`() {
        val many = (0 until EventCodec.MAX_EVENTS + 10).joinToString("\n") { "$it\tINFO\tcom.p.$it\t" }
        val decoded = EventCodec.decode(many)
        assertEquals(EventCodec.MAX_EVENTS, decoded.size)
        assertEquals((EventCodec.MAX_EVENTS + 9).toLong(), decoded.last().at)
    }

    @Test
    fun `sanitize 去两边空白`() {
        assertEquals("abc", EventCodec.sanitize("  abc \n"))
    }
}
