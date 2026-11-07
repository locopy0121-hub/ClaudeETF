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

    /** 依波段低點日期對應常見歷史事件（沒有對應時回傳「yyyy/M 修正」） */
    fun eventName(w: Wave): String {
        val d = Instant.ofEpochMilli(w.troughT).atZone(ZoneId.of("UTC")).toLocalDate()
        val p = Instant.ofEpochMilli(w.peakT).atZone(ZoneId.of("UTC")).toLocalDate()
        val ym = d.year * 100 + d.monthValue
        val pym = p.year * 100 + p.monthValue
        return when {
            pym in 199901..200112 && ym in 200003..200303 -> "網路泡沫"
            ym in 200801..200906 -> "金融海嘯"
            ym in 202002..202004 -> "2020 疫情"
            ym in 202201..202301 -> "2022 升息"
            ym in 201810..201901 -> "2018 Q4"
            ym in 202503..202505 -> "2025 關稅"
            ym in 201508..201603 -> "2015 股災"
            ym in 201108..201112 -> "2011 歐債"
            ym in 202407..202409 -> "2024 套利平倉"
            ym in 199710..199810 -> "亞洲金融風暴"
            else -> "${d.year}/${d.monthValue} 修正"
        }
    }
}
