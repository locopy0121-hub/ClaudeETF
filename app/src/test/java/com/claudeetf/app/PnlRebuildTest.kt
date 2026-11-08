package com.claudeetf.app

import com.claudeetf.app.data.PnlRebuild
import com.claudeetf.app.data.PnlRebuild.Tx
import com.claudeetf.app.data.TradeType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PnlRebuildTest {
    private fun d(day: Int) = LocalDate.of(2026, 11, day)

    @Test fun buyDayOnlyCountsMoveAfterPrice() {
        // 11/2 以 100 買 1000 股（手續費 142），收盤 102；11/3 收 99
        val txs = listOf(Tx(TradeType.BUY, "0050", d(2), 1000.0, 100_000.0, 100.0, 142.0, 0.0))
        val closes = mapOf("0050" to mapOf(d(2) to 102.0, d(3) to 99.0))
        val r = PnlRebuild.compute(txs, closes, mapOf("0050" to 1.0), d(2), d(3))
        assertEquals(102_000.0 - 100_142.0, r[d(2)]!!, 1e-6)   // +1,858
        assertEquals(-3_000.0, r[d(3)]!!, 1e-6)
    }

    @Test fun depositOrBigTradeIsNotALoss() {
        // 已持有 1000 股；11/3 賣出全部（不是虧損），價格不變 → 當天損益只有成本
        val txs = listOf(
            Tx(TradeType.BUY, "2880", d(1), 1000.0, 48_000.0, 48.0, 20.0, 0.0),
            Tx(TradeType.SELL, "2880", d(3), 1000.0, 48_000.0, 48.0, 20.0, 48.0),
        )
        val closes = mapOf("2880" to mapOf(d(2) to 48.0, d(3) to 48.0, d(4) to 50.0))
        val r = PnlRebuild.compute(txs, closes, mapOf("2880" to 1.0), d(2), d(4))
        assertEquals(0.0, r[d(2)]!!, 1e-6)
        assertEquals(-68.0, r[d(3)]!!, 1e-6)       // 只有手續費 20 ＋ 交易稅 48
        assertEquals(0.0, r[d(4)]!!, 1e-6)         // 已無持股，漲價與我無關
    }

    @Test fun splitDoesNotCreateFakeLoss() {
        // 1 拆 4：歷史價已還原（拆股前 400 → 100），拆股當天不該有損益
        val txs = listOf(
            Tx(TradeType.BUY, "X", d(1), 10.0, 4_000.0, 400.0, 0.0, 0.0),
            Tx(TradeType.SPLIT, "X", d(3), 0.0, 0.0, 4.0, 0.0, 0.0),
        )
        val closes = mapOf("X" to mapOf(d(2) to 100.0, d(3) to 100.0, d(4) to 101.0))
        val r = PnlRebuild.compute(txs, closes, mapOf("X" to 1.0), d(2), d(4))
        assertEquals(0.0, r[d(3)]!!, 1e-6)
        assertEquals(40.0, r[d(4)]!!, 1e-6)        // 40 股 × 1
    }

    @Test fun dividendCountsAsGain() {
        val txs = listOf(
            Tx(TradeType.BUY, "0056", d(1), 1000.0, 36_000.0, 36.0, 0.0, 0.0),
            Tx(TradeType.DIVIDEND, "0056", d(3), 1000.0, 1_000.0, 1.0, 10.0, 0.0),
        )
        val closes = mapOf("0056" to mapOf(d(2) to 36.0, d(3) to 35.0))
        val r = PnlRebuild.compute(txs, closes, mapOf("0056" to 1.0), d(2), d(3))
        assertEquals(-1_000.0 + 990.0, r[d(3)]!!, 1e-6)  // 除息跌 1 元 × 1000，領 990
    }
}
