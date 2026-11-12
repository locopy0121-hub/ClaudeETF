package com.claudeetf.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** 從各資料來源取回、尚未合併進 App 的報價 */
data class RemoteQuote(
    val symbol: String,
    val name: String?,
    val price: Double,
    val prevClose: Double,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    /** 成交量（股）、成交金額、成交筆數；沒有資料時為 null */
    val volume: Double? = null,
    val value: Double? = null,
    val transactions: Double? = null,
    /** 資料所屬交易日（收盤資料才有） */
    val date: java.time.LocalDate? = null,
)

/** 民國 1151106、115/11/06 或西元 20261106 → 日期 */
fun parseTwDate(s: String?): java.time.LocalDate? {
    if (s.isNullOrBlank()) return null
    val p = s.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
    return runCatching {
        if (p.size >= 3) java.time.LocalDate.of(p[0].toInt().let { if (it < 1000) it + 1911 else it }, p[1].toInt(), p[2].toInt())
        else {
            val d = p.first()
            when (d.length) {
                7 -> java.time.LocalDate.of(d.take(3).toInt() + 1911, d.substring(3, 5).toInt(), d.takeLast(2).toInt())
                8 -> java.time.LocalDate.of(d.take(4).toInt(), d.substring(4, 6).toInt(), d.takeLast(2).toInt())
                else -> null
            }
        }
    }.getOrNull()
}

private fun JSONObject.num(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = optDouble(key, Double.NaN)
    return if (v.isNaN()) null else v
}

/** "1,465.00"、"+5.0000"、"--"、"X0.00" → Double? */
private fun parseNum(s: String?): Double? {
    if (s == null) return null
    val cleaned = s.replace(",", "").replace("+", "").replace("X", "").trim()
    if (cleaned.isEmpty() || cleaned == "--" || cleaned == "-") return null
    return cleaned.toDoubleOrNull()
}

// ---------------------------------------------------------------------------
// 富果 Fugle MarketData API（盤中即時，需使用者自己的 API 金鑰）
// GET https://api.fugle.tw/marketdata/v1.0/stock/intraday/quote/{symbol}
// Header: X-API-KEY
// ---------------------------------------------------------------------------
object FugleApi {
    private const val BASE = "https://api.fugle.tw/marketdata/v1.0/stock"

    fun quote(symbol: String, apiKey: String): RemoteQuote {
        val body = Http.get("$BASE/intraday/quote/$symbol", mapOf("X-API-KEY" to apiKey))
        val o = JSONObject(body)
        val prev = o.num("previousClose") ?: o.num("referencePrice")
            ?: throw IllegalStateException("富果回應缺少昨收價")
        val price = o.num("lastPrice") ?: o.num("closePrice") ?: prev
        return RemoteQuote(
            symbol = o.optString("symbol", symbol),
            name = o.optString("name").ifBlank { null },
            price = price,
            prevClose = prev,
            open = o.num("openPrice"),
            high = o.num("highPrice"),
            low = o.num("lowPrice"),
        )
    }

    /** 富果盤中分 K（timeframe 分鐘），回傳 時間（毫秒）、開高低收、量 */
    data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

    fun candles(symbol: String, apiKey: String, timeframe: Int = 5): List<Candle> {
        val body = Http.get("$BASE/intraday/candles/$symbol?timeframe=$timeframe", mapOf("X-API-KEY" to apiKey))
        val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val t = runCatching { java.time.OffsetDateTime.parse(o.optString("date")).toInstant().toEpochMilli() }.getOrNull()
                ?: return@mapNotNull null
            Candle(t, o.num("open") ?: return@mapNotNull null, o.num("high") ?: return@mapNotNull null,
                o.num("low") ?: return@mapNotNull null, o.num("close") ?: return@mapNotNull null, o.num("volume") ?: 0.0)
        }.sortedBy { it.time }
    }
}

/** 富果盤中快照：五檔、內外盤、成交量（張） */
data class FugleSnapshot(
    val bids: List<Pair<Double, Double>>,
    val asks: List<Pair<Double, Double>>,
    val volumeLots: Double?,
    val valueTwd: Double?,
    val transactions: Double?,
    val atBidLots: Double?,
    val atAskLots: Double?,
    val avgPrice: Double?,
    val isLimitUp: Boolean,
    val isLimitDown: Boolean,
)

