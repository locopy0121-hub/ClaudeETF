package com.claudeetf.app.data

import com.claudeetf.app.data.KlineAnalysis.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * K 棒策略訊號引擎（普通股／ETF 分流）：
 *   普通股（高 Beta、籌碼洗盤）：SFP 流動性掃蕩、BOS 突破＋FVG 缺口回踩、箱型爆量突破、關鍵均線吞噬
 *   ETF（高關聯、均值回歸、波動壓縮）：母子線壓縮突破、上升三法中繼、恐慌大跌末端止跌、竭盡缺口回補
 * 每個訊號都有明確的進場價、進場時機類型與說明、停損、停利、風報比與信心分數，
 * 並追蹤成立後是否已觸發或失效（跌破停損）。
 */
object KBarSignalEngine {
    enum class SecurityType(val cn: String) { COMMON_STOCK("普通股"), ETF("ETF") }

    enum class StrategyType(val cn: String, val forEtf: Boolean) {
        SFP("流動性掃蕩（SFP）", false),
        BOS_FVG("突破＋缺口回踩（BOS／FVG）", false),
        BOX_BREAKOUT("箱型爆量突破", false),
        ENGULFING("關鍵均線吞噬", false),
        INSIDE_BAR("母子線壓縮突破", true),
        RISING_THREE_METHODS("上升三法中繼", true),
        PANIC_EXHAUSTION("恐慌大跌末端止跌", true),
        GAP_FILL("竭盡缺口回補", true),
    }

    enum class SignalDirection(val cn: String) { BULLISH("做多"), BEARISH("偏空／減碼") }

    enum class EntryTimingType(val cn: String, val description: String) {
        BAR_CLOSE_CONFIRMATION("收盤確認", "K 棒收盤前 5 分鐘或收盤價確認後進場"),
        NEXT_BAR_OPEN("次日開盤", "訊號 K 棒確認後，於下一根 K 棒開盤價進場"),
        INTRADAY_LIMIT_RETEST("限價回踩", "不追高，價格回踩關鍵價位（缺口、頸線、箱頂）時限價買進"),
        INTRADAY_PRICE_BREAKOUT("盤中突破", "價格盤中突破關鍵點位（例如母棒高點＋1 檔）時觸發"),
    }

    /** 訊號狀態 */
    enum class Status(val cn: String) {
        ACTIVE("今日成立"), RECENT("近日成立"), WAITING("等待觸發"), TRIGGERED("已觸發"),
    }

    data class TakeProfitTargets(val tp1: Double, val tp2: Double? = null)

    data class TradeSignal(
        val id: String,
        val symbol: String,
        val securityType: SecurityType,
        val strategy: StrategyType,
        val direction: SignalDirection,
        val entryPrice: Double,
        val stopLossPrice: Double,
        val takeProfitTargets: TakeProfitTargets,
        val riskRewardRatio: Double,
        val confidenceScore: Int,
        val entryTimingType: EntryTimingType,
        val entryTimingDescription: String,
        val timestamp: Long,
        /** 訊號 K 棒距今幾根（0 = 最新一根） */
        val age: Int = 0,
        val status: Status = Status.ACTIVE,
        /** 型態說明 */
        val pattern: String = "",
        /** 移動停利說明（ETF 順勢策略） */
        val trailing: String? = null,
    ) {
        val bullish get() = direction == SignalDirection.BULLISH
    }

    // ------------------------------------------------------------------ 指標

    fun sma(v: List<Double>, n: Int): List<Double?> = v.indices.map { i -> if (i + 1 < n) null else (i - n + 1..i).sumOf { v[it] } / n }

    fun atr(cs: List<Candle>, n: Int = 14, end: Int = cs.lastIndex): Double {
        val from = max(1, end - n + 1)
        if (end < 1) return cs[end].h - cs[end].l
        return (from..end).map { i -> val p = cs[i - 1].c; maxOf(cs[i].h - cs[i].l, abs(cs[i].h - p), abs(cs[i].l - p)) }.average()
    }

