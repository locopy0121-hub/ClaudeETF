package com.claudeetf.app.data

import com.claudeetf.app.data.remote.RemoteQuote
import com.claudeetf.app.data.remote.TpexApi
import com.claudeetf.app.data.remote.TwseApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 台股 ETF 市場概況（證交所＋櫃買中心當日收盤／最新行情）：
 * 漲跌檔數、成交值與占比、各類型（市值型／高股息…以代號與名稱判斷）平均漲跌、漲幅／跌幅／成交值排行。
 */
object EtfMarket {
    data class Row(val symbol: String, val name: String, val otc: Boolean, val price: Double, val pct: Double, val valueYi: Double, val lots: Double)
    data class Cat(val name: String, val count: Int, val avgPct: Double, val up: Int, val down: Int, val valueYi: Double)
    data class Snapshot(
        val date: String, val up: Int, val down: Int, val flat: Int, val valueYi: Double, val marketYi: Double,
        val cats: List<Cat>, val gainers: List<Row>, val losers: List<Row>, val actives: List<Row>, val all: List<Row>,
    )

    /** 類型：依代號字尾與名稱關鍵字 */
    fun category(code: String, name: String): String = when {
        code.endsWith("L") || code.endsWith("R") || name.contains("正2") || name.contains("反1") -> "槓桿／反向"
        code.endsWith("B") || name.contains("債") -> "債券"
        code.endsWith("A") || name.startsWith("主動") -> "主動式"
        code.endsWith("U") || name.contains("原油") || name.contains("黃金") || name.contains("期") -> "期貨／商品"
        listOf("高股息", "高息", "優息", "股利", "收益", "填息", "息").any { name.contains(it) } -> "高股息"
        listOf("美國", "美股", "那斯達克", "NASDAQ", "S&P", "標普", "費城", "日本", "日經", "中國", "印度", "越南", "全球", "歐洲", "韓國", "港").any { name.contains(it, true) } -> "海外"
        listOf("半導體", "科技", "電動車", "AI", "5G", "網通", "電池", "生技", "醫療", "金融", "ESG", "永續", "綠能", "車").any { name.contains(it, true) } -> "主題／產業"
        else -> "市值型／其他"
    }

    @Volatile private var cache: Pair<Long, Snapshot>? = null

    suspend fun load(force: Boolean = false): Snapshot {
        cache?.takeIf { !force && System.currentTimeMillis() - it.first < 5 * 60_000L }?.let { return it.second }
        val (tse, otc) = withContext(Dispatchers.IO) {
            runCatching { TwseApi.dayAll() }.getOrDefault(emptyMap()) to runCatching { TpexApi.dayAll() }.getOrDefault(emptyMap())
        }
        if (tse.isEmpty() && otc.isEmpty()) throw IllegalStateException("取不到證交所／櫃買收盤資料")
        val marketYi = (tse.values.sumOf { it.value ?: 0.0 } + otc.values.sumOf { it.value ?: 0.0 }) / 1e8
        fun row(q: RemoteQuote, o: Boolean): Row? {
            if (!q.symbol.startsWith("00") || q.price <= 0 || q.prevClose <= 0) return null
            // 自選中有較新的即時報價就用即時價
            val live = AppState.quote(q.symbol)?.takeIf { it.price > 0 && it.prevClose > 0 && it.updatedAt > System.currentTimeMillis() - 6 * 3600_000L }
            val price = live?.price ?: q.price
            val prev = live?.prevClose ?: q.prevClose
            return Row(q.symbol, q.name ?: q.symbol, o, price, (price / prev - 1) * 100, (q.value ?: 0.0) / 1e8, (q.volume ?: 0.0) / 1000)
        }
        val rows = tse.values.mapNotNull { row(it, false) } + otc.values.mapNotNull { row(it, true) }
        val traded = rows.filter { it.lots > 0 }
        val up = traded.count { it.pct > 0.0001 }; val down = traded.count { it.pct < -0.0001 }
        val liquid = traded.filter { it.lots >= 200 }
        val cats = traded.groupBy { category(it.symbol, it.name) }.map { (n, l) ->
            Cat(n, l.size, l.map { it.pct }.average(), l.count { it.pct > 0.0001 }, l.count { it.pct < -0.0001 }, l.sumOf { it.valueYi })
        }.sortedByDescending { it.valueYi }
        val date = (tse.values.firstOrNull()?.date ?: otc.values.firstOrNull()?.date)?.toString() ?: ""
        val snap = Snapshot(date, up, down, traded.size - up - down, traded.sumOf { it.valueYi }, marketYi, cats,
            liquid.sortedByDescending { it.pct }.take(15), liquid.sortedBy { it.pct }.take(15), traded.sortedByDescending { it.valueYi }.take(15), traded)
        cache = System.currentTimeMillis() to snap
        return snap
    }
}
