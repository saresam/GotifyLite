package com.gotify.lite

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 令牌密文信封的单元测试（Keystore 本身在 JVM 上不可用，这里只测纯逻辑部分）。
 */
class SecretBoxTest {

    private val key = ByteArray(32) { (it * 7).toByte() }
    private val iv = ByteArray(SecretBox.IV_LEN) { (it + 1).toByte() }

    @Test
    fun `pack 与 unpack 可往返`() {
        val packed = SecretBox.pack(iv, key)
        assertTrue(packed.startsWith(SecretBox.PREFIX))
        val (gotIv, gotCt) = SecretBox.unpack(packed)!!
        assertArrayEquals(iv, gotIv)
        assertArrayEquals(key, gotCt)
    }

    @Test
    fun `密文标记能识别旧版明文`() {
        assertTrue(SecretBox.isEncrypted(SecretBox.pack(iv, key)))
        assertFalse(SecretBox.isEncrypted("AbCdEf123456"))
        assertFalse(SecretBox.isEncrypted(""))
        assertFalse(SecretBox.isEncrypted(null))
    }

    @Test
    fun `明文令牌不会被当成密文解析`() {
        assertNull(SecretBox.unpack("AbCdEf123456"))
        assertNull(SecretBox.unpack(null))
        assertNull(SecretBox.unpack(""))
    }

    @Test
    fun `非 base64 内容返回 null 而不是抛异常`() {
        assertNull(SecretBox.unpack(SecretBox.PREFIX + "!!!not-base64!!!"))
    }

    @Test
    fun `长度不足（缺 tag）返回 null`() {
        // IV + 1 字节 < IV + tag
        val short = java.util.Base64.getEncoder().encodeToString(ByteArray(SecretBox.IV_LEN + 1))
        assertNull(SecretBox.unpack(SecretBox.PREFIX + short))
    }

    @Test
    fun `恰好 IV 加 tag 长度也视为不合法`() {
        val exact = java.util.Base64.getEncoder()
            .encodeToString(ByteArray(SecretBox.IV_LEN + SecretBox.TAG_LEN))
        assertNull(SecretBox.unpack(SecretBox.PREFIX + exact))
    }

    @Test
    fun `空 IV 会被拒绝`() {
        try {
            SecretBox.pack(ByteArray(0), key)
            fail("应该抛出异常")
        } catch (e: IllegalArgumentException) {
            assertEquals("iv must not be empty", e.message)
        }
    }

    @Test
    fun `信封前缀保持稳定（改了就要写迁移）`() {
        assertEquals("v1:", SecretBox.PREFIX)
        assertEquals(12, SecretBox.IV_LEN)
    }
}