    fun rsi(c: List<Double>, n: Int = 14, end: Int = c.lastIndex): Double {
        if (end < n) return 50.0
        var g = 0.0; var l = 0.0
        for (i in end - n + 1..end) { val d = c[i] - c[i - 1]; if (d > 0) g += d else l -= d }
        if (l == 0.0) return 100.0
        val rs = (g / n) / (l / n)
        return 100 - 100 / (1 + rs)
    }

    private val Candle.body get() = abs(c - o)
    private val Candle.upper get() = h - max(o, c)
    private val Candle.lower get() = min(o, c) - l
    private val Candle.range get() = h - l
    private val Candle.bodyRatio get() = if (range > 0) body / range else 0.0
    private val Candle.bull get() = c > o
    private val Candle.bear get() = c < o
    /** 實體太小時用 5% 振幅當分母，避免十字線讓影線比失真 */
    private val Candle.bodyMin get() = max(body, range * 0.05)

    // ------------------------------------------------------------------ 主流程

    /**
     * symbol：代號；etf：是否 ETF（決定使用哪一套策略）；tick：最小升降單位（台股依證交所規則）。
     * 回傳近期仍有效的訊號（已跌破停損者排除），依成立時間新到舊、信心分數高到低排序。
     */
    fun analyze(symbol: String, cs: List<Candle>, etf: Boolean, tick: (Double) -> Double = { 0.01 }): List<TradeSignal> {
        if (cs.size < 30) return emptyList()
        val ctx = Ctx(symbol, cs, if (etf) SecurityType.ETF else SecurityType.COMMON_STOCK, tick)
        val out = ArrayList<TradeSignal>()
        if (etf) {
            out += insideBar(ctx); out += risingThree(ctx); out += panic(ctx); out += gapFill(ctx)
        } else {
            out += sfp(ctx); out += bosFvg(ctx); out += box(ctx); out += engulfing(ctx)
        }
        return out.filter { valid(cs, it) }.distinctBy { it.strategy to it.direction }
            .sortedWith(compareBy<TradeSignal> { it.age }.thenByDescending { it.confidenceScore })
    }

    /** 成立後收盤跌破（多）／站上（空）停損即失效 */
    private fun valid(cs: List<Candle>, s: TradeSignal): Boolean {
        val after = cs.takeLast(s.age.coerceAtLeast(0) + 1).drop(1)
        return if (s.bullish) after.none { it.c < s.stopLossPrice } && cs.last().c > s.stopLossPrice
        else after.none { it.c > s.stopLossPrice } && cs.last().c < s.stopLossPrice
    }

    private class Ctx(val symbol: String, val cs: List<Candle>, val type: SecurityType, val tickOf: (Double) -> Double) {
        val n = cs.size
        val closes = cs.map { it.c }
        val ma20 = sma(closes, 20)
        val ma60 = sma(closes, 60)
        val ma10 = sma(closes, 10)
        val vma20 = sma(cs.map { it.v }, 20)
        fun tick(p: Double) = tickOf(p)
        fun volRatio(i: Int): Double = vma20.getOrNull(i - 1)?.takeIf { it > 0 }?.let { cs[i].v / it } ?: 1.0
        fun atrAt(i: Int) = atr(cs, 14, i)
    }

    private fun f(v: Double) = com.claudeetf.app.util.Fmt.price(v)
    private fun rr(entry: Double, sl: Double, tp: Double) = if (abs(entry - sl) > 0) abs(tp - entry) / abs(entry - sl) else 0.0
    private fun conf(v: Double) = v.toInt().coerceIn(30, 95)

