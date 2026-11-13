package com.claudeetf.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 每日自動備份：使用者選一次備份檔位置（可選 Google 雲端硬碟），之後每天開啟 App 時自動覆寫最新備份。
 * 不需登入或額外套件，透過系統的檔案選擇器取得長期寫入權限。
 */
object AutoBackup {
    private const val KEY_URI = "autoBackupUri"
    private const val KEY_AT = "autoBackupAt"
    private const val KEY_ERR = "autoBackupErr"
    private const val DAY = 86_400_000L

    val uri: String? get() = AppState.cacheGet(KEY_URI)?.takeIf { it.isNotBlank() }
    val lastAt: Long get() = AppState.cacheGet(KEY_AT)?.toLongOrNull() ?: 0L
    val lastError: String? get() = AppState.cacheGet(KEY_ERR)?.takeIf { it.isNotBlank() }

    /** 設定備份檔（取得長期寫入權限）並立即備份一次 */
    suspend fun setTarget(ctx: Context, u: Uri): Boolean {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        AppState.cachePut(KEY_URI, u.toString())
        return runNow(ctx)
    }

    fun disable(ctx: Context) {
        uri?.let { s -> runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(s), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
        AppState.cachePut(KEY_URI, "")
        AppState.cachePut(KEY_ERR, "")
    }

    /** 立即備份到設定的檔案 */
    suspend fun runNow(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val s = uri ?: return@withContext false
        val r = runCatching {
            val text = Backup.export(ctx)
            ctx.contentResolver.openOutputStream(Uri.parse(s), "wt")?.use { it.write(text.toByteArray()) } ?: throw IllegalStateException("無法開啟備份檔")
        }
        if (r.isSuccess) {
            AppState.cachePut(KEY_AT, System.currentTimeMillis().toString())
            AppState.cachePut(KEY_ERR, "")
            withContext(Dispatchers.Main) { AppState.markBackup() }
            true
        } else {
            val msg = r.exceptionOrNull()?.message ?: "寫入失敗"
            AppState.cachePut(KEY_ERR, msg)
            com.claudeetf.app.util.ErrorLog.record("自動備份", msg)
            false
        }
    }

    /** 開啟 App 時呼叫：已設定且距上次超過一天才備份 */
    suspend fun runIfDue(ctx: Context) {
        if (uri == null) return
        if (System.currentTimeMillis() - lastAt < DAY) return
        runNow(ctx)
    }
}
