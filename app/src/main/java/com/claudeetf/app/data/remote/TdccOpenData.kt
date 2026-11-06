package com.claudeetf.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 集保結算所「集保戶股權分散表」開放資料（每週更新，含上市櫃股票與 ETF）。
 * 取每檔「合計」那一列：總股數（ETF 為受益權單位數）與股東／受益人人數。
 * 檔案約數 MB，逐行讀取只留合計列，結果存檔一週內重複使用。
 */
object TdccOpenData {
    private const val URL = "https://opendata.tdcc.com.tw/getOD.ashx?id=1-5"
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
    private val lock = Mutex()

    /** 股數（股／單位）、人數、資料日期（yyyyMMdd） */
    data class Holders(val shares: Double, val people: Int, val date: String)

    @Volatile private var memo: Map<String, Holders>? = null
    @Volatile private var memoAt = 0L
    @Volatile var cacheDir: File? = null

    suspend fun of(code: String): Holders? = all()[code.trim().uppercase()]

    suspend fun all(): Map<String, Holders> = lock.withLock {
        memo?.let { if (System.currentTimeMillis() - memoAt < 24 * 3600_000L) return@withLock it }
        val f = cacheDir?.let { File(it, "tdcc_holders.json") }
        // 一週內的存檔直接用
        if (f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) {
            runCatching { parseCache(f.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { memo = it; memoAt = System.currentTimeMillis(); return@withLock it }
        }
        val fresh = runCatching { download() }.getOrNull()
        if (!fresh.isNullOrEmpty()) {
            memo = fresh; memoAt = System.currentTimeMillis()
            runCatching { f?.writeText(toCache(fresh)) }
            return@withLock fresh
        }
        // 下載失敗：用舊存檔
        val old = f?.takeIf { it.exists() }?.let { runCatching { parseCache(it.readText()) }.getOrNull() } ?: emptyMap()
        memo = old; memoAt = System.currentTimeMillis()
        old
    }

    private suspend fun download(): Map<String, Holders> = withContext(Dispatchers.IO) {
        val out = HashMap<String, Holders>()
        val sums = HashMap<String, Triple<Double, Int, String>>()   // 沒有合計列時自行加總
        client.newCall(Request.Builder().url(URL).header("User-Agent", "Mozilla/5.0 ClaudeETF").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("TDCC HTTP ${r.code}")
            val reader = r.body?.byteStream()?.bufferedReader(Charsets.UTF_8) ?: throw IllegalStateException("空內容")
            reader.useLines { lines ->
                var header = true
                for (raw in lines) {
                    if (header) { header = false; continue }
                    // 資料日期,證券代號,持股分級,人數,股數,占集保庫存數比例%
                    val c = raw.split(',').map { it.trim().trim('"', '﻿') }
                    if (c.size < 5) continue
                    val date = c[0]; val code = c[1].uppercase(); val level = c[2].toIntOrNull() ?: continue
                    val people = c[3].replace(",", "").toIntOrNull() ?: 0
                    val shares = c[4].replace(",", "").toDoubleOrNull() ?: continue
                    when {
                        level == 17 -> out[code] = Holders(shares, people, date)
                        level in 1..16 -> {
                            val s = sums[code]
                            // 16 是「差異數調整」：計入股數，不計人數
                            sums[code] = Triple((s?.first ?: 0.0) + shares, (s?.second ?: 0) + (if (level <= 15) people else 0), date)
                        }
                    }
                }
            }
        }
        sums.forEach { (k, v) -> if (k !in out) out[k] = Holders(v.first, v.second, v.third) }
        out
    }

    private fun toCache(m: Map<String, Holders>): String {
        val o = JSONObject()
        m.forEach { (k, v) -> o.put(k, "${v.shares}|${v.people}|${v.date}") }
        return o.toString()
    }

    private fun parseCache(s: String): Map<String, Holders> {
        val o = JSONObject(s)
        val out = HashMap<String, Holders>()
        o.keys().forEach { k ->
            val p = o.optString(k).split('|')
            if (p.size == 3) out[k] = Holders(p[0].toDoubleOrNull() ?: 0.0, p[1].toIntOrNull() ?: 0, p[2])
        }
        return out
    }
}
