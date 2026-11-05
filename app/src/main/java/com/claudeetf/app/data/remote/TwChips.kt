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
            cache[date] = emptyMap()
            return emptyMap()
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
                val map = runCatching { day(d) }.getOrNull()
                if (map != null && map.isNotEmpty()) {
                    val rec = map[code]
                    // 最近一個交易日就查不到，代表不是上市證券（例如上櫃），直接結束
                    if (rec != null) out += rec else if (out.isEmpty()) return emptyList()
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

    fun fetch(code: String): YahooExtra.EtfHoldings? {
        val html = Http.get(
            "https://www.moneydj.com/ETF/X/Basic/Basic0007.xdjhtm?etfid=${code.uppercase()}.TW",
            mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) Mobile"),
            timeoutMs = 20_000,
        )
        val list = ArrayList<YahooExtra.Holding>()
        for (m in rowRe.findAll(html)) {
            val cells = cellRe.findAll(m.groupValues[1]).map { clean(it.groupValues[1]) }.toList()
            if (cells.size < 2) continue
            val name = cells[0]
            val pct = cells[1].replace("%", "").replace(",", "").trim().toDoubleOrNull() ?: continue
            if (name.isBlank() || pct <= 0 || pct > 100) continue
            if (name.contains("名稱") || name.contains("合計")) continue
            // 「台積電(2330.TW)」→ 代號 2330
            val sym = Regex("\\(([0-9A-Z.]+)\\)").find(name)?.groupValues?.get(1)?.substringBefore(".") ?: ""
            list += YahooExtra.Holding(sym, name.substringBefore("(").trim(), pct)
        }
        if (list.isEmpty()) return null
        val asOf = dateRe.find(html)?.groupValues?.get(1)
        return YahooExtra.EtfHoldings(list.distinctBy { it.name }.sortedByDescending { it.weightPct }, emptyList(), asOf)
    }
}
