package com.claudeetf.app.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 台股交易時段（台灣時間）：
 * 08:30–09:00 盤前試撮；09:00–13:30 盤中（整股、盤中零股）；13:30 收盤；
 * 13:40–14:30 盤後零股；14:00–14:30 盤後定價。國定假日未納入（以實際公告為準）。
 */
object TwSession {
    private val TPE = ZoneId.of("Asia/Taipei")

    data class Info(val label: String, val detail: String, val open: Boolean)

    fun info(now: ZonedDateTime = ZonedDateTime.now(TPE)): Info {
        val t = now.withZoneSameInstant(TPE)
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return Info("休市", "週末休市", false)
        val x = t.toLocalTime()
        fun at(h: Int, m: Int) = LocalTime.of(h, m)
        return when {
            x < at(8, 30) -> Info("尚未開盤", "08:30 盤前試撮 · 09:00 開盤", false)
            x < at(9, 0) -> Info("盤前試撮", "09:00 開盤", false)
            x < at(13, 25) -> Info("盤中交易", "整股與盤中零股 · 13:30 收盤", true)
            x < at(13, 30) -> Info("收盤集合競價", "13:25–13:30 不揭示成交", true)
            x < at(13, 40) -> Info("已收盤", "13:40 起盤後零股", false)
            x < at(14, 0) -> Info("盤後零股", "13:40–14:30 · 14:00 起盤後定價", true)
            x < at(14, 30) -> Info("盤後零股／定價", "14:30 結束", true)
            else -> Info("已收盤", "今日交易結束", false)
        }
    }

    /** 升降單位（股票與 ETF 不同） */
    fun tick(price: Double, etf: Boolean): Double = if (etf) {
        if (price < 50) 0.01 else 0.05
    } else when {
        price < 10 -> 0.01
        price < 50 -> 0.05
        price < 100 -> 0.1
        price < 500 -> 0.5
        price < 1000 -> 1.0
        else -> 5.0
    }

    private fun roundTo(v: Double, step: Double, mode: RoundingMode): Double {
        val s = BigDecimal.valueOf(step)
        return BigDecimal.valueOf(v).divide(s, 0, mode).multiply(s).toDouble()
    }

    /** 漲停價與跌停價（±10%，依升降單位向內取整） */
    /**
     * 依證交所升降單位對齊價格（無條件捨去到最近的升降單位）。
     * 先四捨五入到 4 位消除浮點誤差（Yahoo 7.7399998 視為 7.74）。
     */
    fun snap(v: Double, etf: Boolean): Double {
        if (v <= 0 || v.isNaN()) return v
        val x = BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).toDouble()
        return roundTo(x, tick(x, etf), RoundingMode.DOWN)
    }

    fun limits(prevClose: Double, etf: Boolean): Pair<Double, Double> {
        if (prevClose <= 0) return 0.0 to 0.0
        val upRaw = prevClose * 1.1
        val downRaw = prevClose * 0.9
        val up = roundTo(upRaw, tick(upRaw, etf), RoundingMode.DOWN)
        val down = roundTo(downRaw, tick(downRaw, etf), RoundingMode.UP)
        return up to down
    }

    /** 回傳「漲停」「跌停」或 null */
    fun limitState(price: Double, prevClose: Double, etf: Boolean): String? {
        if (price <= 0 || prevClose <= 0) return null
        val (up, down) = limits(prevClose, etf)
        return when {
            price >= up - 1e-9 -> "漲停"
            price <= down + 1e-9 -> "跌停"
            else -> null
        }
    }
}
