package com.claudeetf.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 櫃買中心（TPEx）官方 OpenAPI v1，免金鑰，上櫃股票：
 *   三大法人買賣超  /openapi/v1/t187ap07_O（SecuritiesCompanyCode、ForeignInvestorsDifference、
 *                  InvestmentTrustDifference、DealerDifference、TotalDifference，單位：股）
 *   融資融券餘額    /openapi/v1/t187ap06_O（SecuritiesCode、MarginPurchaseTodayBalance、ShortSaleTodayBalance，單位：張）
 *   當月營收        /openapi/v1/t187ap05_O（SecuritiesCode、OperatingRevenueCurrentMonth、
 *                  OperatingRevenueLastYearSameMonth、YoYRate，單位：千元）
 * 欄位以官方名稱優先，找不到時再用包含關鍵字比對（容忍大小寫與欄位小改）。
 * 同一資料集一天抓一次；法人資料在 16:00 後換成當天。
 */
object TpexOpenData {
    private const val BASE = "https://www.tpex.org.tw/openapi/v1"
    private val TPE = ZoneId.of("Asia/Taipei")
    private val cache = HashMap<String, Pair<String, JSONArray>>()

    private fun stamp(path: String): String {
        val now = ZonedDateTime.now(TPE)
        // 法人與信用在收盤後才更新，16:00 前後視為不同版本
        return now.toLocalDate().toString() + if (path.endsWith("07_O") || path.endsWith("06_O")) "-" + (now.toLocalTime() >= LocalTime.of(16, 0)) else ""
    }

    /** 最近一次失敗原因（顯示在畫面上方便除錯） */
    @Volatile var lastError: String? = null

    @Synchronized
    private fun dataset(path: String): JSONArray {
        val st = stamp(path)
        cache[path]?.let { (s, arr) -> if (s == st) return arr }
        val body = Http.get(
            BASE + path,
            mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36", "Accept" to "application/json"),
            timeoutMs = 30_000,
        ).trim()
        if (!body.startsWith("[")) throw IllegalStateException("$path 回傳的不是 JSON 陣列")
        val arr = JSONArray(body)
        cache[path] = st to arr
        return arr
    }

    /** 依序試候選端點，第一個能解析出資料的就用；全部失敗時記下原因 */
    private fun <T> firstOf(paths: List<String>, parse: (JSONArray) -> Map<String, T>): Map<String, T> {
        val errs = ArrayList<String>()
        for (p in paths) {
            val r = runCatching { parse(dataset(p)) }
            val m = r.getOrNull()
            if (m != null && m.size >= 20) { lastError = null; return m }
            errs += p + "：" + (r.exceptionOrNull()?.message ?: "欄位對不上（${m?.size ?: 0} 筆）")
        }
        lastError = "櫃買中心 " + errs.joinToString("；")
        return emptyMap()
    }

    /** 欄位名稱包含全部關鍵字（不分大小寫），可排除特定字 */
    private fun JSONObject.like(all: List<String>, none: List<String> = emptyList()): String? {
        val k = keys().asSequence().firstOrNull { key -> all.all { key.contains(it, true) } && none.none { key.contains(it, true) } } ?: return null
        return optString(k).trim().takeIf { it.isNotEmpty() && it != "-" && it != "--" }
    }

    /** 先找完全相同的欄位名，再找包含全部關鍵字的欄位 */
    private fun JSONObject.field(exact: String, vararg contains: String): String? {
        val keys = keys().asSequence().toList()
        val k = keys.firstOrNull { it == exact } ?: keys.firstOrNull { it.equals(exact, true) }
            ?: if (contains.isNotEmpty()) keys.firstOrNull { key -> contains.all { key.contains(it, true) } } else null
        val v = k?.let { optString(it).trim() }
        return v?.takeIf { it.isNotEmpty() && it != "-" && it != "--" }
    }

    private fun num(s: String?): Double? = s?.replace(",", "")?.replace("%", "")?.trim()?.toDoubleOrNull()

    private fun code(o: JSONObject): String? =
        (o.field("SecuritiesCompanyCode") ?: o.field("SecuritiesCode") ?: o.field("Code", "Code")
            ?: o.like(listOf("代號")) ?: o.like(listOf("代碼")))?.trim()?.takeIf { it.isNotEmpty() && it.first().isDigit() }

    /** 民國 1151105／115/11/05 或西元 20261105 */
    private fun date(o: JSONObject): LocalDate? {
        val s = o.field("Date", "Date") ?: return null
        val p = s.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        return runCatching {
            if (p.size >= 3) LocalDate.of(p[0].toInt().let { if (it < 1000) it + 1911 else it }, p[1].toInt(), p[2].toInt())
            else {
                val d = p.first()
                when (d.length) {
                    7 -> LocalDate.of(d.take(3).toInt() + 1911, d.substring(3, 5).toInt(), d.takeLast(2).toInt())
                    8 -> LocalDate.of(d.take(4).toInt(), d.substring(4, 6).toInt(), d.takeLast(2).toInt())
                    else -> null
                }
            }
        }.getOrNull()
    }

