package com.claudeetf.app.data.remote

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Yahoo Finance 的進階資料（免金鑰）：
 * - 走勢：v8/finance/chart（任意區間）
 * - 配息：v8/finance/chart?events=div
 * - 基本面／財務／公司：v10/finance/quoteSummary（需先取得 cookie 與 crumb）
 * - 新聞：v1/finance/search
 */
object YahooExtra {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val Q1 = "https://query1.finance.yahoo.com"
    private const val Q2 = "https://query2.finance.yahoo.com"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------- 走勢 ----------------

    data class History(
        val times: List<Long>,
        val closes: List<Float>,
        val prevClose: Double?,
        val opens: List<Float> = emptyList(),
        val highs: List<Float> = emptyList(),
        val lows: List<Float> = emptyList(),
        val volumes: List<Float> = emptyList(),
        /** 當日成交量（Yahoo meta） */
        val dayVolume: Double? = null,
        /** 還原權值收盤（含息、拆分調整），與 closes 等長；沒有時為空 */
        val adjCloses: List<Float> = emptyList(),
    )

    /** range：1d 5d 1mo 6mo 1y 5y */
    /** range：1d 5d 1mo 3mo 6mo 1y 2y 5y；K 線看日線時多抓一段算均線 */
    /** prePost = true：含美股盤前（04:00）與盤後（至 20:00） */
    fun history(symbol: String, range: String, prePost: Boolean = false, intervalOverride: String? = null): History {
        val interval = intervalOverride ?: when (range) {
            "1d" -> "5m"
            "5d" -> "15m"
            "1mo", "3mo", "6mo", "1y", "2y" -> "1d"
            else -> "1wk"
        }
        // range=max 時 Yahoo 會自動把日 K 降成月 K；全期間日 K 改用起訖時間指定
        val span = if (range == "max" && interval == "1d") "period1=0&period2=${System.currentTimeMillis() / 1000}" else "range=$range"
        val body = Http.get("$Q1/v8/finance/chart/${enc(symbol)}?$span&interval=$interval" + (if (prePost) "&includePrePost=true" else ""), mapOf("User-Agent" to UA))
        val r = JSONObject(body).getJSONObject("chart").optJSONArray("result")?.optJSONObject(0)
            ?: throw HttpException(404, "查無 $symbol")
        val ts = r.optJSONArray("timestamp")
        val qo = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)
        val closes = qo?.optJSONArray("close")
        val oA = qo?.optJSONArray("open")
        val hA = qo?.optJSONArray("high")
        val lA = qo?.optJSONArray("low")
        val vA = qo?.optJSONArray("volume")
        val adjA = r.optJSONObject("indicators")?.optJSONArray("adjclose")?.optJSONObject(0)?.optJSONArray("adjclose")
        val adjs = ArrayList<Float>()
        val times = ArrayList<Long>()
        val values = ArrayList<Float>()
        val opens = ArrayList<Float>()
        val highs = ArrayList<Float>()
        val lows = ArrayList<Float>()
        val vols = ArrayList<Float>()
        fun at(a: org.json.JSONArray?, i: Int, fallback: Double): Float {
            if (a == null || i >= a.length() || a.isNull(i)) return fallback.toFloat()
            val d = a.optDouble(i, Double.NaN)
            return if (d.isNaN()) fallback.toFloat() else d.toFloat()
        }
        if (ts != null && closes != null) {
            for (i in 0 until minOf(ts.length(), closes.length())) {
                if (closes.isNull(i)) continue
                val v = closes.optDouble(i, Double.NaN)
                if (v.isNaN()) continue
                times.add(ts.optLong(i) * 1000)
                values.add(v.toFloat())
                opens.add(at(oA, i, v))
                highs.add(maxOf(at(hA, i, v), v.toFloat()))
                lows.add(minOf(at(lA, i, v), v.toFloat()))
                vols.add(at(vA, i, 0.0))
                if (adjA != null) adjs.add(at(adjA, i, Double.NaN))
            }
        }
        val meta = r.optJSONObject("meta")
        val prev = meta?.let {
            val p = it.optDouble("chartPreviousClose", Double.NaN)
            if (p.isNaN()) null else p
        }
        val dayVol = meta?.optDouble("regularMarketVolume", Double.NaN)?.takeIf { !it.isNaN() }
        return History(times, values, prev, opens, highs, lows, vols, dayVol, adjs.takeIf { it.size == values.size } ?: emptyList())
    }

    // ---------------- 配息 ----------------

    data class Dividend(val time: Long, val amount: Double)

    fun dividends(symbol: String): List<Dividend> {
        val body = Http.get("$Q1/v8/finance/chart/${enc(symbol)}?range=5y&interval=1mo&events=div", mapOf("User-Agent" to UA))
        val r = JSONObject(body).getJSONObject("chart").optJSONArray("result")?.optJSONObject(0) ?: return emptyList()
        val divs = r.optJSONObject("events")?.optJSONObject("dividends") ?: return emptyList()
        val out = ArrayList<Dividend>()
        val keys = divs.keys()
        while (keys.hasNext()) {
            val o = divs.optJSONObject(keys.next()) ?: continue
            val amt = o.optDouble("amount", Double.NaN)
            if (!amt.isNaN()) out.add(Dividend(o.optLong("date") * 1000, amt))
        }
        return out.sortedByDescending { it.time }
    }

    data class Split(val time: Long, val ratio: Double, val text: String)

    /** 近 5 年股票分割（ratio = 分割後股數 ÷ 分割前股數） */
    fun splits(symbol: String): List<Split> {
        val body = Http.get("$Q1/v8/finance/chart/${enc(symbol)}?range=5y&interval=1mo&events=split", mapOf("User-Agent" to UA))
        val r = JSONObject(body).getJSONObject("chart").optJSONArray("result")?.optJSONObject(0) ?: return emptyList()
        val sp = r.optJSONObject("events")?.optJSONObject("splits") ?: return emptyList()
        val out = ArrayList<Split>()
        val keys = sp.keys()
        while (keys.hasNext()) {
            val o = sp.optJSONObject(keys.next()) ?: continue
            val num = o.optDouble("numerator", Double.NaN)
            val den = o.optDouble("denominator", Double.NaN)
            if (num.isNaN() || den.isNaN() || den == 0.0) continue
            out += Split(o.optLong("date") * 1000, num / den, o.optString("splitRatio"))
        }
        return out.sortedByDescending { it.time }
    }

    // ---------------- quoteSummary（需 crumb） ----------------

    @Volatile private var cookie: String? = null
    @Volatile private var crumb: String? = null

    private fun ensureCrumb(force: Boolean = false) {
        if (!force && cookie != null && crumb != null) return
        val first = Http.raw("https://fc.yahoo.com", mapOf("User-Agent" to UA))
        val c = first.cookies.joinToString("; ")
        if (c.isBlank()) throw IllegalStateException("Yahoo 未提供 cookie")
        val cr = Http.raw("$Q2/v1/test/getcrumb", mapOf("User-Agent" to UA, "Cookie" to c))
        if (cr.code !in 200..299 || cr.body.isBlank() || cr.body.contains("<")) {
            throw HttpException(cr.code, "Yahoo crumb 取得失敗")
        }
        cookie = c
        crumb = cr.body.trim()
    }

    /** 回傳 quoteSummary.result[0]，內含各 module 的 JSONObject */
    fun summary(symbol: String, modules: List<String>): JSONObject {
        val base = "$Q2/v10/finance/quoteSummary/${enc(symbol)}?modules=${modules.joinToString(",")}"
        return try {
            summaryOnline(symbol, modules)
        } catch (e: java.io.IOException) {
            if (e is HttpException) throw e
            val body = Http.cached(base) ?: throw e
            JSONObject(body).getJSONObject("quoteSummary").optJSONArray("result")?.optJSONObject(0) ?: throw e
        }
    }

    private fun summaryOnline(symbol: String, modules: List<String>): JSONObject {
        for (attempt in 0..1) {
            ensureCrumb(force = attempt == 1)
            val url = "$Q2/v10/finance/quoteSummary/${enc(symbol)}?modules=${modules.joinToString(",")}&crumb=${enc(crumb ?: "")}"
            val res = Http.raw(url, mapOf("User-Agent" to UA, "Cookie" to (cookie ?: "")))
            if (res.code == 401 || res.code == 403) continue
            if (res.code !in 200..299) throw HttpException(res.code, "quoteSummary ${res.code}")
            return JSONObject(res.body).getJSONObject("quoteSummary").optJSONArray("result")?.optJSONObject(0)
                ?: throw HttpException(404, "查無 $symbol")
        }
        throw HttpException(401, "Yahoo 驗證失敗")
    }

    /** 需要 crumb 的一般 GET；url 不含 crumb 參數 */
    private fun authedGet(url: String): String {
        for (attempt in 0..1) {
            ensureCrumb(force = attempt == 1)
            val sep = if (url.contains("?")) "&" else "?"
            val res = Http.raw(url + sep + "crumb=" + enc(crumb ?: ""), mapOf("User-Agent" to UA, "Cookie" to (cookie ?: "")))
            if (res.code == 401 || res.code == 403) continue
            if (res.code !in 200..299) throw HttpException(res.code, "Yahoo ${res.code}")
            return res.body
        }
        throw HttpException(401, "Yahoo 驗證失敗")
    }

    // ---------------- 批次即時報價 ----------------

    data class LiveQuote(val symbol: String, val name: String?, val price: Double, val prevClose: Double,
                         val open: Double?, val high: Double?, val low: Double?, val volume: Double?, val time: Long, val state: String,
                         val prePrice: Double? = null, val preChangePct: Double? = null,
                         val postPrice: Double? = null, val postChangePct: Double? = null, val extTime: Long = 0L)

    /** v7 批次報價（需 crumb），一次最多 50 檔 */
    fun quotes(symbols: List<String>): List<LiveQuote> {
        val out = ArrayList<LiveQuote>()
        for (chunk in symbols.chunked(50)) {
            val body = authedGet("$Q2/v7/finance/quote?symbols=${enc(chunk.joinToString(","))}&formatted=false")
            val arr = JSONObject(body).optJSONObject("quoteResponse")?.optJSONArray("result") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val price = o.raw("regularMarketPrice") ?: continue
                out += LiveQuote(
                    o.optString("symbol"),
                    o.optString("longName").ifBlank { o.optString("shortName") }.ifBlank { null },
                    price,
                    o.raw("regularMarketPreviousClose") ?: price,
                    o.raw("regularMarketOpen"), o.raw("regularMarketDayHigh"), o.raw("regularMarketDayLow"),
                    o.raw("regularMarketVolume"),
                    ((o.raw("regularMarketTime") ?: 0.0) * 1000).toLong(),
                    o.optString("marketState"),
                    o.raw("preMarketPrice"), o.raw("preMarketChangePercent"),
                    o.raw("postMarketPrice"), o.raw("postMarketChangePercent"),
                    (((o.raw("postMarketTime") ?: o.raw("preMarketTime")) ?: 0.0) * 1000).toLong(),
                )
            }
        }
        return out
    }

    /** 本益比（trailing PE），v7 批次 */
    fun peRatios(symbols: List<String>): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (chunk in symbols.chunked(50)) {
            val body = authedGet("$Q2/v7/finance/quote?symbols=${enc(chunk.joinToString(","))}&formatted=false")
            val arr = JSONObject(body).optJSONObject("quoteResponse")?.optJSONArray("result") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val pe = o.raw("trailingPE") ?: continue
                if (pe > 0) out[o.optString("symbol")] = pe
            }
        }
        return out
    }

    // ---------------- 漲跌排行（預設篩選器） ----------------

    data class Mover(val symbol: String, val name: String, val price: Double, val changePct: Double, val volume: Double)

    /** id：day_gainers、day_losers、most_actives */
    fun screener(id: String, count: Int = 10): List<Mover> {
        val body = authedGet("$Q2/v1/finance/screener/predefined/saved?formatted=false&scrIds=$id&count=$count&lang=en-US&region=US")
        val quotes = JSONObject(body).getJSONObject("finance").optJSONArray("result")?.optJSONObject(0)?.optJSONArray("quotes")
            ?: return emptyList()
        return (0 until quotes.length()).mapNotNull { i ->
            val o = quotes.optJSONObject(i) ?: return@mapNotNull null
            val sym = o.optString("symbol").ifBlank { return@mapNotNull null }
            Mover(
                sym,
                o.optString("shortName").ifBlank { o.optString("longName").ifBlank { sym } },
                o.raw("regularMarketPrice") ?: 0.0,
                o.raw("regularMarketChangePercent") ?: 0.0,
                o.raw("regularMarketVolume") ?: 0.0,
            )
        }
    }

    /** 財報日、除息日、發放日（毫秒）；沒有則為 null */
    data class Events(val earnings: Long?, val exDividend: Long?, val dividendPay: Long?)

    fun events(symbol: String): Events {
        val ce = summary(symbol, listOf("calendarEvents")).optJSONObject("calendarEvents")
        val earn = ce?.optJSONObject("earnings")?.optJSONArray("earningsDate")?.optJSONObject(0)?.raw("raw")
            ?: ce?.optJSONObject("earnings")?.optJSONArray("earningsDate")?.optJSONObject(0)?.optDouble("raw", Double.NaN)?.takeIf { !it.isNaN() }
        return Events(
            earn?.let { (it * 1000).toLong() },
            ce.raw("exDividendDate")?.let { (it * 1000).toLong() },
            ce.raw("dividendDate")?.let { (it * 1000).toLong() },
        )
    }

    // ---------------- ETF 持股、機構持股 ----------------

    /** shares：持有股數（MoneyDJ 有提供時） */
    data class Holding(val symbol: String, val name: String, val weightPct: Double, val shares: Double? = null)
    data class EtfHoldings(val holdings: List<Holding>, val sectors: List<Pair<String, Double>>, val asOf: String?)

    private val sectorNames = mapOf(
        "technology" to "科技", "financial_services" to "金融", "healthcare" to "醫療保健",
        "consumer_cyclical" to "非必需消費", "consumer_defensive" to "必需消費", "industrials" to "工業",
        "communication_services" to "通訊服務", "energy" to "能源", "utilities" to "公用事業",
        "basic_materials" to "原物料", "realestate" to "房地產",
    )

    fun etfHoldings(symbol: String): EtfHoldings? {
        val th = summary(symbol, listOf("topHoldings")).optJSONObject("topHoldings") ?: return null
        val arr = th.optJSONArray("holdings")
        val list = ArrayList<Holding>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val w = o.raw("holdingPercent") ?: continue
            list += Holding(o.optString("symbol"), o.optString("holdingName").ifBlank { o.optString("symbol") }, w * 100)
        }
        val sectors = ArrayList<Pair<String, Double>>()
        th.optJSONArray("sectorWeightings")?.let { sw ->
            for (i in 0 until sw.length()) {
                val o = sw.optJSONObject(i) ?: continue
                val k = o.keys().asSequence().firstOrNull() ?: continue
                val v = o.raw(k) ?: continue
                if (v > 0) sectors += (sectorNames[k] ?: k) to v * 100
            }
        }
        if (list.isEmpty() && sectors.isEmpty()) return null
        return EtfHoldings(list, sectors.sortedByDescending { it.second }, null)
    }

    data class Ownership(val breakdown: List<Pair<String, String>>, val top: List<Triple<String, String, String>>)

    /** 美股機構／內部人持股 */
    fun ownership(symbol: String): Ownership {
        val s = summary(symbol, listOf("majorHoldersBreakdown", "institutionOwnership"))
        val mb = s.optJSONObject("majorHoldersBreakdown")
        val breakdown = listOfNotNull(
            mb.fmt("institutionsPercentHeld")?.let { "機構持股比例" to it },
            mb.fmt("insidersPercentHeld")?.let { "內部人持股比例" to it },
            mb.fmt("institutionsFloatPercentHeld")?.let { "機構占流通股" to it },
            mb.fmt("institutionsCount")?.let { "持股機構數" to it },
        )
        val top = ArrayList<Triple<String, String, String>>()
        s.optJSONObject("institutionOwnership")?.optJSONArray("ownershipList")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 10)) {
                val o = arr.optJSONObject(i) ?: continue
                top += Triple(o.optString("organization"), o.fmt("pctHeld") ?: "—", o.fmt("pctChange") ?: "—")
            }
        }
        return Ownership(breakdown, top)
    }

    // ---------------- 同業（相似股票） ----------------

    /** Yahoo 依瀏覽行為推薦的相似股票代號 */
    fun peers(symbol: String, count: Int = 6): List<String> {
        val body = Http.get("$Q2/v6/finance/recommendationsbysymbol/${enc(symbol)}", mapOf("User-Agent" to UA))
        val arr = JSONObject(body).optJSONObject("finance")?.optJSONArray("result")?.optJSONObject(0)
            ?.optJSONArray("recommendedSymbols") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("symbol")?.ifBlank { null } }.take(count)
    }

    // ---------------- 代號搜尋（美股） ----------------

    data class Hit(val symbol: String, val name: String, val type: String, val exchange: String)

    /** Yahoo 代號搜尋：只回傳美股股票與 ETF */
    fun symbolSearch(query: String, count: Int = 8): List<Hit> {
        val body = Http.get(
            "$Q2/v1/finance/search?q=${enc(query)}&quotesCount=$count&newsCount=0&enableFuzzyQuery=false",
            mapOf("User-Agent" to UA),
        )
        val arr = JSONObject(body).optJSONArray("quotes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val sym = o.optString("symbol")
            val type = o.optString("quoteType")
            // 美股：代號不含交易所後綴（.TW、.HK…）
            if (sym.isBlank() || sym.contains('.') || sym.contains('^') || type !in setOf("EQUITY", "ETF")) null
            else Hit(sym, o.optString("longname").ifBlank { o.optString("shortname") }.ifBlank { sym }, type, o.optString("exchDisp"))
        }
    }

    // ---------------- 新聞 ----------------

    data class News(val title: String, val publisher: String, val link: String, val time: Long, val summary: String = "")

    fun news(query: String, count: Int = 12): List<News> {
        val body = Http.get(
            "$Q2/v1/finance/search?q=${enc(query)}&quotesCount=0&newsCount=$count&lang=zh-Hant-TW&region=TW",
            mapOf("User-Agent" to UA),
        )
        val arr = JSONObject(body).optJSONArray("news") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title")
            val link = o.optString("link")
            if (title.isBlank() || link.isBlank()) null
            else News(title, o.optString("publisher"), link, o.optLong("providerPublishTime") * 1000)
        }
    }
}

/** quoteSummary 的數值欄位格式為 {"raw":…, "fmt":"…"} */
fun JSONObject?.fmt(key: String): String? {
    if (this == null || !has(key) || isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is JSONObject -> v.optString("fmt").ifBlank { null }
        is String -> v.ifBlank { null }
        is Number -> v.toString()
        else -> null
    }
}

fun JSONObject?.raw(key: String): Double? {
    if (this == null || !has(key) || isNull(key)) return null
    val v = opt(key)
    val d = when (v) {
        is JSONObject -> v.optDouble("raw", Double.NaN)
        is Number -> v.toDouble()
        else -> Double.NaN
    }
    return if (d.isNaN()) null else d
}
