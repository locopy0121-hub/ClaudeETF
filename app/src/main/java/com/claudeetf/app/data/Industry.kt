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

    /** 個股頁標題用的產業資訊：市場別、產業、（美股）細分產業 */
    data class Detail(val board: String, val sector: String?, val industry: String?)
    val details = androidx.compose.runtime.mutableStateMapOf<String, Detail>()
    private var twMap: Pair<String, Map<String, String>>? = null
    private var tpexMap: Pair<String, Map<String, String>>? = null
    private fun today() = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).toString()

    suspend fun detail(q: Quote): Detail? = withContext(Dispatchers.IO) {
        details[q.symbol]?.let { return@withContext it }
        val d = if (q.market == Market.TW) {
            val tw = twMap?.takeIf { it.first == today() }?.second
                ?: runCatching { TwOpenData.industries() }.getOrNull()?.also { twMap = today() to it }
            tw?.get(q.symbol)?.let { Detail("上市", it, null) } ?: run {
                val tp = tpexMap?.takeIf { it.first == today() }?.second
                    ?: runCatching { com.claudeetf.app.data.remote.TpexOpenData.industries() }.getOrNull()?.also { tpexMap = today() to it }
                tp?.get(q.symbol)?.let { Detail("上櫃", it, null) }
                    ?: if (q.source == QuoteSource.TPEX) Detail("上櫃", null, null) else null
            }
        } else runCatching {
            val p = YahooExtra.summary(q.symbol, listOf("assetProfile")).optJSONObject("assetProfile")
            val sec = p?.optString("sector")?.ifBlank { null }
            Detail("美股", sec?.let { usSectors[it] ?: it }, p?.optString("industry")?.ifBlank { null }?.let { usIndustry(it) })
        }.getOrNull()
        d?.also { withContext(Dispatchers.Main) {
            details[q.symbol] = it
            // 順便補進持股產業權重用的資料
            if (AppState.industries[q.symbol] == null && it.sector != null) AppState.putIndustries(mapOf(q.symbol to (if (q.market == Market.US) "美股" else "") + it.sector))
        } }
    }

    /** 常見美股細分產業中文 */
    private fun usIndustry(s: String): String = mapOf(
        "Semiconductors" to "半導體", "Semiconductor Equipment & Materials" to "半導體設備",
        "Software—Infrastructure" to "基礎軟體", "Software - Infrastructure" to "基礎軟體",
        "Software—Application" to "應用軟體", "Software - Application" to "應用軟體",
        "Consumer Electronics" to "消費電子", "Internet Content & Information" to "網路內容",
        "Internet Retail" to "網路零售", "Auto Manufacturers" to "汽車製造", "Banks—Diversified" to "綜合銀行",
        "Banks - Diversified" to "綜合銀行", "Credit Services" to "信用服務", "Drug Manufacturers—General" to "製藥",
        "Drug Manufacturers - General" to "製藥", "Entertainment" to "娛樂", "Discount Stores" to "量販零售",
        "Oil & Gas Integrated" to "綜合油氣", "Aerospace & Defense" to "航太國防", "Communication Equipment" to "通訊設備",
        "Information Technology Services" to "資訊服務", "Insurance—Diversified" to "綜合保險", "Insurance - Diversified" to "綜合保險",
        "Restaurants" to "餐飲", "Beverages—Non-Alcoholic" to "飲料", "Beverages - Non-Alcoholic" to "飲料",
        "Medical Devices" to "醫療器材", "Biotechnology" to "生物科技", "Telecom Services" to "電信服務",
        "Computer Hardware" to "電腦硬體", "Electronic Components" to "電子零組件", "Asset Management" to "資產管理",
        "Capital Markets" to "資本市場", "Utilities—Regulated Electric" to "電力公用", "Utilities - Regulated Electric" to "電力公用",
        "REIT—Specialty" to "特殊 REIT", "REIT - Specialty" to "特殊 REIT", "Specialty Retail" to "專賣零售",
    )[s] ?: s

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
