package com.claudeetf.app

import com.claudeetf.app.data.KlineAnalysis.Candle
import com.claudeetf.app.data.KlineConditions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KlineConditionsTest {
    @Test fun rsiAllUpIs100() {
        val c = (1..30).map { it.toDouble() }
        assertEquals(100.0, KlineConditions.rsi(c)!!, 1e-9)
    }

    @Test fun rsiFallingIsLow() {
        val c = (30 downTo 1).map { it.toDouble() } + listOf(1.5)
        assertTrue(KlineConditions.rsi(c)!! < 20)
    }

    @Test fun kdRisingCloseNearHigh() {
        val cs = (1..20).map { Candle(it.toLong(), it - 0.5, it + 0.2, it - 1.0, it.toDouble(), 100.0) }
        val k = KlineConditions.kd(cs).last()
        assertTrue(k.first > 70 && k.second > 60)
    }
}
