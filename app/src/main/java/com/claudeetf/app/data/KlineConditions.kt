package com.claudeetf.app.data

import com.claudeetf.app.data.KlineAnalysis.Candle
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** 單一條件的使用者設定：開關與參數 a、b */
data class KCondSetting(val on: Boolean, val a: Double, val b: Double = 0.0) {
    companion object {
        fun parseAll(s: String): Map<String, KCondSetting> = runCatching {
            if (s.isBlank()) return emptyMap()
            val o = JSONObject(s)
            o.keys().asSequence().associateWith { k ->
                val x = o.getJSONObject(k)
                KCondSetting(x.optBoolean("on"), x.optDouble("a", 0.0), x.optDouble("b", 0.0))
            }
        }.getOrDefault(emptyMap())

        fun toJsonAll(m: Map<String, KCondSetting>): String = JSONObject().apply {
            m.forEach { (k, v) -> put(k, JSONObject().put("on", v.on).put("a", v.a).put("b", v.b)) }
        }.toString()
    }
}

/**
 * K 棒分析條件：使用者在「我的 › K 棒分析條件」設定，個股 K 線分析卡片顯示是否達成，可選擇達成時通知。
 * 「國安基金進場條件」看加權指數（大盤），其餘看個股日 K。
 * 指標公式：
 *   RSI(14)：Wilder 平滑，RSI = 100 − 100 ÷ (1 + 平均漲幅 ÷ 平均跌幅)
 *   KD(9,3,3)：RSV = (收 − 9 日最低) ÷ (9 日最高 − 9 日最低) × 100；K = ⅔K前 + ⅓RSV；D = ⅔D前 + ⅓K（初值 50）
 *   乖離率 = (收 − MA20) ÷ MA20 × 100%
 */
object KlineConditions {
    data class Param(val label: String, val def: Double, val min: Double, val max: Double, val step: Double, val unit: String)

    data class Def(
        val id: String,
        val name: String,
        /** 大盤／個股 */
        val scope: String,
        val desc: String,
        val defOn: Boolean,
        val a: Param,
        val b: Param? = null,
    )

    val defs = listOf(
        Def(
            "nsf", "國安基金進場條件", "大盤",
            "加權指數自近 60 個交易日最高收盤回落達「回落幅度」，或單日跌幅達「單日跌幅」時達成。國安基金（國家金融安定基金）多在大盤急跌、市場恐慌時由委員會開會決議進場護盤；此條件以指數跌幅模擬可能的進場時機，並非官方公告。",
            true, Param("自高點回落", 10.0, 3.0, 40.0, 1.0, "%"), Param("或單日跌幅", 3.0, 1.0, 10.0, 0.5, "%"),
        ),
        Def("pullback", "自高點回檔", "個股", "收盤價距近 60 個交易日最高收盤回落達設定幅度。", false, Param("回檔幅度", 15.0, 3.0, 60.0, 1.0, "%")),
        Def("ma", "跌破均線", "個股", "收盤價低於設定天數的均線（20 日月線、60 日季線、240 日年線）。", false, Param("均線天數", 60.0, 5.0, 240.0, 5.0, " 日")),
        Def("bias", "負乖離過大", "個股", "收盤價低於 20 日均線達設定幅度，常見於急跌後的短線超跌。", false, Param("負乖離", 8.0, 2.0, 30.0, 1.0, "%")),
        Def("rsi", "RSI 超賣", "個股", "RSI(14) 低於設定值。", true, Param("RSI 低於", 30.0, 5.0, 50.0, 1.0, "")),
        Def("kd", "KD 低檔黃金交叉", "個股", "K 值在設定值以下時，K 由下往上穿越 D（最近 3 根內）。", true, Param("K 值低於", 20.0, 5.0, 50.0, 1.0, "")),
        Def("vol", "爆量", "個股", "成交量達 5 日均量的設定倍數。", false, Param("均量倍數", 2.0, 1.2, 10.0, 0.1, " 倍")),
        Def("down", "連續下跌", "個股", "連續設定天數收盤低於前一日。", false, Param("連跌天數", 4.0, 2.0, 15.0, 1.0, " 日")),
        Def("low52", "接近 52 週低點", "個股", "收盤價距 52 週（約 250 個交易日）最低價在設定幅度以內。", false, Param("距離低點", 5.0, 0.0, 30.0, 1.0, "%")),
    )

