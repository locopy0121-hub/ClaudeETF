package com.claudeetf.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 三大法人買賣超（證交所 T86 日報，上市股票／ETF，免金鑰）
 * GET https://www.twse.com.tw/rwd/zh/fund/T86?date=yyyyMMdd&selectType=ALLBUT0999&response=json
 * 回傳 { stat, fields:[…], data:[[…]] }；以欄位名稱關鍵字找出各法人「買賣超股數」。
 */
object TwInstitutional {
    data class Day(
        val date: LocalDate,
        val foreign: Long,
        val trust: Long,
        val dealer: Long,
        val total: Long,
        /** true：來自櫃買中心（上櫃） */
        val otc: Boolean = false,
    )

    private val TPE = ZoneId.of("Asia/Taipei")
    private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 日期 → (代號 → 資料)；空 map 表示該日無資料（休市） */
    private val cache = HashMap<LocalDate, Map<String, Day>>()

    private fun num(s: String?): Long =
        s?.replace(",", "")?.trim()?.toDoubleOrNull()?.toLong() ?: 0L

    @Synchronized
    private fun day(date: LocalDate): Map<String, Day> {
        cache[date]?.let { return it }
        val body = Http.get(
            "https://www.twse.com.tw/rwd/zh/fund/T86?date=${date.format(YMD)}&selectType=ALLBUT0999&response=json",
            timeoutMs = 30_000,
        )
        val o = JSONObject(body)
        val data = o.optJSONArray("data")
        if (o.optString("stat") != "OK" || data == null) {
            // 「沒有符合條件的資料」= 休市，可快取；其他（查詢過於頻繁等）不快取，交給呼叫端重試
            val stat = o.optString("stat")
            if (stat.contains("沒有符合") || stat.contains("查無")) { cache[date] = emptyMap(); return emptyMap() }
            throw IllegalStateException("證交所 T86：" + stat.ifBlank { "暫時無法取得" })
        }
        val fields = o.optJSONArray("fields") ?: JSONArray()
        val names = (0 until fields.length()).map { fields.optString(it) }
        fun idx(pred: (String) -> Boolean) = names.indexOfFirst(pred)
        val iCode = idx { it.contains("代號") }.coerceAtLeast(0)
        val iForeign = idx { it.contains("外陸資買賣超") || (it.contains("外資") && it.contains("買賣超") && !it.contains("自營")) }
        val iForeignDealer = idx { it.contains("外資自營商買賣超") }
        val iTrust = idx { it.contains("投信買賣超") }
        val iDealer = idx { it.startsWith("自營商買賣超") || it == "自營商買賣超股數" }
        val iTotal = idx { it.contains("三大法人買賣超") }

        val out = HashMap<String, Day>(data.length() * 2)
        for (r in 0 until data.length()) {
            val row = data.optJSONArray(r) ?: continue
            fun cell(i: Int) = if (i in 0 until row.length()) row.optString(i) else null
            val code = cell(iCode)?.trim() ?: continue
            val foreign = num(cell(iForeign)) + (if (iForeignDealer >= 0) num(cell(iForeignDealer)) else 0L)
            val trust = num(cell(iTrust))
            val dealer = num(cell(iDealer))
            val total = if (iTotal >= 0) num(cell(iTotal)) else foreign + trust + dealer
            out[code] = Day(date, foreign, trust, dealer, total)
        }
        cache[date] = out
        return out
    }

