package com.claudeetf.app.data

import com.claudeetf.app.data.remote.TpexApi
import com.claudeetf.app.data.remote.TwIsin
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
 * 台股：證交所（上市）＋櫃買中心（上櫃）每日收盤資料＋ISIN 上市櫃一覽表（含新上市、尚無成交的股票與 ETF），
 *       每天更新（收盤後再更新一次），並記錄市場異動；
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

    /** ISIN 一覽表的額外資訊：代號 → 類型、上市日、產業 */
    @Volatile private var meta: Map<String, TwIsin.Sec> = emptyMap()
    /** 目前目錄中的 ISIN 資料筆數（診斷用） */
    val isinCount: Int get() { if (meta.isEmpty() && tw.isEmpty()) runCatching { loadFiles() }; return meta.size }
    /** 上次嘗試補抓 ISIN 的時間（缺 ISIN 時每 30 分鐘最多重試一次） */
    @Volatile private var isinTryAt = 0L
    fun metaOf(symbol: String): TwIsin.Sec? = meta[symbol.uppercase()]
    fun isOtc(symbol: String) = symbol.uppercase() in otc

    private fun f(name: String) = cacheDir?.let { File(it, name) }

    /** 讀取存檔（不檢查新舊；更新交給 refresh） */
    private fun loadFiles(): Boolean {
        val fj = f("tw_directory.json") ?: return false
        if (!fj.exists()) return false
        val m = runCatching { JSONObject(fj.readText()).let { o -> o.keys().asSequence().associateWith { o.optString(it) } } }.getOrNull()
        if (m.isNullOrEmpty()) return false
        tw = m
        otc = runCatching { f("tw_directory_otc.txt")!!.readText().split(",").filter { c -> c.isNotBlank() }.toSet() }.getOrDefault(emptySet())
        meta = runCatching {
            val o = JSONObject(f("tw_isin.json")!!.readText())
            o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                TwIsin.Sec(k, a.optString(0), a.optString(1), a.optString(2), a.optString(3), a.optString(4))
            }
        }.getOrDefault(emptyMap())
        MarketChanges.updatedAt = fj.lastModified()
        return true
    }

    private suspend fun twList(): Map<String, String> {
        if (tw.isNotEmpty()) return tw
        lock.withLock { if (tw.isEmpty()) loadFiles() }
        if (tw.isEmpty()) refresh(force = true)
        return tw
    }

    /**
     * 是否該重新抓目錄：不同日，或今天 14:35（收盤資料公布）後還沒更新過。
     */
    fun stale(now: Long = System.currentTimeMillis()): Boolean {
        val last = f("tw_directory.json")?.takeIf { it.exists() }?.lastModified() ?: return true
        // 還沒有完整的 ISIN 一覽表（之前下載都失敗）：每 30 分鐘補抓一次
        if (f("tw_isin.json")?.exists() != true && now - isinTryAt > 30 * 60_000L) return true
        val z = java.time.ZoneId.of("Asia/Taipei")
        val n = java.time.Instant.ofEpochMilli(now).atZone(z)
        val l = java.time.Instant.ofEpochMilli(last).atZone(z)
        if (n.toLocalDate() != l.toLocalDate()) return true
        val cut = n.toLocalDate().atTime(14, 35).atZone(z)
        return n.isAfter(cut) && l.isBefore(cut)
    }

    /**
     * 更新台股目錄：證交所＋櫃買每日收盤（名稱）＋ISIN 上市／上櫃一覽表（含尚未有成交的新上市股票與 ETF）。
     * 比對前後差異寫入市場異動紀錄。
     */
    suspend fun refresh(force: Boolean = false): Boolean = lock.withLock {
        if (tw.isEmpty()) loadFiles()
        if (!force && !stale()) return@withLock true
        MarketChanges.updating = true
        isinTryAt = System.currentTimeMillis()
        try {
            val o2 = HashSet<String>()
            val (names, isin, complete) = withContext(Dispatchers.IO) {
                val out = LinkedHashMap<String, String>()
                runCatching { TwseApi.latestAll() }.getOrNull()?.forEach { (k, v) -> out[k] = v.name ?: k }
                runCatching { TpexApi.latestAll() }.getOrNull()?.forEach { (k, v) -> if (k !in out) { out[k] = v.name ?: k; o2 += k } }
                val iL = runCatching { TwIsin.fetch(2) }.getOrNull()
                val iO = runCatching { TwIsin.fetch(4) }.getOrNull()
                val im = LinkedHashMap<String, TwIsin.Sec>()
                iL?.forEach { im[it.code] = it }
                iO?.forEach { im[it.code] = it; o2 += it.code }
                // 兩份 ISIN 都取得才當作完整清單（用於判斷下市）
                Triple(out, im, iL != null && iO != null)
            }
            if (names.isEmpty() && isin.isEmpty()) {
                MarketChanges.lastError = "目錄更新失敗（網路或來源暫時無法連線）"
                return@withLock false
            }
            // 合併：每日行情名稱優先，ISIN 補上尚未有成交的代號
            val merged = LinkedHashMap<String, String>(names)
            isin.values.forEach { s -> if (s.code !in merged) merged[s.code] = s.name }
            if (names.isEmpty()) tw.forEach { (k, v) -> merged.putIfAbsent(k, v) }
            val oldNames = runCatching { f("tw_names_day.json")?.takeIf { it.exists() }?.readText()?.let { t -> JSONObject(t).let { o -> o.keys().asSequence().associateWith { o.optString(it) } } } }.getOrNull() ?: emptyMap()
            val oldIsin = meta.keys
            runCatching {
                MarketChanges.record(oldNames, names, if (complete) oldIsin else emptySet(), isin, o2, complete)
            }
            tw = merged; otc = o2 + otc.filter { it in merged && it !in names }
            // 完整才整份替換；只取得部分時與舊資料合併（新上市、名稱仍可用）
            meta = if (complete) isin else meta + isin
            runCatching {
                f("tw_directory.json")?.writeText(JSONObject(merged as Map<*, *>).toString())
                f("tw_directory_otc.txt")?.writeText(otc.joinToString(","))
                if (names.isNotEmpty()) f("tw_names_day.json")?.writeText(JSONObject(names as Map<*, *>).toString())
                if (complete || isin.isNotEmpty()) f("tw_isin.json")?.writeText(JSONObject().apply {
                    meta.values.forEach { s -> put(s.code, org.json.JSONArray(listOf(s.name, s.listDate, s.market, s.industry, s.type))) }
                }.toString())
            }
            MarketChanges.updatedAt = System.currentTimeMillis()
            MarketChanges.lastError = null
            true
        } finally {
            MarketChanges.updating = false
        }
    }

    /** 目錄沒有的台股代號：即時向證交所查詢（新上市當天、目錄尚未更新時） */
    private suspend fun lookupTw(code: String): Item? = withContext(Dispatchers.IO) {
        val t = runCatching { com.claudeetf.app.data.remote.TwseMisApi.quotes(listOf(code)) }.getOrNull()?.get(code) ?: return@withContext null
        val name = t.quote.name ?: return@withContext null
        tw = tw + (code to name)
        if (t.isOtc) otc = otc + code
        Item(code, name, Market.TW, if (code.startsWith("00")) "ETF" else "", if (t.isOtc) "櫃" else "市")
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
                Item(code, name, Market.TW, meta[code]?.type?.takeIf { it != "股票" } ?: if (code.startsWith("00")) "ETF" else "", if (code in otc) "櫃" else "市")
            }
            .toList()
            .let { hits ->
                // 看起來是台股代號但目錄沒有：即時查詢
                if (hits.none { it.symbol == up } && Regex("^\\d{4,6}[A-Z]?$").matches(up)) (listOfNotNull(lookupTw(up)) + hits) else hits
            }
        val usHits = if (q.any { it in 'A'..'Z' || it in 'a'..'z' }) withContext(Dispatchers.IO) {
            runCatching { YahooExtra.symbolSearch(q) }.getOrDefault(emptyList())
                .map { Item(it.symbol, it.name, Market.US, if (it.type == "ETF") "ETF" else "", "美").also { i -> usNames[i.symbol.uppercase()] = i.name } }
        } else emptyList()
        // 英文輸入以美股為主，數字或中文以台股為主
        val merged = if (q.first().isLetter() && q.all { it.code < 128 }) usHits + twHits else twHits + usHits
        return merged.distinctBy { it.symbol }.take(limit)
    }
}
