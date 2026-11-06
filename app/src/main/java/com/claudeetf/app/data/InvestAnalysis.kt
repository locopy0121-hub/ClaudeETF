package com.claudeetf.app.data

import com.claudeetf.app.data.remote.TwInstitutional
import com.claudeetf.app.data.remote.TwOpenData
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.util.Indicators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 投資分析指數（0–100）：依公開市場數據計算的參考分數，不是買賣建議。
 *
 * 因子與權重（缺資料的因子不計，權重自動重新分配）：
 *   趨勢 25：現價與 20／60／120 日均線、20 日均線方向、三關位置
 *   動能 15：RSI(14)、近 20 日漲跌
 *   估值 20：本益比、殖利率（台股證交所；ETF 只看殖利率）
 *   位置 10：52 週高低區間位置（越低安全邊際越高）
 *   籌碼 15：三大法人近 5 日買賣超（台股上市）
 *   市場 10：大盤（台股看加權指數、美股看 S&P 500）是否在 60 日均線之上
 *   風險  5：一年年化波動度
 *   分析師 10：Yahoo 分析師評等（1 強力買進～5 強力賣出）與平均目標價的上漲空間
 */
object InvestAnalysis {
    data class Factor(val name: String, val score: Int, val weight: Int, val note: String)
    /** Yahoo 分析師共識 */
    data class Analyst(
        val rating: String?,
        /** 1 強力買進 … 5 強力賣出 */
        val mean: Double?,
        val count: Int?,
        val target: Double?,
        val high: Double?,
        val low: Double?,
    )

    /** ETF 沒有分析師評等，改看配息、填息、流動性、規模、費用率與折溢價 */
    data class EtfInfo(
        val yieldPct: Double?,
        /** 近 2 年填息次數／配息次數 */
        val filled: Int?,
        val divCount: Int?,
        val avgFillDays: Double?,
        /** 近 20 日平均成交量（台股：張；美股：股） */
        val avgVolume: Double?,
        val assets: Double?,
        val expensePct: Double?,
        val nav: Double?,
        val premiumPct: Double?,
    )

    data class Result(
        val score: Int,
        val label: String,
        val advice: String,
        val factors: List<Factor>,
        val at: Long,
        val analyst: Analyst? = null,
        val etf: EtfInfo? = null,
    )

    /** Compose 可觀察：個股頁標題徽章與分析卡片共用 */
    val results = androidx.compose.runtime.mutableStateMapOf<String, Result>()