fun FugleApi.snapshot(symbol: String, apiKey: String): FugleSnapshot {
    val o = JSONObject(Http.get("https://api.fugle.tw/marketdata/v1.0/stock/intraday/quote/$symbol", mapOf("X-API-KEY" to apiKey)))
    fun levels(key: String): List<Pair<Double, Double>> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val l = arr.optJSONObject(i) ?: return@mapNotNull null
            l.optDouble("price", Double.NaN).takeIf { !it.isNaN() }?.let { it to l.optDouble("size", 0.0) }
        }
    }
    val t = o.optJSONObject("total")
    return FugleSnapshot(
        bids = levels("bids"),
        asks = levels("asks"),
        volumeLots = t?.num("tradeVolume"),
        valueTwd = t?.num("tradeValue"),
        transactions = t?.num("transaction"),
        atBidLots = t?.num("tradeVolumeAtBid"),
        atAskLots = t?.num("tradeVolumeAtAsk"),
        avgPrice = o.num("avgPrice"),
        isLimitUp = o.optBoolean("isLimitUpPrice", false),
        isLimitDown = o.optBoolean("isLimitDownPrice", false),
    )
}

// ---------------------------------------------------------------------------
// 證交所 OpenAPI（盤後收盤行情，免金鑰）
// GET https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL
// 欄位：Code, Name, OpeningPrice, HighestPrice, LowestPrice, ClosingPrice, Change
// ---------------------------------------------------------------------------
object TwseApi {
    /**
     * 證交所「每日收盤行情」（MI_INDEX，全部上市證券含 ETF，不含權證與牛熊證）。
     * 指定交易日，回傳的日期與要求一致才採用，作為盤後官方收盤價的確認來源。
     * 漲跌欄「漲跌(+/-)」是含 HTML 的正負號，「漲跌價差」為絕對值。
     */
    fun miIndex(day: java.time.LocalDate): Map<String, RemoteQuote> {
        val ds = day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        val o = JSONObject(Http.get("https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?date=$ds&type=ALLBUT0999&response=json", timeoutMs = 30_000))
        if (o.optString("stat") != "OK") return emptyMap()
        val d0 = o.optString("date")
        if (d0.isNotBlank() && d0 != ds) return emptyMap()
        val tables = ArrayList<Pair<JSONArray, JSONArray>>()
        o.optJSONArray("tables")?.let { t ->
            for (i in 0 until t.length()) t.optJSONObject(i)?.let { x ->
                val f = x.optJSONArray("fields"); val d = x.optJSONArray("data")
                if (f != null && d != null) tables += f to d
            }
        }
        o.keys().forEach { k ->
            if (k.startsWith("fields")) {
                val f = o.optJSONArray(k); val d = o.optJSONArray("data" + k.removePrefix("fields"))
                if (f != null && d != null) tables += f to d
            }
        }
        fun JSONArray.names() = (0 until length()).map { optString(it).trim() }
        val (f, rows) = tables.firstOrNull { (f, _) -> f.names().let { "證券代號" in it && "收盤價" in it } } ?: return emptyMap()
        val n = f.names()
        fun idx(name: String) = n.indexOfFirst { it.startsWith(name) }
        val iCode = idx("證券代號"); val iName = idx("證券名稱"); val iVol = idx("成交股數"); val iTx = idx("成交筆數")
        val iVal = idx("成交金額"); val iO = idx("開盤價"); val iH = idx("最高價"); val iL = idx("最低價"); val iC = idx("收盤價")
        val iSign = idx("漲跌(+/-)"); val iChg = idx("漲跌價差")
        val out = HashMap<String, RemoteQuote>(rows.length() * 2)
        for (r in 0 until rows.length()) {
            val row = rows.optJSONArray(r) ?: continue
            fun cell(i: Int) = if (i >= 0) row.optString(i) else null
            val code = cell(iCode)?.trim().orEmpty()
            val close = parseNum(cell(iC)) ?: continue
            if (code.isEmpty() || close <= 0) continue
            val signTxt = cell(iSign)?.replace(Regex("<[^>]*>"), "")?.trim().orEmpty()
            val sign = when { signTxt.contains("-") -> -1.0; signTxt.contains("+") -> 1.0; else -> 0.0 }
            val change = sign * (parseNum(cell(iChg)) ?: 0.0)
            out[code] = RemoteQuote(
                symbol = code,
                name = cell(iName)?.trim()?.ifBlank { null },
                price = close,
                prevClose = close - change,
                open = parseNum(cell(iO)),
                high = parseNum(cell(iH)),
                low = parseNum(cell(iL)),
                volume = parseNum(cell(iVol)),
                value = parseNum(cell(iVal)),
                transactions = parseNum(cell(iTx)),
                date = day,
            )
        }
        return out
    }

