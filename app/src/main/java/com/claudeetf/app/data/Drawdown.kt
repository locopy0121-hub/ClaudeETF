package com.claudeetf.app.data

import java.time.Instant
import java.time.ZoneId

/**
 * 波段跌幅統計：以收盤價計算「前波高點 → 其後低點」的波段，
 * 回到前高為一波結束；尚未回到前高者為進行中。
 *
 * 長空頭的拆分（與常見圖表口徑一致，例如 QQQ 網路泡沫與 2008 金融海嘯分開計算）：
 * 若在尚未回到前高時，價格自波段低點翻倍（≥ rallyPct），之後又從反彈高點下跌 ≥ bearPct，
 * 則前一波在原低點結束（回到前高日仍往後找），新的一波自反彈高點起算。
 * 純計算、無 Android 相依，可單元測試。
 */
object Drawdown {
    data class Wave(
        val peakT: Long, val peak: Double,
        val troughT: Long, val trough: Double,
        /** 回到前高的日期；null = 尚未回到 */
        val recoverT: Long?,
        /** 尚未結束（目前仍在此波段中） */
        val ongoing: Boolean,
    ) {
        /** 跌幅 %（負值） */
        val depth: Double get() = (trough / peak - 1) * 100
    }

    data class Bucket(val threshold: Int, val waves: List<Wave>, val everyYears: Double?)

    data class Stats(
        val firstT: Long, val lastT: Long, val years: Double,
        val waves: List<Wave>, val buckets: List<Bucket>,
        /** 目前距歷史前高 %（≤ 0） */
        val fromHigh: Double, val highT: Long, val high: Double,
    )

    fun waves(t: List<Long>, c: List<Double>, rallyPct: Double = 100.0, bearPct: Double = 20.0, minDepth: Double = 5.0): List<Wave> {
        val n = minOf(t.size, c.size)
        if (n < 2) return emptyList()
        val out = ArrayList<Wave>()
        var peakI = 0
        var troughI = 0
        var subPeakI = -1          // 波段低點之後的反彈高點
        var inDd = false
        for (i in 1 until n) {
            val v = c[i]
            if (v <= 0 || v.isNaN()) continue
            if (v >= c[peakI]) {
                if (inDd) out += Wave(t[peakI], c[peakI], t[troughI], c[troughI], t[i], false)
                inDd = false; peakI = i; troughI = i; subPeakI = -1
                continue
            }
            inDd = true
            if (v < c[troughI] || troughI == peakI) { troughI = i; subPeakI = -1; continue }
            if (subPeakI < 0 || v > c[subPeakI]) subPeakI = i
            // 長空頭拆分：自低點翻倍後又跌 ≥ bearPct → 前一波結束、新的一波自反彈高點起算
            val rallied = c[subPeakI] >= c[troughI] * (1 + rallyPct / 100)
            if (rallied && v <= c[subPeakI] * (1 - bearPct / 100)) {
                out += Wave(t[peakI], c[peakI], t[troughI], c[troughI], recoverAfter(t, c, troughI, c[peakI]), false)
                peakI = subPeakI; troughI = i; subPeakI = -1
            }
        }
        if (inDd) out += Wave(t[peakI], c[peakI], t[troughI], c[troughI], null, true)
        return out.filter { -it.depth >= minDepth }
    }

    private fun recoverAfter(t: List<Long>, c: List<Double>, from: Int, level: Double): Long? {
        for (j in from + 1 until minOf(t.size, c.size)) if (c[j] >= level) return t[j]
        return null
    }

    fun stats(t: List<Long>, c: List<Double>, thresholds: List<Int> = listOf(10, 20, 30, 40, 50, 60, 70, 80)): Stats? {
        val n = minOf(t.size, c.size)
        if (n < 30) return null
        val w = waves(t, c)
        val years = (t[n - 1] - t[0]) / (365.25 * 86_400_000.0)
        val buckets = thresholds.map { th ->
            val l = w.filter { -it.depth >= th }.sortedBy { it.depth }
            Bucket(th, l, if (l.isEmpty()) null else years / l.size)
        }
        var hi = 0
        for (i in 0 until n) if (c[i] > c[hi]) hi = i
        return Stats(t[0], t[n - 1], years, w, buckets, (c[n - 1] / c[hi] - 1) * 100, t[hi], c[hi])
    }

    /**
     * 跌深進場回測：每一波自前高下跌達 X% 的「第一天」以收盤價買進，統計之後 1 年、3 年的報酬，
     * 以及從買進到收盤價回到前高所需時間。另外統計每個門檻「從前高到回前高」的平均修復時間。
     */
    data class DipRow(
        val threshold: Int, val count: Int,
        /** 1 年後、3 年後報酬 %（平均、中位數、勝率），樣本不足時為 null */
        val avg1y: Double?, val med1y: Double?, val win1y: Double?, val n1y: Int,
        val avg3y: Double?, val win3y: Double?, val n3y: Int,
        /** 進場後最多再跌 %（平均，≤ 0） */
        val avgFurther: Double?,
        /** 進場後回到前高平均需幾年（只計已回到的）、已回到的比例 */
        val avgRecoverYears: Double?, val recovered: Int,
    )

