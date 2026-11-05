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

    data class History(val times: List<Long>, val closes: List<Float>, val prevClose: Double?)

    /** range：1d 5d 1mo 6mo 1y 5y */
    fun history(symbol: String, range: String): History {
        val interval = when (range) {
            "1d" -> "5m"
            "5d" -> "15m"
            "1mo", "6mo", "1y" -> "1d"
            else -> "1wk"
        }
        val body = Http.get("$Q1/v8/finance/chart/${enc(symbol)}?range=$range&interval=$interval", mapOf("User-Agent" to UA))
        val r = JSONObject(body).getJSONObject("chart").optJSONArray("result")?.optJSONObject(0)
            ?: throw HttpException(404, "查無 $symbol")
        val ts = r.optJSONArray("timestamp")
        val closes = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
        val times = ArrayList<Long>()
        val values = ArrayList<Float>()
        if (ts != null && closes != null) {
            for (i in 0 until minOf(ts.length(), closes.length())) {
                if (closes.isNull(i)) continue
                val v = closes.optDouble(i, Double.NaN)
                if (v.isNaN()) continue
                times.add(ts.optLong(i) * 1000)
                values.add(v.toFloat())
            }
        }
        val meta = r.optJSONObject("meta")
        val prev = meta?.let {
            val p = it.optDouble("chartPreviousClose", Double.NaN)
            if (p.isNaN()) null else p
        }
        return History(times, values, prev)
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

    // ---------------- 新聞 ----------------

    data class News(val title: String, val publisher: String, val link: String, val time: Long)

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