    @Volatile private var latestCache: Pair<Long, Map<String, RemoteQuote>>? = null

    /**
     * 最新一個交易日的全部上市收盤行情（台灣時間）：
     * 平日 14:00 後先向證交所 MI_INDEX 取「今天」的官方收盤（約 14:30 公布），取不到再用 OpenAPI STOCK_DAY_ALL
     * （OpenAPI 常到傍晚才更新，盤後一段時間內仍是前一交易日）。10 分鐘快取。
     */
    fun latestAll(): Map<String, RemoteQuote> {
        latestCache?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Taipei"))
        val today = now.toLocalDate()
        val weekday = now.dayOfWeek != java.time.DayOfWeek.SATURDAY && now.dayOfWeek != java.time.DayOfWeek.SUNDAY
        val day = if (weekday && now.toLocalTime() >= java.time.LocalTime.of(14, 0)) runCatching { miIndex(today) }.getOrDefault(emptyMap()) else emptyMap()
        val all = runCatching { dayAll() }.getOrDefault(emptyMap())
        // OpenAPI 已是今天（或比 MI_INDEX 新）就用 OpenAPI；否則用今天的 MI_INDEX
        val res = if (day.isNotEmpty() && (all.values.firstOrNull()?.date ?: java.time.LocalDate.MIN) < today) day else all.ifEmpty { day }
        if (res.isNotEmpty()) latestCache = System.currentTimeMillis() to res
        return res
    }

