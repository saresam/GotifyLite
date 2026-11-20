package com.gotify.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地消息库命名规则的单元测试。
 *
 * 覆盖第 4 条缺陷：库名曾用 `String.hashCode()`，既对末尾斜杠/大小写敏感（换个写法就像换了台
 * 服务器、历史“消失”），又有 32 位碰撞风险。现在改为归一化 + SHA-256 前缀。
 */
class StoreDbNameTest {

    @Test
    fun `末尾斜杠不影响库名`() {
        assertEquals(
            StoreDbName.forUrl("https://push.example.com"),
            StoreDbName.forUrl("https://push.example.com/")
        )
    }

    @Test
    fun `scheme 与 host 大小写不影响库名`() {
        assertEquals(
            StoreDbName.forUrl("https://push.example.com"),
            StoreDbName.forUrl("HTTPS://Push.Example.COM/")
        )
    }

    @Test
    fun `默认端口与显式端口归一到同一个库`() {
        assertEquals(
            StoreDbName.forUrl("https://push.example.com"),
            StoreDbName.forUrl("https://push.example.com:443/")
        )
        assertEquals(
            StoreDbName.forUrl("http://192.168.1.5"),
            StoreDbName.forUrl("http://192.168.1.5:80")
        )
    }

    @Test
    fun `query 与 fragment 不参与库名`() {
        assertEquals(
            StoreDbName.forUrl("https://push.example.com"),
            StoreDbName.forUrl("https://push.example.com/?a=1#frag")
        )
    }

    @Test
    fun `不同服务器得到不同库`() {
        val a = StoreDbName.forUrl("https://a.example")
        val b = StoreDbName.forUrl("https://b.example")
        val c = StoreDbName.forUrl("http://192.168.1.5:8080")
        assertEquals(3, setOf(a, b, c).size)
    }

    @Test
    fun `路径大小写与子路径是不同的服务器`() {
        assertNotEquals(
            StoreDbName.forUrl("https://example.com/gotify"),
            StoreDbName.forUrl("https://example.com/Gotify")
        )
        assertNotEquals(
            StoreDbName.forUrl("https://example.com/gotify"),
            StoreDbName.forUrl("https://example.com")
        )
    }

    @Test
    fun `未配置地址使用默认库`() {
        assertEquals("messages_default.db", StoreDbName.forUrl(""))
        assertEquals("messages_default.db", StoreDbName.forUrl("   "))
    }

    @Test
    fun `库名格式为 messages_16位十六进制`() {
        val name = StoreDbName.forUrl("https://push.example.com")
        assertTrue(name, Regex("^messages_[0-9a-f]{16}\\.db$").matches(name))
    }

    @Test
    fun `非法地址仍能得到稳定且唯一的库名`() {
        val one = StoreDbName.forUrl("not a url")
        assertEquals(one, StoreDbName.forUrl("not a url "))
        assertNotEquals(one, StoreDbName.forUrl("not a url 2"))
    }

    @Test
    fun `上千台服务器不产生库名冲突`() {
        val names = (1..2000).map { StoreDbName.forUrl("https://push$it.example.com") }.toSet()
        assertEquals(2000, names.size)
    }

    @Test
    fun `旧命名与新命名不同，迁移逻辑才有意义`() {
        val url = "https://push.example.com"
        assertNotEquals(StoreDbName.legacyForUrl(url), StoreDbName.forUrl(url))
    }

    @Test
    fun `旧命名对末尾斜杠敏感，正是本次修复的动机`() {
        assertNotEquals(
            StoreDbName.legacyForUrl("https://push.example.com"),
            StoreDbName.legacyForUrl("https://push.example.com/")
        )
    }
}