    fun cached(symbol: String): Result? = results[symbol]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000L }

    private fun analyst(q: Quote): Analyst? {
        val fd = runCatching { YahooExtra.summary(StockInfoRepo.yahooSymbol(q), listOf("financialData")).optJSONObject("financialData") }.getOrNull() ?: return null
        fun raw(k: String) = fd.optJSONObject(k)?.optDouble("raw", Double.NaN)?.takeIf { !it.isNaN() && it > 0 }
        val key = fd.optString("recommendationKey").takeIf { it.isNotBlank() && it != "none" }
        val a = Analyst(
            key?.let { mapOf("strong_buy" to "強力買進", "buy" to "買進", "hold" to "持有", "underperform" to "表現落後", "sell" to "賣出", "strong_sell" to "強力賣出")[it] ?: it },
            raw("recommendationMean"), raw("numberOfAnalystOpinions")?.toInt(),
            raw("targetMeanPrice"), raw("targetHighPrice"), raw("targetLowPrice"),
        )
        return a.takeIf { it.mean != null || it.target != null }
    }

    private fun analystFactor(a: Analyst?, price: Double): Factor? {
        if (a == null) return null
        val parts = ArrayList<Double>()
        val notes = ArrayList<String>()
        a.mean?.let { parts += (100 - (it - 1) * 21).coerceIn(10.0, 95.0); notes += (a.rating ?: "評等") + "（" + "%.1f".format(it) + "）" }
        a.target?.let { t ->
            val up = (t / price - 1) * 100
            parts += (50 + up * 1.5).coerceIn(10.0, 95.0)
            notes += "目標價 " + "%.2f".format(t) + "（" + "%+.0f".format(up) + "%）"
        }
        if (parts.isEmpty()) return null
        a.count?.let { notes += "$it 位分析師" }
        return Factor("分析師", clamp(parts.average()), 10, notes.joinToString("、"))
    }

    private fun clamp(v: Double) = v.coerceIn(0.0, 100.0).roundToInt()

    fun label(score: Int): Pair<String, String> = when {
        score >= 70 -> "偏多" to "多數指標正向，可考慮分批布局；仍需設定停損與控制部位"
        score >= 58 -> "中性偏多" to "趨勢或基本面略佔優勢，適合拉回分批或續抱"
        score >= 45 -> "中性" to "多空訊號互見，建議觀望或維持原部位"
        score >= 33 -> "中性偏空" to "弱勢指標較多，新資金宜保守，持有者留意支撐"
        else -> "偏空" to "趨勢與籌碼偏弱，宜降低部位或等待轉強訊號"
    }

    private fun trendFactor(c: List<Float>, price: Double, symbol: String): Factor? {
        if (c.size < 25) return null
        val ma20 = Indicators.sma(c, 20).lastOrNull()?.toDouble()
        val ma60 = Indicators.sma(c, 60).lastOrNull()?.toDouble()
        val ma120 = Indicators.sma(c, 120).lastOrNull()?.toDouble()
        val ma20prev = Indicators.sma(c, 20).getOrNull(c.size - 6)?.toDouble()
        var s = 0.0; var n = 0
        val notes = ArrayList<String>()
        listOf("月線" to ma20, "季線" to ma60, "半年線" to ma120).forEach { (name, m) ->
            if (m != null && m > 0) {
                n++
                if (price >= m) { s += 1; notes += "站上$name" } else notes += "跌破$name"
            }
        }
        if (n == 0) return null
        var score = s / n * 80
        if (ma20 != null && ma20prev != null && ma20prev > 0) {
            if (ma20 > ma20prev) { score += 20; notes += "月線上彎" } else notes += "月線下彎"
        }
        AppState.keyPrices[symbol]?.let { k ->
            when {
                price >= k.upper -> { score += 5; notes += "站上上關" }
                price < k.lower -> { score -= 10; notes += "跌破下關" }
            }
        }
        return Factor("趨勢", clamp(score), 25, notes.joinToString("、"))
    }

    private fun momentumFactor(c: List<Float>): Factor? {
        if (c.size < 21) return null
        val rsi = Indicators.rsi(c, 14).lastOrNull()?.toDouble() ?: return null
        val r20 = (c.last().toDouble() / c[c.size - 21] - 1) * 100
        val base = when {
            rsi >= 80 -> 35.0      // 過熱
            rsi >= 70 -> 55.0
            rsi >= 50 -> 50 + (rsi - 50) * 1.5
            rsi >= 30 -> 25 + (rsi - 30) * 1.25
            else -> 45.0           // 超賣，反彈機會
        }
        val score = base + r20.coerceIn(-10.0, 10.0)
        val tag = when { rsi >= 80 -> "過熱"; rsi >= 70 -> "偏強"; rsi >= 50 -> "多方"; rsi >= 30 -> "空方"; else -> "超賣" }
        return Factor("動能", clamp(score), 15, "RSI " + "%.0f".format(rsi) + "（$tag）、近 20 日 " + "%+.1f".format(r20) + "%")
    }

    private fun valuationFactor(q: Quote): Factor? {
        if (q.market != Market.TW) return null
        val v = runCatching { TwOpenData.valuation(q.symbol) }.getOrNull() ?: return null
        val pe = v.pe?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 }
        val y = v.yieldPct?.replace(",", "")?.toDoubleOrNull()?.takeIf { it >= 0 }
        val parts = ArrayList<Double>()
        val notes = ArrayList<String>()
        pe?.let {
            parts += when { it < 10 -> 85.0; it < 15 -> 72.0; it < 20 -> 58.0; it < 30 -> 42.0; else -> 28.0 }
            notes += "本益比 " + "%.1f".format(it)
        }
        y?.let {
            parts += when { it >= 6 -> 85.0; it >= 4.5 -> 72.0; it >= 3 -> 58.0; it >= 1.5 -> 45.0; else -> 35.0 }
            notes += "殖利率 " + "%.2f".format(it) + "%"
        }
        if (parts.isEmpty()) return null
        return Factor("估值", clamp(parts.average()), 20, notes.joinToString("、"))
    }

    private fun rangeFactor(h: YahooExtra.History, price: Double): Factor? {
        val hi = h.highs.filter { !it.isNaN() && it > 0 }.maxOrNull()?.toDouble() ?: h.closes.filter { !it.isNaN() }.maxOrNull()?.toDouble() ?: return null
        val lo = h.lows.filter { !it.isNaN() && it > 0 }.minOrNull()?.toDouble() ?: h.closes.filter { !it.isNaN() }.minOrNull()?.toDouble() ?: return null
        if (hi <= lo) return null
        val pos = ((price - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        val score = 85 - pos * 55
        return Factor("位置", clamp(score), 10, "位於 52 週區間 " + "%.0f".format(pos * 100) + "%（低 " + "%.2f".format(lo) + "／高 " + "%.2f".format(hi) + "）")
    }

    private fun chipsFactor(q: Quote): Factor? {
        if (q.market != Market.TW) return null
        val days = runCatching { TwInstitutional.recent(q.symbol, 5) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val lots = days.sumOf { it.total } / 1000.0
        val foreign = days.sumOf { it.foreign } / 1000.0
        val buyDays = days.count { it.total > 0 }
        val score = 50 + (buyDays - days.size / 2.0) * 12 + (if (lots > 0) 8 else if (lots < 0) -8 else 0)
        return Factor("籌碼", clamp(score), 15, "法人近 ${days.size} 日 " + "%+,.0f".format(lots) + " 張（外資 " + "%+,.0f".format(foreign) + "），買超 $buyDays 天")
    }

    private fun marketFactor(q: Quote): Factor? {
        val sym = if (q.market == Market.TW) "^TWII" else "^GSPC"
        val name = if (q.market == Market.TW) "加權指數" else "S&P 500"
        val h = runCatching { YahooExtra.history(sym, "6mo") }.getOrNull() ?: return null
        val c = h.closes.filter { !it.isNaN() }
        if (c.size < 60) return null
        val last = (AppState.twIndices + AppState.usIndices).firstOrNull { it.symbol == sym }?.value?.takeIf { it > 0 } ?: c.last().toDouble()
        val ma60 = c.takeLast(60).average()
        val ma20 = c.takeLast(20).average()
        var score = if (last >= ma60) 65.0 else 35.0
        if (last >= ma20) score += 15 else score -= 10
        return Factor("市場", clamp(score), 10, name + (if (last >= ma60) " 在季線之上" else " 在季線之下") + if (last >= ma20) "、站上月線" else "、跌破月線")
    }

    private fun riskFactor(c: List<Float>): Factor? {
        if (c.size < 30) return null
        val rets = (1 until c.size).map { ln(c[it].toDouble() / c[it - 1]) }.filter { !it.isNaN() && !it.isInfinite() }
        if (rets.size < 20) return null
        val m = rets.average()
        val vol = sqrt(rets.sumOf { (it - m) * (it - m) } / (rets.size - 1)) * sqrt(252.0) * 100
        val score = when { vol < 15 -> 80.0; vol < 25 -> 65.0; vol < 35 -> 50.0; vol < 50 -> 35.0; else -> 25.0 }
        return Factor("風險", clamp(score), 5, "年化波動 " + "%.1f".format(vol) + "%")
    }

    fun isEtf(q: Quote, quoteType: String? = null) =
        q.isEtf || (q.market == Market.TW && q.symbol.startsWith("00")) || quoteType.equals("ETF", true)

    private suspend fun etfInfo(q: Quote, h: YahooExtra.History, price: Double): EtfInfo {
        val div = runCatching { StockInfoRepo.dividends(q) }.getOrNull()
        val sum = runCatching { YahooExtra.summary(StockInfoRepo.yahooSymbol(q), listOf("summaryDetail", "defaultKeyStatistics", "fundProfile")) }.getOrNull()
        fun raw(mod: String, k: String) = sum?.optJSONObject(mod)?.optJSONObject(k)?.optDouble("raw", Double.NaN)?.takeIf { !it.isNaN() && it > 0 }
        val vols = h.volumes.takeLast(20).filter { it > 0 }
        val avgVol = vols.takeIf { it.isNotEmpty() }?.average()?.let { if (q.market == Market.TW) it / 1000 else it }
        val nav = raw("summaryDetail", "navPrice")
        val expense = sum?.optJSONObject("fundProfile")?.optJSONObject("feesExpensesInvestment")?.optJSONObject("annualReportExpenseRatio")
            ?.optDouble("raw", Double.NaN)?.takeIf { !it.isNaN() && it > 0 }?.let { it * 100 }
        val filled = div?.fillDays?.values
        return EtfInfo(
            yieldPct = div?.yieldPct,
            filled = filled?.count { it != null },
            divCount = filled?.size?.takeIf { it > 0 },
            avgFillDays = filled?.filterNotNull()?.takeIf { it.isNotEmpty() }?.average(),
            avgVolume = avgVol,
            assets = raw("summaryDetail", "totalAssets") ?: raw("defaultKeyStatistics", "totalAssets"),
            expensePct = expense,
            nav = nav,
            premiumPct = nav?.let { (price / it - 1) * 100 },
        )
    }

    private fun etfFactors(e: EtfInfo, q: Quote): List<Factor> {
        val out = ArrayList<Factor>()
        // 配息：殖利率＋填息率
        if (e.yieldPct != null || e.divCount != null) {
            val parts = ArrayList<Double>(); val notes = ArrayList<String>()
            e.yieldPct?.let { y ->
                parts += when { y >= 7 -> 80.0; y >= 5 -> 72.0; y >= 3 -> 60.0; y >= 1 -> 48.0; else -> 40.0 }
                notes += "殖利率 " + "%.2f".format(y) + "%"
            }
            if (e.divCount != null && e.filled != null) {
                parts += 30.0 + 60.0 * e.filled / e.divCount
                notes += "填息 ${e.filled}/${e.divCount}" + (e.avgFillDays?.let { "（平均 " + "%.0f".format(it) + " 天）" } ?: "")
            }
            if (parts.isNotEmpty()) out += Factor("配息", clamp(parts.average()), 10, notes.joinToString("、"))
        }
        // 流動性：日均量
        e.avgVolume?.let { v ->
            val tw = q.market == Market.TW
            val score = if (tw) when { v >= 10_000 -> 85.0; v >= 3_000 -> 72.0; v >= 1_000 -> 60.0; v >= 200 -> 45.0; else -> 30.0 }
            else when { v >= 5e6 -> 85.0; v >= 1e6 -> 72.0; v >= 2e5 -> 58.0; v >= 5e4 -> 45.0; else -> 30.0 }
            out += Factor("流動性", clamp(score), 5, "近 20 日均量 " + (if (tw) "%,.0f 張".format(v) else "%,.0f 股".format(v)) + if (score < 50) "，買賣價差可能較大" else "")
        }
        // 規模與費用率
        if (e.assets != null || e.expensePct != null) {
            val parts = ArrayList<Double>(); val notes = ArrayList<String>()
            e.expensePct?.let { x ->
                parts += when { x <= 0.2 -> 85.0; x <= 0.5 -> 70.0; x <= 1.0 -> 52.0; else -> 35.0 }
                notes += "費用率 " + "%.2f".format(x) + "%"
            }
            e.assets?.let { a ->
                parts += when { a >= 1e10 -> 80.0; a >= 1e9 -> 65.0; a >= 1e8 -> 50.0; else -> 35.0 }
                notes += "規模 " + if (a >= 1e8) "%,.0f 億".format(a / 1e8) else "%,.0f 萬".format(a / 1e4)
            }
            out += Factor("規模費用", clamp(parts.average()), 5, notes.joinToString("、"))
        }
        // 折溢價：溢價過高代表買貴
        e.premiumPct?.let { p ->
            val score = when { p > 2 -> 25.0; p > 1 -> 40.0; p > 0.3 -> 55.0; p >= -0.3 -> 72.0; p >= -1 -> 70.0; else -> 60.0 }
            out += Factor("折溢價", clamp(score), 10, (if (p >= 0) "溢價 " else "折價 ") + "%.2f".format(kotlin.math.abs(p)) + "%（淨值 " + "%.2f".format(e.nav) + "）")
        }
        return out
    }

    suspend fun analyze(q: Quote): Result = withContext(Dispatchers.IO) {
        cached(q.symbol)?.let { return@withContext it }
        val h = YahooExtra.history(StockInfoRepo.yahooSymbol(q), "1y")
        val raw = h.closes.filter { !it.isNaN() && it > 0 }
        val price = q.price.takeIf { it > 0 } ?: raw.lastOrNull()?.toDouble() ?: throw IllegalStateException("沒有 ${q.symbol} 的價格")
        // 最後一根換成目前行情價，盤中分數會跟著現價走
        val c = if (raw.isNotEmpty()) raw.dropLast(1) + price.toFloat() else raw
        // 個股看分析師共識；ETF 沒有分析師，改用 ETF 指標
        val etf = if (isEtf(q)) etfInfo(q, h, price) else null
        val an = if (etf == null) analyst(q) else null
        val factors = (etf?.let { etfFactors(it, q) } ?: emptyList()) + listOfNotNull(
            analystFactor(an, price),
            trendFactor(c, price, q.symbol),
            momentumFactor(c),
            valuationFactor(q),
            rangeFactor(h, price),
            chipsFactor(q),
            marketFactor(q),
            riskFactor(c),
        )
        if (factors.isEmpty()) throw IllegalStateException("歷史資料不足，無法分析")
        val wsum = factors.sumOf { it.weight }
        val score = (factors.sumOf { it.score * it.weight }.toDouble() / wsum).roundToInt()
        val (label, advice) = label(score)
        Result(score, label, advice, factors, System.currentTimeMillis(), an, etf).also { r -> withContext(Dispatchers.Main) { results[q.symbol] = r } }
    }

}
