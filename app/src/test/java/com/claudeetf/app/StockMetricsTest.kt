package com.claudeetf.app

import com.claudeetf.app.data.KlineAnalysis.Candle
import com.claudeetf.app.data.StockMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockMetricsTest {
    private fun bar(i: Int, c: Double, v: Double = 1000.0) = Candle(i * 86_400_000L, c, c + 1, c - 1, c, v)

    @Test fun rsiExtremes() {
        val up = (0 until 30).map { 100.0 + it }
        assertEquals(100.0, StockMetrics.rsi(up)!!, 1e-9)
        val down = (0 until 30).map { 100.0 - it }
        assertTrue(StockMetrics.rsi(down)!! < 1.0)
    }

    @Test fun kdRange() {
        val cs = (0 until 30).map { bar(it, 100.0 + it) }
        val (k, d) = StockMetrics.kd(cs)!!
        assertTrue(k in 0.0..100.0 && d in 0.0..100.0 && k > 80)
    }

    @Test fun priceVolume() {
        val cs = (0 until 10).map { bar(it, 100.0) } + bar(10, 95.0, 3000.0)
        val r = StockMetrics.volumeRatioToMa5(cs)
        assertNotNull(r)
        assertEquals("價跌爆量", StockMetrics.priceVolumeState(cs, r))
    }

    @Test fun localReportTrend() {
        val m = StockMetrics.ComputedStockMetrics(
            "2330", "台積電", "TW", false, "2026-11-12", 110.0, 108.0, 1.85,
            108.0, 106.0, 104.0, 100.0, 5.77, 62.0, 70.0, 65.0, 1.2, "價漲量增", false,
            listOf("多頭吞噬（陽包陰）"), "MA10", 5000, 1200, -300, 4, 18.0, 4.5, 2.1, "合理", 103.0, 115.0,
        )
        val r = StockMetrics.localReport(m)
        assertEquals("多頭趨勢", r.trend)
        assertEquals("投信連續吸籌", r.chipStatus)
        assertTrue(r.overallSignal == "多頭回檔" || r.overallSignal == "強勢多頭")
        assertTrue(r.verdict.length <= 35)
    }

    @Test fun parseTolerant() {
        val raw = "```json\n{\"ticker\":\"2330\",\"overall_signal\":\"區間震盪\",\"confidence_score\":60,\"one_line_verdict\":\"等方向\"," +
            "\"technical_analysis\":{\"trend\":\"盤整\",\"candlestick_insight\":\"x\",\"key_support\":100.5,\"key_resistance\":0}," +
            "\"chip_analysis\":{\"institutional_status\":\"分歧\",\"summary\":\"y\"},\"fundamental_valuation\":{\"valuation_status\":\"合理\",\"peer_comparison\":\"z\",\"risk_warning\":\"目前無重大異常訊號\"}}\n```"
        val r = StockMetrics.StockAnalysisReport.parse(raw, "claude")!!
        assertEquals("區間震盪", r.overallSignal)
        assertEquals(100.5, r.support!!, 1e-9)
        assertEquals(null, r.resistance)
    }
}
