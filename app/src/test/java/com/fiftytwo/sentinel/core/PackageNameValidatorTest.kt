package com.fiftytwo.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNameValidatorTest {

    @Test
    fun `正常包名通过`() {
        assertTrue(PackageNameValidator.isValidPackage("com.example.app"))
        assertTrue(PackageNameValidator.isValidPackage("com.a.b.c.d"))
        assertTrue(PackageNameValidator.isValidPackage("com.example_1.app2"))
        assertTrue(PackageNameValidator.isValidPackage("com.example.app_x"))
    }

    @Test
    fun `只有一段的包名不合法`() {
        assertFalse(PackageNameValidator.isValidPackage("com"))
        assertFalse(PackageNameValidator.isValidPackage("example"))
    }

    @Test
    fun `空段不合法`() {
        assertFalse(PackageNameValidator.isValidPackage("com..app"))
        assertFalse(PackageNameValidator.isValidPackage(".com.app"))
        assertFalse(PackageNameValidator.isValidPackage("com.app."))
    }

    @Test
    fun `以数字开头的段不合法`() {
        assertFalse(PackageNameValidator.isValidPackage("com.1app.x"))
        assertFalse(PackageNameValidator.isValidPackage("1com.app"))
    }

    @Test
    fun `含非法字符不合法`() {
        assertFalse(PackageNameValidator.isValidPackage("com.exa-mple.app"))
        assertFalse(PackageNameValidator.isValidPackage("com.exa mple.app"))
        assertFalse(PackageNameValidator.isValidPackage("com.示例.app"))
    }

    @Test
    fun `通配 pattern 合法与非法的边界`() {
        assertTrue(PackageNameValidator.isValidPattern("com.tencent.*"))
        assertTrue(PackageNameValidator.isValidPattern("com.tencent.mm"))
        // 光一个通配符没有前缀不合法
        assertFalse(PackageNameValidator.isValidPattern(".*"))
        // 前缀也得是合法包名（至少两段）：`com.*` 会匹配到几乎整机，
        // 这种「一不小心卸掉半个系统」的写法要在加规则这一步就挡掉
        assertFalse(PackageNameValidator.isValidPattern("com.*"))
        assertTrue(PackageNameValidator.isValidPattern("com.tencent.*"))
        // 通配符只能出现在结尾
        assertFalse(PackageNameValidator.isValidPattern("com.*.mm"))
    }

    @Test
    fun `describeProblem 对合法输入返回 null`() {
        assertNull(PackageNameValidator.describeProblem("com.example.app"))
        assertNull(PackageNameValidator.describeProblem("  com.example.*  "))
    }

    @Test
    fun `describeProblem 区分空输入 段数不足 与非法段`() {
        assertEquals("包名不能为空", PackageNameValidator.describeProblem("   "))
        assertNotNull(PackageNameValidator.describeProblem("com"))
        val message = PackageNameValidator.describeProblem("com.1bad.app")
        assertNotNull(message)
        assertTrue(message!!.contains("1bad"))
        assertNotNull(PackageNameValidator.describeProblem(".*"))
    }

    @Test
    fun `normalize 只去两边空格`() {
        assertEquals("com.a.b", PackageNameValidator.normalize("  com.a.b\t"))
    }
}
