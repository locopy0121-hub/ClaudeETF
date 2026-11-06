package com.claudeetf.app.chat

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 群組訊息加密：AES-256-GCM，金鑰 = PBKDF2-HMAC-SHA256(群組密碼, salt = 群聊 ID, 60,000 次)。
 * 只有知道群組密碼的成員能解開內容；中繼轉送的成員若密碼不同只看得到密文。
 * （WebRTC 連線本身另有 DTLS 加密，這層是端對端的內容加密。）
 */
object ChatCrypto {
    private var cacheKey: Pair<String, SecretKeySpec>? = null
    private val rnd = SecureRandom()

    private fun key(): SecretKeySpec {
        val id = ChatPrefs.groupId + "\u0000" + ChatPrefs.groupPassword
        cacheKey?.let { (k, v) -> if (k == id) return v }
        val spec = PBEKeySpec(ChatPrefs.groupPassword.ifEmpty { ChatPrefs.groupId }.toCharArray(),
            ("ClaudeETF-chat:" + ChatPrefs.groupId).toByteArray(), 60_000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES").also { cacheKey = id to it }
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }

    fun encText(s: String): String = b64(encrypt(s.toByteArray(Charsets.UTF_8)))
    fun decText(s: String): String? = runCatching { String(decrypt(unb64(s)), Charsets.UTF_8) }.getOrNull()

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP or Base64.URL_SAFE)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP or Base64.URL_SAFE)

    /** 預先在背景算好金鑰（PBKDF2 約 0.1–0.3 秒） */
    fun warmUp() { runCatching { key() } }
}
