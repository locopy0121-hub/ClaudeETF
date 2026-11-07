package com.claudeetf.app

import com.claudeetf.app.data.Drawdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawdownTest {
    private val day = 86_400_000L
    private fun t(n: Int) = (0 until n).map { it * day }

    @Test fun singleWaveRecovers() {
        val c = listOf(100.0, 90.0, 70.0, 80.0, 100.0, 110.0)
        val w = Drawdown.waves(t(c.size), c)
        assertEquals(1, w.size)
        assertEquals(-30.0, w[0].depth, 1e-9)
        assertEquals(4 * day, w[0].recoverT)
        assertTrue(!w[0].ongoing)
    }

    @Test fun ongoingWave() {
        val c = listOf(100.0, 120.0, 100.0, 90.0, 95.0)
        val w = Drawdown.waves(t(c.size), c)
        assertEquals(1, w.size)
        assertEquals(-25.0, w[0].depth, 1e-9)
        assertNull(w[0].recoverT)
        assertTrue(w[0].ongoing)
    }

    @Test fun bearRallyInsideCrashStaysOneWave() {
        // 跌 80%、反彈 50%（未翻倍）再破底：仍是同一波
        val c = listOf(100.0, 20.0, 30.0, 15.0, 50.0, 100.0)
        val w = Drawdown.waves(t(c.size), c)
        assertEquals(1, w.size)
        assertEquals(-85.0, w[0].depth, 1e-9)
    }

    @Test fun longBearSplitsAfterDoubling() {
        // 網路泡沫型：100 → 17（-83%），翻倍到 55，再跌 53% 到 25.85，最後回到 100
        val c = listOf(100.0, 17.0, 40.0, 55.0, 25.85, 60.0, 100.0)
        val w = Drawdown.waves(t(c.size), c)
        assertEquals(2, w.size)
        assertEquals(-83.0, w[0].depth, 1e-9)
        assertEquals(6 * day, w[0].recoverT)          // 回到原前高的日期
        assertEquals(-53.0, w[1].depth, 1e-9)
        assertEquals(5 * day, w[1].recoverT)          // 第二波以反彈高點 55 為前高
    }

    @Test fun bucketsAndFrequency() {
        val c = ArrayList<Double>()
        repeat(400) { c += 100.0 + it * 0.1 }
        c += listOf(80.0, 150.0)                           // 一波約 -42%
        val s = Drawdown.stats(t(c.size), c)!!
        assertEquals(1, s.buckets.first { it.threshold == 40 }.waves.size)
        assertEquals(0, s.buckets.first { it.threshold == 50 }.waves.size)
        assertTrue(s.buckets.first { it.threshold == 10 }.everyYears!! > 0)
        assertEquals(0.0, s.fromHigh, 1e-9)
    }
}
