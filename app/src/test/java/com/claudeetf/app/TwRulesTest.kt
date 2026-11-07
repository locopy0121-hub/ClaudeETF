package com.claudeetf.app

import com.claudeetf.app.util.TwRules
import org.junit.Assert.assertEquals
import org.junit.Test

/** 台股成交金額、手續費、證交稅：一律元以下無條件捨去 */
class TwRulesTest {
    @Test fun grossTruncatesToYuan() {
        assertEquals(1375.0, TwRules.gross(45.0, 30.57), 0.0)      // 1,375.65 → 1,375
        assertEquals(92500.0, TwRules.gross(1000.0, 92.5), 0.0)
        assertEquals(6.0, TwRules.gross(1.0, 6.85), 0.0)
        assertEquals(1010.0, TwRules.gross(100.0, 10.1), 0.0)      // 浮點 1009.9999… 不可變 1009
        assertEquals(0.0, TwRules.gross(0.0, 10.0), 0.0)
    }

    @Test fun grossExactOverManyPrices() {
        // 與精確十進位比對：所有 0.01 價位 × 1..999 股都不可差 1 元
        for (cents in 1000..2000 step 7) for (sh in 1..999 step 13) {
            val exact = java.math.BigDecimal(cents).multiply(java.math.BigDecimal(sh)).divide(java.math.BigDecimal(100))
                .setScale(0, java.math.RoundingMode.DOWN).toDouble()
            assertEquals("$sh × ${cents / 100.0}", exact, TwRules.gross(sh.toDouble(), cents / 100.0), 0.0)
        }
    }

    @Test fun feeFloorsAndAppliesMinimum() {
        assertEquals(1.0, TwRules.fee(1375.0, 0.65, 1.0), 0.0)     // 1.27 → 1
        assertEquals(20.0, TwRules.fee(1375.0, 1.0, 20.0), 0.0)    // 不足最低以最低計
        assertEquals(131.0, TwRules.fee(92500.0, 1.0, 20.0), 0.0)  // 131.81 → 131
        assertEquals(1425.0, TwRules.fee(1_000_000.0, 1.0, 20.0), 0.0)
        assertEquals(0.0, TwRules.fee(0.0, 1.0, 20.0), 0.0)
    }

    @Test fun taxFloors() {
        assertEquals(92.0, TwRules.tax(92500.0, 0.001), 0.0)       // ETF 0.1%：92.5 → 92
        assertEquals(277.0, TwRules.tax(92500.0, 0.003), 0.0)      // 股票 0.3%：277.5 → 277
        assertEquals(0.0, TwRules.tax(100.0, 0.0), 0.0)
    }

    @Test fun normalizeSumsToExactly100() {
        val cases = listOf(listOf(1.0, 1.0, 1.0), listOf(18500.0, 2448.0, 1600.0, 178_000_000.0), listOf(5.0, 0.0, 5.0), List(7) { 1.0 })
        for (w in cases) {
            val n = TwRules.normalizeTenths(w)
            assertEquals(w.toString(), 1000, Math.round(n.sum() * 10).toInt())
            w.indices.filter { w[it] <= 0 }.forEach { assertEquals(0.0, n[it], 0.0) }
        }
        assertEquals(listOf(0.0, 0.0), TwRules.normalizeTenths(listOf(0.0, 0.0)))
    }
}