    private fun sig(
        x: Ctx, st: StrategyType, dir: SignalDirection, i: Int, entry: Double, sl: Double, tp1: Double, tp2: Double?,
        confidence: Int, timing: EntryTimingType, desc: String, pattern: String, status: Status? = null, trailing: String? = null,
    ) = TradeSignal(
        st.name + "_" + x.cs[i].t, x.symbol, x.type, st, dir, entry, sl, TakeProfitTargets(tp1, tp2), rr(entry, sl, tp1), confidence,
        timing, desc, x.cs[i].t, x.n - 1 - i, status ?: if (i == x.n - 1) Status.ACTIVE else Status.RECENT, pattern, trailing,
    )

    // ================================================================== 普通股

    /** SFP：刺穿前 20 根低點（高點）後收回，長下（上）影線 ≥ 1.5 倍實體 */
    private fun sfp(x: Ctx): List<TradeSignal> {
        val out = ArrayList<TradeSignal>()
        for (i in x.n - 3 until x.n) {
            if (i < 21) continue
            val c = x.cs[i]; val prev = x.cs.subList(i - 20, i)
            val swingLow = prev.minOf { it.l }; val swingHigh = prev.maxOf { it.h }
            if (c.l < swingLow && c.c > swingLow && c.lower >= 1.5 * c.bodyMin) {
                val entry = if (i == x.n - 1) c.c else x.cs[i + 1].o
                val sl = c.l - x.tick(c.l)
                val risk = entry - sl
                val tp1 = entry + risk * 2
                val tp2 = swingHigh.takeIf { it > tp1 }
                var cf = 70.0
                if (x.volRatio(i) >= 1.5) cf += 10
                if (c.c >= c.l + c.range * 0.66) cf += 5
                if ((x.ma60[i] ?: 0.0) < c.c) cf += 5
                out += sig(x, StrategyType.SFP, SignalDirection.BULLISH, i, entry, sl, tp1, tp2, conf(cf),
                    if (i == x.n - 1) EntryTimingType.BAR_CLOSE_CONFIRMATION else EntryTimingType.NEXT_BAR_OPEN,
                    if (i == x.n - 1) "收盤前 5 分鐘確認收盤價仍在前低 ${f(swingLow)} 之上，以收盤價 ${f(entry)} 進場；或次日開盤進場。"
                    else "訊號已於 ${x.n - 1 - i} 日前成立，以次日開盤價 ${f(entry)} 為進場參考；現價離進場價過遠時不追。",
                    "盤中跌破前 20 日低點 ${f(swingLow)} 後收回，下影線 ${f(c.lower)} ≥ 實體 1.5 倍（掃停損後拉回）。")
            }
            if (c.h > swingHigh && c.c < swingHigh && c.upper >= 1.5 * c.bodyMin) {
                val entry = c.c
                val sl = c.h + x.tick(c.h)
                val risk = sl - entry
                val tp1 = entry - risk * 2
                var cf = 68.0
                if (x.volRatio(i) >= 1.5) cf += 10
                if ((x.ma20[i] ?: Double.MAX_VALUE) > c.c) cf += 5
                out += sig(x, StrategyType.SFP, SignalDirection.BEARISH, i, entry, sl, tp1, swingLow.takeIf { it < tp1 }, conf(cf),
                    EntryTimingType.BAR_CLOSE_CONFIRMATION,
                    "持股者：收盤確認仍在前高 ${f(swingHigh)} 之下時減碼；空手者不進場。站回 ${f(sl)} 則訊號失效。",
                    "盤中突破前 20 日高點 ${f(swingHigh)} 後拉回，上影線 ≥ 實體 1.5 倍（假突破）。")
            }
        }
        return out
    }