    fun dayAll(): Map<String, RemoteQuote> {
        val arr = JSONArray(Http.get("https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL", timeoutMs = 25_000))
        val out = HashMap<String, RemoteQuote>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("Code").trim()
            val close = parseNum(o.optString("ClosingPrice")) ?: continue
            val change = parseNum(o.optString("Change")) ?: 0.0
            if (code.isEmpty()) continue
            out[code] = RemoteQuote(
                symbol = code,
                name = o.optString("Name").trim().ifBlank { null },
                price = close,
                prevClose = close - change,
                open = parseNum(o.optString("OpeningPrice")),
                high = parseNum(o.optString("HighestPrice")),
                low = parseNum(o.optString("LowestPrice")),
                volume = parseNum(o.optString("TradeVolume")),
                value = parseNum(o.optString("TradeValue")),
                transactions = parseNum(o.optString("Transaction")),
                date = parseTwDate(o.optString("Date")),
            )
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// 櫃買中心 OpenAPI（上櫃股票盤後行情，免金鑰）
// ---------------------------------------------------------------------------
object TpexApi {
    @Volatile private var latestCache: Pair<Long, Map<String, RemoteQuote>>? = null

    /**
     * 最新一個交易日的全部上櫃收盤行情（台灣時間）：OpenAPI 尚未更新到今天時，改向櫃買網站的每日收盤行情查詢今天。
     * 10 分鐘快取。
     */
    fun latestAll(): Map<String, RemoteQuote> {
        latestCache?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Taipei"))
        val today = now.toLocalDate()
        val all = runCatching { dayAll() }.getOrDefault(emptyMap())
        val weekday = now.dayOfWeek != java.time.DayOfWeek.SATURDAY && now.dayOfWeek != java.time.DayOfWeek.SUNDAY
        val res = if (weekday && now.toLocalTime() >= java.time.LocalTime.of(14, 30) && (all.values.firstOrNull()?.date ?: java.time.LocalDate.MIN) < today)
            runCatching { dailyClose(today) }.getOrDefault(emptyMap()).ifEmpty { all } else all
        if (res.isNotEmpty()) latestCache = System.currentTimeMillis() to res
        return res
    }

    /** 櫃買中心網站「上櫃股票每日收盤行情」（指定日期，JSON）；欄位依名稱對應 */
    fun dailyClose(day: java.time.LocalDate): Map<String, RemoteQuote> {
        val d = day.format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        val urls = listOf(
            "https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes?date=" + java.net.URLEncoder.encode(d, "UTF-8") + "&id=&response=json",
            "https://www.tpex.org.tw/www/zh-tw/afterTrading/otc?date=" + java.net.URLEncoder.encode(d, "UTF-8") + "&type=EW&id=&response=json",
        )
        for (u in urls) {
            val o = runCatching { JSONObject(Http.get(u, timeoutMs = 30_000)) }.getOrNull() ?: continue
            val ds = o.optString("date").filter { it.isDigit() }
            if (ds.isNotBlank() && ds != day.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)) continue
            val t = o.optJSONArray("tables") ?: continue
            for (i in 0 until t.length()) {
                val x = t.optJSONObject(i) ?: continue
                val f = x.optJSONArray("fields") ?: continue
                val rows = x.optJSONArray("data") ?: continue
                val n = (0 until f.length()).map { f.optString(it).trim() }
                fun idx(vararg k: String) = n.indexOfFirst { name -> k.any { name.startsWith(it) } }
                val iCode = idx("代號"); val iName = idx("名稱"); val iC = idx("收盤"); val iChg = idx("漲跌")
                val iO = idx("開盤"); val iH = idx("最高"); val iL = idx("最低"); val iVol = idx("成交股數"); val iVal = idx("成交金額"); val iTx = idx("成交筆數")
                if (iCode < 0 || iC < 0) continue
                val out = HashMap<String, RemoteQuote>(rows.length() * 2)
                for (r in 0 until rows.length()) {
                    val row = rows.optJSONArray(r) ?: continue
                    fun cell(k: Int) = if (k >= 0) row.optString(k) else null
                    val code = cell(iCode)?.trim().orEmpty()
                    val close = parseNum(cell(iC)) ?: continue
                    if (code.isEmpty() || close <= 0) continue
                    val chg = cell(iChg)?.replace(Regex("<[^>]*>"), "")?.replace("+", "")?.trim()?.let { parseNum(it) } ?: 0.0
                    out[code] = RemoteQuote(symbol = code, name = cell(iName)?.trim()?.ifBlank { null }, price = close, prevClose = close - chg,
                        open = parseNum(cell(iO)), high = parseNum(cell(iH)), low = parseNum(cell(iL)), volume = parseNum(cell(iVol)),
                        value = parseNum(cell(iVal)), transactions = parseNum(cell(iTx)), date = day)
                }
                if (out.size > 100) return out
            }
        }
        return emptyMap()
    }

    fun dayAll(): Map<String, RemoteQuote> {
        val arr = JSONArray(Http.get("https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes", timeoutMs = 25_000))
        val out = HashMap<String, RemoteQuote>(arr.length() * 2)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("SecuritiesCompanyCode").trim()
            val close = parseNum(o.optString("Close")) ?: continue
            val change = parseNum(o.optString("Change")) ?: 0.0
            if (code.isEmpty()) continue
            out[code] = RemoteQuote(
                symbol = code,
                name = o.optString("CompanyName").trim().ifBlank { null },
                price = close,
                prevClose = close - change,
                open = parseNum(o.optString("Open")),
                high = parseNum(o.optString("High")),
                low = parseNum(o.optString("Low")),
                volume = parseNum(o.optString("TradingShares")),
                value = parseNum(o.optString("TransactionAmount")),
                transactions = parseNum(o.optString("TransactionNumber")),
                date = parseTwDate(o.optString("Date")),
            )
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// Finnhub（美股，需使用者自己的免費 API 金鑰）
// GET https://finnhub.io/api/v1/quote?symbol=AAPL&token=KEY → c, d, dp, h, l, o, pc, t
// ---------------------------------------------------------------------------
object FinnhubApi {
    private const val BASE = "https://finnhub.io/api/v1"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun quote(symbol: String, apiKey: String): RemoteQuote {
        val o = JSONObject(Http.get("$BASE/quote?symbol=${enc(symbol)}&token=${enc(apiKey)}"))
        val c = o.num("c") ?: 0.0
        val pc = o.num("pc") ?: 0.0
        if (c == 0.0 && pc == 0.0) throw HttpException(404, "查無 $symbol")
        return RemoteQuote(symbol, null, c, if (pc > 0) pc else c, o.num("o"), o.num("h"), o.num("l"))
    }

    /** 公司名稱（新增自選時查一次） */
    fun name(symbol: String, apiKey: String): String? = runCatching {
        val o = JSONObject(Http.get("$BASE/stock/profile2?symbol=${enc(symbol)}&token=${enc(apiKey)}"))
        o.optString("name").ifBlank { null }
    }.getOrNull()
}

// ---------------------------------------------------------------------------
// 台灣銀行牌告匯率（文字檔）：取美元「即期」買入與賣出的中價
// GET https://rate.bot.com.tw/xrt/fltxt/0/day
// 例：USD 本行買入 32.475 32.800 ... 本行賣出 33.145 32.950 ...
// ---------------------------------------------------------------------------
object BotFxApi {
    data class Fx(val spotBuy: Double, val spotSell: Double) {
        val mid: Double get() = (spotBuy + spotSell) / 2
    }

    fun usd(): Fx {
        val text = Http.get("https://rate.bot.com.tw/xrt/fltxt/0/day")
        val line = text.lineSequence().firstOrNull { it.trimStart().startsWith("USD") }
            ?: throw IllegalStateException("台銀匯率找不到 USD")
        val t = line.trim().split(Regex("\\s+"))
        val buyIdx = t.indexOf("本行買入")
        val sellIdx = t.indexOf("本行賣出")
        val buy = t.getOrNull(buyIdx + 2)?.toDoubleOrNull()
        val sell = t.getOrNull(sellIdx + 2)?.toDoubleOrNull()
        if (buyIdx < 0 || sellIdx < 0 || buy == null || sell == null || buy <= 0 || sell <= 0) {
            throw IllegalStateException("台銀匯率格式無法解析")
        }
        return Fx(buy, sell)
    }
}

// ---------------------------------------------------------------------------
// Yahoo Finance（美股、指數、債息、美元指數；免金鑰，非官方公開端點）
// GET https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=1d&interval=5m
// meta：regularMarketPrice、previousClose／chartPreviousClose、longName、shortName…
// ---------------------------------------------------------------------------
object YahooApi {
    data class Chart(val quote: RemoteQuote, val spark: List<Float>, val time: Long)

    private val hosts = listOf("https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com")
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
    )

    fun chart(symbol: String): Chart {
        val enc = URLEncoder.encode(symbol, "UTF-8")
        var last: Throwable? = null
        for (host in hosts) {
            try {
                return parse(symbol, Http.get("$host/v8/finance/chart/$enc?range=1d&interval=5m", headers))
            } catch (e: Throwable) {
                last = e
                if (e is HttpException && e.code == 404) throw e
            }
        }
        throw last ?: IllegalStateException("Yahoo 無回應")
    }

    private fun parse(symbol: String, body: String): Chart {
        val chart = JSONObject(body).getJSONObject("chart")
        val result = chart.optJSONArray("result")?.optJSONObject(0)
            ?: throw HttpException(404, "查無 $symbol")
        val meta = result.getJSONObject("meta")
        val price = meta.num("regularMarketPrice") ?: throw IllegalStateException("Yahoo 缺少價格")
        val prev = meta.num("previousClose") ?: meta.num("chartPreviousClose") ?: price
        val name = meta.optString("longName").ifBlank { meta.optString("shortName") }.ifBlank { null }

        // 當日走勢：取收盤價序列，抽樣到約 40 點
        val closes = result.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
        val series = ArrayList<Float>()
        if (closes != null) {
            for (i in 0 until closes.length()) {
                if (!closes.isNull(i)) {
                    val v = closes.optDouble(i, Double.NaN)
                    if (!v.isNaN()) series.add(v.toFloat())
                }
            }
        }
        val step = maxOf(1, series.size / 40)
        val spark = if (series.size < 2) emptyList()
        else listOf(prev.toFloat()) + series.filterIndexed { i, _ -> i % step == 0 } + price.toFloat()

        return Chart(
            RemoteQuote(
                symbol = symbol,
                name = name,
                price = price,
                prevClose = prev,
                open = null,
                high = meta.num("regularMarketDayHigh"),
                low = meta.num("regularMarketDayLow"),
            ),
            spark,
            (meta.optLong("regularMarketTime", 0L)) * 1000,
        )
    }
}

// ---------------------------------------------------------------------------
// 證交所基本市況報導（MIS，盤中即時，免金鑰）
// GET https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=tse_2330.tw|otc_6488.tw&json=1&delay=0
// 欄位：c 代號、n 名稱、z 最近成交價（"-" 表示該檔快照無成交）、y 昨收、o 開、h 高、l 低、
//      v 累積成交量（張）、a/b 五檔賣價／買價（以 _ 分隔）、f/g 五檔賣量／買量、tlong 時間（毫秒）
// 上市、上櫃代號不同前綴：同時送 tse_ 與 otc_，交易所只會回傳存在的那一個。
// ---------------------------------------------------------------------------
object TwseMisApi {
    /** tradeLots：當盤成交張數；bid、ask：最佳買賣價（判斷內外盤） */
    data class Tick(val quote: RemoteQuote, val time: Long, val isOtc: Boolean,
                    val tradeLots: Double? = null, val bid: Double? = null, val ask: Double? = null, val hasTrade: Boolean = false)

    private fun first(s: String?): Double? =
        s?.split("_")?.firstOrNull { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it > 0 }

    private fun num(s: String?): Double? = s?.trim()?.takeIf { it != "-" && it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it > 0 }

    /** channels 例：tse_2330.tw、otc_6488.tw、tse_t00.tw（加權指數） */
    fun fetch(channels: List<String>): Map<String, Tick> {
        val out = HashMap<String, Tick>()
        for (chunk in channels.chunked(40)) {
            val ex = java.net.URLEncoder.encode(chunk.joinToString("|"), "UTF-8")
            val body = Http.get(
                "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=$ex&json=1&delay=0&_=${System.currentTimeMillis()}",
                mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) Mobile", "Referer" to "https://mis.twse.com.tw/stock/index.jsp"),
                timeoutMs = 8_000,
            )
            val arr = JSONObject(body).optJSONArray("msgArray") ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val code = o.optString("c").trim()
                if (code.isEmpty()) continue
                val prev = num(o.optString("y")) ?: continue
                // 沒有新成交時，以最佳買價（其次賣價）代表目前價格
                val price = num(o.optString("z")) ?: first(o.optString("b")) ?: first(o.optString("a")) ?: num(o.optString("o")) ?: prev
                val vol = num(o.optString("v"))
                out[code] = Tick(
                    RemoteQuote(
                        symbol = code,
                        name = o.optString("n").trim().ifBlank { null },
                        price = price,
                        prevClose = prev,
                        open = num(o.optString("o")),
                        high = num(o.optString("h")),
                        low = num(o.optString("l")),
                        volume = vol?.times(1000),
                    ),
                    o.optString("tlong").toLongOrNull() ?: System.currentTimeMillis(),
                    o.optString("ex") == "otc",
                    tradeLots = num(o.optString("tv")),
                    bid = first(o.optString("b")),
                    ask = first(o.optString("a")),
                    hasTrade = num(o.optString("z")) != null,
                )
            }
        }
        return out
    }

    fun quotes(symbols: List<String>): Map<String, Tick> =
        fetch(symbols.flatMap { listOf("tse_$it.tw", "otc_$it.tw") })
}
