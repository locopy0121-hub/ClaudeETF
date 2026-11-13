package com.claudeetf.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * 分析參數（進階）：推薦排行、每日盤後報表的分析範圍、篩選門檻、顯示檔數與綜合分數權重。
 * 存在 AppState 的偏好設定（cache_analysisCfg），背景報表也讀得到。預設值＝原本寫死的數值。
 */
object AnalysisConfig {
    data class Cfg(
        /** 分析範圍：上市／上櫃／ETF 成交值前 N 檔 */
        val tse: Int = 120, val otc: Int = 100, val etf: Int = 80,
        /** 市場頁推薦每邊顯示檔數（個股／ETF） */
        val top: Int = 10, val etfTop: Int = 15,
        /** 每日盤後報表每邊檔數（個股／ETF） */
        val reportTop: Int = 25, val reportEtfTop: Int = 15,
        /** 個股篩選：最低股價（元）、最低成交量（張） */
        val minPrice: Double = 10.0, val minLots: Int = 1000,
        /** ETF 篩選：最低成交量（張） */
        val etfMinLots: Int = 200,
        /** 列入看漲／看跌的分數門檻（絕對值）：個股、ETF */
        val threshold: Double = 0.6, val etfThreshold: Double = 0.3,
        /** 綜合分數各項權重倍數（1.0＝預設） */
        val wKline: Double = 1.0, val wMa60: Double = 1.0, val wMom: Double = 1.0, val wEntry: Double = 1.0, val wZone: Double = 1.0,
        /** 綜合進場建議：各面向權重倍數（技術、價位、基本面、籌碼、大盤、風險、ETF） */
        val gTech: Double = 1.0, val gValue: Double = 1.0, val gFund: Double = 1.0, val gChip: Double = 1.0,
        val gMarket: Double = 1.0, val gRisk: Double = 1.0, val gEtf: Double = 1.0,
        /** 單一指標權重上限（占比）、「建議進場」與「可分批」分數門檻 */
        val cap: Double = 0.18, val strongTh: Double = 0.85, val entryTh: Double = 0.3,
    ) {
        fun groupWeight(g: String): Double = when (g) {
            "技術" -> gTech; "價位" -> gValue; "基本面" -> gFund; "籌碼" -> gChip; "大盤" -> gMarket; "風險" -> gRisk; "ETF" -> gEtf; else -> 1.0
        }

        fun toJson(): JSONObject = JSONObject().put("tse", tse).put("otc", otc).put("etf", etf).put("top", top).put("etfTop", etfTop)
            .put("rTop", reportTop).put("rEtfTop", reportEtfTop).put("minP", minPrice).put("minL", minLots).put("eMinL", etfMinLots)
            .put("th", threshold).put("eTh", etfThreshold)
            .put("wK", wKline).put("wMa", wMa60).put("wMom", wMom).put("wE", wEntry).put("wZ", wZone)
            .put("gT", gTech).put("gV", gValue).put("gF", gFund).put("gC", gChip).put("gM", gMarket).put("gR", gRisk).put("gE", gEtf)
            .put("cap", cap).put("sTh", strongTh).put("eTh2", entryTh)

        companion object {
            fun of(o: JSONObject): Cfg {
                val d = Cfg()
                return Cfg(
                    o.optInt("tse", d.tse).coerceIn(30, 300), o.optInt("otc", d.otc).coerceIn(30, 300), o.optInt("etf", d.etf).coerceIn(20, 200),
                    o.optInt("top", d.top).coerceIn(3, 30), o.optInt("etfTop", d.etfTop).coerceIn(3, 30),
                    o.optInt("rTop", d.reportTop).coerceIn(5, 50), o.optInt("rEtfTop", d.reportEtfTop).coerceIn(5, 40),
                    o.optDouble("minP", d.minPrice).coerceIn(0.0, 1000.0), o.optInt("minL", d.minLots).coerceIn(0, 100_000),
                    o.optInt("eMinL", d.etfMinLots).coerceIn(0, 100_000),
                    o.optDouble("th", d.threshold).coerceIn(0.0, 5.0), o.optDouble("eTh", d.etfThreshold).coerceIn(0.0, 5.0),
                    o.optDouble("wK", 1.0).coerceIn(0.0, 3.0), o.optDouble("wMa", 1.0).coerceIn(0.0, 3.0), o.optDouble("wMom", 1.0).coerceIn(0.0, 3.0),
                    o.optDouble("wE", 1.0).coerceIn(0.0, 3.0), o.optDouble("wZ", 1.0).coerceIn(0.0, 3.0),
                    o.optDouble("gT", 1.0).coerceIn(0.0, 3.0), o.optDouble("gV", 1.0).coerceIn(0.0, 3.0), o.optDouble("gF", 1.0).coerceIn(0.0, 3.0),
                    o.optDouble("gC", 1.0).coerceIn(0.0, 3.0), o.optDouble("gM", 1.0).coerceIn(0.0, 3.0), o.optDouble("gR", 1.0).coerceIn(0.0, 3.0),
                    o.optDouble("gE", 1.0).coerceIn(0.0, 3.0),
                    o.optDouble("cap", d.cap).coerceIn(0.08, 1.0), o.optDouble("sTh", d.strongTh).coerceIn(0.3, 2.0), o.optDouble("eTh2", d.entryTh).coerceIn(0.05, 1.5),
                )
            }
        }
    }

    private var loaded = false
    private var state by mutableStateOf(Cfg())

    /** 目前設定（第一次讀取時從偏好設定載入） */
    val cfg: Cfg
        get() {
            if (!loaded) {
                loaded = true
                AppState.cacheGet("analysisCfg")?.let { s -> runCatching { state = Cfg.of(JSONObject(s)) } }
            }
            return state
        }

    /** 套用設定檔後重新從偏好設定讀取 */
    fun reloadFromPrefs() { loaded = false }

    fun update(f: (Cfg) -> Cfg) {
        val n = f(cfg)
        state = n
        AppState.cachePut("analysisCfg", n.toJson().toString())
    }

    fun reset() = update { Cfg() }

    val isDefault: Boolean get() = cfg == Cfg()
}
