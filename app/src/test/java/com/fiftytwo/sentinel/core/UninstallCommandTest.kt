package com.fiftytwo.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallCommandTest {

    @Test
    fun `默认只卸当前用户`() {
        assertEquals(
            "pm uninstall --user 0 'com.example.app'",
            UninstallCommand.build("com.example.app", allUsers = false),
        )
    }

    @Test
    fun `勾了所有用户就用 --user all`() {
        assertEquals(
            "pm uninstall --user all 'com.example.app'",
            UninstallCommand.build("com.example.app", allUsers = true),
        )
    }

    @Test
    fun `userFlag 与 build 里用的那个一致`() {
        // 两个后端（Root / Stellar）都从这儿取参数，别让谁自己拼一份
        assertTrue(UninstallCommand.build("a.b", false).contains(UninstallCommand.userFlag(false)))
        assertTrue(UninstallCommand.build("a.b", true).contains(UninstallCommand.userFlag(true)))
    }

    @Test
    fun `普通包名原样进单引号`() {
        assertEquals("'com.tencent.mm'", UninstallCommand.quote("com.tencent.mm"))
    }

    @Test
    fun `引号转义是双向的——恶意包名不会多跑一条命令`() {
        // 这条命令是交给特权侧 `sh -c` 解释的，包名里塞分号/引号必须还是「一个参数」
        val nasty = listOf(
            "com.x'; rm -rf /; echo '",
            "a'b",
            "'; su; '",
            "a b c",
            "\$(id)",
            "`id`",
        )
        for (input in nasty) {
            assertEquals(input, unquote(UninstallCommand.quote(input)))
        }
    }

    @Test
    fun `即使包名里带引号，整条命令里也没有裸露的分号逃出引号`() {
        val cmd = UninstallCommand.build("a'; reboot; '", allUsers = false)
        // 参数部分必须是「闭合的单引号串」：以 ' 开、以 ' 收，中间的分号都在引号里
        val arg = cmd.removePrefix("pm uninstall --user 0 ")
        assertTrue(arg.startsWith("'"))
        assertTrue(arg.endsWith("'"))
        assertEquals("a'; reboot; '", unquote(arg))
    }

    @Test
    fun `空包名（校验层会先拦，这里只保证不崩）`() {
        assertEquals("pm uninstall --user 0 ''", UninstallCommand.build("", allUsers = false))
        assertFalse(UninstallCommand.quote("").contains(";"))
    }

    /** 还原 POSIX 单引号串，用来验证 [UninstallCommand.quote] 是双射。 */
    private fun unquote(quoted: String): String {
        assertEquals("以单引号开头", "'", quoted.first().toString())
        assertEquals("以单引号收尾", "'", quoted.last().toString())
        return quoted.substring(1, quoted.length - 1).replace("'\\''", "'")
    }
}
