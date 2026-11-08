package com.claudeetf.app.data

import com.claudeetf.app.data.remote.RemoteQuote
import com.claudeetf.app.data.remote.TpexApi
import com.claudeetf.app.data.remote.TwOpenData
import com.claudeetf.app.data.remote.TwseApi
import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * ETF 成分：持股明細＋個股股價與市值、產業分佈、每日操作（新增／刪除／加碼／減碼）。
 * 台股 ETF：MoneyDJ 持股（含持有股數）＋證交所／櫃買每日收盤＋產業別；
 * 每日持股存成快照，與前一個資料日比較得出操作日報。
 */
object EtfComposition {
    data class Row(
        val symbol: String, val name: String, val weight: Double, val shares: Double?,
        val price: Double?, val chgPct: Double?, val industry: String,
    ) {
        /** 持股市值（元） */
        val mv: Double? get() = if (shares != null && price != null) shares * price else null
        val key: String get() = symbol.ifBlank { name }
    }

    /** kind：0 新增、1 刪除、2 加碼、3 減碼 */
    data class Change(val symbol: String, val name: String, val kind: Int, val deltaShares: Double, val deltaPct: Double?, val weight: Double, val weightDelta: Double)

    data class Result(
        val rows: List<Row>, val industries: List<Pair<String, Double>>, val asOf: String?,
        val changes: List<Change>?, val prevAsOf: String?, val tw: Boolean,
    )

    @Volatile var dir: File? = null
    private var prices: Pair<String, Map<String, RemoteQuote>>? = null
    private var inds: Pair<String, Map<String, String>>? = null
    private fun today() = LocalDate.now(ZoneId.of("Asia/Taipei")).toString()

    private fun twPrices(): Map<String, RemoteQuote> {
        prices?.takeIf { it.first == today() }?.let { return it.second }
        val m = HashMap<String, RemoteQuote>()
        runCatching { TwseApi.dayAll() }.getOrNull()?.let { m.putAll(it) }
        runCatching { TpexApi.dayAll() }.getOrNull()?.forEach { (k, v) -> if (k !in m) m[k] = v }
        if (m.isNotEmpty()) prices = today() to m
        return m
    }

    private fun twIndustries(): Map<String, String> {
        inds?.takeIf { it.first == today() }?.let { return it.second }
        val m = HashMap<String, String>()
        runCatching { TwOpenData.industries() }.getOrNull()?.let { m.putAll(it) }
        runCatching { com.claudeetf.app.data.remote.TpexOpenData.industries() }.getOrNull()?.forEach { (k, v) -> if (k !in m) m[k] = v }
        if (m.isNotEmpty()) inds = today() to m
        return m
    }

    /** 非個股部位（期貨、現金、保證金…）的分類 */
    private fun nonStock(name: String): String = when {
        name.contains("期貨") || name.contains("FUT", true) -> "期貨"
        name.contains("CASH", true) || name.contains("現金") -> "現金"
        name.contains("MARGIN", true) || name.contains("保證金") -> "保證金"
        name.contains("債") -> "債券"
        else -> "其他"
    }

    /** 最近一次結果（同一資料日內重用，穿透持股計算用） */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Result>>()

    suspend fun cached(q: Quote): Result {
        cache[q.symbol]?.takeIf { it.first == today() }?.let { return it.second }
        return load(q)
    }

    suspend fun load(q: Quote): Result = withContext(Dispatchers.IO) {
        val h = StockInfoRepo.holdings(q)
        val tw = q.market == Market.TW
        val px = if (tw) twPrices() else emptyMap()
        val ind = if (tw) twIndustries() else emptyMap()
        val rows = h.holdings.map { x ->
            val p = px[x.symbol]
            val chg = p?.let { if (it.prevClose > 0) (it.price / it.prevClose - 1) * 100 else null }
            Row(x.symbol, x.name, x.weightPct, x.shares, p?.price, chg,
                if (x.symbol.isBlank()) nonStock(x.name) else ind[x.symbol] ?: if (tw) "其他" else "")
        }
        val industries = if (tw) rows.groupBy { it.industry }.map { (k, l) -> k to l.sumOf { it.weight } }.sortedByDescending { it.second }
        else h.sectors
        val (changes, prev) = if (tw && h.asOf != null) diff(q.symbol, h.asOf, rows) else null to null
        Result(rows, industries, h.asOf, changes, prev, tw).also { cache[q.symbol] = today() to it }
    }

    /** 存今天的快照，並與前一個資料日比較 */
    private fun diff(code: String, asOf: String, rows: List<Row>): Pair<List<Change>?, String?> {
        val d = dir?.let { File(it, "etf_snap").apply { mkdirs() } } ?: return null to null
        val f = File(d, "$code.json")
        val all = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        val cur = JSONObject()
        rows.forEach { r -> cur.put(r.key, JSONArray().put(r.shares ?: -1.0).put(r.weight).put(r.name)) }
        all.put(asOf, cur)
        // 只保留最近 10 個資料日
        val keys = all.keys().asSequence().toList().sorted()
        keys.dropLast(10).forEach { all.remove(it) }
        runCatching { f.writeText(all.toString()) }
        val prevKey = keys.filter { it < asOf }.maxOrNull() ?: return null to null
        val prev = all.optJSONObject(prevKey) ?: return null to null
        val out = ArrayList<Change>()
        val now = rows.associateBy { it.key }
        now.forEach { (k, r) ->
            val p = prev.optJSONArray(k)
            if (p == null) { out += Change(r.symbol, r.name, 0, r.shares ?: 0.0, null, r.weight, r.weight); return@forEach }
            val ps = p.optDouble(0, -1.0); val pw = p.optDouble(1, 0.0)
            val s = r.shares ?: return@forEach
            if (ps <= 0) return@forEach
            val delta = s - ps
            if (kotlin.math.abs(delta) >= 1) out += Change(r.symbol, r.name, if (delta > 0) 2 else 3, delta, delta / ps * 100, r.weight, r.weight - pw)
        }
        prev.keys().forEach { k ->
            if (k !in now) {
                val p = prev.optJSONArray(k) ?: return@forEach
                val ps = p.optDouble(0, -1.0)
                out += Change(if (k.all { it.isLetterOrDigit() } && k.any { it.isDigit() }) k else "", p.optString(2, k), 1, if (ps > 0) -ps else 0.0, -100.0, 0.0, -p.optDouble(1, 0.0))
            }
        }
        return out.sortedWith(compareBy({ it.kind }, { -kotlin.math.abs(it.deltaPct ?: 100.0) })) to prevKey
    }
}
