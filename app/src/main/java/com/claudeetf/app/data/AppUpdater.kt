package com.claudeetf.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 線上更新（OTA）：從 GitHub Releases 取得最新開發版 APK，下載後交給系統安裝程式覆蓋更新。
 * - repo 公開，不需要權杖（GitHub API 未登入每小時 60 次，App 每 6 小時最多自動檢查一次）。
 * - 每次建置都用 repo 內同一把 keystore 簽章，所以可以直接覆蓋安裝、資料保留。
 * - 版本號 0.2.N：以「最新發布時間」那一版為準，再比較 N，避免舊版號規則留下的 v0.2.69 被誤判成較新。
 */
object AppUpdater {
    private const val API = "https://api.github.com/repos/locopy0121-hub/ClaudeETF/releases?per_page=15"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class Release(val tag: String, val patch: Int, val name: String, val notes: String, val apkUrl: String, val size: Long, val publishedAt: String)

    /** 檢查結果：null = 尚未檢查 */
    var latest by mutableStateOf<Release?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    /** 下載進度 0..1；null = 未下載 */
    var progress by mutableStateOf<Float?>(null)
        private set
    /** 是否要在首頁跳出更新提示（使用者按「稍後」後本次不再跳） */
    var prompt by mutableStateOf(false)

    fun currentPatch(context: Context): Int = patchOf(currentVersion(context))

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    private fun patchOf(v: String): Int = Regex("""0\.2\.(\d+)""").find(v)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    fun hasUpdate(context: Context): Boolean = latest?.let { it.patch > currentPatch(context) } ?: false

    /** auto = true：啟動時自動檢查（6 小時內檢查過就略過） */
    suspend fun check(context: Context, auto: Boolean = false) {
        if (checking) return
        val prefs = context.getSharedPreferences("claudeetf_update", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (auto && now - prefs.getLong("lastCheck", 0L) < 6 * 3600_000L) {
            // 用上次的結果
            restore(prefs)
            prompt = hasUpdate(context) && prefs.getString("skipped", "") != latest?.tag
            return
        }
        checking = true
        message = null
        try {
            val body = withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder().url(API)
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "ClaudeETF-Android").build(),
                ).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("GitHub 回應 ${r.code}" + if (r.code == 403) "（檢查太頻繁，請稍後再試）" else "")
                    r.body?.string() ?: ""
                }
            }
            val arr = JSONArray(body)
            val list = ArrayList<Release>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optBoolean("draft")) continue
                val tag = o.optString("tag_name")
                val patch = patchOf(tag).takeIf { it >= 0 } ?: continue
                val assets = o.optJSONArray("assets") ?: continue
                var url = ""; var size = 0L
                for (k in 0 until assets.length()) {
                    val a = assets.optJSONObject(k) ?: continue
                    if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); size = a.optLong("size"); break }
                }
                if (url.isBlank()) continue
                list += Release(tag, patch, o.optString("name"), o.optString("body"), url, size, o.optString("published_at"))
            }
            // 以發布時間最新的一版為準
            val newest = list.maxByOrNull { it.publishedAt }
            latest = newest
            prefs.edit().putLong("lastCheck", now).apply()
            newest?.let { save(prefs, it) }
            message = when {
                newest == null -> "找不到可下載的版本"
                newest.patch > currentPatch(context) -> "有新版本 v0.2.${newest.patch}"
                else -> "已是最新版本"
            }
            prompt = auto && hasUpdate(context) && prefs.getString("skipped", "") != newest?.tag
        } catch (e: Exception) {
            message = "檢查失敗：" + (e.message ?: e.javaClass.simpleName)
        } finally {
            checking = false
        }
    }

    private fun save(p: android.content.SharedPreferences, r: Release) {
        p.edit().putString("tag", r.tag).putInt("patch", r.patch).putString("name", r.name).putString("notes", r.notes)
            .putString("url", r.apkUrl).putLong("size", r.size).putString("pub", r.publishedAt).apply()
    }

    private fun restore(p: android.content.SharedPreferences) {
        if (latest != null) return
        val tag = p.getString("tag", null) ?: return
        latest = Release(tag, p.getInt("patch", -1), p.getString("name", "") ?: "", p.getString("notes", "") ?: "",
            p.getString("url", "") ?: "", p.getLong("size", 0), p.getString("pub", "") ?: "")
    }

    /** 「稍後」：這一版不再自動跳出 */
    fun skip(context: Context) {
        prompt = false
        latest?.let { context.getSharedPreferences("claudeetf_update", Context.MODE_PRIVATE).edit().putString("skipped", it.tag).apply() }
    }

    /** Android 8+ 需要「允許安裝未知應用程式」 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 下載 APK（顯示進度），完成後開啟系統安裝畫面 */
    suspend fun downloadAndInstall(context: Context) {
        val r = latest ?: return
        if (progress != null) return
        progress = 0f
        message = null
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "ClaudeETF-${r.tag}.apk")
                client.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", "ClaudeETF-Android").build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("下載失敗 HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("下載內容為空")
                    val total = body.contentLength().takeIf { it > 0 } ?: r.size
                    body.byteStream().use { input ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastUi = 0L
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                o.write(buf, 0, n); done += n
                                val t = System.currentTimeMillis()
                                if (total > 0 && t - lastUi > 150) {
                                    lastUi = t
                                    val p = (done.toFloat() / total).coerceIn(0f, 1f)
                                    withContext(Dispatchers.Main) { progress = p }
                                }
                            }
                        }
                    }
                }
                out
            }
            progress = 1f
            install(context, file)
        } catch (e: Exception) {
            message = "更新失敗：" + (e.message ?: e.javaClass.simpleName)
        } finally {
            progress = null
        }
    }

    private fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}