    /** BOS＋FVG：長紅突破前 20 日高點（結構突破），第 1 根高點 < 第 3 根低點形成缺口，等回踩缺口限價買進 */
    private fun bosFvg(x: Ctx): List<TradeSignal> {
        val out = ArrayList<TradeSignal>()
        for (j in max(21, x.n - 12) until x.n - 2) {
            val b1 = x.cs[j]; val b3 = x.cs[j + 2]
            val atr = x.atrAt(j)
            val prevHigh = x.cs.subList(j - 20, j).maxOf { it.h }
            if (!(b1.bull && b1.bodyRatio >= 0.6 && b1.body >= atr * 0.9 && b1.c > prevHigh && b1.h < b3.l)) continue
            val top = b3.l; val mid = b1.h + (b3.l - b1.h) / 2
            val after = x.cs.subList(j + 3, x.n)
            val touched = after.any { it.l <= top }
            val sl = min(b1.o, b1.l) - x.tick(b1.l)
            val swing = x.cs.subList(j, x.n).maxOf { it.h }
            val entry = mid
            val tp1 = if (swing > entry + (entry - sl)) swing else entry + (entry - sl) * 2
            var cf = 72.0
            if (x.volRatio(j) >= 1.8) cf += 8
            if ((x.ma20[x.n - 1] ?: 0.0) < x.cs.last().c) cf += 5
            out += sig(x, StrategyType.BOS_FVG, SignalDirection.BULLISH, j + 2, entry, sl, tp1, null, conf(cf),
                EntryTimingType.INTRADAY_LIMIT_RETEST,
                if (touched) "價格已回踩缺口（${f(mid)}～${f(top)}），回踩不破可依限價進場；收盤跌破 ${f(sl)} 停損。"
                else "突破當下不追高。掛限價單等待回踩缺口頂部 ${f(top)} 或缺口 50% 位置 ${f(mid)} 買進。",
                "長紅突破前 20 日高點 ${f(prevHigh)}，並留下 ${f(b1.h)}～${f(b3.l)} 的不平衡缺口。",
                if (touched) Status.TRIGGERED else Status.WAITING)
        }
        return out.takeLast(1)
    }

    /** 箱型突破：盤整 ≥ 15 根（箱高 ≤ 12% 或 ≤ 4.5 倍 ATR），實體比 ≥ 0.6、量 ≥ 1.8 倍 20 日均量收在箱頂之上（或箱底之下） */
    private fun box(x: Ctx): List<TradeSignal> {
        val out = ArrayList<TradeSignal>()
        for (i in x.n - 3 until x.n) {
            if (i < 36) continue
            val c = x.cs[i]; val b = x.cs.subList(i - 15, i)
            val top = b.maxOf { it.h }; val bot = b.minOf { it.l }
            val atr = x.atrAt(i - 1)
            if ((top - bot) / bot > 0.12 && top - bot > atr * 4.5) continue
            val vr = x.volRatio(i)
            val h = top - bot
            if (c.c > top && c.bodyRatio >= 0.6 && vr >= 1.8) {
                val fresh = i == x.n - 1
                val entry = if (fresh) c.c else top
                val sl = if (fresh) (c.o + c.c) / 2 - x.tick(c.c) else top - atr * 0.5
                val tp1 = top + h
                var cf = 72.0 + min(10.0, (vr - 1.8) * 5)
                if ((x.ma60[i] ?: 0.0) < c.c) cf += 5
                out += sig(x, StrategyType.BOX_BREAKOUT, SignalDirection.BULLISH, i, entry, sl, max(tp1, entry + (entry - sl) * 1.5), top + h * 2, conf(cf),
                    if (fresh) EntryTimingType.BAR_CLOSE_CONFIRMATION else EntryTimingType.INTRADAY_LIMIT_RETEST,
                    if (fresh) "激進：收盤前 15 分鐘確認量能達標、實體站穩箱頂 ${f(top)}，以 ${f(entry)} 買進。穩健：1～3 天內等縮量回踩 ${f(top)} 不破再進。"
                    else "突破後第 ${x.n - 1 - i} 天：等縮量回踩箱頂 ${f(top)} 附近、盤中止跌時限價買進，跌破 ${f(sl)} 停損。",
                    "盤整 15 日（箱頂 ${f(top)}、箱底 ${f(bot)}）後爆量 ${"%.1f".format(vr)} 倍長紅突破；目標為箱高等倍映射。")
            }
            if (c.c < bot && c.bodyRatio >= 0.6 && vr >= 1.8) {
                val sl = max(bot, (c.o + c.c) / 2) + x.tick(bot)
                out += sig(x, StrategyType.BOX_BREAKOUT, SignalDirection.BEARISH, i, c.c, sl, bot - h, null, conf(70.0 + min(10.0, (vr - 1.8) * 5)),
                    EntryTimingType.BAR_CLOSE_CONFIRMATION,
                    "持股者：爆量跌破箱底 ${f(bot)}，收盤確認後減碼；站回 ${f(sl)} 才重新評估。空手者不進場。",
                    "盤整 15 日後爆量 ${"%.1f".format(vr)} 倍長黑跌破箱底。")
            }
        }
        return out
    }

