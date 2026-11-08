package com.claudeetf.app

import com.claudeetf.app.data.LookThrough
import com.claudeetf.app.data.LookThrough.Position
import org.junit.Assert.assertEquals
import org.junit.Test

class LookThroughTest {
    private val etfA = Position("0050", "元大台灣50", 100_000.0, listOf(
        Triple("2330", "台積電", 60.0), Triple("2317", "鴻海", 10.0), Triple("", "CASH", 2.0),
    ))
    private val etfB = Position("00878", "國泰永續高股息", 50_000.0, listOf(
        Triple("2330", "台積電", 5.0), Triple("2880", "華南金", 4.0),
    ))
    private val stock = Position("2330", "台積電", 20_000.0, null)

    @Test fun exposureFormula() {
        val r = LookThrough.compute(listOf(etfA, etfB, stock))
        assertEquals(170_000.0, r.total, 1e-6)
        val tsmc = r.exposures.first { it.symbol == "2330" }
        // 20,000 直接 + 100,000×60% + 50,000×5% = 82,500
        assertEquals(20_000.0, tsmc.direct, 1e-6)
        assertEquals(60_000.0, tsmc.via["0050"]!!, 1e-6)
        assertEquals(2_500.0, tsmc.via["00878"]!!, 1e-6)
        assertEquals(82_500.0, tsmc.total, 1e-6)
        assertEquals("2330", r.exposures.first().symbol)
    }

    @Test fun undisclosedGoesToOther() {
        val r = LookThrough.compute(listOf(etfA, etfB))
        // 0050：揭露個股 70%，其餘 30%（含 CASH 2%）＝ 30,000；00878：揭露 9%，其餘 45,500
        assertEquals(30_000.0, r.other["0050"]!!, 1e-6)
        assertEquals(45_500.0, r.other["00878"]!!, 1e-6)
        // 個股曝險＋其他＝總市值（守恆）
        assertEquals(r.total, r.exposures.sumOf { it.total } + r.other.values.sum(), 1e-6)
    }

    @Test fun overlapIsSumOfMinWeights() {
        val r = LookThrough.compute(listOf(etfA, etfB))
        assertEquals(1, r.overlap.size)
        assertEquals(5.0, r.overlap[0].third, 1e-9)   // 只有台積電共同：min(60, 5)
    }
}
