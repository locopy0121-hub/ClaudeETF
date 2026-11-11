package com.claudeetf.app

import com.claudeetf.app.data.KBarSignalEngine
import com.claudeetf.app.data.KBarSignalEngine.StrategyType
import com.claudeetf.app.data.KlineAnalysis.Candle
import org.junit.Assert.assertTrue
import org.junit.Test

class KBarSignalEngineTest {
    private fun bar(i: Int, o: Double, h: Double, l: Double, c: Double, v: Double = 1000.0) = Candle(i * 86_400_000L, o, h, l, c, v)

    /** 緩漲盤整的基底資料（每根小幅震盪） */
    private fun base(n: Int, start: Double = 100.0, drift: Double = 0.05): MutableList<Candle> =
        (0 until n).map { i ->
            val c = start + i * drift + (if (i % 2 == 0) 0.4 else -0.4)
            bar(i, c - 0.2, c + 0.6, c - 0.6, c)
        }.toMutableList()

    @Test fun stockSfpBullish() {
        val cs = base(60)
        val last = cs.last().c
        val swingLow = cs.takeLast(20).minOf { it.l }
        // 刺穿前低後收回，長下影線
        cs += bar(60, last, last + 0.3, swingLow - 3.0, last + 0.1, 2500.0)
        val s = KBarSignalEngine.analyze("2330", cs, etf = false)
        assertTrue(s.toString(), s.any { it.strategy == StrategyType.SFP && it.bullish && it.entryTimingType == KBarSignalEngine.EntryTimingType.BAR_CLOSE_CONFIRMATION })
    }

    @Test fun stockBoxBreakout() {
        // 15 根以上窄幅箱型，再爆量長紅突破
        val cs = (0 until 60).map { i -> val c = 100.0 + (if (i % 2 == 0) 0.5 else -0.5); bar(i, c, c + 0.8, c - 0.8, c) }.toMutableList()
        cs += bar(60, 100.8, 104.2, 100.7, 104.0, 4000.0)
        val s = KBarSignalEngine.analyze("2317", cs, etf = false)
        assertTrue(s.toString(), s.any { it.strategy == StrategyType.BOX_BREAKOUT && it.bullish && it.takeProfitTargets.tp1 > 104.0 })
    }

    @Test fun etfInsideBarWaitsForBreakout() {
        val cs = base(60, drift = 0.2)
        val p = cs.last().c
        cs += bar(60, p, p + 3.0, p - 1.0, p + 2.0)      // 母棒
        cs += bar(61, p + 1.0, p + 2.0, p + 0.2, p + 1.5) // 子棒
        val s = KBarSignalEngine.analyze("0050", cs, etf = true) { 0.05 }
        val ib = s.firstOrNull { it.strategy == StrategyType.INSIDE_BAR }
        assertTrue(s.toString(), ib != null && ib.status == KBarSignalEngine.Status.WAITING &&
            ib.entryTimingType == KBarSignalEngine.EntryTimingType.INTRADAY_PRICE_BREAKOUT && ib.entryPrice > p + 3.0)
    }

    @Test fun etfPanicExhaustion() {
        // 連續下跌讓 RSI 低於 30，再出現爆量長下影線
        val cs = base(40).toMutableList()
        var p = cs.last().c
        for (i in 40 until 58) { val n = p - 1.2; cs += bar(i, p, p + 0.1, n - 0.1, n, 1000.0); p = n }
        cs += bar(58, p, p + 0.2, p - 6.0, p + 0.1, 3000.0)
        val s = KBarSignalEngine.analyze("0056", cs, etf = true) { 0.01 }
        assertTrue(s.toString(), s.any { it.strategy == StrategyType.PANIC_EXHAUSTION && it.bullish })
    }

    @Test fun stockEngineIgnoresEtfStrategies() {
        val cs = base(80)
        val s = KBarSignalEngine.analyze("2330", cs, etf = false)
        assertTrue(s.none { it.strategy.forEtf })
    }
}
