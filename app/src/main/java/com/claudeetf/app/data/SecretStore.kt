package com.claudeetf.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API 金鑰（富果、Finnhub）的加密儲存：
 * 以 Android Keystore 的 AES-256-GCM 金鑰加密後存在獨立的 prefs 檔，
 * 金鑰本身留在硬體安全區、無法匯出；該檔也排除在雲端備份之外（見 backup_rules）。
 * 解密失敗（例如換機還原、Keystore 被清除）時回傳空字串，讓使用者重新輸入，不會當機。
 */
object SecretStore {
    private const val PREFS = "claudeetf_secret"
    private const val ALIAS = "claudeetf_api_keys"
    private const val GCM_TAG = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** 確認這台裝置的 Keystore 可以正常加解密 */
    fun probe(context: Context): Boolean = runCatching {
        put(context, "_probe", "ok"); get(context, "_probe") == "ok"
    }.getOrDefault(false)

    fun get(context: Context, name: String): String? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG, iv))
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun put(context: Context, name: String, value: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (value.isEmpty()) { p.edit().remove(name).apply(); return }
        runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val enc = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
            p.edit().putString(name, Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
        }
    }
}
