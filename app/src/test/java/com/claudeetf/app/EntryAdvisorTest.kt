package com.claudeetf.app

import com.claudeetf.app.data.EntryAdvisor
import com.claudeetf.app.data.KlineAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryAdvisorTest {
    private fun candles(f: (Int) -> Double): List<KlineAnalysis.Candle> = (0 until 200).map { i ->
        val c = f(i); KlineAnalysis.Candle(i * 86_400_000L, c, c * 1.01, c * 0.99, c, 1000.0)
    }

    @Test fun volatilityCalmVsSpike() {
        val calm = EntryAdvisor.volatility(candles { 100.0 + kotlin.math.sin(it / 3.0) })
        assertNotNull(calm)
        val spike = EntryAdvisor.volatility(candles { i -> if (i < 180) 100.0 + kotlin.math.sin(i / 3.0) else 100.0 + (if (i % 2 == 0) 8.0 else -8.0) })!!
        assertTrue("spike regime ${spike.regime}", spike.regime > 1.5)
        assertEquals("波動急遽放大", spike.label)
    }
}
