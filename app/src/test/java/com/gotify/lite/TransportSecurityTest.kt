package com.gotify.lite

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 明文传输判定的单元测试。 */
class TransportSecurityTest {

    @Test
    fun `http 视为不安全`() {
        assertTrue(TransportSecurity.isInsecure("http://192.168.1.5:8080"))
        assertTrue(TransportSecurity.isInsecure("HTTP://example.com"))
    }

    @Test
    fun `https 视为安全`() {
        assertFalse(TransportSecurity.isInsecure("https://push.example.com"))
        assertFalse(TransportSecurity.isInsecure("HTTPS://push.example.com/"))
    }

    @Test
    fun `非法或空地址不误报`() {
        assertFalse(TransportSecurity.isInsecure(""))
        assertFalse(TransportSecurity.isInsecure("   "))
        assertFalse(TransportSecurity.isInsecure("not a url"))
    }

    @Test
    fun `http 时给出 HTTPS 提示文案`() {
        val warning = TransportSecurity.warning("http://example.com")
        assertNotNull(warning)
        assertTrue(warning!!.contains("HTTPS"))
    }

    @Test
    fun `https 时没有提示`() {
        assertNull(TransportSecurity.warning("https://example.com"))
        assertNull(TransportSecurity.shortMark("https://example.com"))
    }

    @Test
    fun `状态栏短标记`() {
        assertTrue(TransportSecurity.shortMark("http://example.com")!!.contains("HTTP"))
    }
}
