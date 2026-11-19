package com.gotify.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseTest {

    @Test
    fun parseBareMessageWithExtras() {
        val json = """{"id":42,"appid":7,"message":"hello **world**","title":"Hi","priority":5,"date":"2024-01-02T03:04:05Z","extras":{"client::notification":{"click":{"url":"https://x.example/a"}},"android::action":{"onReceive":{"intentUrl":"https://y.example/b"}}}}"""
        val m = GotifyMessage.parse(json)
        assertEquals(42L, m.id)
        assertEquals(7L, m.appId)
        assertEquals("Hi", m.title)
        assertEquals("hello **world**", m.body)
        assertEquals(5, m.priority)
        assertEquals(1704164645000L, m.dateMillis)
        assertEquals("https://x.example/a", m.clickUrl)
        assertEquals("https://y.example/b", m.onReceiveIntentUrl)
    }

    @Test
    fun parseMessageWithoutExtrasAndTitle() {
        val json = """{"id":1,"appid":2,"message":"only body","title":"","priority":0,"date":"2024-06-01T00:00:00Z"}"""
        val m = GotifyMessage.parse(json)
        assertEquals(1L, m.id)
        assertEquals(2L, m.appId)
        assertEquals("", m.title)
        assertEquals("only body", m.body)
        assertEquals(0, m.priority)
        assertNull(m.clickUrl)
        assertNull(m.onReceiveIntentUrl)
    }

    @Test
    fun parseChineseAndTimezoneDate() {
        // 2024-07-08T09:10:11+08:00 == 2024-07-08T01:10:11Z
        val json = """{"id":9,"appid":3,"message":"备份完成","title":"Backup","priority":8,"date":"2024-07-08T09:10:11+08:00"}"""
        val m = GotifyMessage.parse(json)
        assertEquals("备份完成", m.body)
        assertEquals("Backup", m.title)
        assertEquals(1720401011000L, m.dateMillis)
    }

    @Test
    fun parseMalformedJsonFallsBackToDefaults() {
        // 服务端理论不会发这种数据，但解析不应崩溃
        val m = GotifyMessage.parse("""{"id":5}""")
        assertEquals(5L, m.id)
        assertEquals(0L, m.appId)
        assertEquals("", m.title)
    }

    @Test
    fun priorityChannelMapping() {
        assertEquals(Notifier.CH_MIN, Notifier.channelForPriority(0))
        assertEquals(Notifier.CH_MIN, Notifier.channelForPriority(-5))
        assertEquals(Notifier.CH_LOW, Notifier.channelForPriority(1))
        assertEquals(Notifier.CH_LOW, Notifier.channelForPriority(3))
        assertEquals(Notifier.CH_DEFAULT, Notifier.channelForPriority(4))
        assertEquals(Notifier.CH_DEFAULT, Notifier.channelForPriority(7))
        assertEquals(Notifier.CH_HIGH, Notifier.channelForPriority(8))
        assertEquals(Notifier.CH_HIGH, Notifier.channelForPriority(10))
    }
}
