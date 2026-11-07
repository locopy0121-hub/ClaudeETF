package com.claudeetf.app.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 台股交易金額規則（純計算、無 Android 相依，可單元測試）。
 * 全部以 BigDecimal 精確十進位計算，避免 30.57 × 45 這類浮點誤差。
 */
object TwRules {
    /** 手續費率 0.1425% */
    val FEE_RATE: BigDecimal = BigDecimal("0.001425")

    /** 成交金額：股數 × 價格，元以下無條件捨去 */
    fun gross(shares: Double, price: Double): Double {
        if (shares <= 0 || price <= 0 || shares.isNaN() || price.isNaN()) return 0.0
        return BigDecimal.valueOf(shares).multiply(BigDecimal.valueOf(price)).setScale(0, RoundingMode.DOWN).toDouble()
    }

    /** 手續費：成交金額 × 0.1425% × 折扣，元以下捨去，不足最低收費以最低計 */
    fun fee(amount: Double, discount: Double, minFee: Double): Double {
        if (amount <= 0) return 0.0
        val f = BigDecimal.valueOf(amount).multiply(FEE_RATE).multiply(BigDecimal.valueOf(discount)).setScale(0, RoundingMode.DOWN).toDouble()
        return maxOf(minFee, f)
    }

    /** 證交稅：成交金額 × 稅率，元以下捨去 */
    fun tax(amount: Double, rate: Double): Double {
        if (amount <= 0 || rate <= 0) return 0.0
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rate)).setScale(0, RoundingMode.DOWN).toDouble()
    }

    /**
     * 把一組權重換成到 0.1% 的百分比，用最大餘數法補差，合計剛好 100.0%。
     * （再平衡「目標＝目前」「平均分配」用）
     */
    fun normalizeTenths(weights: List<Double>): List<Double> {
        val sum = weights.filter { it > 0 }.sum()
        if (sum <= 0) return weights.map { 0.0 }
        val raw = weights.map { if (it > 0) it / sum * 1000 else 0.0 }
        val base = raw.map { Math.floor(it).toInt() }.toMutableList()
        var left = 1000 - base.sum()
        raw.indices.sortedByDescending { raw[it] - base[it] }.forEach { i -> if (left > 0 && raw[i] > 0) { base[i]++; left-- } }
        return base.map { it / 10.0 }
    }
}
