package com.fiftytwo.sentinel.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleMatcherTest {

    @Test
    fun `精确包名要求完全相等`() {
        assertTrue(RuleMatcher.matches("com.a.b", "com.a.b"))
        assertFalse(RuleMatcher.matches("com.a.b", "com.a.b.c"))
        assertFalse(RuleMatcher.matches("com.a.b", "com.a.bc"))
        assertFalse(RuleMatcher.matches("com.a", "com.a.b"))
    }

    @Test
    fun `通配只匹配子包不匹配前缀本身`() {
        assertTrue(RuleMatcher.matches("com.a.*", "com.a.b"))
        assertTrue(RuleMatcher.matches("com.a.*", "com.a.b.c"))
        assertFalse(RuleMatcher.matches("com.a.*", "com.a"))
        assertFalse(RuleMatcher.matches("com.a.*", "com.ab.c"))
    }

    @Test
    fun `只有通配符没有前缀时不匹配任何东西`() {
        assertFalse(RuleMatcher.matches(".*", "com.a.b"))
        assertFalse(RuleMatcher.matches(".*", ""))
    }

    @Test
    fun `两侧空格不影响匹配`() {
        assertTrue(RuleMatcher.matches("  com.a.b  ", "com.a.b"))
        assertTrue(RuleMatcher.matches("com.a.* ", " com.a.b "))
    }

    @Test
    fun `空 pattern 或空包名不匹配`() {
        assertFalse(RuleMatcher.matches("", "com.a.b"))
        assertFalse(RuleMatcher.matches("com.a.b", ""))
        assertFalse(RuleMatcher.matches("   ", "com.a.b"))
    }

    @Test
    fun `isWildcard 只看结尾`() {
        assertTrue(RuleMatcher.isWildcard("com.a.*"))
        assertFalse(RuleMatcher.isWildcard("com.a.b"))
        assertFalse(RuleMatcher.isWildcard("com.*.b"))
    }

    @Test
    fun `matching 保留传入顺序`() {
        val installed = listOf("com.z.b", "com.a.b", "com.a.c", "com.a")
        assertEquals(listOf("com.a.b", "com.a.c"), RuleMatcher.matching("com.a.*", installed))
        assertEquals(listOf("com.a"), RuleMatcher.matching("com.a", installed))
    }
}
