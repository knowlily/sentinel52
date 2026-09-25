package com.fiftytwo.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallPlannerTest {

    private val self = "com.fiftytwo.sentinel"

    @Test
    fun `三个后端的命脉包都在保护名单里`() {
        // 这几个包被卸掉 = 对应的特权后端（甚至手机本身）立刻废掉，必须挡住
        listOf(
            "roro.stellar.manager",
            "com.rosan.dhizuku",
            "me.weishu.kernelsu",
            "com.topjohnwu.magisk",
            "me.bmax.apatch",
            "com.android.shell",
        ).forEach { pkg ->
            assertTrue("$pkg 应该在保护名单里", UninstallPlanner.isProtected(pkg))
        }
    }

    @Test
    fun `命中的包被判为待卸载`() {
        val entries = UninstallPlanner.plan(
            installed = listOf("com.evil.app", "com.good.app"),
            rules = listOf(WatchRule("com.evil.app")),
            selfPackage = self,
        )
        assertEquals(listOf(PlanEntry("com.evil.app", "com.evil.app", PlanReason.TARGET)), entries)
        assertEquals(listOf("com.evil.app"), UninstallPlanner.targets(entries))
    }

    @Test
    fun `没装的不出现在计划里`() {
        val entries = UninstallPlanner.plan(
            installed = listOf("com.good.app"),
            rules = listOf(WatchRule("com.evil.app")),
            selfPackage = self,
        )
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `保护名单里的包只记录不卸载`() {
        val entries = UninstallPlanner.plan(
            installed = listOf("com.android.settings", "com.android.shell"),
            rules = listOf(WatchRule("com.android.settings"), WatchRule("com.android.shell")),
            selfPackage = self,
        )
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.reason == PlanReason.PROTECTED })
        assertTrue(UninstallPlanner.targets(entries).isEmpty())
        assertTrue(UninstallPlanner.isProtected("moe.shizuku.privileged.api"))
        // Stellar 管理器同样必须挡住：它和 Shizuku 都是本应用的授权来源
        assertTrue(UninstallPlanner.isProtected("roro.stellar.manager"))
    }

    @Test
    fun `规则写到本应用自己时跳过`() {
        val entries = UninstallPlanner.plan(
            installed = listOf(self),
            rules = listOf(WatchRule(self)),
            selfPackage = self,
        )
        assertEquals(listOf(PlanEntry(self, self, PlanReason.SELF)), entries)
        assertTrue(UninstallPlanner.targets(entries).isEmpty())
    }

    @Test
    fun `停用的规则不产生卸载目标`() {
        val entries = UninstallPlanner.plan(
            installed = listOf("com.evil.app"),
            rules = listOf(WatchRule("com.evil.app", enabled = false)),
            selfPackage = self,
        )
        assertEquals(listOf(PlanEntry("com.evil.app", "com.evil.app", PlanReason.DISABLED)), entries)
        assertTrue(UninstallPlanner.targets(entries).isEmpty())
    }

    @Test
    fun `同一包名只被第一条命中的规则认领`() {
        val entries = UninstallPlanner.plan(
            installed = listOf("com.x.app", "com.x.other"),
            rules = listOf(
                WatchRule("com.x.app", enabled = false),
                WatchRule("com.x.*", enabled = true),
            ),
            selfPackage = self,
        )
        val byName = entries.associateBy { it.packageName }
        // 前一条规则停用了，就把这个包认领走了：不会因为后面的通配规则又变成 TARGET
        assertEquals(PlanReason.DISABLED, byName.getValue("com.x.app").reason)
        assertEquals(PlanReason.TARGET, byName.getValue("com.x.other").reason)
        assertEquals(listOf("com.x.other"), UninstallPlanner.targets(entries))
    }

    @Test
    fun `通配规则只吃子包`() {
        val entries = UninstallPlanner.plan(
            installed = listOf("com.bad", "com.bad.one", "com.bad.two", "com.badly"),
            rules = listOf(WatchRule("com.bad.*")),
            selfPackage = self,
        )
        assertEquals(listOf("com.bad.one", "com.bad.two"), UninstallPlanner.targets(entries))
        assertFalse(entries.any { it.packageName == "com.bad" || it.packageName == "com.badly" })
    }

    @Test
    fun `目标去重并按字母序输出`() {
        val entries = UninstallPlanner.plan(
            installed = listOf("com.z.app", "com.a.app", "com.m.app"),
            rules = listOf(WatchRule("com.z.app", enabled = false), WatchRule("com.*")),
            selfPackage = self,
        )
        // 通配规则会命中全部三个；com.z.app 已被前一条（停用的）规则认领 → 不再是 TARGET；
        // 输出按字母序，且同一个包只会出现一次
        assertEquals(listOf("com.a.app", "com.m.app"), UninstallPlanner.targets(entries))
        assertEquals(PlanReason.DISABLED, entries.first { it.packageName == "com.z.app" }.reason)
        assertEquals(entries.size, entries.map { it.packageName }.distinct().size)
    }

    @Test
    fun `计划按包名排序保证输出稳定`() {
        val entries = UninstallPlanner.plan(
            installed = listOf("com.c.app", "com.a.app", "com.b.app"),
            rules = listOf(WatchRule("com.a.app"), WatchRule("com.b.app"), WatchRule("com.c.app")),
            selfPackage = self,
        )
        assertEquals(listOf("com.a.app", "com.b.app", "com.c.app"), entries.map { it.packageName })
    }

    @Test
    fun `没有规则时计划为空`() {
        assertTrue(
            UninstallPlanner.plan(
                installed = listOf("com.evil.app"),
                rules = emptyList(),
                selfPackage = self,
            ).isEmpty(),
        )
    }
}