    fun def(id: String) = defs.first { it.id == id }

    fun setting(id: String): KCondSetting {
        val d = def(id)
        return AppState.kconds[id] ?: KCondSetting(d.defOn, d.a.def, d.b?.def ?: 0.0)
    }

    fun enabled(): List<Def> = defs.filter { setting(it.id).on }

    data class Eval(val def: Def, val met: Boolean, val value: String)

    // ---------------------------------------------------------------- 指標

    fun rsi(c: List<Double>, n: Int = 14): Double? {
        if (c.size <= n) return null
        var gain = 0.0; var loss = 0.0
        for (i in 1..n) { val d = c[i] - c[i - 1]; if (d > 0) gain += d else loss -= d }
        gain /= n; loss /= n
        for (i in n + 1 until c.size) {
            val d = c[i] - c[i - 1]
            gain = (gain * (n - 1) + max(d, 0.0)) / n
            loss = (loss * (n - 1) + max(-d, 0.0)) / n
        }
        return if (loss == 0.0) 100.0 else 100 - 100 / (1 + gain / loss)
    }

    /** KD(9,3,3)，回傳每根的 (K, D) */
    fun kd(cs: List<Candle>, n: Int = 9): List<Pair<Double, Double>> {
        var k = 50.0; var d = 50.0
        return cs.indices.map { i ->
            val from = max(0, i - n + 1)
            val hi = (from..i).maxOf { cs[it].h }; val lo = (from..i).minOf { cs[it].l }
            val rsv = if (hi > lo) (cs[i].c - lo) / (hi - lo) * 100 else 50.0
            k = k * 2 / 3 + rsv / 3
            d = d * 2 / 3 + k / 3
            k to d
        }
    }

    private fun f1(v: Double) = String.format("%.1f", v)

    /** 加權指數是否達國安基金進場條件 */
    fun evalIndex(cs: List<Candle>): Eval? {
        if (cs.size < 2) return null
        val s = setting("nsf")
        val c = cs.last().c
        val hi = cs.takeLast(60).maxOf { it.c }
        val dd = (hi - c) / hi * 100
        val day = (cs[cs.size - 2].c - c) / cs[cs.size - 2].c * 100
        val met = dd >= s.a || day >= s.b
        return Eval(def("nsf"), met, "距 60 日高點 −${f1(dd)}%，今日 ${if (day >= 0) "−" else "+"}${f1(abs(day))}%")
    }

