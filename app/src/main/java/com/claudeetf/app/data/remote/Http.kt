package com.claudeetf.app.data.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int, message: String) : IOException(message)

/** 最小的 HTTPS GET（呼叫端負責放在 IO 執行緒），附離線快取 */
object Http {
    /** 快取目錄（App 啟動時設定）；成功的回應會存檔，斷線時改讀最近一次的內容 */
    @Volatile var cacheDir: java.io.File? = null

    /** 去掉 crumb、token、防快取時間戳（_=）等會變動或敏感的參數後當作快取鍵 */
    private fun key(url: String): String {
        val clean = url.replace(Regex("([?&])(crumb|token|_)=[^&]*"), "$1").replace("?&", "?").replace("&&", "&").trimEnd('&', '?')
        val md = java.security.MessageDigest.getInstance("MD5").digest(clean.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    /**
     * 即時報價端點不做離線快取：斷線時回傳舊的「即時」價會被當成最新成交，
     * 而且每 5 秒一次的輪詢會讓快取檔無限增加。
     */
    private fun cacheable(url: String) = !url.contains("mis.twse.com.tw") && !url.contains("/intraday/quote/") && !url.contains("finnhub.io/api/v1/quote")

    /** 快取上限：檔數或總大小超過時刪掉最舊的 */
    private const val MAX_FILES = 400
    private const val MAX_BYTES = 40L * 1024 * 1024
    private val writes = java.util.concurrent.atomic.AtomicInteger()

    private fun prune(dir: java.io.File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if (i >= MAX_FILES || total > MAX_BYTES) f.delete()
        }
    }

    fun store(url: String, body: String) {
        val dir = cacheDir ?: return
        if (body.isEmpty() || body.length > 3_000_000 || !cacheable(url)) return
        runCatching {
            dir.mkdirs()
            // 先寫暫存檔再改名，避免寫到一半被讀到殘缺內容
            val f = java.io.File(dir, key(url))
            val tmp = java.io.File(dir, f.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            if (writes.incrementAndGet() % 50 == 1) prune(dir)
        }
    }

    fun cached(url: String): String? = if (!cacheable(url)) null else runCatching {
        val f = java.io.File(cacheDir ?: return null, key(url))
        if (f.exists()) f.readText() else null
    }.getOrNull()

    /** 網路錯誤（非伺服器回應錯誤）時才改用快取 */
    private fun isNetwork(e: Throwable) = e is IOException && e !is HttpException

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): String {
        return try {
            fetch(url, headers, timeoutMs).also { store(url, it) }
        } catch (e: Exception) {
            if (isNetwork(e)) cached(url) ?: throw e else throw e
        }
    }

    private fun fetch(url: String, headers: Map<String, String>, timeoutMs: Int): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("User-Agent", "ClaudeETF-Android")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw HttpException(code, "HTTP $code " + body.take(120))
            }
            return body
        } finally {
            conn.disconnect()
        }
    }
}

/** 不因非 2xx 拋錯，並回傳 Set-Cookie（Yahoo 取得 crumb 用） */
data class RawResponse(val code: Int, val body: String, val cookies: List<String>)

fun Http.raw(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): RawResponse {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "GET"
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = runCatching { stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "" }.getOrDefault("")
        val cookies = conn.headerFields.entries
            .filter { it.key != null && it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .map { it.substringBefore(";") }
        if (code in 200..299 && (body.startsWith("{") || body.startsWith("["))) Http.store(url, body)
        return RawResponse(code, body, cookies)
    } finally {
        conn.disconnect()
    }
}

/** 轉成給使用者看的錯誤訊息 */
fun Throwable.friendly(): String = when (this) {
    is HttpException -> when (code) {
        401, 403 -> "金鑰無效或沒有權限（$code）"
        404 -> "查無此代號（404）"
        429 -> "請求太頻繁，稍後再試（429）"
        else -> "伺服器錯誤（$code）"
    }
    is java.net.UnknownHostException -> "沒有網路連線"
    is java.net.SocketTimeoutException -> "連線逾時"
    else -> message ?: javaClass.simpleName
}
