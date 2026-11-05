package com.claudeetf.app.data

import com.claudeetf.app.data.remote.TwOpenData
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.data.remote.fmt
import com.claudeetf.app.data.remote.raw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

data class InfoItem(val label: String, val value: String)

data class Fundamentals(
    val overview: List<InfoItem>,
    val financials: List<InfoItem>,
    /** 季度 EPS：期間、實際、預估 */
    val quarterly: List<Triple<String, String, String>>,
    /** 年度：年份、營收、淨利 */
    val yearly: List<Triple<String, String, String>>,
    val profile: List<InfoItem>,
    val summary: String?,
    val analyst: List<InfoItem>,
)

data class DividendInfo(
    val history: List<YahooExtra.Dividend>,
    val ttm: Double,
    val yieldPct: Double?,
    val extra: List<InfoItem>,
)

/** 個股詳細資料：概覽、財務、股息、新聞、公司。各部分獨立載入，失敗不影響其他部分。 */
object StockInfoRepo {
    private const val TTL = 10 * 60_000L
    private val fundCache = HashMap<String, Pair<Long, Fundamentals>>()
    private val newsCache = HashMap<String, Pair<Long, List<YahooExtra.News>>>()

    /** Yahoo 代號：上市 .TW、上櫃 .TWO，美股不變 */
    fun yahooSymbol(q: Quote): String = when (q.market) {
        Market.US -> q.symbol
        Market.TW -> q.symbol + if (q.source == QuoteSource.TPEX) ".TWO" else ".TW"
    }

    suspend fun history(q: Quote, range: String): YahooExtra.History = withContext(Dispatchers.IO) {
        YahooExtra.history(yahooSymbol(q), range)
    }

    suspend fun fundamentals(q: Quote): Fundamentals = withContext(Dispatchers.IO) {
        val key = q.symbol
        fundCache[key]?.let { (t, f) -> if (System.currentTimeMillis() - t < TTL) return@withContext f }
        val f = buildFundamentals(q)
        fundCache[key] = System.currentTimeMillis() to f
        f
    }