    /** 關鍵均線吞噬：價格貼近 20 日或 60 日均線（1 倍 ATR 內），第 2 根實體完全包覆第 1 根 */
    private fun engulfing(x: Ctx): List<TradeSignal> {
        val out = ArrayList<TradeSignal>()
        for (i in x.n - 2 until x.n) {
            if (i < 61) continue
            val b1 = x.cs[i - 1]; val b2 = x.cs[i]
            val atr = x.atrAt(i)
            val ma = listOfNotNull(x.ma20[i]?.let { "20 日均線" to it }, x.ma60[i]?.let { "60 日均線" to it })
                .firstOrNull { (_, m) -> abs(b2.l - m) <= atr || abs(b2.h - m) <= atr || (b2.l <= m && b2.h >= m) } ?: continue
            val resist = x.cs.subList(i - 20, i).maxOf { it.h }
            val support = x.cs.subList(i - 20, i).minOf { it.l }
            if (b1.bear && b2.bull && b2.o <= b1.c && b2.c >= b1.o && b2.body > b1.body) {
                val fresh = i == x.n - 1
                val entry = if (fresh) b2.c else x.cs[i + 1].o
                val sl = b2.l - x.tick(b2.l)
                val tp1 = if (resist > entry + (entry - sl)) resist else entry + (entry - sl) * 2
                var cf = 68.0
                if (x.volRatio(i) >= 1.3) cf += 8
                if (b2.c > ma.second) cf += 6
                out += sig(x, StrategyType.ENGULFING, SignalDirection.BULLISH, i, entry, sl, tp1, null, conf(cf),
                    if (fresh) EntryTimingType.BAR_CLOSE_CONFIRMATION else EntryTimingType.NEXT_BAR_OPEN,
                    if (fresh) "吞噬紅 K 收盤確定（完全包覆前一根）時，於收盤前以 ${f(entry)} 進場，或次日開盤買進。"
                    else "吞噬於昨日成立，以今日開盤 ${f(entry)} 為進場參考。",
                    "在${ma.first}（${f(ma.second)}）附近出現多頭吞噬。")
            }
            if (b1.bull && b2.bear && b2.o >= b1.c && b2.c <= b1.o && b2.body > b1.body) {
                val sl = b2.h + x.tick(b2.h)
                val tp1 = if (support < b2.c - (sl - b2.c)) support else b2.c - (sl - b2.c) * 2
                out += sig(x, StrategyType.ENGULFING, SignalDirection.BEARISH, i, b2.c, sl, tp1, null, conf(if (b2.c < ma.second) 74.0 else 66.0),
                    EntryTimingType.BAR_CLOSE_CONFIRMATION,
                    "持股者：空頭吞噬收盤確定後減碼；站回 ${f(sl)} 視為失效。",
                    "在${ma.first}（${f(ma.second)}）附近出現空頭吞噬。")
            }
        }
        return out
    }

    // ================================================================== ETF

