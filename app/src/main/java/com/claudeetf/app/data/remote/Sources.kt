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