    private fun buildFundamentals(q: Quote): Fundamentals {
        val ys = yahooSymbol(q)
        val s: JSONObject? = runCatching {
            YahooExtra.summary(
                ys,
                listOf("summaryDetail", "defaultKeyStatistics", "financialData", "calendarEvents", "assetProfile", "earnings", "price"),
            )
        }.getOrNull()
        val sd = s?.optJSONObject("summaryDetail")
        val ks = s?.optJSONObject("defaultKeyStatistics")
        val fd = s?.optJSONObject("financialData")
        val ce = s?.optJSONObject("calendarEvents")
        val ap = s?.optJSONObject("assetProfile")
        val er = s?.optJSONObject("earnings")
        val pr = s?.optJSONObject("price")

        val tw = q.market == Market.TW
        val twVal = if (tw) runCatching { TwOpenData.valuation(q.symbol) }.getOrNull() else null
        val twRev = if (tw) runCatching { TwOpenData.monthlyRevenue(q.symbol) }.getOrNull() else null
        val twCo = if (tw) runCatching { TwOpenData.company(q.symbol) }.getOrNull() else null

        if (s == null && twVal == null && twRev == null && twCo == null) {
            throw IllegalStateException("無法取得 ${q.symbol} 的基本資料")
        }

        val nextEarnings = ce?.optJSONObject("earnings")?.optJSONArray("earningsDate")?.optJSONObject(0)?.optString("fmt")
        val ccy = pr?.optString("currency")?.ifBlank { null } ?: q.market.currency

        val overview = listOfNotNull(
            sd.fmt("open")?.let { InfoItem("開盤", it) },
            sd.fmt("previousClose")?.let { InfoItem("昨收", it) },
            sd.fmt("dayHigh")?.let { InfoItem("最高", it) },
            sd.fmt("dayLow")?.let { InfoItem("最低", it) },
            sd.fmt("volume")?.let { InfoItem("成交量", it) },
            sd.fmt("averageVolume")?.let { InfoItem("平均量", it) },
            sd.fmt("fiftyTwoWeekHigh")?.let { InfoItem("52 週高", it) },
            sd.fmt("fiftyTwoWeekLow")?.let { InfoItem("52 週低", it) },
            (sd.fmt("marketCap") ?: pr.fmt("marketCap"))?.let { InfoItem("市值（$ccy）", it) },
            (twVal?.pe ?: sd.fmt("trailingPE"))?.let { InfoItem("本益比", it) },
            sd.fmt("forwardPE")?.let { InfoItem("預估本益比", it) },
            (twVal?.pb ?: ks.fmt("priceToBook"))?.let { InfoItem("股價淨值比", it) },
            ks.fmt("trailingEps")?.let { InfoItem("EPS（近四季）", it) },
            (twVal?.yieldPct?.let { "$it%" } ?: sd.fmt("dividendYield") ?: sd.fmt("trailingAnnualDividendYield"))
                ?.let { InfoItem("殖利率", it) },
            sd.fmt("beta")?.let { InfoItem("Beta", it) },
            nextEarnings?.let { InfoItem("下次財報", it) },
        )

        val financials = ArrayList<InfoItem>()
        twRev?.let { r ->
            financials += InfoItem("月營收（${r.month}）", r.revenueK?.let { "%,.1f 億元".format(Locale.US, it / 1e5) } ?: "—")
            r.momPct?.let { financials += InfoItem("月增率", "$it%") }
            r.yoyPct?.let { financials += InfoItem("年增率", "$it%") }
            r.ytdK?.let { financials += InfoItem("今年累計營收", "%,.1f 億元".format(Locale.US, it / 1e5)) }
            r.ytdYoyPct?.let { financials += InfoItem("累計年增率", "$it%") }
        }
        listOf(
            "totalRevenue" to "營收（近四季）",
            "revenueGrowth" to "營收成長（年）",
            "grossMargins" to "毛利率",
            "operatingMargins" to "營業利益率",
            "profitMargins" to "淨利率",
            "returnOnEquity" to "ROE",
            "returnOnAssets" to "ROA",
            "earningsGrowth" to "獲利成長（年）",
            "debtToEquity" to "負債權益比",
            "freeCashflow" to "自由現金流",
            "totalCash" to "現金",
            "totalDebt" to "總負債",
        ).forEach { (k, label) -> fd.fmt(k)?.let { financials += InfoItem(label, it) } }

        val quarterly = ArrayList<Triple<String, String, String>>()
        er?.optJSONObject("earningsChart")?.optJSONArray("quarterly")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                quarterly += Triple(o.optString("date"), o.fmt("actual") ?: "—", o.fmt("estimate") ?: "—")
            }
        }
        val yearly = ArrayList<Triple<String, String, String>>()
        er?.optJSONObject("financialsChart")?.optJSONArray("yearly")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                yearly += Triple(o.optString("date"), o.fmt("revenue") ?: "—", o.fmt("earnings") ?: "—")
            }
        }

        val profile = ArrayList<InfoItem>()
        twCo?.fields?.forEach { (k, v) -> profile += InfoItem(k, v) }
        if (twCo == null) twRev?.industry?.let { profile += InfoItem("產業別", it) }
        if (profile.isEmpty()) {
            ap?.optString("sector")?.ifBlank { null }?.let { profile += InfoItem("類股", it) }
            ap?.optString("industry")?.ifBlank { null }?.let { profile += InfoItem("產業", it) }
            ap?.optInt("fullTimeEmployees", 0)?.takeIf { it > 0 }?.let { profile += InfoItem("員工數", "%,d".format(Locale.US, it)) }
            listOfNotNull(ap?.optString("city")?.ifBlank { null }, ap?.optString("country")?.ifBlank { null })
                .takeIf { it.isNotEmpty() }?.let { profile += InfoItem("總部", it.joinToString(", ")) }
            ap?.optString("website")?.ifBlank { null }?.let { profile += InfoItem("網址", it) }
        }
        val summary = ap?.optString("longBusinessSummary")?.ifBlank { null }

        val analyst = listOfNotNull(
            fd?.optString("recommendationKey")?.ifBlank { null }?.takeIf { it != "none" }?.let {
                InfoItem("分析師建議", mapOf("strong_buy" to "強力買進", "buy" to "買進", "hold" to "持有", "sell" to "賣出", "strong_sell" to "強力賣出")[it] ?: it)
            },
            fd.fmt("targetMeanPrice")?.let { InfoItem("平均目標價", it) },
            fd.fmt("targetHighPrice")?.let { InfoItem("最高目標價", it) },
            fd.fmt("targetLowPrice")?.let { InfoItem("最低目標價", it) },
            fd.fmt("numberOfAnalystOpinions")?.let { InfoItem("分析師人數", it) },
        )

        return Fundamentals(overview, financials, quarterly, yearly, profile, summary, analyst)
    }

    suspend fun dividends(q: Quote): DividendInfo = withContext(Dispatchers.IO) {
        val list = YahooExtra.dividends(yahooSymbol(q))
        val yearAgo = System.currentTimeMillis() - 365L * 24 * 3600 * 1000
        val ttm = list.filter { it.time >= yearAgo }.sumOf { it.amount }
        val yieldPct = if (q.price > 0 && ttm > 0) ttm / q.price * 100 else null
        val extra = ArrayList<InfoItem>()
        if (q.market == Market.US) extra += InfoItem("美股預扣稅", "30%（實收 = 配息 × 0.7）")
        if (q.market == Market.TW) extra += InfoItem("二代健保", "單筆 ≥ NT$20,000 扣 2.11%")
        DividendInfo(list, ttm, yieldPct, extra)
    }

    /** 台股用 Google 中文新聞；美股用 Yahoo 新聞，失敗時改用 Google */
    suspend fun news(q: Quote): List<YahooExtra.News> = withContext(Dispatchers.IO) {
        newsCache[q.symbol]?.let { (t, n) -> if (System.currentTimeMillis() - t < TTL) return@withContext n }
        val list = if (q.market == Market.TW) {
            com.claudeetf.app.data.remote.GoogleNews.search("${q.symbol} ${q.name} 股")
        } else {
            runCatching { YahooExtra.news(q.symbol) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: com.claudeetf.app.data.remote.GoogleNews.search("${q.symbol} ${q.name} 股價")
        }
        newsCache[q.symbol] = System.currentTimeMillis() to list
        list
    }
}
