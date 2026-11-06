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

    @Synchronized
    private fun dataset(path: String): JSONArray {
        val st = stamp(path)
        cache[path]?.let { (s, arr) -> if (s == st) return arr }
        val arr = JSONArray(Http.get(BASE + path, timeoutMs = 30_000))
        cache[path] = st to arr
        return arr
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
        (o.field("SecuritiesCompanyCode") ?: o.field("SecuritiesCode") ?: o.field("Code", "Code") ?: o.field("公司代號"))?.trim()

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

    /** 上櫃三大法人買賣超（股）：代號 → Day */
    fun institutional(): Map<String, TwInstitutional.Day> {
        val arr = dataset("/t187ap07_O")
        val out = HashMap<String, TwInstitutional.Day>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val c = code(o) ?: continue
            val f = num(o.field("ForeignInvestorsDifference", "Foreign", "Difference"))?.toLong() ?: 0L
            val t = num(o.field("InvestmentTrustDifference", "Trust", "Difference"))?.toLong() ?: 0L
            val d = num(o.field("DealerDifference", "Dealer", "Difference"))?.toLong() ?: 0L
            val total = num(o.field("TotalDifference", "Total", "Difference"))?.toLong() ?: (f + t + d)
            out[c] = TwInstitutional.Day(date(o) ?: LocalDate.now(TPE), f, t, d, total)
        }
        return out
    }

    /** 上櫃融資融券餘額（張） */
    fun margin(): Map<String, TwOpenData.Margin> {
        val arr = dataset("/t187ap06_O")
        val out = HashMap<String, TwOpenData.Margin>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val c = code(o) ?: continue
            out[c] = TwOpenData.Margin(
                num(o.field("MarginPurchaseTodayBalance", "MarginPurchase", "Today", "Balance")),
                num(o.field("MarginPurchasePreviousDayBalance", "MarginPurchase", "Previous", "Balance")),
                num(o.field("ShortSaleTodayBalance", "ShortSale", "Today", "Balance")),
                num(o.field("ShortSalePreviousDayBalance", "ShortSale", "Previous", "Balance")),
            )
        }
        return out
    }

    /** 上櫃公司當月營收（千元） */
    fun revenue(): Map<String, TwOpenData.MonthlyRevenue> {
        val arr = dataset("/t187ap05_O")
        val out = HashMap<String, TwOpenData.MonthlyRevenue>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val c = code(o) ?: continue
            val cur = num(o.field("OperatingRevenueCurrentMonth", "Revenue", "CurrentMonth"))
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
        return out
    }
}
