package com.gotify.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlTest {

    // 注意：OkHttp 的 HttpUrl 只接受 http/https，ws/wss 由 OkHttp 连接时自动升级

    @Test
    fun httpsUrlForWssUpgrade() {
        val u = GotifyApi.wsUrl("https://push.example.com/", "abc123")
        assertEquals("https://push.example.com/stream?token=abc123", u.toString())
    }

    @Test
    fun httpUrlWithPort() {
        val u = GotifyApi.wsUrl("http://192.168.1.5:8080", "tok_123")
        assertEquals("http://192.168.1.5:8080/stream?token=tok_123", u.toString())
    }

    @Test
    fun httpUrlWithTrailingSlash() {
        val u = GotifyApi.wsUrl("http://10.0.0.2:8080/", "t")
        assertEquals("http://10.0.0.2:8080/stream?token=t", u.toString())
    }

    @Test
    fun invalidUrlReturnsNull() {
        assertNull(GotifyApi.wsUrl("not a url", "t"))
        assertNull(GotifyApi.wsUrl("", "t"))
        assertNull(GotifyApi.wsUrl("ftp://x.example", "t"))
        assertNull(GotifyApi.wsUrl("ws://x.example/stream", "t"))
    }
}
