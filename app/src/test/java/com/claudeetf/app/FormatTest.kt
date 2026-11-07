package com.claudeetf.app

import com.claudeetf.app.util.Fmt
import com.claudeetf.app.util.TwSession
import org.junit.Assert.assertEquals
import org.junit.Test

/** 行情數字無條件捨去、證交所升降單位 */
class FormatTest {
    @Test fun truncNotRound() {
        assertEquals("6.85", Fmt.price(6.8499999999))   // 浮點誤差視為 6.85
        assertEquals("7.74", Fmt.price(7.7399998))      // Yahoo 單精度
        assertEquals("1.23", Fmt.price(1.239))          // 捨去、不進位
        assertEquals("0.138", Fmt.numTrunc(0.1389, 3))
    }

    @Test fun ticks() {
        assertEquals(0.01, TwSession.tick(9.99, false), 0.0)
        assertEquals(0.05, TwSession.tick(30.0, false), 0.0)
        assertEquals(0.5, TwSession.tick(300.0, false), 0.0)
        assertEquals(5.0, TwSession.tick(1200.0, false), 0.0)
        assertEquals(0.01, TwSession.tick(31.99, true), 0.0)
        assertEquals(0.05, TwSession.tick(92.5, true), 0.0)
    }

    @Test fun snapDown() {
        assertEquals(92.5, TwSession.snap(92.54, true), 1e-9)
        assertEquals(30.55, TwSession.snap(30.57, false), 1e-9)
        assertEquals(7.74, TwSession.snap(7.7399998, false), 1e-9)
    }

    @Test fun limitsInsideTenPercent() {
        val (up, down) = TwSession.limits(100.0, false)
        assertEquals(110.0, up, 1e-9); assertEquals(90.0, down, 1e-9)
        val (up2, down2) = TwSession.limits(46.0, false)          // 50.6 落在 0.1 級距
        assertEquals(50.6, up2, 1e-9); assertEquals(41.4, down2, 1e-9)
    }
}
