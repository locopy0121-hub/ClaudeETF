package com.claudeetf.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 綜合進場建議：把所有可取得的分析結果換算成同一把尺（−2 偏空 … +2 偏多）再加權彙總，
 * 不讓任何單一因素決定結論（單一來源權重上限 18%），並以「各來源方向是否一致」計算信心度。
 *
 * 來源：
 *   K 線（日、週）形態與量價、投資分析指數各因子（趨勢、動能、估值、位置、籌碼、大盤、分析師、
 *   ETF 配息／折溢價／流動性／規模費用）、一年股價光譜、波動（ATR、年化波動與波動是否擴大）、
 *   K 棒條件（RSI 超賣、KD 低檔交叉、負乖離、接近 52 週低點）、三關價位置、月營收年增、融資增減。
 */
object EntryAdvisor {
    data class Source(val name: String, val group: String, val score: Double, val weight: Double, val note: String) {
        /** 實際影響（權重正規化後） */
        var share: Double = 0.0
    }

    data class Vola(val atrPct: Double, val annual: Double, val regime: Double, val label: String)

    data class Advice(
        val entry: KlineAnalysis.Entry,
        /** −2 … +2 */
        val total: Double,
        /** 0–100：50 中性 */
        val score: Int,
        /** 0–100：各來源方向一致程度 × 資料完整度 */
        val confidence: Int,
        /** 建議現在先投入比例（%） */
        val ratio: Int,
        val sources: List<Source>,
        val pros: List<String>,
        val cons: List<String>,
        val conflicts: List<String>,
        val guards: List<String>,
        val vola: Vola?,
        val plan: KlineAnalysis.Plan?,
        val complete: Boolean,
        val at: Long,
    )

    val results = androidx.compose.runtime.mutableStateMapOf<String, Advice>()

    /** 大盤背景：指數收盤序列（相對強弱用）、VIX 恐慌指數、當日類股漲跌 */
    data class Macro(val twii: List<Double>, val spx: List<Double>, val vix: List<Double>, val sectors: Map<String, Double>, val at: Long)
    @Volatile private var macro: Macro? = null
    private val macroLock = kotlinx.coroutines.sync.Mutex()

