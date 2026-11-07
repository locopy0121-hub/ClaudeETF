package com.claudeetf.app.data

import com.claudeetf.app.data.remote.TpexApi
import com.claudeetf.app.data.remote.TwseApi
import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 搜尋用股票目錄。
 * 台股：證交所（上市）＋櫃買中心（上櫃）每日收盤資料的代號與中文名稱，存檔 3 天內重複使用；
 * 美股：Yahoo 代號搜尋。
 */
object StockDirectory {
    data class Item(val symbol: String, val name: String, val market: Market, val tag: String)

    @Volatile var cacheDir: File? = null
    private val lock = Mutex()
    @Volatile private var tw: Map<String, String> = emptyMap()

    private suspend fun twList(): Map<String, String> = lock.withLock {
        if (tw.isNotEmpty()) return@withLock tw
        val f = cacheDir?.let { File(it, "tw_directory.json") }
        if (f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < 3 * 86_400_000L) {
            runCatching { JSONObject(f.readText()).let { o -> o.keys().asSequence().associateWith { o.optString(it) } } }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let { tw = it; return@withLock it }
        }
        val m = withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, String>()
            runCatching { TwseApi.dayAll() }.getOrNull()?.forEach { (k, v) -> out[k] = v.name ?: k }
            runCatching { TpexApi.dayAll() }.getOrNull()?.forEach { (k, v) -> if (k !in out) out[k] = v.name ?: k }
            out
        }
        if (m.isNotEmpty()) {
            tw = m
            runCatching { f?.writeText(JSONObject(m as Map<*, *>).toString()) }
        }
        m
    }

    /** 依輸入找相關個股：台股代號開頭或名稱包含；有英文字母時另查美股 */
    suspend fun search(query: String, limit: Int = 12): List<Item> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val up = q.uppercase()
        val list = twList()
        val twHits = list.entries.asSequence()
            .filter { (code, name) -> code.startsWith(up) || name.contains(q, ignoreCase = true) }
            .sortedWith(compareBy({ !it.key.startsWith(up) }, { it.key.length }, { it.key }))
            .take(limit)
            .map { (code, name) -> Item(code, name, Market.TW, if (code.startsWith("00")) "ETF" else "台股") }
            .toList()
        val usHits = if (q.any { it in 'A'..'Z' || it in 'a'..'z' }) withContext(Dispatchers.IO) {
            runCatching { YahooExtra.symbolSearch(q) }.getOrDefault(emptyList())
                .map { Item(it.symbol, it.name, Market.US, if (it.type == "ETF") "美股 ETF" else "美股") }
        } else emptyList()
        // 英文輸入以美股為主，數字或中文以台股為主
        val merged = if (q.first().isLetter() && q.all { it.code < 128 }) usHits + twHits else twHits + usHits
        return merged.distinctBy { it.symbol }.take(limit)
    }
}
