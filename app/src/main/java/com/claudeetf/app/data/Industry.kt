package com.claudeetf.app.data

import com.claudeetf.app.data.remote.TwOpenData
import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 持股產業：台股用證交所產業別，美股用 Yahoo 產業；ETF 依類型歸類 */
object IndustryRepo {
    private val usSectors = mapOf(
        "Technology" to "科技", "Healthcare" to "醫療保健", "Financial Services" to "金融",
        "Consumer Cyclical" to "非必需消費", "Consumer Defensive" to "必需消費", "Communication Services" to "通訊服務",
        "Industrials" to "工業", "Energy" to "能源", "Utilities" to "公用事業", "Real Estate" to "不動產",
        "Basic Materials" to "原物料",
    )

    private fun isEtf(symbol: String): Boolean {
        val q = AppState.quote(symbol)
        return q?.isEtf == true || symbol.startsWith("00")
    }

    /** 尚未知道產業的持股：下載一次後存在本機 */
    suspend fun ensure() = withContext(Dispatchers.IO) {
        val missing = AppState.holdings.map { it.symbol }.filter { !isEtf(it) && AppState.industries[it] == null }
        if (missing.isEmpty()) return@withContext
        val found = HashMap<String, String>()
        val tw = missing.filter { AppState.quote(it)?.market == Market.TW }
        if (tw.isNotEmpty()) {
            runCatching { TwOpenData.industries() }.getOrNull()?.let { all ->
                tw.forEach { s -> found[s] = all[s] ?: "上櫃／其他" }
            }
        }
        missing.filter { AppState.quote(it)?.market == Market.US }.forEach { s ->
            runCatching {
                YahooExtra.summary(s, listOf("assetProfile")).optJSONObject("assetProfile")?.optString("sector")?.ifBlank { null }
            }.getOrNull()?.let { found[s] = "美股" + (usSectors[it] ?: it) }
        }
        withContext(Dispatchers.Main) { AppState.putIndustries(found) }
    }

    fun of(symbol: String): String {
        val q = AppState.quote(symbol)
        if (isEtf(symbol)) return when (TaxRules.kindOf(symbol)) {
            TaxKind.TW_ETF_BOND -> "債券 ETF"
            TaxKind.TW_ETF_OVERSEAS -> "海外 ETF"
            TaxKind.TW_ETF_OPTION -> "收益型 ETF"
            else -> if (q?.market == Market.US) "美國 ETF" else "台股 ETF"
        }
        return AppState.industries[symbol] ?: if (q?.market == Market.US) "美股（產業載入中）" else "台股（產業載入中）"
    }
}
