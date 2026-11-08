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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 線上更新（OTA）：從 GitHub Releases 取得最新開發版 APK，下載後交給系統安裝程式覆蓋更新。
 * - repo 公開，不需要權杖（GitHub API 未登入每小時 60 次；回到 App 時距上次超過 15 分鐘才自動檢查）。
 * - 每次建置都用 repo 內同一把 keystore 簽章，所以可以直接覆蓋安裝、資料保留。
 * - 以「最新發布時間」那一版為準，再比較版本（主.次.修訂），避免舊版號規則留下的 v0.2.69 被誤判成較新。
 * - 版本自 v1.0.1 起；0.2.x 開發版也能更新到 1.0.x。
 */
object AppUpdater {
    private const val REPO = "locopy0121-hub/ClaudeETF"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=10"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    data class Release(val tag: String, val patch: Int, val name: String, val notes: String, val apkUrl: String, val size: Long, val publishedAt: String, val sha256: String = "")

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
    /** 上次檢查時間（毫秒） */
    var lastCheckAt by mutableStateOf(0L)
        private set
    /** 是否要在首頁跳出更新提示（使用者按「稍後」後本次不再跳） */
    var prompt by mutableStateOf(false)
    /** 最近的版本（新到舊），供「版本紀錄」顯示 */
    var releases by mutableStateOf<List<Release>>(emptyList())
        private set
    /** 剛完成更新：顯示「已更新至 vX」視窗（值為新版本號） */
    var justUpdated by mutableStateOf<String?>(null)
    /** 已預先下載並驗證完成的版本（tag）；null = 尚未下載 */
    var ready by mutableStateOf<String?>(null)
        private set
    /** 背景預先下載中 */
    var prefetching by mutableStateOf(false)
        private set
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

    /** 這次開啟 App 時使用者在系統確認畫面按了取消的版本：不再自動跳安裝，改顯示 App 視窗 */
    @Volatile private var abortedTag: String? = null

    /** 下載完成後直接進入系統安裝確認（預設開） */
    fun autoInstall(context: Context): Boolean = context.getSharedPreferences("claudeetf_update", Context.MODE_PRIVATE).getBoolean("autoInstall", true)
    fun setAutoInstall(context: Context, v: Boolean) = context.getSharedPreferences("claudeetf_update", Context.MODE_PRIVATE).edit().putBoolean("autoInstall", v).apply()

    /** 預先下載：0 僅 Wi-Fi（預設）、1 任何網路、2 關閉（按下更新才下載） */
    fun prefetchMode(context: Context): Int = context.getSharedPreferences("claudeetf_update", Context.MODE_PRIVATE).getInt("prefetch", 0)
    fun setPrefetchMode(context: Context, v: Int) {
        context.getSharedPreferences("claudeetf_update", Context.MODE_PRIVATE).edit().putInt("prefetch", v).apply()
        if (v != 2) appScope.launch { prefetch(context) }
    }

    private fun apkFile(context: Context, r: Release) = File(File(context.cacheDir, "updates"), "ClaudeETF-${r.tag}.apk")

    /** 最新版的安裝檔已下載驗證完成 */
    fun readyFile(context: Context): File? = latest?.takeIf { hasUpdate(context) }?.let { r -> apkFile(context, r).takeIf { it.exists() && it.length() > 0 } }

    private fun refreshReady(context: Context) { ready = readyFile(context)?.let { latest?.tag } }

