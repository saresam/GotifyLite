package com.gotify.lite

import java.util.Base64

/**
 * 令牌密文的信封格式（纯逻辑，便于在 JVM 单元测试里验证）。
 *
 * 形式：`v1:` + Base64(iv ‖ ciphertext‖tag)
 *
 * - 版本前缀让将来换算法/换密钥能平滑迁移，也让“这是密文还是旧版明文”一眼可辨；
 * - IV 随密文一起存，解密时取出即可（GCM 要求每次加密使用新的随机 IV）。
 */
object SecretBox {

    const val PREFIX = "v1:"

    /** AES/GCM 推荐 12 字节 IV。 */
    const val IV_LEN = 12

    /** GCM 认证标签长度（字节）。 */
    const val TAG_LEN = 16

    fun isEncrypted(stored: String?): Boolean = stored != null && stored.startsWith(PREFIX)

    fun pack(iv: ByteArray, cipherText: ByteArray): String {
        require(iv.isNotEmpty()) { "iv must not be empty" }
        val buf = ByteArray(iv.size + cipherText.size)
        iv.copyInto(buf, 0)
        cipherText.copyInto(buf, iv.size)
        return PREFIX + Base64.getEncoder().encodeToString(buf)
    }

    /** 解析密文信封；格式不对返回 null（调用方按“无法解密”处理，不要抛给界面）。 */
    fun unpack(stored: String?): Pair<ByteArray, ByteArray>? {
        if (!isEncrypted(stored)) return null
        val body = stored!!.substring(PREFIX.length)
        val bytes = try {
            Base64.getDecoder().decode(body)
        } catch (e: IllegalArgumentException) {
            return null
        }
        // 至少要有 IV + 一个完整的 GCM tag
        if (bytes.size <= IV_LEN + TAG_LEN) return null
        val iv = bytes.copyOfRange(0, IV_LEN)
        val cipherText = bytes.copyOfRange(IV_LEN, bytes.size)
        return iv to cipherText
    }
}
