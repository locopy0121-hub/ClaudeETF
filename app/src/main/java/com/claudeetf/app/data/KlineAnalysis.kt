package com.claudeetf.app.data

import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * K 線形態分析引擎
 * ---------------------------------------------------------------
 * 以參考範本為基礎精進：
 * 1. 形態加上「前趨勢」判斷（鎚子／吊人、流星／倒鎚、吞噬、晨星需要對應的前趨勢才算數），
 *    並排除十字線誤判成鎚子、極小 K 棒誤判成十字等情況。
 * 2. 平頂／平底各自判斷（範本用 else-if 導致平頂永遠不會出現）。
 * 3. 支撐壓力自動取前 20 根高低點與 MA60，不必手動輸入；新增帶量突破／跌破前高前低。
 * 4. 量價背離實際計算（價創 20 日新高但量縮）。
 * 5. 停損用 ATR 調整，避免固定百分比在高低波動股上失真；盈虧比依 ATR 與壓力位計算。
 * 6. 每種形態在該股自身歷史上回測「5 根 K 棒後」的勝率與平均報酬，作為可信度參考。
 * 7. 偏空時以「持股減碼／防守」角度給計畫（多數投資人只做多）。
 */
object KlineAnalysis {

    enum class Dir(val weight: Int, val label: String) {
        STRONG_BULL(2, "強烈偏多"), BULL(1, "偏多"), NEUTRAL(0, "中性"), BEAR(-1, "偏空"), STRONG_BEAR(-2, "強烈偏空");
        val bullish get() = weight > 0
        val bearish get() = weight < 0
    }

    enum class Cat(val label: String) { REVERSAL("反轉"), CONTINUATION("延續"), GAP("缺口/突破") }

    enum class Pat(val cn: String, val cat: Cat, val dir: Dir, val bars: Int) {
        MARUBOZU_BULL("大陽線（長紅 K）", Cat.CONTINUATION, Dir.STRONG_BULL, 1),
        MARUBOZU_BEAR("大陰線（長黑 K）", Cat.CONTINUATION, Dir.STRONG_BEAR, 1),
        HAMMER("鎚子線", Cat.REVERSAL, Dir.BULL, 1),
        INVERTED_HAMMER("倒鎚線", Cat.REVERSAL, Dir.BULL, 1),
        SHOOTING_STAR("流星線", Cat.REVERSAL, Dir.BEAR, 1),
        HANGING_MAN("吊人線", Cat.REVERSAL, Dir.BEAR, 1),
        DOJI("十字線", Cat.REVERSAL, Dir.NEUTRAL, 1),
        DOJI_DRAGONFLY("蜻蜓十字（T 字）", Cat.REVERSAL, Dir.BULL, 1),
        DOJI_GRAVESTONE("墓碑十字（倒 T）", Cat.REVERSAL, Dir.BEAR, 1),
        ENGULFING_BULL("多頭吞噬（陽包陰）", Cat.REVERSAL, Dir.STRONG_BULL, 2),
        ENGULFING_BEAR("空頭吞噬（陰包陽）", Cat.REVERSAL, Dir.STRONG_BEAR, 2),
        PIERCING("貫穿線（曙光初現）", Cat.REVERSAL, Dir.BULL, 2),
        DARK_CLOUD("烏雲罩頂", Cat.REVERSAL, Dir.BEAR, 2),
        HARAMI_BULL("多頭母子（陰孕陽）", Cat.REVERSAL, Dir.BULL, 2),
        HARAMI_BEAR("空頭母子（陽孕陰）", Cat.REVERSAL, Dir.BEAR, 2),
        TWEEZER_BOTTOM("平底（雙腳打底）", Cat.REVERSAL, Dir.BULL, 2),
        TWEEZER_TOP("平頂（雙頂受阻）", Cat.REVERSAL, Dir.BEAR, 2),
        MORNING_STAR("晨星", Cat.REVERSAL, Dir.STRONG_BULL, 3),
        EVENING_STAR("暮星（夜星）", Cat.REVERSAL, Dir.STRONG_BEAR, 3),
        THREE_SOLDIERS("紅三兵", Cat.CONTINUATION, Dir.STRONG_BULL, 3),
        THREE_CROWS("黑三鴉", Cat.CONTINUATION, Dir.STRONG_BEAR, 3),
        GAP_UP("向上跳空缺口", Cat.GAP, Dir.STRONG_BULL, 2),
        GAP_DOWN("向下跳空缺口", Cat.GAP, Dir.STRONG_BEAR, 2),
        BREAKOUT("帶量突破前高", Cat.GAP, Dir.STRONG_BULL, 1),
        BREAKDOWN("帶量跌破前低", Cat.GAP, Dir.STRONG_BEAR, 1),
        BULL_TRAP("假突破（多頭陷阱）", Cat.GAP, Dir.STRONG_BEAR, 1),
        BEAR_TRAP("假跌破（空頭陷阱）", Cat.GAP, Dir.STRONG_BULL, 1),
    }

