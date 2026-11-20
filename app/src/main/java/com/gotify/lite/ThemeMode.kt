package com.gotify.lite

/**
 * 外观模式（纯逻辑，便于单元测试）。
 *
 * 三种取值与 Android 的 night-mode 概念对应：
 * - [SYSTEM]：跟随系统（不覆盖配置，由 `values-night` 资源自动生效）；
 * - [LIGHT] / [DARK]：强制覆盖，即使系统是另一种模式也按用户选择走。
 */
object ThemeMode {

    /** 跟随系统。 */
    const val SYSTEM = 0

    /** 强制浅色。 */
    const val LIGHT = 1

    /** 强制深色。 */
    const val DARK = 2

    /** 存储里的取值可能是旧数据/脏数据，统一收敛到已知集合。 */
    fun sanitize(raw: Int): Int = when (raw) {
        LIGHT, DARK, SYSTEM -> raw
        else -> SYSTEM
    }

    /**
     * 该模式最终是否以深色渲染。
     * @param systemNight 当前系统是否处于深色（API 29+ 可用 Configuration 判断）
     */
    fun isDark(mode: Int, systemNight: Boolean): Boolean = when (sanitize(mode)) {
        DARK -> true
        LIGHT -> false
        else -> systemNight
    }

    /** 选择对话框里的顺序：跟随系统 → 浅色 → 深色。 */
    val choices: IntArray
        get() = intArrayOf(SYSTEM, LIGHT, DARK)

    fun label(mode: Int): String = when (sanitize(mode)) {
        LIGHT -> "浅色"
        DARK -> "深色"
        else -> "跟随系统"
    }
}