    private fun unmetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)

    /**
     * 偵測到新版時預先在背景下載並驗證（依設定限 Wi-Fi），完成後等使用者點「立即安裝」。
     * 回傳 true = 安裝檔已就緒。
     */
    suspend fun prefetch(context: Context): Boolean {
        val r = latest ?: return false
        if (!hasUpdate(context)) return false
        refreshReady(context)
        if (ready == r.tag) return true
        val mode = prefetchMode(context)
        if (mode == 2 || (mode == 0 && !unmetered(context))) return false
        if (progress != null || prefetching) return false
        prefetching = true
        return try {
            download(context, r, showProgress = false)
            refreshReady(context)
            ready == r.tag
        } catch (e: Exception) {
            false
        } finally {
            prefetching = false
        }
    }

    /** App 啟動時呼叫：版本與上次執行不同 → 代表剛更新完成 */
    fun detectUpdated(context: Context) {
        val p = context.getSharedPreferences("claudeetf_update", Context.MODE_PRIVATE)
        val cur = currentVersion(context)
        val last = p.getString("lastRunVersion", "") ?: ""
        // 線上更新下載的 APK 還在 → 剛透過線上更新安裝完成（1.0.3 之前沒有記錄 lastRunVersion 也能判斷）
        val dir = File(context.cacheDir, "updates")
        val viaOta = dir.listFiles()?.any { it.name.contains(cur.removeSuffix("-dev")) } == true
        if ((last.isNotEmpty() && last != cur && patchOf(cur) > patchOf(last)) || (last.isEmpty() && viaOta)) justUpdated = cur
        // 安裝完成後清掉下載的 APK，節省空間
        if (last != cur) dir.listFiles()?.forEach { it.delete() }
        p.edit().putString("lastRunVersion", cur).apply()
    }

    /** 某版本的更新說明（版本紀錄已載入時） */
    fun notesFor(version: String): String? = releases.firstOrNull { it.tag.removePrefix("v").removeSuffix("-dev") == version.removeSuffix("-dev") }?.notes

    fun currentPatch(context: Context): Int = patchOf(currentVersion(context))

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /** 「主.次.修訂」換成可比較的數字：1.0.3 → 1_000_003；0.2.30 → 2_030 */
    private fun patchOf(v: String): Int {
        val m = Regex("""(\d+)\.(\d+)\.(\d+)""").find(v) ?: return -1
        val (a, b, c) = m.destructured
        return a.toInt() * 1_000_000 + b.toInt() * 1_000 + c.toInt()
    }

    fun hasUpdate(context: Context): Boolean = latest?.let { it.patch > currentPatch(context) } ?: false

    /**
     * auto = true：回到 App 時自動檢查（距上次 minGapMs 內就沿用上次結果）。
     * popup = false：只更新資料不跳視窗（背景通知、關於頁刷新用）。
     */
    suspend fun check(context: Context, auto: Boolean = false, minGapMs: Long = 15 * 60_000L, popup: Boolean = auto) {
        if (checking) return
        val prefs = context.getSharedPreferences("claudeetf_update", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (lastCheckAt == 0L) lastCheckAt = prefs.getLong("lastCheck", 0L)
        if (auto && now - prefs.getLong("lastCheck", 0L) < minGapMs) {
            // 用上次的結果
            restore(prefs)
            refreshReady(context)
            if (popup) afterCheckPopup(context, prefs)
            return
        }
        checking = true
        message = null
        try {
            // 1) 版本清單 API（失敗重試一次）→ 2) 最新版 API → 3) GitHub 網頁轉址（不走 API、不受頻率限制）
            val list = withContext(Dispatchers.IO) { fetchReleases() }
            // 以發布時間最新的一版為準
            val newest = list.maxByOrNull { it.publishedAt }
            latest = newest
            // 只拿到最新一版時保留先前的版本紀錄
            releases = if (list.size > 1 || releases.isEmpty()) list.sortedByDescending { it.publishedAt }
                else (list + releases).distinctBy { it.tag }.sortedByDescending { it.publishedAt }
            saveHistory(prefs)
            prefs.edit().putLong("lastCheck", now).apply()
            lastCheckAt = now
            newest?.let { save(prefs, it) }
            message = when {
                newest == null -> "找不到可下載的版本"
                newest.patch > currentPatch(context) -> "有新版本 " + newest.tag
                else -> "已是最新版本"
            }
            refreshReady(context)
            if (popup) afterCheckPopup(context, prefs)
            else if (hasUpdate(context)) appScope.launch { prefetch(context) }
        } catch (e: Exception) {
            message = "檢查失敗：" + (e.message ?: e.javaClass.simpleName) + "，稍後會自動重試"
            restore(prefs)
        } finally {
            checking = false
        }
    }

    private fun get(url: String): okhttp3.Response = client.newCall(
        Request.Builder().url(url).header("Accept", "application/vnd.github+json").header("User-Agent", "ClaudeETF-Android").build(),
    ).execute()

    private fun parse(o: org.json.JSONObject): Release? {
        if (o.optBoolean("draft")) return null
        val tag = o.optString("tag_name")
        val patch = patchOf(tag).takeIf { it >= 0 } ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (k in 0 until assets.length()) {
            val a = assets.optJSONObject(k) ?: continue
            if (a.optString("name").endsWith(".apk"))
                return Release(tag, patch, o.optString("name"), o.optString("body"), a.optString("browser_download_url"), a.optLong("size"), o.optString("published_at"),
                    // GitHub 會提供資產的 SHA-256（"digest": "sha256:…"），用來驗證下載完整性
                    a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 } ?: "")
        }
        return null
    }

    /** 依序嘗試三種來源；GitHub 偶爾回 502／504 時不會直接失敗 */
    private fun fetchReleases(): List<Release> {
        var lastErr: String? = null
        // 1) 版本清單（含更新說明），重試一次
        repeat(2) { attempt ->
            runCatching {
                get(API).use { r ->
                    if (r.isSuccessful) {
                        val arr = JSONArray(r.body?.string() ?: "[]")
                        val l = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::parse) }
                        if (l.isNotEmpty()) return l
                    } else { lastErr = "GitHub 回應 ${r.code}" + if (r.code == 403) "（檢查太頻繁）" else "" }
                    Unit
                }
            }.onFailure { lastErr = it.message }
            if (attempt == 0) Thread.sleep(1_500)
        }
        // 2) 最新正式版
        runCatching {
            get(API_LATEST).use { r ->
                if (r.isSuccessful) { parse(org.json.JSONObject(r.body?.string() ?: "{}"))?.let { return listOf(it) } }
                else { lastErr = "GitHub 回應 ${r.code}" }
                Unit
            }
        }.onFailure { lastErr = it.message }
        // 3) 網頁轉址：github.com/…/releases/latest → …/releases/tag/v1.0.N，下載網址依命名規則組成
        runCatching {
            val noRedirect = client.newBuilder().followRedirects(false).build()
            noRedirect.newCall(Request.Builder().url("https://github.com/$REPO/releases/latest").header("User-Agent", "ClaudeETF-Android").build())
                .execute().use { r ->
                    val loc = r.header("Location") ?: ""
                    val tag = loc.substringAfterLast("/tag/", "").substringBefore('?')
                    if (tag.isNotBlank() && patchOf(tag) >= 0) {
                        val url = "https://github.com/$REPO/releases/download/$tag/ClaudeETF-$tag.apk"
                        return listOf(Release(tag, patchOf(tag), "ClaudeETF $tag", "（GitHub 暫時無法取得更新說明）", url, 0L,
                            java.time.Instant.now().toString()))
                    }
                }
        }.onFailure { lastErr = it.message }
        throw IllegalStateException(lastErr ?: "無法連線 GitHub")
    }

    /** 版本紀錄存檔：檢查失敗時仍能顯示 */
    private fun saveHistory(p: android.content.SharedPreferences) {
        val arr = JSONArray()
        releases.take(15).forEach { r ->
            arr.put(org.json.JSONObject().put("tag", r.tag).put("patch", r.patch).put("name", r.name).put("notes", r.notes)
                .put("url", r.apkUrl).put("size", r.size).put("pub", r.publishedAt))
        }
        p.edit().putString("history", arr.toString()).apply()
    }

    private fun loadHistory(p: android.content.SharedPreferences) {
        if (releases.isNotEmpty()) return
        val arr = runCatching { JSONArray(p.getString("history", "[]")) }.getOrNull() ?: return
        releases = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o -> Release(o.optString("tag"), o.optInt("patch"), o.optString("name"), o.optString("notes"),
                o.optString("url"), o.optLong("size"), o.optString("pub")) }
        }
    }

    private fun save(p: android.content.SharedPreferences, r: Release) {
        p.edit().putString("tag", r.tag).putInt("patch", r.patch).putString("name", r.name).putString("notes", r.notes)
            .putString("url", r.apkUrl).putLong("size", r.size).putString("pub", r.publishedAt).putString("sha", r.sha256).apply()
    }

    private fun restore(p: android.content.SharedPreferences) {
        loadHistory(p)
        if (latest != null) return
        val tag = p.getString("tag", null) ?: return
        latest = Release(tag, p.getInt("patch", -1), p.getString("name", "") ?: "", p.getString("notes", "") ?: "",
            p.getString("url", "") ?: "", p.getLong("size", 0), p.getString("pub", "") ?: "", p.getString("sha", "") ?: "")
    }

    /** 背景通知用：這一版是否已通知過（每版只通知一次） */
    /** 有新版：能預先下載就先下載完再提示「已下載，點擊安裝」；不能（非 Wi-Fi／關閉）就直接提示 */
    private fun afterCheckPopup(context: Context, prefs: android.content.SharedPreferences) {
        if (!hasUpdate(context) || prefs.getString("skipped", "") == latest?.tag) { prompt = false; return }
        appScope.launch {
            prefetch(context)
            if (!hasUpdate(context) || prefs.getString("skipped", "") == latest?.tag) return@launch
            // 已下載且開啟「下載完成直接安裝」：不再先跳 App 自己的視窗，直接進系統確認（只按一次「更新」）
            if (ready == latest?.tag && autoInstall(context) && abortedTag != latest?.tag) downloadAndInstall(context)
            else prompt = true
        }
    }

    fun shouldNotify(context: Context): Boolean {
        val r = latest ?: return false
        if (!hasUpdate(context)) return false
        val p = context.getSharedPreferences("claudeetf_update", Context.MODE_PRIVATE)
        if (p.getString("notified", "") == r.tag) return false
        p.edit().putString("notified", r.tag).apply()
        return true
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

    /** 安裝：已預先下載就直接安裝；否則下載（顯示進度）後安裝 */
    suspend fun downloadAndInstall(context: Context) {
        val r = latest ?: return
        if (progress != null) return
        message = null
        readyFile(context)?.let { f -> runCatching { install(context, f) }.onFailure { message = "更新失敗：" + (it.message ?: it.javaClass.simpleName) }; return }
        // 背景正在預先下載：等它完成再安裝，不重複下載
        if (prefetching) {
            progress = 0f
            while (prefetching) kotlinx.coroutines.delay(300)
            progress = null
            readyFile(context)?.let { f -> install(context, f); return }
        }
        progress = 0f
        try {
            val file = download(context, r, showProgress = true)
            progress = 1f
            refreshReady(context)
            install(context, file)
        } catch (e: Exception) {
            message = "更新失敗：" + (e.message ?: e.javaClass.simpleName)
        } finally {
            progress = null
        }
    }

    /** 下載並驗證（長度與 SHA-256），回傳安裝檔 */
    private suspend fun download(context: Context, r: Release, showProgress: Boolean): File = withContext(Dispatchers.IO) {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "ClaudeETF-${r.tag}.apk")
                // 先下載到 .part，驗證長度與 SHA-256 後才改名，避免把殘缺檔交給安裝程式
                val part = File(dir, out.name + ".part")
                val md = java.security.MessageDigest.getInstance("SHA-256")
                var expected = 0L
                var got = 0L
                client.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", "ClaudeETF-Android").build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("下載失敗 HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("下載內容為空")
                    val total = body.contentLength().takeIf { it > 0 } ?: r.size
                    expected = total
                    body.byteStream().use { input ->
                        part.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastUi = 0L
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                o.write(buf, 0, n); md.update(buf, 0, n); done += n
                                val t = System.currentTimeMillis()
                                if (showProgress && total > 0 && t - lastUi > 150) {
                                    lastUi = t
                                    val p = (done.toFloat() / total).coerceIn(0f, 1f)
                                    withContext(Dispatchers.Main) { progress = p }
                                }
                            }
                            got = done
                        }
                    }
                }
                if (expected > 0 && got != expected) { part.delete(); throw IllegalStateException("下載不完整（$got / $expected bytes），請重試") }
                val hash = md.digest().joinToString("") { "%02x".format(it) }
                if (r.sha256.isNotEmpty() && !hash.equals(r.sha256, ignoreCase = true)) { part.delete(); throw IllegalStateException("檔案校驗失敗（SHA-256 不符），已刪除") }
                if (!part.renameTo(out)) throw IllegalStateException("無法儲存安裝檔")
                out
    }

    /**
     * 安裝：優先用 PackageInstaller 工作階段。
     * Android 12 以上設定「不需使用者確認」：App 自己是安裝來源時（第一次線上更新之後），
     * 系統直接在背景完成更新，不再跳出安裝畫面；第一次仍會跳一次確認。
     * 工作階段失敗時改用傳統的系統安裝畫面。
     */
    private suspend fun install(context: Context, file: File) {
        val ok = runCatching { installSession(context, file) }.isSuccess
        if (!ok) installViaIntent(context, file)
    }

    private suspend fun installSession(context: Context, file: File) = withContext(Dispatchers.IO) {
        val pi = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(file.length())
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        // 以「應用程式商店」身分安裝並取得更新擁有權（Android 14+）：
        // 這次確認後，之後的更新由本 App 擁有，系統可直接安裝不再詢問
        if (Build.VERSION.SDK_INT >= 34) {
            params.setPackageSource(android.content.pm.PackageInstaller.PACKAGE_SOURCE_STORE)
            runCatching { params.setRequestUpdateOwnership(true) }
        }
        val id = pi.createSession(params)
        // 寫入或提交失敗要放棄工作階段，否則系統會殘留未完成的安裝工作階段（佔用空間）
        try { pi.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction("com.claudeetf.app.INSTALL_RESULT")
            val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
            val sender = android.app.PendingIntent.getBroadcast(context, id, intent, flags)
            session.commit(sender.intentSender)
        } } catch (e: Exception) {
            runCatching { pi.abandonSession(id) }
            throw e
        }
        withContext(Dispatchers.Main) { message = "安裝中…完成後 App 會重新啟動" }
    }

    fun installViaIntent(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    /** 安裝結果（UpdateReceiver 回報） */
    fun onInstallStatus(context: Context, status: Int, msg: String?, confirm: Intent?) {
        when (status) {
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 第一次（或系統要求）需要使用者確認
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> message = "更新完成"
            // 使用者在確認畫面按了取消：不要再跳一次安裝畫面
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED -> { message = "已取消更新"; abortedTag = latest?.tag }
            // 簽章不符或空間不足：改用系統安裝畫面也一樣會失敗，直接告知原因
            android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT,
            android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> message = "更新失敗：與已安裝版本簽章不符，請先移除舊版再安裝"
            android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE -> message = "更新失敗：手機儲存空間不足"
            else -> {
                message = "更新失敗：" + (msg ?: "代碼 $status")
                // 其他工作階段失敗：改用系統安裝畫面
                File(context.cacheDir, "updates").listFiles()?.firstOrNull { it.name.endsWith(".apk") }?.let { runCatching { installViaIntent(context, it) } }
            }
        }
    }
}

/** PackageInstaller 工作階段的結果 */
class UpdateReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999)
        val msg = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
        AppUpdater.onInstallStatus(context, status, msg, confirm)
    }
}

/**
 * 更新安裝完成（App 被系統替換後）：Android 不允許背景自動開啟畫面，
 * 改發通知「已更新至 vX，點此開啟」。
 */
class PackageReplacedReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val v = AppUpdater.currentVersion(context)
        runCatching { com.claudeetf.app.alerts.AlertEngine.notifyUpdated(context, v) }
    }
}