    data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double) {
        val body get() = abs(c - o)
        val range get() = h - l
        val upper get() = h - max(o, c)
        val lower get() = min(o, c) - l
        val bull get() = c > o
        val bear get() = c < o
        val mid get() = (o + c) / 2
    }

    data class Hit(val pat: Pat, val idx: Int, val conf: Double, val low: Double, val high: Double, val note: String)

    enum class Vol(val label: String) {
        CONFIRMED("帶量（≥ 1.5 倍均量）"), DRY("量縮（≤ 0.6 倍均量）"),
        DIVERGENCE_TOP("量價背離：價創高、量縮"), DIVERGENCE_BOTTOM("量價背離：價創低、量縮"), NORMAL("量能正常"),
    }

    enum class Loc(val label: String) { SUPPORT("接近支撐"), RESISTANCE("接近壓力"), MID("區間中段"), ABOVE("突破壓力上方"), BELOW("跌破支撐下方") }

    data class Ctx(
        /** 1 多頭、-1 空頭、0 盤整 */
        val trend: Int,
        val ma20: Double,
        val ma60: Double?,
        val volMa20: Double,
        val atr: Double,
        val support: Double,
        val resistance: Double,
        val vol: Vol,
        val loc: Loc,
    ) {
        val trendLabel get() = when (trend) { 1 -> "多頭趨勢"; -1 -> "空頭趨勢"; else -> "盤整" }
    }

    /** 該形態在此股歷史上出現後 N 根的表現 */
    data class Stat(val count: Int, val winRate: Double, val avgRet: Double)

    data class Plan(
        val title: String,
        val trigger: String,
        val entryMin: Double,
        val entryMax: Double,
        val stop: Double,
        val tp1: Double,
        val tp2: Double,
        val rr: Double,
        val note: String,
    )

    data class Result(
        val timeframe: String,
        val candles: List<Candle>,
        val dir: Dir,
        val strength: Int,
        val score: Double,
        /** 最近 3 根 K 棒完成的形態（新到舊） */
        val latest: List<Hit>,
        /** 近 40 根出現過的形態（畫在圖上） */
        val recent: List<Hit>,
        val ctx: Ctx,
        val plan: Plan?,
        val stats: Map<Pat, Stat>,
        val forward: Int,
        val reasons: List<String>,
        val entry: Entry,
    )

    /** 進場建議：level 2 建議進場 … -2 不宜進場 */
    data class Entry(val label: String, val level: Int, val reason: String, val holderTip: String)

    // ---------------------------------------------------------------- 指標

    private fun sma(v: List<Double>, i: Int, n: Int): Double? = if (i + 1 < n) null else (i - n + 1..i).sumOf { v[it] } / n

    private fun atrAt(cs: List<Candle>, i: Int, n: Int = 14): Double {
        val from = max(1, i - n + 1)
        if (i < 1) return cs[i].range
        return (from..i).map { k -> maxOf(cs[k].h - cs[k].l, abs(cs[k].h - cs[k - 1].c), abs(cs[k].l - cs[k - 1].c)) }.average()
    }

    /** 前趨勢：看 i 之前那根（不含形態本身）相對 MA20 與 MA20 斜率 */
    private fun trendAt(closes: List<Double>, i: Int): Int {
        val j = i - 1
        val ma = sma(closes, j, 20) ?: return 0
        val maPrev = sma(closes, j - 5, 20) ?: return 0
        val c = closes[j]
        return when {
            c > ma && ma > maPrev -> 1
            c < ma && ma < maPrev -> -1
            // 近 5 根明顯下跌／上漲也算
            closes[j] < closes[max(0, j - 5)] * 0.97 -> -1
            closes[j] > closes[max(0, j - 5)] * 1.03 -> 1
            else -> 0
        }
    }

    // ---------------------------------------------------------------- 形態偵測

    /** 偵測「在第 i 根完成」的所有形態 */
    private fun detectAt(cs: List<Candle>, closes: List<Double>, i: Int): List<Hit> {
        if (i < 22) return emptyList()
        val out = ArrayList<Hit>()
        val c = cs[i]
        val p = cs[i - 1]
        val avgBody = (i - 10 until i).map { cs[it].body }.average().takeIf { it > 0 } ?: return out
        val avgRange = (i - 10 until i).map { cs[it].range }.average().takeIf { it > 0 } ?: return out
        val trend = trendAt(closes, i)
        val volMa = (i - 20 until i).map { cs[it].v }.average()
        val bigVol = volMa > 0 && c.v >= volMa * 1.5
        fun adj(base: Double, wantTrend: Int) = (base + if (trend == wantTrend) 0.05 else if (trend == -wantTrend) -0.25 else -0.1).coerceIn(0.3, 0.97)

        // ---- 單根 ----
        val isDoji = c.range > 0 && c.body / c.range <= 0.1 && c.range >= avgRange * 0.5
        if (c.range > 0 && !isDoji) {
            if (c.body >= avgBody * 1.8 && c.upper <= c.body * 0.15 && c.lower <= c.body * 0.15) {
                if (c.bull) out += Hit(Pat.MARUBOZU_BULL, i, if (bigVol) 0.88 else 0.78, c.l, c.h, "實體長紅、幾乎無影線，買盤主導" + if (bigVol) "，且帶量。" else "。")
                else out += Hit(Pat.MARUBOZU_BEAR, i, if (bigVol) 0.88 else 0.78, c.l, c.h, "實體長黑、幾乎無影線，賣壓主導" + if (bigVol) "，且帶量。" else "。")
            }
            val longLower = c.lower >= c.body * 2.0 && c.upper <= max(c.body * 0.5, c.range * 0.1) && c.range >= avgRange * 0.8
            val longUpper = c.upper >= c.body * 2.0 && c.lower <= max(c.body * 0.5, c.range * 0.1) && c.range >= avgRange * 0.8
            if (longLower) when (trend) {
                -1 -> out += Hit(Pat.HAMMER, i, 0.78, c.l, c.h, "下跌後出現長下影線，低檔有買盤承接；隔日收高確認更佳。")
                1 -> out += Hit(Pat.HANGING_MAN, i, 0.66, c.l, c.h, "上漲後出現長下影線，盤中曾遭大量賣壓，留意高檔轉弱。")
            }
            if (longUpper) when (trend) {
                1 -> out += Hit(Pat.SHOOTING_STAR, i, 0.78, c.l, c.h, "上漲後留長上影線，上方賣壓沉重。")
                -1 -> out += Hit(Pat.INVERTED_HAMMER, i, 0.6, c.l, c.h, "下跌後出現倒鎚，買盤試探反攻，需隔日紅 K 確認。")
            }
        }
        if (isDoji) when {
            c.lower >= c.range * 0.7 -> out += Hit(Pat.DOJI_DRAGONFLY, i, adj(0.72, -1), c.l, c.h, "蜻蜓十字（T 字），下方有強支撐。")
            c.upper >= c.range * 0.7 -> out += Hit(Pat.DOJI_GRAVESTONE, i, adj(0.72, 1), c.l, c.h, "墓碑十字（倒 T），上方壓力強。")
            else -> out += Hit(Pat.DOJI, i, 0.55, c.l, c.h, "十字線，多空拉鋸，" + (if (trend != 0) "趨勢中出現常為變盤訊號。" else "觀察下一根方向。"))
        }

        // ---- 雙根 ----
        val lo2 = min(p.l, c.l); val hi2 = max(p.h, c.h)
        if (p.bear && c.bull && p.body >= avgBody * 0.3 && c.c >= p.o && c.o <= p.c && c.body > p.body)
            out += Hit(Pat.ENGULFING_BULL, i, adj(0.86, -1), lo2, hi2, "陽線實體完全吞噬前一根陰線，多方反攻" + if (bigVol) "，且帶量。" else "。")
        if (p.bull && c.bear && p.body >= avgBody * 0.3 && c.c <= p.o && c.o >= p.c && c.body > p.body)
            out += Hit(Pat.ENGULFING_BEAR, i, adj(0.86, 1), lo2, hi2, "陰線實體完全吞噬前一根陽線，空方反撲" + if (bigVol) "，且帶量。" else "。")
        if (p.bear && p.body >= avgBody && c.bull && c.o <= p.c && c.c > p.mid && c.c < p.o)
            out += Hit(Pat.PIERCING, i, adj(0.78, -1), lo2, hi2, "開低後收復前一根長黑實體一半以上，買盤回籠。")
        if (p.bull && p.body >= avgBody && c.bear && c.o >= p.c && c.c < p.mid && c.c > p.o)
            out += Hit(Pat.DARK_CLOUD, i, adj(0.78, 1), lo2, hi2, "開高後跌破前一根長紅實體一半，賣壓湧現。")
        val inside = max(c.o, c.c) <= max(p.o, p.c) && min(c.o, c.c) >= min(p.o, p.c) && c.body <= p.body * 0.5
        if (inside && p.body >= avgBody) {
            if (p.bear) out += Hit(Pat.HARAMI_BULL, i, adj(0.68, -1), lo2, hi2, "長黑後出現小實體孕線，賣壓暫歇。")
            if (p.bull) out += Hit(Pat.HARAMI_BEAR, i, adj(0.68, 1), lo2, hi2, "長紅後出現小實體孕線，買氣轉弱。")
        }
        if (p.l > 0 && abs(p.l - c.l) / p.l <= 0.002 && trend == -1 && c.c > c.o)
            out += Hit(Pat.TWEEZER_BOTTOM, i, 0.72, lo2, hi2, "連兩根低點幾乎相同，低點獲得支撐。")
        if (p.h > 0 && abs(p.h - c.h) / p.h <= 0.002 && trend == 1 && c.c < c.o)
            out += Hit(Pat.TWEEZER_TOP, i, 0.72, lo2, hi2, "連兩根高點幾乎相同，上檔遇壓。")

        // ---- 三根 ----
        val a = cs[i - 2]; val b = p
        val lo3 = minOf(a.l, b.l, c.l); val hi3 = maxOf(a.h, b.h, c.h)
        if (a.bear && a.body >= avgBody && b.body <= a.body * 0.4 && max(b.o, b.c) <= a.c + a.body * 0.1 && c.bull && c.c > a.mid)
            out += Hit(Pat.MORNING_STAR, i, adj(0.9, -1), lo3, hi3, "長黑、小星、長紅三根組合，底部反轉訊號。")
        if (a.bull && a.body >= avgBody && b.body <= a.body * 0.4 && min(b.o, b.c) >= a.c - a.body * 0.1 && c.bear && c.c < a.mid)
            out += Hit(Pat.EVENING_STAR, i, adj(0.9, 1), lo3, hi3, "長紅、小星、長黑三根組合，頭部反轉訊號。")
        fun soldier(x: Candle, prev: Candle) = x.bull && x.c > prev.c && x.o >= min(prev.o, prev.c) && x.o <= max(prev.o, prev.c) &&
            x.body >= avgBody * 0.5 && x.upper <= x.body * 0.35
        fun crow(x: Candle, prev: Candle) = x.bear && x.c < prev.c && x.o >= min(prev.o, prev.c) && x.o <= max(prev.o, prev.c) &&
            x.body >= avgBody * 0.5 && x.lower <= x.body * 0.35
        if (a.bull && a.body >= avgBody * 0.5 && soldier(b, a) && soldier(c, b))
            out += Hit(Pat.THREE_SOLDIERS, i, if (trend == 1) 0.8 else 0.88, lo3, hi3, "連三根實體紅 K 逐步墊高，多方動能延續" + if (trend == 1) "（已漲一段，留意追高）。" else "。")
        if (a.bear && a.body >= avgBody * 0.5 && crow(b, a) && crow(c, b))
            out += Hit(Pat.THREE_CROWS, i, if (trend == -1) 0.8 else 0.88, lo3, hi3, "連三根實體黑 K 逐步走低，賣壓持續。")

        // ---- 缺口、突破、陷阱 ----
        if (c.l > p.h) out += Hit(Pat.GAP_UP, i, if (bigVol) 0.86 else 0.74, p.h, c.h, "向上跳空，缺口 " + fmt(p.h) + "～" + fmt(c.l) + " 為短線支撐。")
        if (c.h < p.l) out += Hit(Pat.GAP_DOWN, i, if (bigVol) 0.86 else 0.74, c.l, p.l, "向下跳空，缺口 " + fmt(c.h) + "～" + fmt(p.l) + " 為短線壓力。")
        val res = (i - 20 until i).maxOf { cs[it].h }
        val sup = (i - 20 until i).minOf { cs[it].l }
        if (c.c > res && bigVol) out += Hit(Pat.BREAKOUT, i, 0.84, sup, c.h, "帶量收在前 20 根高點 " + fmt(res) + " 之上。")
        if (c.c < sup && bigVol) out += Hit(Pat.BREAKDOWN, i, 0.84, c.l, res, "帶量收在前 20 根低點 " + fmt(sup) + " 之下。")
        if (c.h > res && c.c < res && c.upper >= c.body) out += Hit(Pat.BULL_TRAP, i, 0.8, c.l, c.h, "盤中突破前高 " + fmt(res) + " 後拉回收在下方，形成多頭陷阱。")
        if (c.l < sup && c.c > sup && c.lower >= c.body) out += Hit(Pat.BEAR_TRAP, i, 0.8, c.l, c.h, "盤中跌破前低 " + fmt(sup) + " 後收復，形成空頭陷阱。")
        return out
    }

    private fun fmt(v: Double) = com.claudeetf.app.util.Fmt.price(v)

    // ---------------------------------------------------------------- 主流程

    fun analyze(cs: List<Candle>, timeframe: String, forward: Int = 5): Result? {
        if (cs.size < 40) return null
        val n = cs.size
        val closes = cs.map { it.c }
        val last = n - 1
        val cur = cs[last]

        // 環境
        val ma20 = sma(closes, last, 20)!!
        val ma60 = sma(closes, last, 60)
        val volMa20 = (last - 20 until last).map { cs[it].v }.average()
        val atr = atrAt(cs, last)
        val resistance = (last - 20 until last).maxOf { cs[it].h }
        var support = (last - 20 until last).minOf { cs[it].l }
        // MA60 若介於現價與 20 日低點之間，視為較近的支撐
        if (ma60 != null && ma60 < cur.c && ma60 > support) support = ma60
        val trend = trendAt(closes, n)  // 以最新一根為基準的目前趨勢
        val hi20c = (last - 20 until last).maxOf { closes[it] }
        val lo20c = (last - 20 until last).minOf { closes[it] }
        val vol = when {
            cur.c >= hi20c && volMa20 > 0 && cur.v < volMa20 * 0.8 && cs[last - 1].v < volMa20 -> Vol.DIVERGENCE_TOP
            cur.c <= lo20c && volMa20 > 0 && cur.v < volMa20 * 0.8 && cs[last - 1].v < volMa20 -> Vol.DIVERGENCE_BOTTOM
            volMa20 > 0 && cur.v >= volMa20 * 1.5 -> Vol.CONFIRMED
            volMa20 > 0 && cur.v <= volMa20 * 0.6 -> Vol.DRY
            else -> Vol.NORMAL
        }
        val near = max(atr, cur.c * 0.015)
        val loc = when {
            cur.c > resistance -> Loc.ABOVE
            cur.c < support -> Loc.BELOW
            cur.c - support <= near -> Loc.SUPPORT
            resistance - cur.c <= near -> Loc.RESISTANCE
            else -> Loc.MID
        }
        val ctx = Ctx(trend, ma20, ma60, volMa20, atr, support, resistance, vol, loc)

        // 全歷史掃描（統計勝率）＋近期形態
        val all = (22..last).flatMap { detectAt(cs, closes, it) }
        val stats = all.filter { it.idx + forward <= last && it.pat.dir != Dir.NEUTRAL }.groupBy { it.pat }.mapValues { (p, hs) ->
            val rets = hs.map { h -> (closes[h.idx + forward] / closes[h.idx] - 1) * (if (p.dir.bullish) 1 else -1) }
            Stat(hs.size, rets.count { it > 0 }.toDouble() / rets.size, rets.average() * 100)
        }
        val recent = all.filter { it.idx >= n - 40 }
        val latest = all.filter { it.idx >= n - 3 }.sortedWith(compareByDescending<Hit> { it.idx }.thenByDescending { it.conf })

        // 綜合評分：近 3 根形態依新舊遞減權重，量能、位置、趨勢、背離加減分；歷史勝率調整可信度
        val decay = mapOf(last to 1.0, last - 1 to 0.6, last - 2 to 0.35)
        val reasons = ArrayList<String>()
        var score = 0.0
        latest.forEach { h ->
            val st = stats[h.pat]
            val hist = if (st != null && st.count >= 3) (0.6 + st.winRate * 0.8) else 1.0   // 勝率 50% → 1.0；70% → 1.16；30% → 0.84
            val w = h.pat.dir.weight * h.conf * (decay[h.idx] ?: 0.3) * hist
            score += w
        }
        if (latest.isNotEmpty()) reasons += "近 3 根出現：" + latest.distinctBy { it.pat }.joinToString("、") { it.pat.cn }
        val lastDir = latest.filter { it.idx == last }.sumOf { it.pat.dir.weight }
        if (vol == Vol.CONFIRMED && lastDir != 0) { score += 0.4 * Integer.signum(lastDir); reasons += "今日帶量，" + (if (lastDir > 0) "多方" else "空方") + "形態可信度提高" }
        if (vol == Vol.DIVERGENCE_TOP) { score -= 0.8; reasons += "價創 20 日新高但量縮，追價力道不足" }
        if (vol == Vol.DIVERGENCE_BOTTOM) { score += 0.5; reasons += "價創 20 日新低但量縮，殺盤力道減弱" }
        when (loc) {
            Loc.SUPPORT -> { score += 0.5; reasons += "股價接近支撐 " + fmt(support) }
            Loc.RESISTANCE -> { score -= 0.5; reasons += "股價接近壓力 " + fmt(resistance) }
            Loc.ABOVE -> { score += 0.4; reasons += "收盤站上前 20 根高點" }
            Loc.BELOW -> { score -= 0.4; reasons += "收盤跌破前 20 根低點" }
            Loc.MID -> {}
        }
        score += trend * 0.5
        reasons += "趨勢：" + ctx.trendLabel + "（MA20 " + fmt(ma20) + (ma60?.let { "、MA60 " + fmt(it) } ?: "") + "）"

        val dir = when {
            score >= 2.2 -> Dir.STRONG_BULL
            score >= 0.8 -> Dir.BULL
            score <= -2.2 -> Dir.STRONG_BEAR
            score <= -0.8 -> Dir.BEAR
            else -> Dir.NEUTRAL
        }
        val strength = (50 + score * 14).toInt().coerceIn(3, 97)
        val plan = plan(cur, dir, latest, ctx)
        val entry = entry(dir, plan, ctx, latest, last)
        return Result(timeframe, cs, dir, strength, score, latest, recent, ctx, plan, stats, forward, reasons, entry)
    }

    /**
     * 是否進場：綜合方向、盈虧比、位置與量價。
     * 偏多但盈虧比不足或貼近壓力 → 等回測；偏空但最新一根出現止跌形態 → 等確認。
     */
    private fun entry(dir: Dir, plan: Plan?, x: Ctx, latest: List<Hit>, last: Int): Entry {
        val rr = plan?.rr ?: 0.0
        val bullRev = latest.any { it.idx == last && it.pat.dir.bullish && (it.pat.cat == Cat.REVERSAL || it.pat == Pat.BEAR_TRAP) }
        val bearRev = latest.any { it.idx == last && it.pat.dir.bearish && (it.pat.cat == Cat.REVERSAL || it.pat == Pat.BULL_TRAP) }
        val p = plan
        return when {
            dir == Dir.STRONG_BULL && rr >= 1.5 && x.vol != Vol.DIVERGENCE_TOP && x.loc != Loc.RESISTANCE ->
                Entry("建議進場", 2, "多方訊號強、盈虧比 1:" + r2(rr) + "，可依布局區間進場，跌破 " + fmt(p!!.stop) + " 停損。", "可續抱，停利上移")
            dir.bullish && rr >= 1.2 && x.loc != Loc.RESISTANCE && x.vol != Vol.DIVERGENCE_TOP ->
                Entry("可分批進場", 1, "偏多、盈虧比 1:" + r2(rr) + "，先進 1/3～1/2，站穩形態高點再加碼。", "續抱")
            dir.bullish ->
                Entry("等回測再進", 0, when {
                    x.vol == Vol.DIVERGENCE_TOP -> "價漲量縮，追價風險高，等回測支撐 " + fmt(x.support) + " 附近量縮不破再進。"
                    x.loc == Loc.RESISTANCE -> "接近壓力 " + fmt(x.resistance) + "，等突破站穩或回測再進。"
                    else -> "方向偏多但盈虧比僅 1:" + r2(rr) + "，等拉回到 " + fmt(p?.entryMin ?: x.ma20) + " 附近再進。"
                }, "續抱")
            dir == Dir.NEUTRAL && bullRev ->
                Entry("觀察止跌", 0, "出現止跌形態但力道未確認，等下一根收紅並站上 " + fmt(x.ma20) + "（MA20）再考慮。", "續抱觀察")
            dir == Dir.NEUTRAL ->
                Entry("觀望", 0, "多空不明，等帶量突破 " + fmt(x.resistance) + " 或回測 " + fmt(x.support) + " 有撐再決定。", "續抱觀察")
            dir.bearish && bullRev ->
                Entry("等待止跌確認", -1, "空頭格局中出現止跌形態，尚未扭轉，等站回 MA20 " + fmt(x.ma20) + " 再評估。", "暫不加碼")
            dir == Dir.BEAR ->
                Entry("暫不進場", -1, "偏空" + (if (bearRev) "，最新出現轉弱形態" else "") + "，等止跌訊號出現再評估。", "反彈減碼、跌破 " + fmt(x.support) + " 防守")
            else ->
                Entry("不宜進場", -2, "強烈偏空" + (if (x.loc == Loc.BELOW) "且跌破支撐" else "") + "，避免接刀。", "建議減碼，嚴設停損")
        }
    }

    private fun r2(v: Double) = com.claudeetf.app.util.Fmt.num(v, 2)

    private fun plan(cur: Candle, dir: Dir, latest: List<Hit>, x: Ctx): Plan? {
        val p = cur.c
        val atr = x.atr.takeIf { it > 0 } ?: return null
        if (dir.bullish) {
            val patLow = latest.filter { it.pat.dir.bullish }.minOfOrNull { it.low } ?: cur.l
            val patHigh = latest.filter { it.pat.dir.bullish }.maxOfOrNull { it.high } ?: cur.h
            // 停損：形態低點與支撐取較低者再留 0.3 ATR；風險限制在 0.8～3 ATR 之間
            var stop = min(patLow, if (x.support < p) x.support else patLow) - atr * 0.3
            stop = stop.coerceIn(p - atr * 3, p - atr * 0.8)
            val risk = p - stop
            val tp1 = if (x.resistance > p + risk) x.resistance else p + risk * 1.5
            val tp2 = max(p + risk * 3, tp1 + risk)
            return Plan(
                "偏多操作參考",
                "收盤站穩形態高點 " + fmt(patHigh) + "，或回測 " + fmt(p - atr * 0.5) + " 附近不破再布局",
                p - atr * 0.5, p, stop, tp1, tp2, (tp1 - p) / risk,
                "停損以 ATR(14)=" + fmt(atr) + " 調整；目標一優先取前 20 根壓力 " + fmt(x.resistance) + "。",
            )
        }
        if (dir.bearish) {
            val patHigh = latest.filter { it.pat.dir.bearish }.maxOfOrNull { it.high } ?: cur.h
            val patLow = latest.filter { it.pat.dir.bearish }.minOfOrNull { it.low } ?: cur.l
            var stop = max(patHigh, if (x.resistance > p) x.resistance else patHigh) + atr * 0.3
            stop = stop.coerceIn(p + atr * 0.8, p + atr * 3)
            val risk = stop - p
            val tp1 = if (x.support < p - risk) x.support else p - risk * 1.5
            val tp2 = max(0.0, min(p - risk * 3, tp1 - risk))
            return Plan(
                "偏空：持股減碼／防守參考",
                "反彈至 " + fmt(p) + "～" + fmt(p + atr * 0.5) + " 減碼；收盤跌破形態低點 " + fmt(patLow) + " 轉保守",
                p, p + atr * 0.5, stop, tp1, tp2, (p - tp1) / risk,
                "空手者觀望；放空者以 " + fmt(stop) + " 為停損。下方第一支撐 " + fmt(x.support) + "。",
            )
        }
        return null
    }

    // ---------------------------------------------------------------- 資料

    /** 原始 K 棒快取：symbol|d/w → (取得時間, K 棒) */
    private val raw: MutableMap<String, Pair<Long, List<Candle>>> = java.util.Collections.synchronizedMap(HashMap())
    /** 分析結果快取：key|價格 */
    private val cache: MutableMap<String, Result?> = java.util.Collections.synchronizedMap(HashMap())
    /** 最近一次各股日 K 結果（標題徽章、摘要列共用） */
    val latestDaily = androidx.compose.runtime.mutableStateMapOf<String, Result>()

    private val netLimit = kotlinx.coroutines.sync.Semaphore(3)

    private suspend fun candles(q: Quote, weekly: Boolean, maxAgeMs: Long): List<Candle> = withContext(Dispatchers.IO) {
        val key = q.symbol + if (weekly) "|w" else "|d"
        raw[key]?.let { (t, cs) -> if (System.currentTimeMillis() - t < maxAgeMs) return@withContext cs }
        // 清單一次載入很多檔時限制同時連線數
        val h = netLimit.withPermit {
            raw[key]?.let { (t, cs) -> if (System.currentTimeMillis() - t < maxAgeMs) return@withContext cs }
            YahooExtra.history(StockInfoRepo.yahooSymbol(q), if (weekly) "5y" else "2y")
        }
        val cs = ArrayList<Candle>()
        for (k in h.closes.indices) {
            val o = h.opens.getOrNull(k)?.toDouble() ?: continue
            val hi = h.highs.getOrNull(k)?.toDouble() ?: continue
            val lo = h.lows.getOrNull(k)?.toDouble() ?: continue
            val c = h.closes[k].toDouble()
            if (o.isNaN() || hi.isNaN() || lo.isNaN() || c.isNaN() || c <= 0) continue
            cs += Candle(h.times[k], o, hi, lo, c, h.volumes.getOrNull(k)?.toDouble()?.takeIf { !it.isNaN() } ?: 0.0)
        }
        raw[key] = System.currentTimeMillis() to cs
        cs
    }

    /**
     * weekly = false：近 2 年日 K；true：近 5 年週 K。
     * maxAgeMs：K 棒資料最久可用多久（盤中即時訊號用 2 分鐘，讓當日高低點更新）。
     * 日 K 的最後一根以目前報價補上（盤中即為「形成中」的 K 棒）。
     */
    suspend fun load(q: Quote, weekly: Boolean, maxAgeMs: Long = 10 * 60_000L): Result? {
        val cs0 = candles(q, weekly, maxAgeMs)
        val key = q.symbol + (if (weekly) "|w|" else "|d|") + cs0.size + "|" + (cs0.lastOrNull()?.t ?: 0) + "|" + q.price
        cache[key]?.let { return it }
        val r = withContext(Dispatchers.Default) {
            val cs = ArrayList(cs0)
            if (!weekly && q.price > 0 && cs.isNotEmpty()) {
                val zone = java.time.ZoneId.of(if (q.market == Market.TW) "Asia/Taipei" else "America/New_York")
                fun day(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate()
                val l = cs.last()
                val lastDay = day(l.t)
                // 報價屬於哪一天：台股依資料來源判斷，美股看更新時間
                val qDay = if (q.market == Market.TW) MarketData.quoteDay(q.symbol) else if (q.updatedAt > 0) day(q.updatedAt) else null
                val ds = AppState.dayStats[q.symbol]
                when {
                    // Yahoo 還沒有最後交易日的 K 棒（常見於收盤後到隔天）：用報價補一根
                    qDay != null && qDay > lastDay && qDay.dayOfWeek.value <= 5 -> cs += Candle(
                        qDay.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli(),
                        q.prevClose.takeIf { it > 0 } ?: l.c,
                        max(ds?.high ?: q.price, q.price), min(ds?.low ?: q.price, q.price), q.price, ds?.volume ?: 0.0,
                    )
                    // 同一天：最後一根以目前報價更新（盤中即為形成中的 K 棒）
                    qDay == null || qDay == lastDay -> if (System.currentTimeMillis() - l.t < 30 * 3600_000L)
                        cs[cs.size - 1] = l.copy(c = q.price, h = max(l.h, q.price), l = min(l.l, q.price))
                }
            }
            analyze(cs, if (weekly) "週 K" else "日 K", if (weekly) 4 else 5)?.let { res ->
                // 台股：操作計畫的價位依證交所升降單位對齊
                if (q.market != Market.TW || res.plan == null) res else {
                    val etf = q.isEtf || q.symbol.startsWith("00")
                    fun s(v: Double) = com.claudeetf.app.util.TwSession.snap(v, etf)
                    val p = res.plan
                    res.copy(plan = p.copy(entryMin = s(p.entryMin), entryMax = s(p.entryMax), stop = s(p.stop), tp1 = s(p.tp1), tp2 = s(p.tp2)))
                }
            }
        }
        if (cache.size > 200) cache.clear()
        cache[key] = r
        if (!weekly && r != null) latestDaily[q.symbol] = r
        return r
    }

    /** 長線（投資指數建議投入比例）× 短線（K 線進場建議）→ 現在先投入多少 */
    fun combined(ratio: Int, entry: Entry): Pair<String, Int> {
        fun r5(v: Double) = (Math.round(v / 5) * 5).toInt()
        return when (entry.level) {
            2 -> "進場" to ratio
            1 -> "分批" to r5(ratio * 0.5)
            0 -> (if (entry.label == "觀望") "觀望" else "等回測") to r5(ratio * 0.3)
            -1 -> "等止跌" to r5(ratio * 0.15)
            else -> "暫緩" to 0
        }
    }
}