    suspend fun macro(): Macro? = macroLock.withLock {
        macro?.takeIf { System.currentTimeMillis() - it.at < 30 * 60_000L }?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            fun cl(sym: String, range: String) = runCatching { com.claudeetf.app.data.remote.YahooExtra.history(sym, range) }.getOrNull()
                ?.closes?.map { it.toDouble() }?.filter { !it.isNaN() && it > 0 } ?: emptyList()
            val m = Macro(cl("^TWII", "6mo"), cl("^GSPC", "6mo"), cl("^VIX", "3mo"),
                runCatching { com.claudeetf.app.data.remote.TwOpenData.sectorIndices().toMap() }.getOrDefault(emptyMap()), System.currentTimeMillis())
            if (m.twii.isNotEmpty() || m.vix.isNotEmpty()) macro = m
            macro
        }
    }

    private val gate = Semaphore(3)
    private val weekly = java.util.concurrent.ConcurrentHashMap<String, KlineAnalysis.Result>()
    private val clampS = { v: Double -> v.coerceIn(-2.0, 2.0) }

    /** 投資分析指數：清單用的結果 60 分鐘內沿用，個股頁 10 分鐘 */
    private suspend fun invest(q: Quote, maxAgeMs: Long): InvestAnalysis.Result? {
        InvestAnalysis.results[q.symbol]?.takeIf { System.currentTimeMillis() - it.at < maxAgeMs }?.let { return it }
        return gate.withPermit { runCatching { InvestAnalysis.analyze(q) }.getOrNull() } ?: InvestAnalysis.results[q.symbol]
    }

    /** full = true：個股頁（加上週 K、較新的投資分析）；false：清單標籤 */
    suspend fun load(q: Quote, full: Boolean = false): Advice? {
        if (q.price <= 0) return results[q.symbol]
        val k = runCatching { KlineAnalysis.load(q, false) }.getOrNull()
        val inv = invest(q, if (full) 10 * 60_000L else 60 * 60_000L)
        // 週 K：個股頁載入後記住，清單沿用（同一檔在清單與個股頁的結論一致）
        val kw = if (full) runCatching { KlineAnalysis.load(q, true, 60 * 60_000L) }.getOrNull()?.also { weekly[q.symbol] = it } else weekly[q.symbol]
        val mac = runCatching { macro() }.getOrNull()
        val a = withContext(Dispatchers.Default) { compute(q, k, kw, inv, mac) } ?: return results[q.symbol]
        withContext(Dispatchers.Main) { results[q.symbol] = a }
        return a
    }

    private fun factorScore(s: Int) = clampS((s - 50) / 25.0)

    /** 波動：ATR(14)/價、近 20 日年化波動，與近一年的 20 日波動中位數比較 */
    fun volatility(cs: List<KlineAnalysis.Candle>): Vola? {
        if (cs.size < 60) return null
        val r = (1 until cs.size).map { ln(cs[it].c / cs[it - 1].c) }
        fun sd(l: List<Double>): Double { val m = l.average(); return sqrt(l.sumOf { (it - m) * (it - m) } / (l.size - 1).coerceAtLeast(1)) }
        val cur = sd(r.takeLast(20)) * sqrt(250.0)
        val hist = (20..r.size).filter { it % 5 == 0 }.map { sd(r.subList(it - 20, it)) * sqrt(250.0) }.takeLast(50)
        val med = hist.sorted().let { if (it.isEmpty()) cur else it[it.size / 2] }
        val atr = (cs.size - 14 until cs.size).map { i ->
            val p = cs[i - 1].c; maxOf(cs[i].h - cs[i].l, abs(cs[i].h - p), abs(cs[i].l - p))
        }.average() / cs.last().c * 100
        val regime = if (med > 0) cur / med else 1.0
        val label = when {
            regime >= 1.6 -> "波動急遽放大"
            regime >= 1.25 -> "波動放大"
            regime <= 0.7 -> "波動收斂"
            else -> "波動正常"
        }
        return Vola(atr, cur * 100, regime, label)
    }

    fun compute(q: Quote, k: KlineAnalysis.Result?, kw: KlineAnalysis.Result?, inv: InvestAnalysis.Result?, mac: Macro? = null): Advice? {
        if (k == null && inv == null) return null
        val src = ArrayList<Source>()
        // ---- K 線（日、週）
        val etf = inv?.etf != null || k?.etf == true
        k?.let {
            // ETF 均值回歸、短線形態雜訊多：日 K 權重較低；普通股較高
            src += Source("K 線（日）", "技術", clampS(it.score / 1.2), if (etf) 12.0 else 16.0, it.dir.label + " · " + it.entry.label + "（強度 " + it.strength + "）")
            // K 棒策略訊號（普通股：SFP、BOS／FVG、箱型突破、均線吞噬；ETF：母子線、上升三法、恐慌止跌、缺口回補）
            val act = it.signals.filter { s -> s.age <= 2 }
            if (act.isNotEmpty()) {
                val v = act.sumOf { s ->
                    val w = s.confidenceScore / 100.0 * (if (s.status == KBarSignalEngine.Status.WAITING) 0.4 else 1.0) * (if (s.age == 0) 1.0 else 0.7)
                    if (s.bullish) w * 1.6 else -w * 1.6
                }
                src += Source("K 棒策略", "技術", clampS(v), 10.0,
                    (if (etf) "ETF 引擎：" else "普通股引擎：") + act.joinToString("、") { s -> s.strategy.cn + (if (s.bullish) "（多" else "（空") + "，" + s.status.cn + "）" })
            }
        }
        kw?.let {
            src += Source("K 線（週）", "技術", clampS(it.score / 1.2), 12.0, it.dir.label + "（中期，強度 " + it.strength + "）")
        }
        // ---- 投資分析指數各因子（風險因子改由「波動」計算，避免重複）
        val fwBase = mapOf(
            "趨勢" to 11.0, "動能" to 7.0, "估值" to 11.0, "位置" to 5.0, "籌碼" to 10.0, "市場" to 8.0, "分析師" to 10.0,
            "股價光譜" to 13.0, "配息" to 8.0, "折溢價" to 6.0, "流動性" to 3.0, "規模費用" to 3.0,
        )
        // ETF：光譜（均值回歸）與大盤權重提高，動能降低
        val fw = if (etf) fwBase + mapOf("股價光譜" to 16.0, "市場" to 11.0, "動能" to 5.0) else fwBase
        val groupOf = mapOf("趨勢" to "技術", "動能" to "技術", "位置" to "價位", "股價光譜" to "價位", "估值" to "基本面", "分析師" to "基本面",
            "籌碼" to "籌碼", "市場" to "大盤", "配息" to "ETF", "折溢價" to "ETF", "流動性" to "ETF", "規模費用" to "ETF")
        inv?.factors?.forEach { f ->
            val w = fw[f.name] ?: return@forEach
            src += Source(f.name, groupOf[f.name] ?: "其他", factorScore(f.score), w, f.note)
        }
        val cs = k?.candles ?: emptyList()
        // ---- 相對強弱：近 20／60 日報酬減去大盤（台股對加權指數、美股對 S&P 500）
        mac?.let { m ->
            val idx = if (q.market == Market.TW) m.twii else m.spx
            fun ret(l: List<Double>, n: Int) = if (l.size > n) l.last() / l[l.size - 1 - n] - 1 else null
            val c = cs.map { it.c }
            val r20 = ret(c, 20); val i20 = ret(idx, 20); val r60 = ret(c, 60); val i60 = ret(idx, 60)
            if (r20 != null && i20 != null) {
                val rs20 = (r20 - i20) * 100; val rs60 = if (r60 != null && i60 != null) (r60 - i60) * 100 else null
                val v = rs20 / 6.0 + (rs60 ?: 0.0) / 15.0
                src += Source("相對強弱", "大盤", clampS(v), 7.0,
                    "近 20 日" + (if (rs20 >= 0) "強於" else "弱於") + (if (q.market == Market.TW) "加權指數 " else "S&P 500 ") + "%.1f".format(abs(rs20)) + " 個百分點" +
                        (rs60?.let { "，近 60 日 " + "%+.1f".format(it) + " 個百分點" } ?: ""))
            }
            // 類股當日表現（台股，依 ISIN 產業別對應證交所類股指數）
            if (q.market == Market.TW && m.sectors.isNotEmpty()) {
                val ind = StockDirectory.metaOf(q.symbol)?.industry?.removeSuffix("業")?.takeIf { it.isNotBlank() }
                val hit = ind?.let { n -> m.sectors.entries.firstOrNull { it.key == n || it.key.contains(n) || n.contains(it.key) } }
                if (hit != null) {
                    val rank = m.sectors.values.sortedDescending().indexOf(hit.value) + 1
                    src += Source("類股", "大盤", clampS(hit.value / 1.5), 4.0,
                        hit.key + "類股今日 " + "%+.2f".format(hit.value) + "%（${m.sectors.size} 類中第 $rank）")
                }
            }
            // VIX 恐慌指數：極度恐慌時逆勢分批（ETF 加分較多），恐慌升溫扣分，過度樂觀小扣
            if (m.vix.size >= 6) {
                val v = m.vix.last(); val v5 = m.vix[m.vix.size - 6]
                val rising = v > v5 * 1.15
                val s = when {
                    v >= 35 -> if (etf) 1.0 else 0.4
                    v >= 25 -> if (rising) -0.8 else -0.2
                    v < 14 -> -0.3
                    else -> 0.2
                }
                src += Source("VIX 恐慌指數", "大盤", s, 5.0,
                    "VIX " + "%.1f".format(v) + "（5 日前 " + "%.1f".format(v5) + "）" + when {
                        v >= 35 -> "：極度恐慌，長線可逆勢分批"
                        v >= 25 -> if (rising) "：恐慌升溫，先保守" else "：偏高但未擴大"
                        v < 14 -> "：市場過度樂觀，留意追高"
                        else -> "：正常"
                    })
            }
        }
        // ---- 波動
        val vola = volatility(cs)
        vola?.let { v ->
            val down = (k?.ctx?.trend ?: 0) < 0
            var s = when {
                v.regime >= 1.6 -> if (down) -1.6 else -0.9
                v.regime >= 1.25 -> if (down) -1.0 else -0.4
                v.regime <= 0.7 -> 0.4
                else -> 0.2
            }
            if (v.annual >= 60) s -= 0.5
            src += Source("波動", "風險", clampS(s), 8.0,
                v.label + "：近 20 日年化 " + "%.0f".format(v.annual) + "%（一年中位數的 " + "%.1f".format(v.regime) + " 倍）、ATR " + "%.1f".format(v.atrPct) + "%")
        }
        // ---- K 棒條件（超賣、低檔交叉等反向訊號，只在達成時加分）
        if (cs.size >= 30) {
            val ids = setOf("rsi", "kd", "bias", "low52")
            val met = runCatching { KlineConditions.evalStock(cs, KlineConditions.defs.filter { it.id in ids }) }.getOrDefault(emptyList()).filter { it.met }
            if (met.isNotEmpty()) src += Source("K 棒條件", "技術", clampS(0.6 * met.size), 6.0, "達成：" + met.joinToString("、") { it.def.name + " " + it.value })
        }
        // ---- 三關價
        AppState.keyPrices[q.symbol]?.let { kp ->
            val p = q.price
            val (s, n) = when {
                p >= kp.upper -> 0.9 to "站上上關 " + "%.2f".format(kp.upper)
                p >= kp.mid -> 0.4 to "位於中關～上關"
                p > kp.lower -> -0.3 to "位於下關～中關"
                else -> -1.0 to "跌破下關 " + "%.2f".format(kp.lower)
            }
            src += Source("三關價", "價位", s, 4.0, n)
        }
        // ---- 月營收、融資（台股，有資料時）
        AppState.twDaily[q.symbol]?.let { d ->
            d.revenue?.let { rv ->
                val yoy = rv.yoyPct?.replace("%", "")?.trim()?.toDoubleOrNull()
                val ytd = rv.ytdYoyPct?.replace("%", "")?.trim()?.toDoubleOrNull()
                val base = ytd ?: yoy
                if (base != null) {
                    val s = clampS(base / 15.0)
                    src += Source("月營收", "基本面", s, 7.0, rv.month + " 年增 " + (yoy?.let { "%+.1f".format(it) + "%" } ?: "—") +
                        (ytd?.let { "，累計年增 " + "%+.1f".format(it) + "%" } ?: ""))
                }
            }
            d.margin?.let { m ->
                val f = m.financing; val fp = m.financingPrev
                if (f != null && fp != null && fp > 0) {
                    val chg = (f - fp) / fp * 100
                    val priceUp = q.change >= 0
                    // 融資大增且股價下跌＝散戶接刀；融資減少＝籌碼沉澱
                    val s = when {
                        chg > 3 && !priceUp -> -1.0
                        chg > 3 -> -0.3
                        chg < -3 -> 0.4
                        else -> 0.0
                    }
                    if (s != 0.0) src += Source("融資", "籌碼", s, 4.0, "融資餘額單日 " + "%+.1f".format(chg) + "%")
                }
            }
        }
        if (src.isEmpty()) return null

        // ---- 權重正規化：單一來源最多 18%（避免單一因素主導）
        var ws = src.map { it.weight }
        repeat(4) {
            val sum = ws.sum(); val cap = sum * 0.18
            ws = ws.map { minOf(it, cap) }
        }
        val wsum = ws.sum()
        src.forEachIndexed { i, s -> s.share = ws[i] / wsum }
        var total = src.sumOf { it.score * it.share }

        // ---- 信心度：同方向的權重比例 × 資料完整度
        val decided = src.filter { abs(it.score) >= 0.25 }
        val agree = if (decided.isEmpty()) 0.5 else decided.filter { Math.signum(it.score) == Math.signum(total) }.sumOf { it.share } / decided.sumOf { it.share }
        val groups = src.map { it.group }.toSet()
        val coverage = (listOf("技術", "價位", "基本面", "籌碼", "大盤", "風險", "ETF").count { it in groups }.toDouble() / if (inv?.etf != null) 5.0 else 6.0).coerceAtMost(1.0)
        val complete = k != null && inv != null
        val confidence = ((agree * 0.75 + coverage * 0.25) * 100).toInt().coerceIn(5, 98)

        // ---- 分歧與保護規則（明列原因，不讓單一因素直接翻轉結論）
        val guards = ArrayList<String>()
        val conflicts = ArrayList<String>()
        val tech = src.filter { it.group == "技術" }.let { l -> if (l.isEmpty()) null else l.sumOf { it.score * it.share } / l.sumOf { it.share } }
        val value = src.filter { it.group == "價位" || it.group == "基本面" }.let { l -> if (l.isEmpty()) null else l.sumOf { it.score * it.share } / l.sumOf { it.share } }
        if (tech != null && value != null) {
            if (tech >= 0.5 && value <= -0.5) conflicts += "技術面偏多，但價位／基本面偏貴或偏弱：追價風險較高"
            if (tech <= -0.5 && value >= 0.5) conflicts += "價位便宜、基本面不差，但技術面仍偏空：可等止跌再分批"
        }
        val kLevel = k?.entry?.level ?: 0
        val zone = inv?.band?.zone
        var level = when {
            total >= 0.85 && agree >= 0.6 -> 2
            total >= 0.3 -> 1
            total > -0.3 -> 0
            total > -0.85 -> -1
            else -> -2
        }
        if (level == 2 && kLevel <= -1) { level = 1; guards += "短線 K 線仍偏空，降為分批" }
        if (level >= 1 && zone == 5) { level = minOf(level, 0); guards += "股價位於光譜「極貴」區，不建議追高" }
        if (level >= 1 && (vola?.regime ?: 1.0) >= 1.6 && (k?.ctx?.trend ?: 0) < 0) { level = 0; guards += "波動急遽放大且趨勢向下，先觀望" }
        if (level == -2 && zone != null && zone <= 1 && (tech ?: 0.0) > -1.2) { level = -1; guards += "價格已在光譜便宜區，避免過度悲觀" }
        if (!complete && level == 2) { level = 1; guards += "部分資料尚未取得，結論保守一級" }

        val bullRev = k?.latest?.any { it.idx == k.candles.lastIndex && it.pat.dir.bullish } == true
        val label = when (level) {
            2 -> "建議進場"
            1 -> "可分批進場"
            0 -> when {
                k?.entry?.label == "等突破觸發" -> "等突破觸發"
                (tech ?: 0.0) >= 0.4 && (value ?: 0.0) < 0 -> "等回測再進"
                bullRev && total < 0 -> "觀察止跌"
                else -> "觀望"
            }
            -1 -> if (bullRev) "等待止跌確認" else "暫不進場"
            else -> "不宜進場"
        }
        val tip = when (level) { 2 -> "可續抱，停利上移"; 1 -> "續抱"; 0 -> "續抱觀察"; -1 -> "暫不加碼"; else -> "建議減碼，嚴設停損" }

        // ---- 建議先投比例：依等級、信心度與波動調整
        val base = when (level) { 2 -> 80.0; 1 -> 50.0; 0 -> 20.0; -1 -> 5.0; else -> 0.0 }
        val volAdj = vola?.let { (1.0 / it.regime).coerceIn(0.6, 1.0) } ?: 1.0
        val ratio = (Math.round(base * (0.6 + 0.4 * confidence / 100.0) * volAdj / 5) * 5).toInt()

        val sorted = src.sortedByDescending { it.score * it.share }
        val pros = sorted.filter { it.score >= 0.4 }.take(4).map { it.name + "：" + it.note }
        val cons = sorted.reversed().filter { it.score <= -0.4 }.take(4).map { it.name + "：" + it.note }
        val reason = buildString {
            append("綜合 ${src.size} 項指標，偏多 ${src.count { it.score >= 0.25 }} 項、偏空 ${src.count { it.score <= -0.25 }} 項，信心度 $confidence%。")
            pros.firstOrNull()?.let { append("主要利多：" + it.substringBefore("：") + "。") }
            cons.firstOrNull()?.let { append("主要利空：" + it.substringBefore("：") + "。") }
            guards.firstOrNull()?.let { append(it + "。") }
        }
        total = clampS(total)
        return Advice(
            KlineAnalysis.Entry(label, level, reason, tip), total, ((total + 2) / 4 * 100).toInt().coerceIn(0, 100), confidence, ratio,
            src.sortedByDescending { abs(it.score * it.share) }, pros, cons, conflicts, guards, vola, k?.plan, complete, System.currentTimeMillis(),
        )
    }
}
