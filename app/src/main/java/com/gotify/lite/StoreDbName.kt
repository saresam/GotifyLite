package com.gotify.lite

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/**
 * 本地消息库的命名规则（纯函数，便于单元测试）。
 *
 * 不用 `String.hashCode()` 的原因：
 * - 32 位哈希在“多服务器”场景有实际碰撞概率，两台服务器的消息会被写进同一个库、互相覆盖；
 * - hashCode 对末尾斜杠和大小写敏感，`https://a.example` 与 `https://a.example/` 会得到两个库，
 *   用户看到的是“换了台服务器历史全没了”。
 *
 * 改为：先归一化地址（scheme/host 小写、去掉默认端口、去掉 query 与 fragment、根路径补斜杠），
 * 再取 SHA-256 的前 8 字节（16 个十六进制字符）作为库名。
 */
object StoreDbName {

    private const val PREFIX = "messages_"
    private const val SUFFIX = ".db"
    private const val HASH_HEX_LEN = 16

    /**
     * 归一化服务器地址，作为“是不是同一台服务器”的判据。
     * 非法地址退化为 trim + 小写（仍保持稳定，不再抛异常）。
     */
    fun normalize(url: String): String {
        val raw = url.trim()
        if (raw.isEmpty()) return ""
        val u = raw.toHttpUrlOrNull() ?: return raw.lowercase()
        return u.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    /** 由服务器地址得到库名，如 `messages_5f3c8a1b9d2e4f60.db`；未配置时用默认库。 */
    fun forUrl(url: String): String {
        val n = normalize(url)
        if (n.isEmpty()) return PREFIX + "default" + SUFFIX
        return PREFIX + sha256Hex(n).take(HASH_HEX_LEN) + SUFFIX
    }

    /** 旧版本命名（`String.hashCode()` 的十六进制），仅用于把老库迁移到新名字。 */
    fun legacyForUrl(url: String): String {
        val t = url.trim()
        if (t.isEmpty()) return PREFIX + "default" + SUFFIX
        return PREFIX + Integer.toHexString(t.hashCode()) + SUFFIX
    }

    private fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
