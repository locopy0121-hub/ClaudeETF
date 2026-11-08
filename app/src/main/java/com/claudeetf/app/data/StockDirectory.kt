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
    /** board：上市「市」、上櫃「櫃」、美股「美」 */
    data class Item(val symbol: String, val name: String, val market: Market, val tag: String, val board: String = "")

    @Volatile var cacheDir: File? = null
    private val lock = Mutex()
    @Volatile private var tw: Map<String, String> = emptyMap()
    /** 上櫃代號 */
    @Volatile private var otc: Set<String> = emptySet()
    /** 美股搜尋過的名稱（建立報價時用來帶入名稱） */
    private val usNames = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 台股名稱 → 代號（ETF 成分只有名稱時用；名稱需完全相同，去除空白） */
    suspend fun twCodeByName(): Map<String, String> =
        twList().entries.groupBy({ it.value.replace(" ", "") }, { it.key }).mapValues { (_, codes) -> codes.minByOrNull { it.length }!! }

    /** 已知的名稱（台股目錄或搜尋過的美股）；不會觸發網路 */
    fun cachedName(symbol: String): String? = tw[symbol.uppercase()] ?: usNames[symbol.uppercase()]

    private suspend fun twList(): Map<String, String> = lock.withLock {
        if (tw.isNotEmpty()) return@withLock tw
        val f = cacheDir?.let { File(it, "tw_directory.json") }
        val fo = cacheDir?.let { File(it, "tw_directory_otc.txt") }
        if (f != null && f.exists() && fo != null && fo.exists() && System.currentTimeMillis() - f.lastModified() < 3 * 86_400_000L) {
            runCatching { JSONObject(f.readText()).let { o -> o.keys().asSequence().associateWith { o.optString(it) } } }.getOrNull()
                ?.takeIf { it.isNotEmpty() }?.let {
                    tw = it
                    otc = runCatching { fo.readText().split(",").filter { c -> c.isNotBlank() }.toSet() }.getOrDefault(emptySet())
                    return@withLock it
                }
        }
        val o2 = HashSet<String>()
        val m = withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, String>()
            runCatching { TwseApi.dayAll() }.getOrNull()?.forEach { (k, v) -> out[k] = v.name ?: k }
            runCatching { TpexApi.dayAll() }.getOrNull()?.forEach { (k, v) -> if (k !in out) { out[k] = v.name ?: k; o2 += k } }
            out
        }
        if (m.isNotEmpty()) {
            tw = m; otc = o2
            runCatching { f?.writeText(JSONObject(m as Map<*, *>).toString()); fo?.writeText(o2.joinToString(",")) }
        }
        m
    }

    /**
     * 依輸入找相關個股（類似券商 App）：
     * 代號開頭符合 → 代號包含 → 名稱開頭 → 名稱包含；同級依代號長短、代號排序。
     * 有英文字母時另查美股。
     */
    suspend fun search(query: String, limit: Int = 30): List<Item> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val up = q.uppercase()
        val list = twList()
        fun rank(code: String, name: String): Int = when {
            code == up -> 0
            code.startsWith(up) -> 1
            code.contains(up) -> 2
            name.startsWith(q, ignoreCase = true) -> 3
            name.contains(q, ignoreCase = true) -> 4
            else -> 9
        }
        val twHits = list.entries.asSequence()
            .map { (code, name) -> Triple(code, name, rank(code, name)) }
            .filter { it.third < 9 }
            .sortedWith(compareBy({ it.third }, { it.first.length }, { it.first }))
            .take(limit)
            .map { (code, name, _) ->
                Item(code, name, Market.TW, if (code.startsWith("00")) "ETF" else "", if (code in otc) "櫃" else "市")
            }
            .toList()
        val usHits = if (q.any { it in 'A'..'Z' || it in 'a'..'z' }) withContext(Dispatchers.IO) {
            runCatching { YahooExtra.symbolSearch(q) }.getOrDefault(emptyList())
                .map { Item(it.symbol, it.name, Market.US, if (it.type == "ETF") "ETF" else "", "美").also { i -> usNames[i.symbol.uppercase()] = i.name } }
        } else emptyList()
        // 英文輸入以美股為主，數字或中文以台股為主
        val merged = if (q.first().isLetter() && q.all { it.code < 128 }) usHits + twHits else twHits + usHits
        return merged.distinctBy { it.symbol }.take(limit)
    }
}
