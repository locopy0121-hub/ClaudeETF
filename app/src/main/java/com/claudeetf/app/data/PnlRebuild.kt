package com.claudeetf.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/**
 * 重建每日損益：依交易紀錄還原「每天收盤時持有的股數」，配合歷史收盤價計算。
 *
 * 公式（每個交易日 d）：
 *   市值_d   ＝ Σ 股數_d × 還原拆股係數_d × 收盤價_d × 匯率
 *   損益_d   ＝ 市值_d − 市值_(前一交易日) − 當日買進成本 ＋ 當日賣出所得 ＋ 當日股息
 *   買進成本 ＝ (成交金額 ＋ 手續費) × 匯率；賣出所得 ＝ (成交金額 − 手續費 − 交易稅) × 匯率
 *   股息     ＝ (股數 × 每股股利 − 稅 − 費用) × 匯率
 * 匯率一律用目前匯率（只計股價損益，不含匯兌），避免買進當天因匯率差出現假損益。
 * 歷史收盤價已還原拆股，所以拆股前的股數乘上之後的拆股比例。
 * 某檔當天沒有收盤價（停牌、休市）沿用前一個收盤價。
 * 純計算、無 Android 相依，可單元測試。
 */
object PnlRebuild {
    data class Tx(val type: TradeType, val symbol: String, val date: LocalDate, val shares: Double, val gross: Double, val price: Double, val fee: Double, val tax: Double)

    /**
     * @param closes 代號 → (日期 → 收盤價，已還原拆股)
     * @param fx 代號 → 目前匯率（台股 1）
     * @return 日期 → 當日損益（台幣）；只含 [from, to] 之間有收盤價的交易日
     */
    fun compute(
        txs: List<Tx>, closes: Map<String, Map<LocalDate, Double>>, fx: Map<String, Double>, from: LocalDate, to: LocalDate,
        /** 選填：收集每個交易日的持股市值（台幣） */
        values: MutableMap<LocalDate, Double>? = null,
    ): Map<LocalDate, Double> {
        val days = closes.values.flatMap { it.keys }.filter { it in from..to }.toSortedSet()
        if (days.isEmpty()) return emptyMap()
        val sorted = txs.sortedBy { it.date }
        // 拆股：某日之後的累積比例（歷史價已除以這個比例）
        fun splitAfter(sym: String, d: LocalDate): Double =
            sorted.filter { it.symbol == sym && it.type == TradeType.SPLIT && it.date > d && it.price > 0 }.fold(1.0) { a, t -> a * t.price }
        val shares = HashMap<String, Double>()
        val lastClose = HashMap<String, Double>()
        var ti = 0
        // 區間開始前的持股與收盤價
        while (ti < sorted.size && sorted[ti].date < days.first()) { apply(sorted[ti], shares); ti++ }
        // 區間前一個收盤價；沒有（資料從區間內才開始）就用區間內第一個收盤價，避免第一天把整筆市值算成獲利
        closes.forEach { (s, m) ->
            (m.filterKeys { it < days.first() }.maxByOrNull { it.key } ?: m.filterKeys { it >= days.first() }.minByOrNull { it.key })
                ?.let { lastClose[s] = it.value }
        }
        fun value(d: LocalDate): Double = shares.entries.sumOf { (s, n) ->
            val c = lastClose[s] ?: return@sumOf 0.0
            n * splitAfter(s, d) * c * (fx[s] ?: 1.0)
        }
        var prev = value(days.first().minusDays(1))
        val out = LinkedHashMap<LocalDate, Double>()
        for (d in days) {
            var flow = 0.0
            while (ti < sorted.size && sorted[ti].date <= d) {
                val t = sorted[ti]; val f = fx[t.symbol] ?: 1.0
                when (t.type) {
                    TradeType.BUY -> flow -= (t.gross + t.fee) * f
                    TradeType.SELL -> flow += (t.gross - t.fee - t.tax) * f
                    TradeType.DIVIDEND -> flow += (t.gross - t.tax - t.fee) * f
                    TradeType.SPLIT -> Unit
                }
                apply(t, shares); ti++
            }
            closes.forEach { (s, m) -> m[d]?.let { lastClose[s] = it } }
            val v = value(d)
            out[d] = v - prev + flow
            values?.put(d, v)
            prev = v
        }
        return out
    }

    private fun apply(t: Tx, shares: HashMap<String, Double>) {
        when (t.type) {
            TradeType.BUY -> shares[t.symbol] = (shares[t.symbol] ?: 0.0) + t.shares
            TradeType.SELL -> shares[t.symbol] = ((shares[t.symbol] ?: 0.0) - t.shares).coerceAtLeast(0.0)
            TradeType.SPLIT -> shares[t.symbol]?.let { if (t.price > 0) shares[t.symbol] = it * t.price }
            TradeType.DIVIDEND -> Unit
        }
    }
}

/** 重建每日損益並保存（損益日曆用） */
object PnlHistory {
    var running by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
        private set

    suspend fun rebuild(): String? {
        if (running) return null
        running = true
        try {
            val trades = AppState.trades.toList()
            if (trades.isEmpty()) return "沒有交易紀錄"
            val tz = java.time.ZoneId.of("Asia/Taipei")
            val today = LocalDate.now(tz)
            val first = trades.mapNotNull { runCatching { LocalDate.parse(it.date.replace("/", "-")) }.getOrNull() }.minOrNull() ?: today
            val from = maxOf(first, today.minusDays(730))
            val txs = trades.mapNotNull { t ->
                val d = runCatching { LocalDate.parse(t.date.replace("/", "-")) }.getOrNull() ?: return@mapNotNull null
                PnlRebuild.Tx(t.type, t.symbol, d, t.shares, t.gross, t.price, t.fee, t.tax)
            }
            val symbols = txs.map { it.symbol }.distinct()
            val closes = HashMap<String, Map<LocalDate, Double>>()
            val fx = HashMap<String, Double>()
            var fail = 0
            symbols.forEachIndexed { i, sym ->
                progress = "載入歷史股價 ${i + 1}/${symbols.size}：$sym"
                val market = AppState.marketOf(sym)
                fx[sym] = AppState.fxFor(market)
                val q = AppState.quote(sym) ?: Quote(sym, sym, market, 0.0, 0.0)
                val zone = if (market == Market.US) java.time.ZoneId.of("America/New_York") else tz
                val h = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { com.claudeetf.app.data.remote.YahooExtra.history(StockInfoRepo.yahooSymbol(q), "2y", intervalOverride = "1d") }.getOrNull()
                }
                if (h == null || h.times.isEmpty()) { fail++; return@forEachIndexed }
                closes[sym] = h.times.indices.associate { j ->
                    java.time.Instant.ofEpochMilli(h.times[j]).atZone(zone).toLocalDate() to h.closes[j].toDouble()
                }.filterValues { it > 0 }
            }
            progress = "計算中…"
            val values = LinkedHashMap<LocalDate, Double>()
            val r = PnlRebuild.compute(txs, closes, fx, from, today, values)
            AppState.setPnlHistory(r.mapKeys { it.key.toString() }.mapValues { Math.round(it.value).toDouble() })
            // 總資產走勢一併重建：持股市值依交易紀錄還原＋目前現金（舊紀錄中刪改交易造成的異常值一併清除）
            AppState.rebuildNav(values.mapKeys { it.key.toString() }, from.toString())
            AppState.pnlDirty = false
            return "已重新計算 ${r.size} 個交易日" + if (fail > 0) "（$fail 檔取不到歷史股價，未計入）" else ""
        } finally {
            running = false
            progress = ""
        }
    }
}
