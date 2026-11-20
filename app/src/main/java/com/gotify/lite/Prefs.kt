package com.gotify.lite

import android.content.Context
import android.content.SharedPreferences

/**
 * 轻量偏好存储：服务器地址、令牌、开关、已收最大消息 id。
 *
 * 客户端令牌用 [SecretStore]（Android Keystore + AES/GCM）加密后才落盘；
 * 旧版本留下的明文会在第一次读取时就地升级为密文。
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("gotify_lite", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_URL, value.trim()).apply()

    var token: String
        get() = readToken()
        set(value) = writeToken(value.trim())

    /** 读令牌：密文解密；旧版明文顺手升级成密文；解不开时返回空串（当作未配置）。 */
    private fun readToken(): String {
        val stored = sp.getString(KEY_TOKEN, "") ?: ""
        if (stored.isEmpty()) return ""
        if (!SecretBox.isEncrypted(stored)) {
            // 旧版明文：升级失败（Keystore 不可用）就保持原样，功能优先
            SecretStore.encrypt(stored)?.let { sp.edit().putString(KEY_TOKEN, it).apply() }
            return stored
        }
        return SecretStore.decrypt(stored) ?: ""
    }

    /** 写令牌：加密失败时退回明文（宁可少一层保护，也不能让用户配不上服务器）。 */
    private fun writeToken(plain: String) {
        val stored = if (plain.isEmpty()) "" else SecretStore.encrypt(plain) ?: plain
        sp.edit().putString(KEY_TOKEN, stored).apply()
    }

    /** 用户是否主动开启过推送（决定开机自启是否恢复）。 */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 已收到的最大消息 id；-1 表示未知（首次需要从服务器拉取最新 id）。
     *  该值只对记录它的那台服务器有意义：换服务器后 id 体系不同，必须先归零。 */
    var lastMsgId: Long
        get() = if (sp.getString(KEY_LAST_MSG_URL, "") == serverUrl) {
            sp.getLong(KEY_LAST_MSG_ID, -1L)
        } else {
            -1L
        }
        set(value) {
            sp.edit()
                .putLong(KEY_LAST_MSG_ID, value)
                .putString(KEY_LAST_MSG_URL, serverUrl)
                .apply()
        }

    /** 外观模式：[ThemeMode.SYSTEM]（跟随系统）/ LIGHT / DARK。 */
    var themeMode: Int
        get() = ThemeMode.sanitize(sp.getInt(KEY_THEME_MODE, ThemeMode.SYSTEM))
        set(value) = sp.edit().putInt(KEY_THEME_MODE, ThemeMode.sanitize(value)).apply()

    /** 是否已经弹过一次通知权限申请框（避免每次回到前台都骚扰）。 */
    var notifPermissionAsked: Boolean
        get() = sp.getBoolean(KEY_PERM_ASKED, false)
        set(value) = sp.edit().putBoolean(KEY_PERM_ASKED, value).apply()

    /** 首页应用筛选：-1 表示「全部消息」，否则为应用 id。 */
    var filterAppId: Long
        get() = sp.getLong(KEY_FILTER_APP, -1L)
        set(value) = sp.edit().putLong(KEY_FILTER_APP, value).apply()

    val isConfigured: Boolean
        get() = serverUrl.isNotBlank() && token.isNotBlank()

    /** 配置指纹：服务器地址 + 令牌。变化说明换了服务器/令牌，界面需要重新加载。 */
    val configKey: String
        get() = serverUrl + "\u0000" + token

    private companion object {
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_ENABLED = "enabled"
        const val KEY_LAST_MSG_ID = "last_msg_id"
        const val KEY_LAST_MSG_URL = "last_msg_url"
        const val KEY_FILTER_APP = "filter_app_id"
        const val KEY_PERM_ASKED = "notif_perm_asked"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
