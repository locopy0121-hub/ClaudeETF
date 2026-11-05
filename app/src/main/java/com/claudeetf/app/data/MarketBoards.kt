package com.claudeetf.app.data

import com.claudeetf.app.data.remote.FinnhubApi
import com.claudeetf.app.data.remote.TwOpenData
import com.claudeetf.app.data.remote.YahooApi
import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 市場頁：類股熱力圖與漲跌排行 */
object MarketBoards {
    data class Board(
        val sectors: List<SectorMove>,
        val gainers: List<YahooExtra.Mover>,
        val losers: List<YahooExtra.Mover>,
        val actives: List<YahooExtra.Mover>,
        val notes: List<String>,
    )

    /** S&P 500 十一大類股以 SPDR 類股 ETF 代表 */
    private val usSectorEtfs = listOf(
        "XLK" to "資訊科技", "XLC" to "通訊服務", "XLY" to "非必需消費", "XLF" to "金融",
        "XLV" to "醫療保健", "XLI" to "工業", "XLP" to "必需消費", "XLE" to "能源",
        "XLU" to "公用事業", "XLB" to "原物料", "XLRE" to "房地產",
    )

    private const val TTL = 5 * 60_000L
    private var usCache: Pair<Long, Board>? = null
    private var twCache: Pair<Long, Board>? = null

    suspend fun us(force: Boolean = false): Board = withContext(Dispatchers.IO) {
        usCache?.let { (t, b) -> if (!force && System.currentTimeMillis() - t < TTL) return@withContext b }
        coroutineScope {
            val sectorsJob = async {
                usSectorEtfs.mapNotNull { (sym, name) ->
                    runCatching { YahooApi.chart(sym).quote }.getOrNull()?.let { q ->
                        SectorMove(name, if (q.prevClose > 0) (q.price - q.prevClose) / q.prevClose * 100 else 0.0)
                    }
                }
            }
            val g = async { runCatching { YahooExtra.screener("day_gainers") }.getOrDefault(emptyList()) }
            val l = async { runCatching { YahooExtra.screener("day_losers") }.getOrDefault(emptyList()) }
            val a = async { runCatching { YahooExtra.screener("most_actives") }.getOrDefault(emptyList()) }
            val notes = ArrayList<String>()
            val sectors = sectorsJob.await()
            if (sectors.isEmpty()) notes += "類股資料暫時無法取得"
            val board = Board(sectors, g.await(), l.await(), a.await(), notes)
            if (board.gainers.isEmpty() && board.losers.isEmpty()) notes += "排行資料暫時無法取得（Yahoo 驗證失敗或休市）"
            if (sectors.isEmpty() && board.gainers.isEmpty()) throw IllegalStateException("市場資料暫時無法取得")
            usCache = System.currentTimeMillis() to board
            board
        }
    }

    suspend fun tw(force: Boolean = false): Board = withContext(Dispatchers.IO) {
        twCache?.let { (t, b) -> if (!force && System.currentTimeMillis() - t < TTL) return@withContext b }
        val notes = ArrayList<String>()
        val sectors = runCatching { TwOpenData.sectorIndices() }.getOrElse { notes += "類股指數暫時無法取得"; emptyList() }
            .map { SectorMove(it.first, it.second) }
        // 個股排行：只取四碼普通股，排除成交量太小（< 500 張）的冷門股
        val all = MarketData.closeAll().values.map { it.first }
            .filter { Regex("^[1-9][0-9]{3}$").matches(it.symbol) && it.prevClose > 0 }
        val liquid = all.filter { (it.volume ?: 0.0) >= 500_000 }
        fun mover(r: com.claudeetf.app.data.remote.RemoteQuote) = YahooExtra.Mover(
            r.symbol, r.name ?: r.symbol, r.price, (r.price - r.prevClose) / r.prevClose * 100, (r.volume ?: 0.0) / 1000,
        )
        val movers = liquid.map(::mover)
        if (all.isEmpty()) notes += "證交所收盤資料暫時無法取得"
        val board = Board(
            sectors = sectors,
            gainers = movers.sortedByDescending { it.changePct }.take(10),
            losers = movers.sortedBy { it.changePct }.take(10),
            actives = all.sortedByDescending { it.volume ?: 0.0 }.take(10).map(::mover),
            notes = notes,
        )
        if (sectors.isEmpty() && movers.isEmpty()) throw IllegalStateException("台股市場資料暫時無法取得")
        twCache = System.currentTimeMillis() to board
        board
    }
}
