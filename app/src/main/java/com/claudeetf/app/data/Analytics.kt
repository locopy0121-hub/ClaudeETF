package com.claudeetf.app.data

import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.pow
import kotlin.math.sqrt

/** 績效分析：持股回測對比大盤、風險指標、XIRR、每月股息 */
object Analytics {
    private val TPE = ZoneId.of("Asia/Taipei")

    data class Backtest(
        val dates: List<LocalDate>,
        /** 皆為累積報酬 %（起點 0） */
        val mine: List<Float>,
        val tw50: List<Float>,
        val spy: List<Float>,
        val maxDrawdownPct: Double,
        val volPct: Double,
        val sharpe: Double,
        val missing: List<String>,
    )

    private fun toDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(TPE).toLocalDate()

    /** 日期 → 收盤（同日多筆取最後一筆） */
    private fun daily(h: YahooExtra.History): Map<LocalDate, Float> {
        val m = LinkedHashMap<LocalDate, Float>()
        for (i in h.times.indices) m[toDate(h.times[i])] = h.closes[i]
        return m
    }

    private var cache: Pair<Long, Backtest>? = null

    /**
     * 以「目前持股」回測過去一年：每天的組合市值 = Σ 股數 × 當日收盤（美股以目前匯率換算），
     * 與 0050、SPY 比較累積報酬。
     */
    suspend fun backtest(force: Boolean = false): Backtest = withContext(Dispatchers.IO) {
        cache?.let { (t, b) -> if (!force && System.currentTimeMillis() - t < 30 * 60_000L) return@withContext b }
        val holdings = AppState.holdings.toList()
        if (holdings.isEmpty()) throw IllegalStateException("目前沒有持股，無法回測")
        val fx = AppState.usdTwd
        val missing = ArrayList<String>()
        val series = HashMap<String, Map<LocalDate, Float>>()
        for (h in holdings) {
            val q = AppState.quote(h.symbol) ?: continue
            val hist = runCatching { YahooExtra.history(StockInfoRepo.yahooSymbol(q), "1y") }.getOrNull()
            if (hist == null || hist.closes.size < 10) missing += h.symbol else series[h.symbol] = daily(hist)
        }
        val b50 = daily(YahooExtra.history("0050.TW", "1y"))
        val bSpy = runCatching { daily(YahooExtra.history("SPY", "1y")) }.getOrDefault(emptyMap())
        if (series.isEmpty()) throw IllegalStateException("無法取得持股歷史價格")

        // 以台股交易日為主軸，缺值沿用前一日
        val axis = b50.keys.sorted()
        val last = HashMap<String, Float>()
        var lastSpy: Float? = null
        val values = ArrayList<Double>()
        val spyVals = ArrayList<Float>()
        val tw50Vals = ArrayList<Float>()
        val dates = ArrayList<LocalDate>()
        val spyDates = bSpy.keys.sorted()
        var spyIdx = 0
        for (d in axis) {
            for ((sym, m) in series) m[d]?.let { last[sym] = it }
            while (spyIdx < spyDates.size && !spyDates[spyIdx].isAfter(d)) { lastSpy = bSpy[spyDates[spyIdx]]; spyIdx++ }
            if (last.size < series.size) continue // 等所有持股都有第一筆價格
            var v = 0.0
            for (h in holdings) {
                val p = last[h.symbol] ?: continue
                val isUs = AppState.marketOf(h.symbol) == Market.US
                v += AppState.valueOf(h.symbol, h.shares, p) * (if (isUs) fx else 1.0)
            }
            values += v
            tw50Vals += b50[d] ?: tw50Vals.lastOrNull() ?: 0f
            spyVals += lastSpy ?: spyVals.lastOrNull() ?: 0f
            dates += d
        }
        if (values.size < 10) throw IllegalStateException("歷史資料不足")

        fun cum(v: List<Double>) = v.map { ((it / v.first() - 1) * 100).toFloat() }
        val mine = cum(values)
        val tw = cum(tw50Vals.map { it.toDouble() })
        val spy = if (spyVals.first() > 0f) cum(spyVals.map { it.toDouble() }) else emptyList()

        // 風險：最大回撤、年化波動、夏普值（無風險利率以 1.5% 計）
        var peak = values.first()
        var mdd = 0.0
        for (v in values) { if (v > peak) peak = v; mdd = minOf(mdd, v / peak - 1) }
        val rets = (1 until values.size).map { values[it] / values[it - 1] - 1 }
        val mean = rets.average()
        val sd = sqrt(rets.sumOf { (it - mean).pow(2) } / maxOf(1, rets.size - 1))
        val vol = sd * sqrt(252.0)
        val annRet = (values.last() / values.first()).pow(252.0 / rets.size) - 1
        val sharpe = if (vol > 0) (annRet - 0.015) / vol else 0.0

        val b = Backtest(dates, mine, tw, spy, mdd * 100, vol * 100, sharpe, missing)
        cache = System.currentTimeMillis() to b
        b
    }

    /**
     * XIRR：以實際交易現金流（買進為負、賣出與股息為正）加上目前市值計算年化報酬。
     * 含期初持股（沒有買入日期）時無法計算，回傳 null。
     */
    fun xirr(): Double? {
        val trades = AppState.trades.filter { it.origin != TradeOrigin.LEGACY }
        if (trades.isEmpty() || trades.any { it.origin == TradeOrigin.OPENING }) return null
        val flows = ArrayList<Pair<LocalDate, Double>>()
        for (t in trades) {
            val d = runCatching { LocalDate.parse(t.date.replace('/', '-')) }.getOrNull() ?: return null
            val isUs = AppState.marketOf(t.symbol) == Market.US
            val fx = if (isUs) t.fx else 1.0
            val amt = when (t.type) {
                TradeType.BUY -> -(t.gross + t.fee) * fx
                TradeType.SELL -> (t.gross - t.fee - t.tax) * fx
                TradeType.DIVIDEND -> AppState.dividendById[t.id] ?: 0.0
                TradeType.SPLIT -> 0.0
            }
            if (amt != 0.0) flows += d to amt
        }
        val today = LocalDate.now(TPE)
        flows += today to AppState.totalMarketValueTwd()
        val t0 = flows.minOf { it.first }
        if (ChronoUnit.DAYS.between(t0, today) < 30) return null
        fun npv(r: Double) = flows.sumOf { (d, a) -> a / (1 + r).pow(ChronoUnit.DAYS.between(t0, d) / 365.0) }
        var lo = -0.99
        var hi = 10.0
        if (npv(lo) * npv(hi) > 0) return null
        repeat(200) {
            val mid = (lo + hi) / 2
            if (npv(lo) * npv(mid) <= 0) hi = mid else lo = mid
        }
        return (lo + hi) / 2 * 100
    }

    /** 今年每月股息實收（台幣），index 0 = 1 月 */
    fun monthlyDividends(year: Int): List<Double> {
        val out = DoubleArray(12)
        val y = year.toString()
        for (t in AppState.trades) {
            if (t.type != TradeType.DIVIDEND || t.origin == TradeOrigin.LEGACY || !t.date.startsWith(y)) continue
            val m = t.date.substring(5, 7).toIntOrNull() ?: continue
            out[m - 1] += AppState.dividendById[t.id] ?: 0.0
        }
        return out.toList()
    }
}
