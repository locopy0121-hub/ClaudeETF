package com.claudeetf.app.data

import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * 股價光譜（趨勢通道，類樂活五線譜）
 * ---------------------------------------------------------------
 * 在選定期間（半年～十年）對收盤價做線性迴歸得到「趨勢線」T，
 * 以殘差標準差 σ 畫出五條線：T−2σ、T−σ、T、T+σ、T+2σ，
 * 把目前股價放進六個區間：特價、便宜、合理偏低、合理偏高、昂貴、極貴。
 * 價格位置只代表相對過去趨勢的高低，不代表基本面價值。
 */
object PriceBand {
    /** 顯示名稱 → Yahoo range（Yahoo 沒有 3y，三年用 5y 再截取） */
    val periods = listOf("半年" to "6mo", "一年" to "1y", "三年" to "3y", "五年" to "5y", "十年" to "10y")
    val zoneNames = listOf("特價", "便宜", "合理偏低", "合理偏高", "昂貴", "極貴")

    data class Result(
        val times: List<Long>,
        val closes: List<Double>,
        /** 趨勢線 y = a + b × 第 i 根 */
        val a: Double,
        val b: Double,
        val sigma: Double,
        val price: Double,
    ) {
        fun trendAt(i: Int) = a + b * i
        val trend: Double get() = trendAt(closes.size - 1)
        /** 由低到高：T−2σ、T−σ、T、T+σ、T+2σ */
        val lines: List<Double> get() = listOf(-2.0, -1.0, 0.0, 1.0, 2.0).map { trend + it * sigma }
        /** 0 特價 … 5 極貴 */
        val zone: Int get() = lines.indexOfFirst { price < it }.let { if (it < 0) 5 else it }
        val zoneName: String get() = zoneNames[zone]
        /** 偏離趨勢幾個標準差 */
        val z: Double get() = if (sigma > 0) (price - trend) / sigma else 0.0
    }

    /** 分割斷層修正（與歷年報酬相同規則） */
    private fun fixSplits(v: MutableList<Double>, tw: Boolean) {
        for (i in v.size - 1 downTo 1) {
            val a = v[i - 1]; val b = v[i]
            if (a <= 0 || b <= 0) continue
            val r = b / a
            if (if (tw) (r < 0.45 || r > 2.2) else (r < 0.35 || r > 3.0)) for (k in 0 until i) v[k] = v[k] * r
        }
    }

    fun compute(times: List<Long>, closesIn: List<Double>, price: Double?, tw: Boolean): Result? {
        val ok = closesIn.indices.filter { !closesIn[it].isNaN() && closesIn[it] > 0 }
        if (ok.size < 20) return null
        val t = ok.map { times[it] }
        val y = ok.map { closesIn[it] }.toMutableList()
        fixSplits(y, tw)
        val p = price?.takeIf { it > 0 } ?: y.last()
        y[y.size - 1] = p
        val n = y.size
        val xm = (n - 1) / 2.0
        val ym = y.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) { sxy += (i - xm) * (y[i] - ym); sxx += (i - xm) * (i - xm) }
        val b = if (sxx > 0) sxy / sxx else 0.0
        val a = ym - b * xm
        val sigma = sqrt((0 until n).sumOf { val r = y[it] - (a + b * it); r * r } / (n - 2).coerceAtLeast(1))
        return Result(t, y, a, b, sigma, p)
    }

    fun compute(h: YahooExtra.History, price: Double?, tw: Boolean): Result? =
        compute(h.times, h.closes.map { it.toDouble() }, price, tw)

    private val cache = HashMap<String, Pair<Long, Result?>>()

    suspend fun load(q: Quote, period: String): Result? = withContext(Dispatchers.IO) {
        val key = q.symbol + "|" + period
        cache[key]?.let { (ts, r) -> if (System.currentTimeMillis() - ts < 10 * 60_000L) return@withContext r?.let { it.copy(price = q.price.takeIf { p -> p > 0 } ?: it.price) } }
        val h = YahooExtra.history(StockInfoRepo.yahooSymbol(q), if (period == "3y") "5y" else period)
        val cut = if (period == "3y") System.currentTimeMillis() - 3L * 365 * 86_400_000L else 0L
        val idx = h.times.indices.filter { h.times[it] >= cut }
        compute(idx.map { h.times[it] }, idx.map { h.closes[it].toDouble() }, q.price, q.market == Market.TW)
            .also { cache[key] = System.currentTimeMillis() to it }
    }

    /** 光譜位置的估值分數（越便宜越高） */
    fun zoneScore(zone: Int) = listOf(90, 75, 60, 45, 25, 10)[zone.coerceIn(0, 5)]
}
