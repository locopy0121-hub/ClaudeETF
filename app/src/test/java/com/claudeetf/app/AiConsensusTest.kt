package com.claudeetf.app

import com.claudeetf.app.data.AiAnalysis
import com.claudeetf.app.data.StockMetrics.StockAnalysisReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConsensusTest {
    private fun r(sig: String, c: Int) = StockAnalysisReport("00949", sig, c, "v", "t", "k", null, null, "", "", "", "", "", "ai", "m")

    @Test fun majorityWins() {
        val x = AiAnalysis.consensus(listOf(r("區間震盪", 62), r("多頭回檔", 85), r("區間震盪", 75)), 3)!!
        assertEquals("區間震盪", x.overallSignal)
        assertTrue(x.model.contains("2/3"))
    }

    @Test fun tieTakesConservative() {
        val x = AiAnalysis.consensus(listOf(r("強勢多頭", 80), r("區間震盪", 60)), 3)!!
        assertEquals("區間震盪", x.overallSignal)
    }

    @Test fun unanimousKeepsMedian() {
        val x = AiAnalysis.consensus(listOf(r("多頭回檔", 60), r("多頭回檔", 70), r("多頭回檔", 80)), 3)!!
        assertEquals(70, x.confidence)
    }
}