    /** 母子線：價格在 20 日均線上方，子棒完全在母棒內；盤中突破母棒高點＋1 檔觸發 */
    private fun insideBar(x: Ctx): List<TradeSignal> {
        val out = ArrayList<TradeSignal>()
        for (i in x.n - 3 until x.n) {
            if (i < 21) continue
            val m = x.cs[i - 1]; val k = x.cs[i]
            val ma = x.ma20[i] ?: continue
            if (!(k.h <= m.h && k.l >= m.l && k.c > ma)) continue
            val trigger = m.h + x.tick(m.h)
            val after = x.cs.subList(i + 1, x.n)
            val hit = after.any { it.h >= trigger }
            val sl = k.l - x.tick(k.l)
            val tp1 = trigger + (trigger - sl) * 2
            var cf = 70.0
            if ((x.ma60[i] ?: 0.0) < k.c) cf += 6
            if (k.range < m.range * 0.6) cf += 5
            out += sig(x, StrategyType.INSIDE_BAR, SignalDirection.BULLISH, i, trigger, sl, tp1, null, conf(cf),
                EntryTimingType.INTRADAY_PRICE_BREAKOUT,
                if (hit) "已盤中突破母棒高點 ${f(trigger)} 觸發；以子棒低點 ${f(sl)} 停損，收盤跌破 20 日均線（${f(ma)}）出場。"
                else "設定盤中觸發單：價格突破母棒高點 ${f(m.h)}＋1 檔（${f(trigger)}）瞬間進場；未突破前不動作。",
                "20 日均線上方的母子線（子棒 ${f(k.l)}～${f(k.h)} 收斂在母棒 ${f(m.l)}～${f(m.h)} 內），波動壓縮待表態。",
                if (hit) Status.TRIGGERED else Status.WAITING, "移動停利：收盤跌破 20 日均線出場")
        }
        return out.takeLast(1)
    }

    /** 上升三法：長紅 → 三根小實體不破長紅低點 → 長紅收盤超越第一根高點 */
    private fun risingThree(x: Ctx): List<TradeSignal> {
        val out = ArrayList<TradeSignal>()
        for (e in x.n - 2 until x.n) {
            if (e < 25) continue
            val b1 = x.cs[e - 4]; val mid = x.cs.subList(e - 3, e); val b5 = x.cs[e]
            val atr = x.atrAt(e - 4)
            if (!(b1.bull && b1.bodyRatio >= 0.6 && b1.body >= atr * 0.8)) continue
            if (!mid.all { it.body <= b1.body * 0.5 && it.l >= b1.l && it.h <= b1.h + atr * 0.3 }) continue
            if (!(b5.bull && b5.bodyRatio >= 0.5 && b5.c > b1.h)) continue
            val fresh = e == x.n - 1
            val entry = if (fresh) b5.c else x.cs[e + 1].o
            val sl = b1.l - x.tick(b1.l)
            out += sig(x, StrategyType.RISING_THREE_METHODS, SignalDirection.BULLISH, e, entry, sl, entry + (entry - sl) * 2, null,
                conf(if ((x.ma20[e] ?: 0.0) < b5.c) 78.0 else 68.0),
                if (fresh) EntryTimingType.BAR_CLOSE_CONFIRMATION else EntryTimingType.NEXT_BAR_OPEN,
                if (fresh) "第 5 根長紅收盤前確認已超越第 1 根高點 ${f(b1.h)}，於收盤以 ${f(entry)} 進場。" else "上升三法昨日確立，以今日開盤 ${f(entry)} 為參考。",
                "長紅後三根小 K 整理不破 ${f(b1.l)}，再以長紅突破 ${f(b1.h)}（多方中繼）。",
                trailing = "移動停利：沿 10 日均線（${f(x.ma10[e] ?: b5.c)}）或 20 日均線抱牢")
        }
        return out
    }

