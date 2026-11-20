package com.gotify.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外观模式判定的单元测试。
 *
 * 三条用户可选值必须有确定的行为：跟随系统时完全交给系统（不覆盖配置），
 * 强制浅色/深色时无论系统怎么设都按用户选择走；存储里出现脏值要能兜住。
 */
class ThemeModeTest {

    @Test
    fun `跟随系统时以系统深浅色为准`() {
        assertTrue(ThemeMode.isDark(ThemeMode.SYSTEM, systemNight = true))
        assertFalse(ThemeMode.isDark(ThemeMode.SYSTEM, systemNight = false))
    }

    @Test
    fun `强制深色时忽略系统浅色`() {
        assertTrue(ThemeMode.isDark(ThemeMode.DARK, systemNight = false))
        assertTrue(ThemeMode.isDark(ThemeMode.DARK, systemNight = true))
    }

    @Test
    fun `强制浅色时忽略系统深色`() {
        assertFalse(ThemeMode.isDark(ThemeMode.LIGHT, systemNight = true))
        assertFalse(ThemeMode.isDark(ThemeMode.LIGHT, systemNight = false))
    }

    @Test
    fun `脏数据回落到跟随系统`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.sanitize(-1))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.sanitize(99))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.sanitize(Int.MIN_VALUE))
        assertFalse(ThemeMode.isDark(99, systemNight = false))
        assertTrue(ThemeMode.isDark(99, systemNight = true))
    }

    @Test
    fun `已知取值原样保留`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.sanitize(ThemeMode.SYSTEM))
        assertEquals(ThemeMode.LIGHT, ThemeMode.sanitize(ThemeMode.LIGHT))
        assertEquals(ThemeMode.DARK, ThemeMode.sanitize(ThemeMode.DARK))
    }

    @Test
    fun `选择器顺序为 跟随系统 浅色 深色`() {
        val choices = ThemeMode.choices
        assertEquals(3, choices.size)
        assertEquals(ThemeMode.SYSTEM, choices[0])
        assertEquals(ThemeMode.LIGHT, choices[1])
        assertEquals(ThemeMode.DARK, choices[2])
    }

    @Test
    fun `每一项都有中文标签`() {
        assertEquals("跟随系统", ThemeMode.label(ThemeMode.SYSTEM))
        assertEquals("浅色", ThemeMode.label(ThemeMode.LIGHT))
        assertEquals("深色", ThemeMode.label(ThemeMode.DARK))
        assertEquals("跟随系统", ThemeMode.label(12345))
    }
}
