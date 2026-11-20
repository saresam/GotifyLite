package com.gotify.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断线补收策略的单元测试。
 *
 * 这里锁住的是两个曾经的真实缺陷：
 * 1. 服务重启后不再补收（基准没有从持久化恢复）——用“基准已知仍会补”的用例覆盖；
 * 2. 拉取失败时把基准写成 0，导致下次把历史消息全部重新通知——用“基准未知只静默确立”
 *    与“空结果不改变基准”的用例覆盖。
 */
class CatchUpPolicyTest {

    private fun msg(id: Long, appId: Long = 1, priority: Int = 5) = GotifyMessage(
        id = id,
        appId = appId,
        title = "t$id",
        body = "b$id",
        priority = priority,
        dateMillis = id * 1000L
    )

    @Test
    fun `基准未知时静默确立，不补任何历史消息`() {
        val r = CatchUpPolicy.plan(-1L, listOf(msg(30), msg(29), msg(28)))
        assertTrue(r.silentInit)
        assertEquals(30L, r.baseId)
        assertTrue(r.missed.isEmpty())
        assertFalse(r.serverReset)
    }

    @Test
    fun `服务器为空时基准确定为 0，不是 -1`() {
        val r = CatchUpPolicy.plan(-1L, emptyList())
        assertTrue(r.silentInit)
        assertEquals(0L, r.baseId)
        assertTrue(r.missed.isEmpty())
    }

    @Test
    fun `基准已知时只补 id 更大的消息，并按 id 升序`() {
        val r = CatchUpPolicy.plan(
            10L,
            listOf(msg(30), msg(12), msg(25), msg(10), msg(9))
        )
        assertFalse(r.silentInit)
        assertEquals(30L, r.baseId)
        assertEquals(listOf(12L, 25L, 30L), r.missed.map { it.id })
    }

    @Test
    fun `没有新消息时不产生通知，基准不变`() {
        val r = CatchUpPolicy.plan(30L, listOf(msg(30), msg(29), msg(28)))
        assertEquals(30L, r.baseId)
        assertTrue(r.missed.isEmpty())
        assertFalse(r.silentInit)
        assertFalse(r.serverReset)
    }

    @Test
    fun `拉取为空时基准保持不变`() {
        val r = CatchUpPolicy.plan(42L, emptyList())
        assertEquals(42L, r.baseId)
        assertTrue(r.missed.isEmpty())
        assertFalse(r.serverReset)
    }

    @Test
    fun `服务器 id 空间倒退时重置基准且不补收`() {
        // 服务端被重建：本地基准 500，服务器最大 id 只有 3
        val r = CatchUpPolicy.plan(500L, listOf(msg(3), msg(2), msg(1)))
        assertTrue(r.serverReset)
        assertEquals(3L, r.baseId)
        assertTrue(r.missed.isEmpty())
    }

    @Test
    fun `重置基准后新消息能被正常收下`() {
        val reset = CatchUpPolicy.plan(500L, listOf(msg(3), msg(2)))
        val next = CatchUpPolicy.plan(reset.baseId, listOf(msg(5), msg(4), msg(3)))
        assertEquals(listOf(4L, 5L), next.missed.map { it.id })
        assertEquals(5L, next.baseId)
    }

    @Test
    fun `基准为 0 视为已知，会补收全部消息`() {
        val r = CatchUpPolicy.plan(0L, listOf(msg(2), msg(1)))
        assertFalse(r.silentInit)
        assertEquals(listOf(1L, 2L), r.missed.map { it.id })
    }

    @Test
    fun `补收条数超过一页上限时仍全部返回`() {
        val fetched = (100L downTo 1L).map { msg(it) }
        val r = CatchUpPolicy.plan(90L, fetched)
        assertEquals((91L..100L).toList(), r.missed.map { it.id })
        assertEquals(100L, r.baseId)
    }
}
