package com.gotify.lite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 优先级分档与配色映射的单元测试。
 *
 * 分档阈值决定了“哪条消息进哪个通知通道、色条取哪个颜色”，
 * 一旦改动必须在两个主题下都还说得通，这里先把阈值钉死。
 */
class PaletteTest {

    @Test
    fun `分档阈值与原版一致`() {
        assertEquals(Palette.Tier.MIN, Palette.tier(-5))
        assertEquals(Palette.Tier.MIN, Palette.tier(0))
        assertEquals(Palette.Tier.LOW, Palette.tier(1))
        assertEquals(Palette.Tier.LOW, Palette.tier(3))
        assertEquals(Palette.Tier.NORMAL, Palette.tier(4))
        assertEquals(Palette.Tier.NORMAL, Palette.tier(7))
        assertEquals(Palette.Tier.HIGH, Palette.tier(8))
        assertEquals(Palette.Tier.HIGH, Palette.tier(10))
    }

    @Test
    fun `同一档位取同一颜色资源`() {
        assertEquals(Palette.barRes(1), Palette.barRes(3))
        assertEquals(Palette.chipTextRes(4), Palette.chipTextRes(7))
        assertEquals(Palette.chipBgRes(8), Palette.chipBgRes(100))
        assertEquals(Palette.chipTextRes(0), Palette.chipTextRes(-3))
    }

    @Test
    fun `不同档位取不同颜色资源`() {
        val res = listOf(-1, 2, 5, 9).map { Palette.barRes(it) }
        assertEquals(4, res.toSet().size)

        val chipText = listOf(-1, 2, 5, 9).map { Palette.chipTextRes(it) }
        assertEquals(4, chipText.toSet().size)

        val chipBg = listOf(-1, 2, 5, 9).map { Palette.chipBgRes(it) }
        assertEquals(4, chipBg.toSet().size)
    }

    @Test
    fun `色条与胶囊文字用的是不同资源`() {
        assertNotEquals(Palette.barRes(5), Palette.chipTextRes(5))
        assertNotEquals(Palette.chipTextRes(5), Palette.chipBgRes(5))
    }

    @Test
    fun `通知通道映射与分档一致`() {
        assertEquals(Notifier.CH_MIN, Notifier.channelForPriority(0))
        assertEquals(Notifier.CH_LOW, Notifier.channelForPriority(2))
        assertEquals(Notifier.CH_DEFAULT, Notifier.channelForPriority(5))
        assertEquals(Notifier.CH_HIGH, Notifier.channelForPriority(9))
    }
}
