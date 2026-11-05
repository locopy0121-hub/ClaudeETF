package com.claudeetf.app.data.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int, message: String) : IOException(message)

/** 最小的 HTTPS GET（呼叫端負責放在 IO 執行緒） */
object Http {
    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): String {
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