    /** 個股條件（只算已開啟的；國安基金另由 evalIndex 計算） */
    fun evalStock(cs: List<Candle>, only: List<Def> = enabled()): List<Eval> {
        if (cs.size < 21) return emptyList()
        val closes = cs.map { it.c }
        val c = closes.last()
        val out = ArrayList<Eval>()
        for (d in only) {
            if (d.id == "nsf") continue
            val s = setting(d.id)
            out += when (d.id) {
                "pullback" -> {
                    val hi = closes.takeLast(60).max(); val dd = (hi - c) / hi * 100
                    Eval(d, dd >= s.a, "距高點 −${f1(dd)}%")
                }
                "ma" -> {
                    val n = s.a.toInt().coerceAtLeast(2)
                    if (closes.size < n) Eval(d, false, "資料不足 $n 日") else {
                        val ma = closes.takeLast(n).average()
                        Eval(d, c < ma, "MA$n ${com.claudeetf.app.util.Fmt.price(ma)}")
                    }
                }
                "bias" -> {
                    val ma = closes.takeLast(20).average(); val b = (c - ma) / ma * 100
                    Eval(d, b <= -s.a, "乖離 ${if (b >= 0) "+" else ""}${f1(b)}%")
                }
                "rsi" -> {
                    val r = rsi(closes) ?: 50.0
                    Eval(d, r <= s.a, "RSI ${f1(r)}")
                }
                "kd" -> {
                    val k = kd(cs)
                    val cross = (k.size - 3 until k.size).any { i -> i > 0 && k[i - 1].first <= k[i - 1].second && k[i].first > k[i].second && k[i].first <= s.a + 10 && k[i - 1].first <= s.a }
                    Eval(d, cross, "K ${f1(k.last().first)}／D ${f1(k.last().second)}")
                }
                "vol" -> {
                    val v = cs.last().v; val avg = cs.dropLast(1).takeLast(5).map { it.v }.average()
                    val x = if (avg > 0) v / avg else 0.0
                    Eval(d, avg > 0 && x >= s.a, "量 ${f1(x)} 倍")
                }
                "down" -> {
                    var n = 0
                    for (i in closes.size - 1 downTo 1) { if (closes[i] < closes[i - 1]) n++ else break }
                    Eval(d, n >= s.a.toInt(), "連跌 $n 日")
                }
                "low52" -> {
                    val lo = cs.takeLast(250).minOf { it.l }; val dist = (c - lo) / lo * 100
                    Eval(d, dist <= s.a, "距低點 +${f1(dist)}%")
                }
                else -> continue
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 加權指數日 K（快取 10 分鐘）

    @Volatile private var idxCache: Pair<Long, List<Candle>>? = null

    suspend fun indexCandles(): List<Candle> {
        idxCache?.let { (t, cs) -> if (System.currentTimeMillis() - t < 10 * 60_000L) return withLive(cs) }
        val cs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val h = com.claudeetf.app.data.remote.YahooExtra.history("^TWII", "1y")
                h.closes.indices.mapNotNull { k ->
                    val o = h.opens.getOrNull(k)?.toDouble(); val hi = h.highs.getOrNull(k)?.toDouble()
                    val lo = h.lows.getOrNull(k)?.toDouble(); val c = h.closes[k].toDouble()
                    if (o == null || hi == null || lo == null || o.isNaN() || hi.isNaN() || lo.isNaN() || c.isNaN() || c <= 0) null
                    else Candle(h.times[k], o, hi, lo, c, 0.0)
                }
            }.getOrDefault(emptyList())
        }
        if (cs.isNotEmpty()) idxCache = System.currentTimeMillis() to cs
        return withLive(cs)
    }

    /** 以 App 內的即時加權指數更新最後一根（盤中形成中的 K 棒） */
    private fun withLive(cs: List<Candle>): List<Candle> {
        val ix = AppState.twIndices.firstOrNull { it.symbol == "^TWII" } ?: return cs
        if (cs.isEmpty() || ix.value <= 0 || ix.updatedAt <= 0) return cs
        val zone = java.time.ZoneId.of("Asia/Taipei")
        val qDay = java.time.Instant.ofEpochMilli(ix.updatedAt).atZone(zone).toLocalDate()
        val l = cs.last()
        val lDay = java.time.Instant.ofEpochMilli(l.t).atZone(zone).toLocalDate()
        return when {
            qDay == lDay -> cs.dropLast(1) + l.copy(c = ix.value, h = max(l.h, ix.value), l = minOf(l.l, ix.value))
            qDay > lDay && qDay.dayOfWeek.value <= 5 && ix.prev > 0 && abs(ix.prev - l.c) / l.c < 0.02 ->
                cs + Candle(qDay.atStartOfDay(zone).plusHours(9).toInstant().toEpochMilli(), ix.prev, max(ix.prev, ix.value), minOf(ix.prev, ix.value), ix.value, 0.0)
            else -> cs
        }
    }
}
