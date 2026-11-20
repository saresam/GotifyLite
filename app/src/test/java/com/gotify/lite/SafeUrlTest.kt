package com.gotify.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * extras 链接白名单的单元测试。
 *
 * 这些值来自服务器转发的任意消息，属于外部输入；旧实现把它们直接交给
 * `ACTION_VIEW`，等于“谁能往服务器推消息，谁就能让手机打开任意 URI”。
 */
class SafeUrlTest {

    @Test
    fun `https 链接放行并归一化`() {
        assertEquals("https://example.com/a", SafeUrl.normalized("https://example.com/a"))
        assertTrue(SafeUrl.isOpenable("https://example.com"))
    }

    @Test
    fun `http 链接放行（局域网自建服务器常见）`() {
        assertEquals("http://192.168.1.5:8080/x", SafeUrl.normalized("http://192.168.1.5:8080/x"))
    }

    @Test
    fun `大小写与空白会被归一化`() {
        assertEquals("https://a.example/p", SafeUrl.normalized("  HTTPS://A.EXAMPLE/p  "))
    }

    @Test
    fun `危险 scheme 一律拒绝`() {
        assertNull(SafeUrl.normalized("javascript:alert(1)"))
        assertNull(SafeUrl.normalized("file:///data/data/com.gotify.lite/shared_prefs/gotify_lite.xml"))
        assertNull(SafeUrl.normalized("content://com.android.contacts/data/1"))
        assertNull(SafeUrl.normalized("intent://scan/#Intent;scheme=zxing;end"))
        assertNull(SafeUrl.normalized("ftp://example.com/x"))
        assertNull(SafeUrl.normalized("chrome://settings"))
    }

    @Test
    fun `空值与垃圾输入不崩溃`() {
        assertNull(SafeUrl.normalized(null))
        assertNull(SafeUrl.normalized(""))
        assertNull(SafeUrl.normalized("   "))
        assertNull(SafeUrl.normalized("not a url"))
    }

    @Test
    fun `含控制字符的串被拒绝`() {
        assertNull(SafeUrl.normalized("https://example.com/\u0000x"))
        assertNull(SafeUrl.normalized("https://example.com/\nSet-Cookie: x"))
    }

    @Test
    fun `超长链接被拒绝`() {
        val long = "https://example.com/" + "a".repeat(SafeUrl.MAX_LENGTH)
        assertNull(SafeUrl.normalized(long))
    }

    @Test
    fun `刚好达到长度上限的链接仍然可用`() {
        val prefix = "https://example.com/"
        val url = prefix + "a".repeat(SafeUrl.MAX_LENGTH - prefix.length)
        assertEquals(SafeUrl.MAX_LENGTH, url.length)
        assertEquals(url, SafeUrl.normalized(url))
    }

    @Test
    fun `isOpenable 与 normalized 结论一致`() {
        assertFalse(SafeUrl.isOpenable("javascript:void(0)"))
        assertTrue(SafeUrl.isOpenable("http://example.com"))
    }
}
