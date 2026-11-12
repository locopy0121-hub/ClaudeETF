package com.claudeetf.app.data

import com.claudeetf.app.data.KlineAnalysis.Candle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * AI 個股體檢：本地運算層（純 Kotlin）＋本地規則體檢。
 * 所有數字（均線、乖離、RSI、KD、量比、法人、估值、K 棒型態）都在這裡計算，
 * Claude 只負責解讀 [ComputedStockMetrics.toJson]；沒有金鑰或 API 失敗時改用 [localReport]。
 */
object StockMetrics {

    data class ComputedStockMetrics(
        val ticker: String, val name: String, val market: String, val isEtf: Boolean, val date: String,
        val close: Double, val prevClose: Double, val changePct: Double,
        val ma5: Double?, val ma10: Double?, val ma20: Double?, val ma60: Double?,
        val bias20Pct: Double?, val rsi14: Double?, val k: Double?, val d: Double?,
        val volumeRatioToMa5: Double?, val priceVolumeState: String, val isNewHigh20: Boolean,
        val patterns: List<String>, val patternNearMa: String?,
        val foreignNet5Lots: Long?, val trustNet5Lots: Long?, val dealerNet5Lots: Long?, val trustConsecutiveDays: Int?,
        val pe: Double?, val pb: Double?, val yieldPct: Double?, val priceBandZone: String?,
        val support: Double?, val resistance: Double?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ticker", ticker); put("name", name); put("market", market); put("isEtf", isEtf); put("latestTradingDate", date)
            put("close", close); put("prevClose", prevClose); put("changePct", r2(changePct))
            fun n(k: String, v: Double?) = put(k, v?.let { r2(it) } ?: JSONObject.NULL)
            n("ma5", ma5); n("ma10", ma10); n("ma20", ma20); n("ma60", ma60)
            n("bias20Pct", bias20Pct); n("rsi14", rsi14); n("kdK", k); n("kdD", d)
            n("volumeRatioToMa5", volumeRatioToMa5); put("priceVolumeState", priceVolumeState); put("isNewHigh20", isNewHigh20)
            put("candlestickPatterns", JSONArray(patterns)); put("patternNearMa", patternNearMa ?: JSONObject.NULL)
            put("foreignNet5Lots", foreignNet5Lots ?: JSONObject.NULL); put("trustNet5Lots", trustNet5Lots ?: JSONObject.NULL)
            put("dealerNet5Lots", dealerNet5Lots ?: JSONObject.NULL); put("trustConsecutiveDays", trustConsecutiveDays ?: JSONObject.NULL)
            n("pe", pe); n("pb", pb); n("yieldPct", yieldPct); put("priceBandZone", priceBandZone ?: JSONObject.NULL)
            n("support", support); n("resistance", resistance)
        }
    }

    data class StockAnalysisReport(
        val ticker: String, val overallSignal: String, val confidence: Int, val verdict: String,
        val trend: String, val candleInsight: String, val support: Double?, val resistance: Double?,
        val chipStatus: String, val chipSummary: String,
        val valuationStatus: String, val peerComparison: String, val riskWarning: String,
        /** "claude"：Claude 解讀；"local"：本地規則 */
        val source: String, val model: String = "", val at: Long = System.currentTimeMillis(),
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("ticker", ticker).put("overall_signal", overallSignal).put("confidence_score", confidence).put("one_line_verdict", verdict)
            .put("technical_analysis", JSONObject().put("trend", trend).put("candlestick_insight", candleInsight)
                .put("key_support", support ?: 0.0).put("key_resistance", resistance ?: 0.0))
            .put("chip_analysis", JSONObject().put("institutional_status", chipStatus).put("summary", chipSummary))
            .put("fundamental_valuation", JSONObject().put("valuation_status", valuationStatus).put("peer_comparison", peerComparison).put("risk_warning", riskWarning))
            .put("_source", source).put("_model", model).put("_at", at)

        companion object {
            /** 容錯解析：去掉 Markdown 圍欄，取第一個 { 到最後一個 } */
            fun parse(raw: String, source: String, model: String = ""): StockAnalysisReport? = runCatching {
                val t = raw.replace("```json", "").replace("```", "").trim()
                val o = JSONObject(t.substring(t.indexOf('{'), t.lastIndexOf('}') + 1))
                val ta = o.optJSONObject("technical_analysis") ?: JSONObject()
                val ca = o.optJSONObject("chip_analysis") ?: JSONObject()
                val fv = o.optJSONObject("fundamental_valuation") ?: JSONObject()
                StockAnalysisReport(
                    o.optString("ticker"), o.optString("overall_signal", "區間震盪"), o.optInt("confidence_score", 50).coerceIn(0, 100),
                    o.optString("one_line_verdict"),
                    ta.optString("trend"), ta.optString("candlestick_insight"),
                    ta.optDouble("key_support").takeIf { !it.isNaN() && it > 0 }, ta.optDouble("key_resistance").takeIf { !it.isNaN() && it > 0 },
                    ca.optString("institutional_status"), ca.optString("summary"),
                    fv.optString("valuation_status"), fv.optString("peer_comparison"), fv.optString("risk_warning"),
                    o.optString("_source", source), o.optString("_model", model), o.optLong("_at", System.currentTimeMillis()),
                )
            }.getOrNull()
        }
    }

    private fun r2(v: Double) = Math.round(v * 100) / 100.0

    // ------------------------------------------------------------------ 指標（純函數）

    fun ma(c: List<Double>, n: Int): Double? = if (c.size >= n) c.takeLast(n).average() else null

    fun rsi(c: List<Double>, n: Int = 14): Double? {
        if (c.size <= n) return null
        // Wilder 平滑
        var g = 0.0; var l = 0.0
        for (i in 1..n) { val d = c[i] - c[i - 1]; if (d > 0) g += d else l -= d }
        var ag = g / n; var al = l / n
        for (i in n + 1 until c.size) {
            val d = c[i] - c[i - 1]
            ag = (ag * (n - 1) + maxOf(d, 0.0)) / n
            al = (al * (n - 1) + maxOf(-d, 0.0)) / n
        }
        return if (al == 0.0) 100.0 else 100 - 100 / (1 + ag / al)
    }

    /** KD(9,3,3)：RSV＝(C－L9)/(H9－L9)×100，K＝⅔K₋₁＋⅓RSV，D＝⅔D₋₁＋⅓K，初值 50 */
    fun kd(cs: List<Candle>, n: Int = 9): Pair<Double, Double>? {
        if (cs.size < n) return null
        var k = 50.0; var d = 50.0
        for (i in n - 1 until cs.size) {
            val w = cs.subList(i - n + 1, i + 1)
            val hi = w.maxOf { it.h }; val lo = w.minOf { it.l }
            val rsv = if (hi > lo) (cs[i].c - lo) / (hi - lo) * 100 else 50.0
            k = k * 2 / 3 + rsv / 3
            d = d * 2 / 3 + k / 3
        }
        return k to d
    }

    fun volumeRatioToMa5(cs: List<Candle>): Double? {
        if (cs.size < 6) return null
        val avg = cs.subList(cs.size - 6, cs.size - 1).map { it.v }.average()
        return if (avg > 0) cs.last().v / avg else null
    }

    fun priceVolumeState(cs: List<Candle>, ratio: Double?): String {
        if (cs.size < 2 || ratio == null) return "資料不足"
        val up = cs.last().c >= cs[cs.size - 2].c
        return when {
            !up && ratio >= 2.0 -> "價跌爆量"
            up && ratio >= 2.0 -> "價漲爆量"
            up && ratio >= 1.0 -> "價漲量增"
            up -> "價漲量縮"
            ratio >= 1.0 -> "價跌量增"
            else -> "價跌量縮"
        }
    }

    // ------------------------------------------------------------------ 組合

    suspend fun compute(q: Quote): ComputedStockMetrics? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val k = runCatching { KlineAnalysis.load(q, false) }.getOrNull() ?: return@withContext null
        val cs = k.candles.takeLast(130)
        if (cs.size < 20) return@withContext null
        val c = cs.map { it.c }
        val close = c.last()
        val ma5 = ma(c, 5); val ma10 = ma(c, 10); val ma20 = ma(c, 20); val ma60 = ma(c, 60)
        val kdv = kd(cs)
        val ratio = volumeRatioToMa5(cs)
        val last = k.candles.lastIndex
        val pats = k.latest.filter { it.idx >= last - 2 }.distinctBy { it.pat }.map { it.pat.cn }
        val lowLast = cs.last().l
        val nearMa = listOfNotNull(ma10?.let { "MA10" to it }, ma20?.let { "MA20" to it }, ma60?.let { "MA60" to it })
            .firstOrNull { (_, m) -> abs(lowLast / m - 1) <= 0.02 || abs(close / m - 1) <= 0.02 }?.first
        val tw = q.market == Market.TW
        // 三大法人（台股）：近 5 個交易日
        var f5: Long? = null; var t5: Long? = null; var d5: Long? = null; var tCons: Int? = null
        if (tw) runCatching { com.claudeetf.app.data.remote.TwInstitutional.recent(q.symbol, 10) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { days ->
            val s = days.sortedByDescending { it.date }
            val five = s.take(5)
            f5 = five.sumOf { it.foreign } / 1000; t5 = five.sumOf { it.trust } / 1000; d5 = five.sumOf { it.dealer } / 1000
            tCons = s.takeWhile { it.trust > 0 }.size
        }
        // 估值
        var pe: Double? = null; var pb: Double? = null; var yld: Double? = null
        if (tw) runCatching { com.claudeetf.app.data.remote.TwOpenData.valuation(q.symbol) }.getOrNull()?.let { v ->
            fun num(s: String?) = s?.replace(",", "")?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
            pe = num(v.pe); pb = num(v.pb); yld = num(v.yieldPct)
        } else runCatching { com.claudeetf.app.data.remote.YahooExtra.peRatios(listOf(q.symbol))[q.symbol] }.getOrNull()?.let { pe = it }
        val band = InvestAnalysis.results[q.symbol]?.band
        val date = java.time.Instant.ofEpochMilli(cs.last().t).atZone(java.time.ZoneId.of(if (tw) "Asia/Taipei" else "America/New_York")).toLocalDate().toString()
        ComputedStockMetrics(
            q.symbol, q.name, if (tw) "TW" else "US", InvestAnalysis.isEtf(q), date,
            close, q.prevClose.takeIf { it > 0 } ?: c[c.size - 2], q.changePct,
            ma5, ma10, ma20, ma60, ma20?.let { (close / it - 1) * 100 }, rsi(c), kdv?.first, kdv?.second,
            ratio, priceVolumeState(cs, ratio), close >= c.takeLast(20).max(),
            pats, nearMa, f5, t5, d5, tCons, pe, pb, yld, band?.zoneName, k.ctx.support, k.ctx.resistance,
        )
    }

    // ------------------------------------------------------------------ 本地規則體檢（依 SKILL.md 規則）

    private val BOTTOM = setOf("多頭吞噬（陽包陰）", "鎚子線", "蜻蜓十字（T 字）", "晨星", "紅三兵")
    private val TOP = setOf("流星線", "空頭吞噬（陰包陽）", "墓碑十字（倒 T）", "暮星（夜星）")

    fun localReport(m: ComputedStockMetrics): StockAnalysisReport {
        val f = { v: Double -> com.claudeetf.app.util.Fmt.price(v) }
        val c = m.close
        val trend = when {
            m.ma20 != null && m.ma60 != null && c > m.ma20 && m.ma20 > m.ma60 -> "多頭趨勢"
            m.ma20 != null && m.ma60 != null && c < m.ma20 && m.ma20 < m.ma60 -> "空頭趨勢"
            else -> "區間震盪／均線糾結"
        }
        val rsi = m.rsi14; val bias = m.bias20Pct
        val pullback = trend == "多頭趨勢" && listOfNotNull(m.ma10, m.ma20).any { abs(c / it - 1) <= 0.02 } &&
            rsi != null && rsi in 40.0..55.0 && m.priceVolumeState == "價跌量縮"
        val overheat = (bias != null && bias > 12) || (rsi != null && rsi > 75)
        val bottom = m.patterns.filter { it in BOTTOM }
        val topP = m.patterns.filter { it in TOP }
        val bottomValid = bottom.isNotEmpty() && m.patternNearMa != null
        val distribution = (bias ?: 0.0) > 10 && topP.isNotEmpty() && m.priceVolumeState == "價跌爆量"
        val divergence = m.isNewHigh20 && m.priceVolumeState == "價漲量縮"

        val insight = buildString {
            append(trend)
            m.ma20?.let { append("，收盤 ${f(c)} " + (if (c >= it) "站上" else "跌破") + " MA20（${f(it)}）") }
            bias?.let { append("，20 日乖離 " + "%+.1f".format(it) + "%") }
            rsi?.let { append("，RSI " + "%.0f".format(it)) }
            if (m.k != null && m.d != null) append("，KD " + "%.0f".format(m.k) + "／" + "%.0f".format(m.d))
            append("。量價：${m.priceVolumeState}")
            m.volumeRatioToMa5?.let { append("（量比 " + "%.1f".format(it) + " 倍）") }
            append("。")
            if (m.patterns.isNotEmpty()) append("近期 K 棒：" + m.patterns.joinToString("、") + "。")
            if (bottomValid) append("止跌型態出現在 ${m.patternNearMa} 附近，屬有效止跌攻擊訊號。")
            if (pullback) append("多頭回檔至均線附近且量縮，屬回檔觀察買點。")
            if (divergence) append("股價創 20 日新高但價漲量縮，追價動能不足。")
        }

        val chipStatus: String; val chipSummary: String
        if (m.foreignNet5Lots == null || m.trustNet5Lots == null) {
            chipStatus = if (m.market == "US") "量能觀察" else "法人資料不足"
            chipSummary = "量比 " + (m.volumeRatioToMa5?.let { "%.1f".format(it) + " 倍" } ?: "—") + "，" + m.priceVolumeState +
                (if (m.market == "US") "（美股無三大法人資料，以量能判讀）。" else "。")
        } else {
            val fo = m.foreignNet5Lots; val tr = m.trustNet5Lots
            val above20 = m.ma20 != null && c >= m.ma20
            chipStatus = when {
                (m.trustConsecutiveDays ?: 0) >= 3 && tr > 0 -> "投信連續吸籌"
                fo < 0 && tr > 0 && above20 -> "土洋對作、內資主導換手"
                fo < 0 && tr < 0 && !above20 -> "法人籌碼鬆動"
                fo > 0 && tr > 0 -> "法人同步買超"
                else -> "法人分歧"
            }
            chipSummary = "近 5 日外資 " + "%+,d".format(fo) + " 張、投信 " + "%+,d".format(tr) + " 張" +
                (m.dealerNet5Lots?.let { "、自營商 " + "%+,d".format(it) + " 張" } ?: "") +
                "；投信連買 ${m.trustConsecutiveDays ?: 0} 日。"
        }

        val zone = m.priceBandZone
        val valuation = when {
            zone == null && m.pe == null -> "資料不足"
            zone in setOf("特價") || (m.pe != null && m.pe < 10) -> "低估"
            zone in setOf("便宜") || (m.pe != null && m.pe < 14) -> "合理偏低"
            zone in setOf("昂貴", "極貴") || (m.pe != null && m.pe > 30) -> "偏高"
            else -> "合理"
        }
        val peer = listOfNotNull(
            m.pe?.let { "本益比 " + "%.1f".format(it) }, m.pb?.let { "淨值比 " + "%.2f".format(it) },
            m.yieldPct?.let { "殖利率 " + "%.2f".format(it) + "%" + if (it >= 5) "（具下檔保護）" else "" },
            zone?.let { "一年光譜「$it」" },
        ).joinToString("、").ifEmpty { "估值資料不足" } + "。"
        val risks = buildList {
            if (overheat) add("短線乖離過大（" + listOfNotNull(bias?.let { "乖離 " + "%+.1f".format(it) + "%" }, rsi?.let { "RSI " + "%.0f".format(it) }).joinToString("、") + "），切勿盲目追高")
            if (distribution) add("高檔出現" + topP.joinToString("、") + "且價跌爆量，留意主力調節")
            else if (topP.isNotEmpty() && (bias ?: 0.0) > 5) add("相對高檔出現" + topP.joinToString("、") + "，留意轉弱")
            if (divergence) add("創高量縮，量價背離")
            if (chipStatus == "法人籌碼鬆動") add("法人同步撤出且跌破月線")
        }
        val signal = when {
            distribution || (trend == "空頭趨勢" && chipStatus == "法人籌碼鬆動") -> "偏空警示"
            trend == "空頭趨勢" -> "轉弱防守"
            pullback || (trend == "多頭趨勢" && bottomValid) -> "多頭回檔"
            trend == "多頭趨勢" && !overheat -> "強勢多頭"
            trend == "多頭趨勢" -> "強勢多頭"
            else -> "區間震盪"
        }
        var conf = 55
        if (trend != "區間震盪／均線糾結") conf += 10
        if (bottomValid || pullback) conf += 8
        if (chipStatus in setOf("投信連續吸籌", "法人同步買超") && signal in setOf("強勢多頭", "多頭回檔")) conf += 8
        if (chipStatus == "法人籌碼鬆動" && signal in setOf("偏空警示", "轉弱防守")) conf += 8
        if (overheat && signal == "強勢多頭") conf -= 10
        if (divergence) conf -= 5
        val verdict = when (signal) {
            "強勢多頭" -> if (overheat) "多頭但乖離過大，等拉回再布局" else "趨勢向上，沿均線偏多操作"
            "多頭回檔" -> "多頭回檔至均線，可分批觀察承接"
            "轉弱防守" -> "均線走空，保守防守為主"
            "偏空警示" -> "轉弱訊號明確，宜減碼避險"
            else -> "均線糾結，等突破方向再動作"
        }
        return StockAnalysisReport(
            m.ticker, signal, conf.coerceIn(30, 90), verdict.take(35), trend, insight, m.support, m.resistance,
            chipStatus, chipSummary, valuation, peer, if (risks.isEmpty()) "目前無重大異常訊號" else risks.joinToString("；") + "。", "local",
        )
    }
}