    /** 恐慌止跌：RSI(14) < 30，且出現爆量長下影線（下影 ≥ 2.5 倍實體、量 ≥ 2 倍均量）或早晨之星 */
    private fun panic(x: Ctx): List<TradeSignal> {
        val out = ArrayList<TradeSignal>()
        for (i in x.n - 3 until x.n) {
            if (i < 25) continue
            val c = x.cs[i]
            val r = min(KBarSignalEngine.rsi(x.closes, 14, i), KBarSignalEngine.rsi(x.closes, 14, i - 1))
            if (r >= 30) continue
            val hammer = c.lower >= 2.5 * c.bodyMin && x.volRatio(i) >= 2.0
            val a = x.cs[i - 2]; val b = x.cs[i - 1]
            val star = a.bear && a.bodyRatio >= 0.55 && b.body <= a.body * 0.4 && max(b.o, b.c) <= a.c + x.atrAt(i) * 0.3 &&
                c.bull && c.c >= (a.o + a.c) / 2
            if (!hammer && !star) continue
            val fresh = i == x.n - 1
            val entry = if (fresh) c.c else x.cs[i + 1].o
            val lowMin = x.cs.subList(i - 4, i + 1).minOf { it.l }
            val sl = lowMin - x.tick(lowMin)
            val ma60 = x.ma60[i]
            val tp1 = ma60?.takeIf { it > entry + (entry - sl) } ?: entry + (entry - sl) * 2
            out += sig(x, StrategyType.PANIC_EXHAUSTION, SignalDirection.BULLISH, i, entry, sl, tp1, null,
                conf(if (hammer && star) 85.0 else if (hammer) 78.0 else 74.0),
                if (fresh) EntryTimingType.BAR_CLOSE_CONFIRMATION else EntryTimingType.NEXT_BAR_OPEN,
                (if (fresh) "止跌 K 棒收盤確定時進場" else "止跌訊號 ${x.n - 1 - i} 日前成立，可在 ${f(entry)} 附近") +
                    "；ETF 大跌末端屬右側較安全的分批加碼點，建議分 2～3 批。停損 ${f(sl)}，目標回升至 60 日均線" + (ma60?.let { "（${f(it)}）" } ?: "") + "。",
                "RSI(14) ${"%.0f".format(r)} 超賣，" + (if (hammer) "爆量 ${"%.1f".format(x.volRatio(i))} 倍長下影線" else "早晨之星") + "止跌。")
        }
        return out.takeLast(1)
    }

    /** 竭盡缺口回補：下跌末端向下跳空，1～3 天內紅 K 收盤完全回補缺口 */
    private fun gapFill(x: Ctx): List<TradeSignal> {
        val out = ArrayList<TradeSignal>()
        for (g in max(22, x.n - 5) until x.n - 1) {
            val pre = x.cs[g - 1]; val gap = x.cs[g]
            if (!(gap.h < pre.l)) continue
            if ((x.ma20[g] ?: 0.0) <= gap.c && pre.c >= (x.ma20[g - 1] ?: 0.0)) continue // 需在下跌段
            val fillIdx = (g + 1..min(x.n - 1, g + 3)).firstOrNull { k -> x.cs[k].bull && x.cs[k].c > pre.l } ?: continue
            val fresh = fillIdx == x.n - 1
            val c = x.cs[fillIdx]
            val entry = if (fresh) c.c else x.cs[fillIdx + 1].o
            val sl = gap.l - x.tick(gap.l)
            val top = x.cs.subList(max(0, g - 20), g).maxOf { it.h }
            val tp1 = if (top > entry + (entry - sl)) top else entry + (entry - sl) * 2
            out += sig(x, StrategyType.GAP_FILL, SignalDirection.BULLISH, fillIdx, entry, sl, tp1, null,
                conf(if (x.volRatio(fillIdx) >= 1.3) 78.0 else 70.0),
                if (fresh) EntryTimingType.BAR_CLOSE_CONFIRMATION else EntryTimingType.NEXT_BAR_OPEN,
                if (fresh) "紅 K 已完全回補向下缺口（收在 ${f(pre.l)} 之上），收盤前以 ${f(entry)} 進場。" else "缺口回補已確立，以 ${f(entry)} 為進場參考。",
                "下跌末端跳空缺口（${f(gap.h)}～${f(pre.l)}）於 ${fillIdx - g} 日內被紅 K 回補，空方力竭。")
        }
        return out.takeLast(1)
    }
}
