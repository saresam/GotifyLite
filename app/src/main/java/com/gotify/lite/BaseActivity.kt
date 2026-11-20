package com.gotify.lite

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/**
 * 把用户选定的外观模式应用到 Context。
 *
 * 做法是给 Activity 造一个“改了 uiMode 的配置副本”，再 `createConfigurationContext()`：
 * 之后这个 Activity 里的所有资源（`values-night/colors.xml`、`values-night/themes.xml`、
 * 各种 drawable 里的 `@color/xxx`）都会按深色解析——不依赖 AppCompat，也不改系统设置。
 *
 * 之所以不用 `UiModeManager.setApplicationNightMode()`：那是 API 31+ 才有的，
 * 而本应用 minSdk 26，需要一条全版本可用的路子。
 */
object ThemedContext {

    fun wrap(base: Context): Context {
        val mode = ThemeMode.sanitize(Prefs(base).themeMode)
        if (mode == ThemeMode.SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        val nightBits = when (mode) {
            ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> Configuration.UI_MODE_NIGHT_NO
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBits
        return base.createConfigurationContext(config)
    }

    /** 当前是否以深色渲染（用于少数需要自己判断的地方，如自绘颜色）。 */
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

/** 所有界面 Activity 的基类：统一在挂载时套用外观模式。 */
abstract class BaseActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemedContext.wrap(newBase))
    }

    /** 切换外观后重建界面，让新配置生效。 */
    protected fun applyThemeChange() {
        recreate()
    }
}