    /** 上櫃三大法人買賣超（股）：代號 → Day。先試您提供的 t187ap07_O，再試 tpex_3insti_daily_trading */
    fun institutional(): Map<String, TwInstitutional.Day> = firstOf(listOf("/t187ap07_O", "/tpex_3insti_daily_trading")) { arr ->
        val out = HashMap<String, TwInstitutional.Day>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val c = code(o) ?: continue
            val fS = o.field("ForeignInvestorsDifference")
                ?: o.like(listOf("Foreign", "Total", "Difference"))
                ?: o.like(listOf("Foreign", "Difference"), listOf("Dealer"))
                ?: o.like(listOf("外資", "合計", "買賣超")) ?: o.like(listOf("外", "買賣超"), listOf("自營"))
            val tS = o.field("InvestmentTrustDifference") ?: o.like(listOf("Trust", "Difference")) ?: o.like(listOf("投信", "買賣超"))
            val dS = o.field("DealerDifference") ?: o.like(listOf("Dealers", "Total", "Difference"))
                ?: o.like(listOf("Dealer", "Total", "Difference"), listOf("Foreign"))
                ?: o.like(listOf("Dealer", "Difference"), listOf("Foreign", "Proprietary", "Hedg"))
                ?: o.like(listOf("自營商", "合計", "買賣超"), listOf("外資")) ?: o.like(listOf("自營商", "買賣超"), listOf("外資", "自行", "避險"))
            val totS = o.field("TotalDifference") ?: o.like(listOf("Total", "Difference"), listOf("Foreign", "Dealer", "Trust"))
                ?: o.like(listOf("三大法人", "買賣超"))
            if (fS == null && tS == null && dS == null && totS == null) continue
            val f = num(fS)?.toLong() ?: 0L
            val t = num(tS)?.toLong() ?: 0L
            val d = num(dS)?.toLong() ?: 0L
            out[c] = TwInstitutional.Day(date(o) ?: LocalDate.now(TPE), f, t, d, num(totS)?.toLong() ?: (f + t + d), otc = true)
        }
        out
    }

    /** 上櫃融資融券餘額（張） */
    fun margin(): Map<String, TwOpenData.Margin> = firstOf(listOf("/t187ap06_O", "/tpex_mainboard_margin_balance")) { arr ->
        val out = HashMap<String, TwOpenData.Margin>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val c = code(o) ?: continue
            val fin = num(o.field("MarginPurchaseTodayBalance") ?: o.like(listOf("MarginPurchase", "Balance"), listOf("Previous", "Prev", "Limit", "Quota"))
                ?: o.like(listOf("資", "今日餘額"), listOf("券")) ?: o.like(listOf("融資", "餘額"), listOf("前日", "限額")))
            val sh = num(o.field("ShortSaleTodayBalance") ?: o.like(listOf("ShortSale", "Balance"), listOf("Previous", "Prev", "Limit", "Quota"))
                ?: o.like(listOf("券", "今日餘額")) ?: o.like(listOf("融券", "餘額"), listOf("前日", "限額")))
            if (fin == null && sh == null) continue
            out[c] = TwOpenData.Margin(
                fin, num(o.like(listOf("MarginPurchase", "Previous", "Balance")) ?: o.like(listOf("資", "前日餘額"), listOf("券"))),
                sh, num(o.like(listOf("ShortSale", "Previous", "Balance")) ?: o.like(listOf("券", "前日餘額"))),
            )
        }
        out
    }

    /** 上櫃公司當月營收（千元） */
    fun revenue(): Map<String, TwOpenData.MonthlyRevenue> = firstOf(listOf("/t187ap05_O", "/mopsfin_t187ap05_O")) { arr ->
        val out = HashMap<String, TwOpenData.MonthlyRevenue>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val c = code(o) ?: continue
            val cur = num(o.field("OperatingRevenueCurrentMonth", "Revenue", "CurrentMonth") ?: o.like(listOf("當月營收"), listOf("累計")))
            val ly = num(o.field("OperatingRevenueLastYearSameMonth", "Revenue", "LastYear"))
            val lm = num(o.field("OperatingRevenueLastMonth", "Revenue", "LastMonth"))
            val yoy = o.field("YoYRate", "YoY")?.replace("%", "")
                ?: if (cur != null && ly != null && ly > 0) "%.2f".format((cur / ly - 1) * 100) else null
            val mom = o.field("MoMRate", "MoM")?.replace("%", "")
                ?: if (cur != null && lm != null && lm > 0) "%.2f".format((cur / lm - 1) * 100) else null
            val ym = o.field("DataYearMonth", "YearMonth")?.filter { it.isDigit() }
            val month = when {
                ym == null -> "—"
                ym.length == 6 -> ym.take(4) + "/" + ym.takeLast(2)
                ym.length >= 4 -> "${(ym.dropLast(2).toIntOrNull() ?: 0) + 1911}/${ym.takeLast(2)}"
                else -> ym
            }
            out[c] = TwOpenData.MonthlyRevenue(
                month = month,
                revenueK = cur,
                momPct = mom,
                yoyPct = yoy,
                ytdK = num(o.field("CumulativeOperatingRevenueCurrentYear", "Cumulative", "Current")),
                ytdYoyPct = o.field("CumulativeYoYRate", "Cumulative", "Rate")?.replace("%", ""),
                industry = o.field("IndustryCategory", "Industry"),
            )
        }
        out
    }
}
