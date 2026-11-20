package com.gotify.lite

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 客户端令牌的加密存储：AES-256/GCM，密钥由 **Android Keystore** 生成并保管
 * （不可导出、绑定设备），SharedPreferences 里只落密文。
 *
 * 动机：令牌等价于“用你的身份收全部消息”，原先明文躺在 `/data/data/…/shared_prefs/
 * gotify_lite.xml` 里，任何能读到应用私有目录的途径（root、调试备份、部分 ROM 的
 * 云备份）都能拿到。
 *
 * 兼容与降级：
 * - 加密失败（个别 ROM 的 Keystore 异常）返回 null，调用方继续用明文，功能不受影响；
 * - 读取时若发现是旧版明文，会自动就地升级为密文。
 */
object SecretStore {

    private const val TAG = "SecretStore"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "gotify_lite_token_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** 加密；不可用时返回 null（调用方应回退到明文，不要因此丢配置）。 */
    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        SecretBox.pack(cipher.iv, cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    } catch (e: Exception) {
        Log.w(TAG, "encrypt unavailable: ${e.message}")
        null
    }

    /** 解密；格式不对或密钥丢失时返回 null。 */
    fun decrypt(stored: String): String? {
        val (iv, cipherText) = SecretBox.unpack(stored) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "decrypt failed: ${e.message}")
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
