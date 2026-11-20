package com.gotify.lite

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 传输层安全性判定（纯函数，便于单元测试）。
 *
 * 应用必须允许明文 HTTP（局域网/自建服务器常见），但**不能默默允许**：
 * 一旦走 http，客户端令牌和所有消息都在网上裸奔，界面必须直说。
 */
object TransportSecurity {

    /** 该地址是否以明文 HTTP 传输（https 或非法地址都返回 false）。 */
    fun isInsecure(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false
        return u.scheme == "http"
    }

    /** 需要在界面上显示的安全提示；没有风险时返回 null。 */
    fun warning(url: String): String? =
        if (isInsecure(url)) "⚠ 使用明文 HTTP，令牌与消息不加密传输，公网部署请改用 HTTPS" else null

    /** 安全提示的极简标记（状态栏那种一行文案用）。 */
    fun shortMark(url: String): String? = if (isInsecure(url)) "明文 HTTP" else null
}