    /** 最近一個有資料的交易日全部上市證券（今天 16:00 前不查今天） */
    fun latest(): Map<String, Day> {
        val now = ZonedDateTime.now(TPE)
        var d = now.toLocalDate()
        if (now.toLocalTime() < LocalTime.of(16, 0)) d = d.minusDays(1)
        repeat(10) {
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
                val map = runCatching { day(d) }.getOrNull()
                if (!map.isNullOrEmpty()) return withOtc(map)
            }
            d = d.minusDays(1)
        }
        return withOtc(emptyMap())
    }

    /** 加入上櫃（櫃買中心 t187ap07_O 最新一日），上市資料優先 */
    private fun withOtc(twse: Map<String, Day>): Map<String, Day> {
        val otc = runCatching { TpexOpenData.institutional() }.getOrNull().orEmpty()
        if (otc.isEmpty()) return twse
        return otc + twse
    }

    /** 最近 n 個有資料的交易日（今天 16:00 前不查今天），由新到舊 */
    fun recent(code: String, n: Int = 5): List<Day> {
        val now = ZonedDateTime.now(TPE)
        var d = now.toLocalDate()
        if (now.toLocalTime() < LocalTime.of(16, 0)) d = d.minusDays(1)
        val out = ArrayList<Day>()
        var tries = 0
        while (out.size < n && tries < n + 8) {
            tries++
            if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
                // 證交所限制查詢頻率：未快取的日期間隔 0.5 秒，失敗等 2 秒再試一次
                val fresh = !cache.containsKey(d)
                if (fresh && out.isNotEmpty()) Thread.sleep(500)
                val map = runCatching { day(d) }.getOrNull()
                    ?: run { Thread.sleep(2_000); runCatching { day(d) }.getOrNull() }
                if (map != null && map.isNotEmpty()) {
                    val rec = map[code]
                    // 最近一個交易日就查不到，代表不是上市證券：改用櫃買中心（只有最新一日）
                    if (rec != null) out += rec
                    else if (out.isEmpty()) return runCatching { TpexOpenData.institutional()[code] }.getOrNull()?.let { listOf(it) } ?: emptyList()
                }
            }
            d = d.minusDays(1)
        }
        return out
    }
}

/**
 * 台股 ETF 持股明細（MoneyDJ 持股頁，HTML）
 * https://www.moneydj.com/ETF/X/Basic/Basic0007.xdjhtm?etfid=0050.TW
 * 以「名稱 + 百分比」的表格列為準，容忍網頁版型小幅變動。
 */
object MoneyDjHoldings {
    private val rowRe = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val cellRe = Regex("<t[dh][^>]*>(.*?)</t[dh]>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val tagRe = Regex("<[^>]+>")
    private val dateRe = Regex("資料日期[：:]\\s*([0-9/]+)")

    private fun clean(s: String) = tagRe.replace(s, "").replace("&nbsp;", " ").replace("&amp;", "&").trim()

    private fun asOfDate(s: String?): java.time.LocalDate? = s?.let { parseTwDate(it) }

    /**
     * 兩個頁面都抓：
     *  Basic0007 ＝ 最新資料日的前十大（含代號與持有股數，每日更新）
     *  Basic0007B ＝ 全部持股（多為月報／季報，日期較舊，部分只有公司全名）
     * 全部持股的日期不比前十大舊、且筆數較多時用全部持股；否則用最新的前十大。
     */
    fun fetch(code: String): YahooExtra.EtfHoldings? {
        val ua = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) Mobile")
        val top = runCatching { parse(Http.get("https://www.moneydj.com/ETF/X/Basic/Basic0007.xdjhtm?etfid=${code.uppercase()}.TW", ua, timeoutMs = 20_000)) }.getOrNull()
        val all = runCatching { parse(Http.get("https://www.moneydj.com/ETF/X/Basic/Basic0007B.xdjhtm?etfid=${code.uppercase()}.TW", ua, timeoutMs = 20_000)) }.getOrNull()
        if (top == null) return all
        if (all == null) return top
        val dt = asOfDate(top.asOf); val da = asOfDate(all.asOf)
        return if (all.holdings.size > top.holdings.size && (dt == null || da == null || !da.isBefore(dt))) all else top
    }

