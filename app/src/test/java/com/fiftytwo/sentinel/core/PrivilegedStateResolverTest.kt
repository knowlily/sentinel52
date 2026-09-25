package com.fiftytwo.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedStateResolverTest {

    @Test
    fun `全绿才是就绪`() {
        assertEquals(
            PrivilegedState.READY,
            PrivilegedStateResolver.resolve(managerInstalled = true, binderAlive = true, permissionGranted = true),
        )
    }

    @Test
    fun `没装优先于其它一切判定`() {
        assertEquals(
            PrivilegedState.NOT_INSTALLED,
            PrivilegedStateResolver.resolve(managerInstalled = false, binderAlive = true, permissionGranted = true),
        )
    }

    @Test
    fun `装了但没激活报未运行而不是未授权`() {
        // 这个顺序很重要：报「未授权」会把用户推去点一个永远不会弹出来的授权框
        assertEquals(
            PrivilegedState.NOT_RUNNING,
            PrivilegedStateResolver.resolve(managerInstalled = true, binderAlive = false, permissionGranted = false),
        )
    }

    @Test
    fun `条件具备但没授权才是待授权`() {
        assertEquals(
            PrivilegedState.NO_PERMISSION,
            PrivilegedStateResolver.resolve(managerInstalled = true, binderAlive = true, permissionGranted = false),
        )
    }

    @Test
    fun `只有待授权状态允许弹授权框`() {
        assertTrue(PrivilegedStateResolver.canRequestPermission(PrivilegedState.NO_PERMISSION))
        assertFalse(PrivilegedStateResolver.canRequestPermission(PrivilegedState.NOT_RUNNING))
        assertFalse(PrivilegedStateResolver.canRequestPermission(PrivilegedState.NOT_INSTALLED))
        assertFalse(PrivilegedStateResolver.canRequestPermission(PrivilegedState.READY))
    }

    @Test
    fun `只有就绪状态允许执行卸载`() {
        assertTrue(PrivilegedStateResolver.canOperate(PrivilegedState.READY))
        assertFalse(PrivilegedStateResolver.canOperate(PrivilegedState.NO_PERMISSION))
        assertFalse(PrivilegedStateResolver.canOperate(PrivilegedState.NOT_RUNNING))
        assertFalse(PrivilegedStateResolver.canOperate(PrivilegedState.NOT_INSTALLED))
    }

    @Test
    fun `uid 身份文案分别对应 Root 与 ADB`() {
        assertEquals("Root", PrivilegedStateResolver.describeUid(0))
        assertEquals("ADB / Shell", PrivilegedStateResolver.describeUid(2000))
        assertEquals("System", PrivilegedStateResolver.describeUid(1000))
        assertEquals("未知", PrivilegedStateResolver.describeUid(-1))
        assertEquals("UID 10123", PrivilegedStateResolver.describeUid(10123))
    }

    @Test
    fun `未知状态的默认值是不可用的 NOT_INSTALLED`() {
        // 界面冷启动时先拿这个占位，不能让它看起来像「就绪」
        assertEquals(PrivilegedState.NOT_INSTALLED, PrivilegedStatus.UNKNOWN.state)
        assertEquals(-1, PrivilegedStatus.UNKNOWN.uid)
        assertNull(PrivilegedStatus.UNKNOWN.kind)
        assertEquals("无可用后端", PrivilegedStatus.UNKNOWN.kindLabel)
        assertFalse(PrivilegedStateResolver.canOperate(PrivilegedStatus.UNKNOWN.state))
    }

    @Test
    fun `uidMode 跟着 uid 走`() {
        assertEquals(
            "ADB / Shell",
            PrivilegedStatus(BackendKind.STELLAR, PrivilegedState.READY, uid = 2000).uidMode,
        )
        assertEquals("Root", PrivilegedStatus(BackendKind.STELLAR, PrivilegedState.READY, uid = 0).uidMode)
    }

    @Test
    fun `kindLabel 用后端自己的名字`() {
        assertEquals("Dhizuku", PrivilegedStatus(BackendKind.DHIZUKU, PrivilegedState.READY).kindLabel)
        assertEquals("Stellar", PrivilegedStatus(BackendKind.STELLAR, PrivilegedState.READY).kindLabel)
    }

    // ---------- 后端选择 ----------

    private fun probe(
        kind: BackendKind,
        installed: Boolean,
        alive: Boolean,
        granted: Boolean,
    ) = BackendProbe(kind, managerInstalled = installed, binderAlive = alive, permissionGranted = granted)

    private val ready = probe(BackendKind.STELLAR, true, true, true)
    private val notInstalled = probe(BackendKind.STELLAR, false, false, false)

    @Test
    fun `两个都就绪时优先 Dhizuku`() {
        // 设备所有者这条路不依赖常驻服务，重启也不用重新授权，能用就用它
        val dhizuku = probe(BackendKind.DHIZUKU, true, true, true)
        assertEquals(BackendKind.DHIZUKU, BackendSelector.select(listOf(dhizuku, ready)))
        // 顺序反过来也一样——判定看的是「谁就绪」，不是列表顺序
        assertEquals(BackendKind.DHIZUKU, BackendSelector.select(listOf(ready, dhizuku)))
    }

    @Test
    fun `Dhizuku 没装就退到 Stellar`() {
        val dhizuku = notInstalled.copy(kind = BackendKind.DHIZUKU)
        assertEquals(BackendKind.STELLAR, BackendSelector.select(listOf(dhizuku, ready)))
    }

    @Test
    fun `Dhizuku 装了但没激活时选已就绪的 Stellar`() {
        // 典型场景：Dhizuku 装上了但没设成设备所有者 → 别因为它而卡住
        val dhizuku = probe(BackendKind.DHIZUKU, true, false, false)
        assertEquals(BackendKind.STELLAR, BackendSelector.select(listOf(dhizuku, ready)))
    }

    @Test
    fun `都没就绪时挑那个装了的（好去引导用户）`() {
        val dhizuku = probe(BackendKind.DHIZUKU, false, false, false)
        val stellar = probe(BackendKind.STELLAR, true, false, false)
        assertEquals(BackendKind.STELLAR, BackendSelector.select(listOf(dhizuku, stellar)))
        assertEquals(
            BackendKind.DHIZUKU,
            BackendSelector.select(
                listOf(
                    stellar.copy(managerInstalled = false),
                    probe(BackendKind.DHIZUKU, true, false, false),
                ),
            ),
        )
    }

    @Test
    fun `都没装时没有后端`() {
        val dhizuku = probe(BackendKind.DHIZUKU, false, false, false)
        assertNull(BackendSelector.select(listOf(dhizuku, notInstalled)))
        assertNull(BackendSelector.select(emptyList()))
    }

    @Test
    fun `probeOf 能按后端取回探测结果`() {
        val dhizuku = probe(BackendKind.DHIZUKU, false, false, false)
        val probes = listOf(dhizuku, ready)
        assertEquals(dhizuku, BackendSelector.probeOf(probes, BackendKind.DHIZUKU))
        assertEquals(PrivilegedState.READY, BackendSelector.probeOf(probes, BackendKind.STELLAR)?.state)
        assertNull(BackendSelector.probeOf(probes, null))
    }

    // ---------- 三个后端时的优先级：Root > Dhizuku > Stellar ----------

    private fun p(kind: BackendKind, installed: Boolean, granted: Boolean) =
        probe(kind, installed, installed, granted)

    @Test
    fun `三个都就绪时用 Root`() {
        val probes = listOf(
            p(BackendKind.ROOT, true, true),
            p(BackendKind.DHIZUKU, true, true),
            p(BackendKind.STELLAR, true, true),
        )
        assertEquals(BackendKind.ROOT, BackendSelector.select(probes))
        // 换个顺序也还是 Root——优先级不随传参顺序变
        assertEquals(BackendKind.ROOT, BackendSelector.select(probes.reversed()))
    }

    @Test
    fun `Root 装了但没授权时退到 Dhizuku`() {
        // 典型场景：装了 KernelSU / Magisk，但用户还没在 su 管理器里允许本应用
        val probes = listOf(
            p(BackendKind.ROOT, true, false),
            p(BackendKind.DHIZUKU, true, true),
            p(BackendKind.STELLAR, true, true),
        )
        assertEquals(BackendKind.DHIZUKU, BackendSelector.select(probes))
    }

    @Test
    fun `Root 和 Dhizuku 都没就绪时退到 Stellar`() {
        val probes = listOf(
            p(BackendKind.ROOT, false, false),
            p(BackendKind.DHIZUKU, true, false),
            p(BackendKind.STELLAR, true, true),
        )
        assertEquals(BackendKind.STELLAR, BackendSelector.select(probes))
    }

    @Test
    fun `只有 Root 时用它，哪怕另外两个都装了`() {
        val probes = listOf(
            p(BackendKind.ROOT, true, true),
            p(BackendKind.DHIZUKU, true, false),
            p(BackendKind.STELLAR, false, false),
        )
        assertEquals(BackendKind.ROOT, BackendSelector.select(probes))
    }

    @Test
    fun `都没就绪时按声明顺序挑装了的（Root 在前）`() {
        val probes = listOf(
            p(BackendKind.STELLAR, true, false),
            p(BackendKind.ROOT, true, false),
            p(BackendKind.DHIZUKU, true, false),
        )
        assertEquals(BackendKind.ROOT, BackendSelector.select(probes))
    }

    @Test
    fun `只有 Stellar 装了时不会硬挑 Root`() {
        val probes = listOf(
            p(BackendKind.ROOT, false, false),
            p(BackendKind.DHIZUKU, false, false),
            p(BackendKind.STELLAR, true, false),
        )
        assertEquals(BackendKind.STELLAR, BackendSelector.select(probes))
    }

    @Test
    fun `后端标签分别是 Root Dhizuku Stellar`() {
        assertEquals("Root", BackendKind.ROOT.label)
        assertEquals("Dhizuku", BackendKind.DHIZUKU.label)
        assertEquals("Stellar", BackendKind.STELLAR.label)
    }
}