    fun dipBacktest(t: List<Long>, c: List<Double>, thresholds: List<Int> = listOf(10, 20, 30, 40, 50)): List<DipRow> {
        val n = minOf(t.size, c.size)
        if (n < 60) return emptyList()
        val w = waves(t, c)
        val yearMs = 365.25 * 86_400_000.0
        fun idxAt(ms: Long): Int { var lo = 0; var hi = n - 1; while (lo < hi) { val m = (lo + hi) / 2; if (t[m] < ms) lo = m + 1 else hi = m }; return lo }
        fun med(l: List<Double>) = if (l.isEmpty()) null else l.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
        return thresholds.map { th ->
            val r1 = ArrayList<Double>(); val r3 = ArrayList<Double>(); val further = ArrayList<Double>(); val rec = ArrayList<Double>()
            var count = 0
            w.filter { -it.depth >= th }.forEach { wv ->
                val p0 = idxAt(wv.peakT)
                val level = wv.peak * (1 - th / 100.0)
                var e = -1
                for (j in p0 until n) { if (t[j] > wv.troughT) break; if (c[j] <= level) { e = j; break } }
                if (e < 0) return@forEach
                count++
                val buy = c[e]
                further += (wv.trough / buy - 1) * 100
                val j1 = idxAt(t[e] + (yearMs).toLong()); if (t[n - 1] >= t[e] + yearMs.toLong() && j1 < n) r1 += (c[j1] / buy - 1) * 100
                val j3 = idxAt(t[e] + (3 * yearMs).toLong()); if (t[n - 1] >= t[e] + (3 * yearMs).toLong() && j3 < n) r3 += (c[j3] / buy - 1) * 100
                wv.recoverT?.let { rec += (it - t[e]) / yearMs }
            }
            DipRow(th, count,
                r1.takeIf { it.isNotEmpty() }?.average(), med(r1), r1.takeIf { it.isNotEmpty() }?.let { l -> l.count { it > 0 } * 100.0 / l.size }, r1.size,
                r3.takeIf { it.isNotEmpty() }?.average(), r3.takeIf { it.isNotEmpty() }?.let { l -> l.count { it > 0 } * 100.0 / l.size }, r3.size,
                further.takeIf { it.isNotEmpty() }?.average(), rec.takeIf { it.isNotEmpty() }?.average(), rec.size)
        }.filter { it.count > 0 }
    }

    /** 目前跌幅在歷史波段中的位置：歷史上有幾成的波段比現在更深 */
    fun deeperShare(waves: List<Wave>, current: Double): Double? {
        val done = waves.filter { !it.ongoing }
        if (done.isEmpty() || current > -5) return null
        return done.count { it.depth < current } * 100.0 / done.size
    }

    /**
     * 依波段高點與低點日期對應重大歷史事件（名稱參考 YP-Finance「台股美股全球歷次大跌」）；
     * 沒有對應時回傳 null。
     */
    fun eventOf(w: Wave): String? {
        val d = Instant.ofEpochMilli(w.troughT).atZone(ZoneId.of("UTC")).toLocalDate()
        val p = Instant.ofEpochMilli(w.peakT).atZone(ZoneId.of("UTC")).toLocalDate()
        val ym = d.year * 100 + d.monthValue
        val pym = p.year * 100 + p.monthValue
        return when {
            ym in 199707..199810 -> "亞洲金融風暴"
            pym in 199901..200112 && ym in 200003..200303 -> "網路泡沫、911"
            ym in 200301..200305 -> "SARS"
            ym in 200404..200408 -> "319 槍擊案、中國宏觀調控"
            ym in 200801..200906 -> "全球金融海嘯"
            ym in 201005..201008 -> "歐債危機（希臘）"
            ym in 201108..201201 -> "歐債危機、美債遭降評"
            ym in 201508..201603 -> "中國股災、人民幣貶值"
            ym in 201810..201901 -> "中美貿易戰"
            ym in 202002..202004 -> "新冠疫情"
            ym in 202201..202301 -> "美國快速升息、俄烏戰爭"
            pym in 202406..202408 && ym in 202503..202505 -> "日圓套利平倉、美國對等關稅"
            ym in 202407..202409 -> "日圓套利平倉"
            ym in 202503..202505 -> "美國對等關稅"
            else -> null
        }
    }

    /** 事件名稱；沒有對應的重大事件時回傳「yyyy/M 修正」 */
    fun eventName(w: Wave): String = eventOf(w) ?: Instant.ofEpochMilli(w.troughT).atZone(ZoneId.of("UTC")).toLocalDate().let { "${it.year}/${it.monthValue} 修正" }
}
