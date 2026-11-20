package com.gotify.lite

/**
 * 优先级分档与对应配色（纯逻辑 + 资源 id 映射）。
 *
 * 分档阈值沿用 Gotify 语义：`<=0` 静默 / `1–3` 低 / `4–7` 普通 / `>=8` 紧急。
 * 颜色本身放在资源里（`values/colors.xml` + `values-night/colors.xml`），
 * 这样深色模式下色条与胶囊会自动换成另一套，而不是把浅色调硬画在暗背景上。
 */
object Palette {

    enum class Tier { MIN, LOW, NORMAL, HIGH }

    fun tier(priority: Int): Tier = when {
        priority < 1 -> Tier.MIN
        priority < 4 -> Tier.LOW
        priority < 8 -> Tier.NORMAL
        else -> Tier.HIGH
    }

    /** 列表左侧竖条。 */
    fun barRes(priority: Int): Int = when (tier(priority)) {
        Tier.MIN -> R.color.prio_bar_min
        Tier.LOW -> R.color.prio_bar_low
        Tier.NORMAL -> R.color.prio_bar_normal
        Tier.HIGH -> R.color.prio_bar_high
    }

    /** 应用名胶囊文字色。 */
    fun chipTextRes(priority: Int): Int = when (tier(priority)) {
        Tier.MIN -> R.color.chip_text_min
        Tier.LOW -> R.color.chip_text_low
        Tier.NORMAL -> R.color.chip_text_normal
        Tier.HIGH -> R.color.chip_text_high
    }

    /** 应用名胶囊底色。 */
    fun chipBgRes(priority: Int): Int = when (tier(priority)) {
        Tier.MIN -> R.color.chip_bg_min
        Tier.LOW -> R.color.chip_bg_low
        Tier.NORMAL -> R.color.chip_bg_normal
        Tier.HIGH -> R.color.chip_bg_high
    }
}
