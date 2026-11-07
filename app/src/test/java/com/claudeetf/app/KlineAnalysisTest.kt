package com.claudeetf.app

import com.claudeetf.app.data.KlineAnalysis
import com.claudeetf.app.data.KlineAnalysis.Candle
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K 線分析：各種極端資料都不可當機，結果需在合理範圍 */
class KlineAnalysisTest {
    private fun series(n: Int, f: (Int) -> Double, vol: (Int) -> Double = { 1000.0 }) = (0 until n).map { i ->
        val c = f(i); val o = f(maxOf(0, i - 1))
        Candle(i * 86_400_000L, o, maxOf(o, c) * 1.01, minOf(o, c) * 0.99, c, vol(i))
    }

    private fun check(cs: List<Candle>) {
        val r = KlineAnalysis.analyze(cs, "日")
        assertNotNull(r); r!!
        assertTrue(r.entry.level in -2..2)
        assertTrue(!r.score.isNaN())
        r.plan?.let { assertTrue(it.stop > 0 && !it.rr.isNaN()) }
    }

    @Test fun tooShortReturnsNull() = assertNull(KlineAnalysis.analyze(series(10, { 10.0 }), "日"))

    @Test fun uptrend() = check(series(200, { 50.0 + it * 0.3 }))

    @Test fun downtrend() = check(series(200, { 120.0 - it * 0.3 }))

    @Test fun sideways() = check(series(200, { 80.0 + Math.sin(it / 5.0) * 3 }))

    @Test fun flatZeroVolume() {
        // 完全不動、零成交量（停牌）：不可除以零當機
        val cs = (0 until 120).map { Candle(it * 86_400_000L, 10.0, 10.0, 10.0, 10.0, 0.0) }
        KlineAnalysis.analyze(cs, "日")?.let { assertTrue(it.entry.level in -2..2) }
    }

    @Test fun gapCrash() = check(series(150, { if (it < 140) 100.0 + it * 0.1 else 60.0 }, { if (it >= 140) 9000.0 else 1000.0 }))
}
