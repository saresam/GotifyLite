package com.gotify.lite

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 处理消息 extras 里带来的 URL（`client::notification.click.url`、
 * `android::action.onReceive.intentUrl`）。
 *
 * 这些值来自**服务器推送的任何一条消息**，等于外部输入：旧实现直接
 * `startActivity(ACTION_VIEW, Uri.parse(url))`，于是任何能给服务器推消息的人
 * （或任何接入了同一应用的第三方脚本）都能让用户的手机去打开任意 URI——
 * `javascript:`、`file:`、`content:`、`intent:`、各种自定义 scheme 全都放行。
 *
 * 这里只放行 http/https，并且要求能被 OkHttp 正常解析；其余一律当作“没有链接”。
 */
object SafeUrl {

    /** 与 Android 上 Uri/浏览器打交道的常识上限，超过基本是恶意构造。 */
    const val MAX_LENGTH = 2048

    /** 可以安全交给 ACTION_VIEW 的 URL；不合法返回 null。 */
    fun normalized(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        // 控制字符会破坏 Intent 解析（也常被用来绕过简单前缀校验）
        if (text.any { it.code < 0x20 || it.code == 0x7F }) return null
        val url = text.toHttpUrlOrNull() ?: return null
        // toHttpUrlOrNull 只接受 http/https，这里再显式确认一次，防止未来行为变化
        if (url.scheme != "http" && url.scheme != "https") return null
        return url.toString()
    }

    fun isOpenable(raw: String?): Boolean = normalized(raw) != null
}