    private fun parse(html: String): YahooExtra.EtfHoldings? {
        val list = ArrayList<YahooExtra.Holding>()
        for (m in rowRe.findAll(html)) {
            val raw = cellRe.findAll(m.groupValues[1]).map { it.groupValues[1] }.toList()
            val cells = raw.map { clean(it) }
            if (cells.size < 2) continue
            val name = cells[0]
            val pct = cells[1].replace("%", "").replace(",", "").trim().toDoubleOrNull() ?: continue
            if (name.isBlank() || pct <= 0 || pct > 100) continue
            if (name.contains("名稱") || name.contains("合計")) continue
            // 「台積電(2330.TW)」→ 代號 2330
            // 代號來源依序：名稱括號「台積電(2330.TW)」→ 連結參數（etfid=／a=／stockid=…）；都沒有時由 EtfComposition 以名稱比對證交所目錄
            val sym = Regex("\\(([0-9A-Z.]+)\\)").find(name)?.groupValues?.get(1)?.substringBefore(".")
                ?: Regex("[?&](?:a|stockid|id|symbol|etfid|code)=([0-9]{4,6}[A-Z]?)(?:\\.TW[O]?)?", RegexOption.IGNORE_CASE).find(raw[0])?.groupValues?.get(1)
                ?: ""
            // 第三欄：持有股數（表頭「持有股數」）
            val shares = cells.getOrNull(2)?.replace(",", "")?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }
            list += YahooExtra.Holding(sym, name.substringBefore("(").trim(), pct, shares)
        }
        if (list.isEmpty()) return null
        val asOf = dateRe.find(html)?.groupValues?.get(1)
        return YahooExtra.EtfHoldings(list.distinctBy { it.name }.sortedByDescending { it.weightPct }, emptyList(), asOf, "MoneyDJ")
    }
}

/**
 * 主動式 ETF 每日完整持股（PCF）：zdsetf.com 彙整各投信官網每日公告的投資組合（含代號、股數、權重、市值）。
 * GET https://zdsetf.com/api/etfs/{代號}/snapshot
 * 欄位名稱各檔略有不同，依序容錯：日期 snapshot_date／as_of_date／date；清單 holdings／positions／stocks；
 * 代號 stock_code／code／ticker；名稱 stock_name／name；權重 weight_pct／weight／weight_percent；股數 shares／share。
 */
object ActiveEtfPcf {
    fun fetch(code: String): YahooExtra.EtfHoldings? {
        val body = Http.get(
            "https://zdsetf.com/api/etfs/${code.uppercase()}/snapshot",
            mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36", "Accept" to "application/json"),
            timeoutMs = 20_000,
        ).trim()
        if (!body.startsWith("{")) return null
        val o = org.json.JSONObject(body)
        val date = listOf("snapshot_date", "as_of_date", "date").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() && it != "null" } }
        val arr = listOf("holdings", "positions", "stocks").firstNotNullOfOrNull { o.optJSONArray(it) } ?: return null
        fun num(x: org.json.JSONObject, vararg keys: String): Double? = keys.firstNotNullOfOrNull { k ->
            if (!x.has(k) || x.isNull(k)) null else x.optString(k).replace(",", "").replace("%", "").trim().toDoubleOrNull()
        }
        val list = ArrayList<YahooExtra.Holding>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val sym = listOf("stock_code", "code", "ticker").firstNotNullOfOrNull { k -> x.optString(k).trim().takeIf { it.isNotBlank() && it != "null" } } ?: ""
            val name = listOf("stock_name", "name").firstNotNullOfOrNull { k -> x.optString(k).trim().takeIf { it.isNotBlank() && it != "null" } } ?: sym
            val w = num(x, "weight_pct", "weight", "weight_percent") ?: continue
            if (name.isBlank()) continue
            // 純數字代號才是台股（期貨、現金等保留名稱，歸「其他部位」）
            val tw = sym.takeIf { it.matches(Regex("[0-9]{4,6}[A-Z]?")) } ?: ""
            list += YahooExtra.Holding(tw, if (tw.isEmpty() && sym.isNotEmpty() && sym != name) "$name（$sym）" else name, w, num(x, "shares", "share")?.takeIf { it > 0 })
        }
        if (list.isEmpty()) return null
        // 權重若是小數（合計約 1），換成百分比
        val total = list.sumOf { it.weightPct }
        val fixed = if (total in 0.5..1.5) list.map { it.copy(weightPct = it.weightPct * 100) } else list
        return YahooExtra.EtfHoldings(fixed.sortedByDescending { it.weightPct }, emptyList(), date?.replace("-", "/"), "投信每日 PCF（zdsetf 彙整）")
    }
}
