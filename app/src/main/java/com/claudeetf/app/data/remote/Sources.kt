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
)

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
            )
        }
        return out
    }
}

// ---------------------------------------------------------------------------
// 櫃買中心 OpenAPI（上櫃股票盤後行情，免金鑰）
// ---------------------------------------------------------------------------
object TpexApi {
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
