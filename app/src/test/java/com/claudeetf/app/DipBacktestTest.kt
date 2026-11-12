package com.claudeetf.app

import com.claudeetf.app.data.Drawdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DipBacktestTest {
    private val day = 86_400_000L

    @Test fun buysAtThresholdAndMeasuresForward() {
        // 100 → 跌到 70（-30%）→ 兩年內漲到 130，之後持平 3 年
        val c = ArrayList<Double>()
        repeat(50) { c += 50.0 + it }            // 50..99
        c += 100.0
        repeat(60) { c += 100.0 - (it + 1) * 0.5 } // 到 70
        repeat(500) { c += 70.0 + (it + 1) * 0.12 } // 到 130
        repeat(800) { c += 130.0 }
        val t = c.indices.map { it * day }
        val rows = Drawdown.dipBacktest(t, c)
        val r20 = rows.first { it.threshold == 20 }
        assertEquals(1, r20.count)
        assertNotNull(r20.avg1y)
        assertTrue(r20.avg1y!! > 0)
        assertEquals(1, r20.recovered)
        assertTrue(rows.none { it.threshold == 40 })
    }
}
