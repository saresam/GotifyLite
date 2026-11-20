package com.gotify.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知权限判定的单元测试。
 *
 * 覆盖第 1 条缺陷：Android 13+ 的 POST_NOTIFICATIONS 是运行时权限，旧代码从不申请，
 * 系统默认静默拒绝，用户装完应用“一切正常”但一条通知也收不到。
 */
class NotifPermissionTest {

    @Test
    fun `API 33 起才需要运行时权限`() {
        assertFalse(NotifPermission.needsRuntimePermission(26))
        assertFalse(NotifPermission.needsRuntimePermission(32))
        assertTrue(NotifPermission.needsRuntimePermission(33))
        assertTrue(NotifPermission.needsRuntimePermission(35))
    }

    @Test
    fun `首次进入且未授权时应当弹框`() {
        assertTrue(NotifPermission.shouldPrompt(33, granted = false, alreadyAsked = false))
        assertTrue(NotifPermission.shouldPrompt(34, granted = false, alreadyAsked = false))
    }

    @Test
    fun `已授权不再弹框`() {
        assertFalse(NotifPermission.shouldPrompt(33, granted = true, alreadyAsked = false))
        assertFalse(NotifPermission.shouldPrompt(33, granted = true, alreadyAsked = true))
    }

    @Test
    fun `已经问过一次就不再反复弹框`() {
        assertFalse(NotifPermission.shouldPrompt(33, granted = false, alreadyAsked = true))
    }

    @Test
    fun `低版本不需要运行时权限也不该弹框`() {
        assertFalse(NotifPermission.shouldPrompt(30, granted = false, alreadyAsked = false))
        assertFalse(NotifPermission.shouldPrompt(28, granted = true, alreadyAsked = false))
    }

    @Test
    fun `已授权时没有提示文案`() {
        assertNull(NotifPermission.statusHint(33, granted = true))
        assertNull(NotifPermission.statusHint(30, granted = true))
    }

    @Test
    fun `未授权时给出可点击的提示文案`() {
        val modern = NotifPermission.statusHint(33, granted = false)
        assertNotNull(modern)
        assertTrue(modern!!.contains("点此开启"))

        val legacy = NotifPermission.statusHint(30, granted = false)
        assertNotNull(legacy)
        assertTrue(legacy!!.contains("点此开启"))
    }

    @Test
    fun `请求码稳定在应用自定义区间内`() {
        assertEquals(4001, NotifPermission.REQ_CODE)
    }
}
