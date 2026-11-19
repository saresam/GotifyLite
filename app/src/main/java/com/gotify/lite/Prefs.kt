package com.gotify.lite

import android.content.Context
import android.content.SharedPreferences

/** 轻量偏好存储：服务器地址、令牌、开关、已收最大消息 id。 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("gotify_lite", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(KEY_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_URL, value.trim()).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(value) = sp.edit().putString(KEY_TOKEN, value.trim()).apply()

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
    }
}
