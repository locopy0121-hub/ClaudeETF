package com.claudeetf.app.data

import com.claudeetf.app.data.remote.FugleApi
import com.claudeetf.app.data.remote.MoneyDjHoldings
import com.claudeetf.app.data.remote.TwInstitutional
import com.claudeetf.app.data.remote.TwOpenData
import com.claudeetf.app.data.remote.snapshot
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
    /** 除息時間 → 填息天數（日曆天）；null = 尚未填息；沒有鍵 = 無股價資料 */
    val fillDays: Map<Long, Int?> = emptyMap(),
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
        // 台股盤中、僅用富果：1 日分時改用富果 5 分 K
        if (range == "1d" && q.market == Market.TW && AppState.twUseFugle && AppState.fugleKey.isNotBlank() && MarketData.isTwIntraday()) {
            runCatching { fugleIntraday(q) }.getOrNull()?.let { return@withContext it }
        }
        YahooExtra.history(yahooSymbol(q), range, prePost = range == "1d" && q.market == Market.US && AppState.usExtendedChart)
    }

    private fun fugleIntraday(q: Quote): YahooExtra.History? {
        val c = FugleApi.candles(q.symbol, AppState.fugleKey, 5)
        if (c.size < 2) return null
        // 量的單位：與當日總量（股）比對，若明顯是「張」則換算為股
        val sum = c.sumOf { it.volume }
        val day = AppState.dayStats[q.symbol]?.volume
        val mult = if (day != null && sum > 0 && sum * 200 < day) 1000.0 else 1.0
        return YahooExtra.History(
            times = c.map { it.time },
            closes = c.map { it.close.toFloat() },
            prevClose = q.prevClose.takeIf { it > 0 },
            opens = c.map { it.open.toFloat() },
            highs = c.map { it.high.toFloat() },
            lows = c.map { it.low.toFloat() },
            volumes = c.map { (it.volume * mult).toFloat() },
            dayVolume = day,
        )
    }

    /**
     * 三關價（盤中參考）：以前一交易日最高 H、最低 L 計算
     * 上關 = L + (H − L) × 1.382、中關 = (H + L) ÷ 2、下關 = H − (H − L) × 1.382
     */
    data class KeyPrices(val upper: Double, val mid: Double, val lower: Double, val prevHigh: Double, val prevLow: Double, val basisDate: String)

    private val keyCache = HashMap<String, Pair<Long, KeyPrices>>()

    suspend fun keyPrices(q: Quote): KeyPrices? = withContext(Dispatchers.IO) {
        keyCache[q.symbol]?.let { (t, k) -> if (System.currentTimeMillis() - t < 30 * 60_000) return@withContext k }
        val h = YahooExtra.history(yahooSymbol(q), "1mo")
        val zone = java.time.ZoneId.of(if (q.market == Market.TW) "Asia/Taipei" else "America/New_York")
        fun day(i: Int) = java.time.Instant.ofEpochMilli(h.times[i]).atZone(zone).toLocalDate()
        val valid = h.times.indices.filter { i ->
            val hi = h.highs.getOrNull(i); val lo = h.lows.getOrNull(i)
            hi != null && lo != null && !hi.isNaN() && !lo.isNaN() && hi > 0 && lo > 0
        }
        if (valid.isEmpty()) return@withContext null
        val today = java.time.LocalDate.now(zone)
        // 今天已有 K 棒（盤中或已收盤）→ 用前一根；尚未開盤 → 最後一根就是前一交易日
        val k = if (day(valid.last()) == today) valid.getOrNull(valid.size - 2) else valid.last()
        k ?: return@withContext null
        val hi = h.highs[k].toDouble(); val lo = h.lows[k].toDouble()
        val r = hi - lo
        val kp = KeyPrices(lo + r * 1.382, (hi + lo) / 2, hi - r * 1.382, hi, lo, day(k).toString())
        keyCache[q.symbol] = System.currentTimeMillis() to kp
        kp
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
            px(sd, "open")?.let { InfoItem("開盤", it) },
            px(sd, "previousClose")?.let { InfoItem("昨收", it) },
            px(sd, "dayHigh")?.let { InfoItem("最高", it) },
            px(sd, "dayLow")?.let { InfoItem("最低", it) },
            sd.fmt("volume")?.let { InfoItem("成交量", it) },
            sd.fmt("averageVolume")?.let { InfoItem("平均量", it) },
            px(sd, "fiftyTwoWeekHigh")?.let { InfoItem("52 週高", it) },
            px(sd, "fiftyTwoWeekLow")?.let { InfoItem("52 週低", it) },
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
        // 填息天數：除息前一日收盤價，之後第一次收盤 ≥ 該價的天數（近 2 年日線）
        val fill = HashMap<Long, Int?>()
        runCatching {
            val h = YahooExtra.history(yahooSymbol(q), "2y")
            val zone = java.time.ZoneId.of(if (q.market == Market.TW) "Asia/Taipei" else "America/New_York")
            fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
            for (d in list) {
                val exDay = day(d.time)
                val exIdx = h.times.indexOfFirst { !day(it).isBefore(exDay) }
                if (exIdx <= 0) continue
                val pre = h.closes[exIdx - 1]
                if (pre.isNaN()) continue
                val j = (exIdx until h.closes.size).firstOrNull { !h.closes[it].isNaN() && h.closes[it] >= pre }
                fill[d.time] = j?.let { java.time.temporal.ChronoUnit.DAYS.between(exDay, day(h.times[it])).toInt() }
            }
        }
        DividendInfo(list, ttm, yieldPct, extra, fill)
    }

    /** 台股用 Google 中文新聞；美股用 Yahoo 新聞，失敗時改用 Google */
    suspend fun news(q: Quote): List<YahooExtra.News> = withContext(Dispatchers.IO) {
        // 換了新聞來源就重新抓
        val key = q.symbol + "|" + AppState.newsSources.joinToString(",")
        newsCache[key]?.let { (t, n) -> if (System.currentTimeMillis() - t < TTL) return@withContext n }
        // RSS 新聞（Yahoo股市／Yahoo Finance，備援 Google 新聞），內文在 App 內閱讀
        val list = com.claudeetf.app.data.remote.RssNews.stock(q)
        newsCache[key] = System.currentTimeMillis() to list
        list
    }

    // ---------------- 當日交易 ----------------

    data class Trading(
        val items: List<InfoItem>,
        val bids: List<Pair<Double, Double>>,
        val asks: List<Pair<Double, Double>>,
        /** 內盤、外盤（張） */
        val inOut: Pair<Double, Double>?,
        val source: String,
    )

    /** 價格欄位一律兩位小數 */
    private fun px(o: org.json.JSONObject?, k: String): String? =
        o.raw(k)?.takeIf { it > 0 }?.let { "%,.2f".format(Locale.US, it) } ?: o.fmt(k)

    private fun lots(v: Double?) = v?.let { "%,.0f 張".format(Locale.US, it) }
    private fun money(v: Double?) = v?.let {
        if (it >= 1e8) "%,.2f 億".format(Locale.US, it / 1e8) else "%,.0f 萬".format(Locale.US, it / 1e4)
    }

    suspend fun trading(q: Quote): Trading = withContext(Dispatchers.IO) {
        if (q.market == Market.TW) {
            val key = AppState.fugleKey
            // 13:30–14:30 證交所日資料還是昨天的，繼續用富果今日快照
            if (key.isNotBlank() && (MarketData.isTwIntraday() || MarketData.isTwTodayPending())) {
                val snap = runCatching { FugleApi.snapshot(q.symbol, key) }.getOrNull()
                if (snap != null) {
                    val items = listOfNotNull(
                        lots(snap.volumeLots)?.let { InfoItem("成交量", it) },
                        money(snap.valueTwd)?.let { InfoItem("成交金額", it) },
                        snap.transactions?.let { InfoItem("成交筆數", "%,.0f".format(Locale.US, it)) },
                        snap.avgPrice?.let { InfoItem("均價", "%.2f".format(Locale.US, it)) },
                        if (snap.isLimitUp) InfoItem("狀態", "漲停") else if (snap.isLimitDown) InfoItem("狀態", "跌停") else null,
                    )
                    val io = if (snap.atBidLots != null && snap.atAskLots != null) snap.atBidLots to snap.atAskLots else null
                    return@withContext Trading(items, snap.bids, snap.asks, io, if (MarketData.isTwIntraday()) "富果快照（等待串流）" else "富果今日收盤快照")
                }
            }
            val rec = MarketData.closeRecord(q.symbol)?.first
            val items = listOfNotNull(
                rec?.open?.let { InfoItem("開盤", "%.2f".format(Locale.US, it)) },
                rec?.high?.let { InfoItem("最高", "%.2f".format(Locale.US, it)) },
                rec?.low?.let { InfoItem("最低", "%.2f".format(Locale.US, it)) },
                rec?.prevClose?.let { InfoItem("昨收", "%.2f".format(Locale.US, it)) },
                lots(rec?.volume?.div(1000))?.let { InfoItem("成交量", it) },
                money(rec?.value)?.let { InfoItem("成交金額", it) },
                rec?.transactions?.let { InfoItem("成交筆數", "%,.0f".format(Locale.US, it)) },
            )
            if (items.isEmpty()) throw IllegalStateException("尚無 ${q.symbol} 的成交資料")
            return@withContext Trading(items, emptyList(), emptyList(), null, "證交所／櫃買 收盤資料")
        }
        val h = YahooExtra.history(q.symbol, "1d")
        val items = listOfNotNull(
            h.opens.firstOrNull()?.let { InfoItem("開盤", "%.2f".format(Locale.US, it)) },
            h.highs.maxOrNull()?.let { InfoItem("最高", "%.2f".format(Locale.US, it)) },
            h.lows.minOrNull()?.let { InfoItem("最低", "%.2f".format(Locale.US, it)) },
            h.prevClose?.let { InfoItem("昨收", "%.2f".format(Locale.US, it)) },
            (h.dayVolume ?: h.volumes.sumOf { it.toDouble() }.takeIf { it > 0 })?.let { InfoItem("成交量", "%,.0f 股".format(Locale.US, it)) },
            h.dayVolume?.let { v -> money(v * q.price)?.let { InfoItem("成交金額（估）", "$" + it) } },
        )
        Trading(items, emptyList(), emptyList(), null, "Yahoo Finance")
    }

    // ---------------- 籌碼 ----------------

    sealed class Chips {
        data class Tw(val days: List<TwInstitutional.Day>) : Chips()
        data class Us(val ownership: YahooExtra.Ownership) : Chips()
    }

    suspend fun chips(q: Quote): Chips = withContext(Dispatchers.IO) {
        if (q.market == Market.TW) Chips.Tw(TwInstitutional.recent(q.symbol, 5))
        else Chips.Us(YahooExtra.ownership(q.symbol))
    }

    // ---------------- 同業 ----------------

    data class Peer(val symbol: String, val yahoo: String, val name: String, val price: Double, val changePct: Double, val ret1y: Double?)

    private val peerCache = HashMap<String, Pair<Long, List<Peer>>>()

    /** Yahoo 代號轉回 App 代號：2330.TW → 2330 */
    private fun appSymbol(y: String) = y.removeSuffix(".TWO").removeSuffix(".TW")

    suspend fun peers(q: Quote): List<Peer> = withContext(Dispatchers.IO) {
        peerCache[q.symbol]?.let { (t, p) -> if (System.currentTimeMillis() - t < TTL) return@withContext p }
        val list = YahooExtra.peers(yahooSymbol(q)).filter { appSymbol(it) != q.symbol }
        val out = list.mapNotNull { y ->
            runCatching {
                val h = YahooExtra.history(y, "1y")
                val chart = com.claudeetf.app.data.remote.YahooApi.chart(y).quote
                Peer(
                    appSymbol(y), y, chart.name ?: y, chart.price,
                    if (chart.prevClose > 0) (chart.price - chart.prevClose) / chart.prevClose * 100 else 0.0,
                    if (h.closes.size > 1) (h.closes.last() / h.closes.first() - 1) * 100.0 else null,
                )
            }.getOrNull()
        }
        peerCache[q.symbol] = System.currentTimeMillis() to out
        out
    }

    // ---------------- ETF 成分股 ----------------

    suspend fun holdings(q: Quote): YahooExtra.EtfHoldings = withContext(Dispatchers.IO) {
        val tw = q.market == Market.TW
        val fromDj = if (tw) runCatching { MoneyDjHoldings.fetch(q.symbol) }.getOrNull() else null
        val fromYahoo = runCatching { YahooExtra.etfHoldings(yahooSymbol(q)) }.getOrNull()
        when {
            fromDj != null -> fromDj.copy(sectors = fromYahoo?.sectors ?: emptyList())
            fromYahoo != null -> fromYahoo
            else -> throw IllegalStateException("查無 ${q.symbol} 的成分股資料")
        }
    }
}
