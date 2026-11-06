package com.claudeetf.app.data

import com.claudeetf.app.data.remote.Http
import com.claudeetf.app.data.remote.YahooExtra
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 除權息行事曆：除息日、最後買進日、發放日、每股股利與持股預估。
 *
 * 台股上市：證交所 OpenAPI 除權除息預告表（官方公告，含每股現金股利）
 *   GET https://openapi.twse.com.tw/v1/exchangeReport/TWT48U
 * 上櫃與美股：Yahoo Finance calendarEvents（除息日、發放日），每股金額以上次配息估計。
 * App 開啟時自動更新，同一天只抓一次（可強制重抓）。
 */
object DividendCalendar {
    private val TPE: ZoneId = ZoneId.of("Asia/Taipei")

    data class Plan(
        val symbol: String,
        val exDate: LocalDate,
        /** 最後買進日：除息日前一個交易日（僅排除週末，遇國定假日以券商公告為準） */
        val lastBuy: LocalDate,
        val payDate: LocalDate?,
        /** 每股現金股利（原幣） */
        val cash: Double?,
        /** 每股配股（股） */
        val stock: Double?,
        /** 除息、除權、除權息 */
        val type: String,
        /** true：官方公告金額；false：以上次配息估計 */
        val announced: Boolean,
        val source: String,
    ) {
        fun daysToEx(today: LocalDate = LocalDate.now(ZoneId.of("Asia/Taipei"))) = ChronoUnit.DAYS.between(today, exDate)
        fun daysToLastBuy(today: LocalDate = LocalDate.now(ZoneId.of("Asia/Taipei"))) = ChronoUnit.DAYS.between(today, lastBuy)
    }

    /** 持股預估實收 */
    data class Estimate(val gross: Double, val nhi: Double, val withholding: Double, val net: Double, val stockShares: Double)

    private var day = ""
    // 狀態要能通知畫面，否則載入完成後畫面仍停在「查詢中…」
    var loading by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun lastBuyDay(ex: LocalDate): LocalDate {
        var d = ex.minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }

    private fun today() = LocalDate.now(TPE)

    private fun JSONObject.find(vararg keywords: String, exclude: String? = null): String? {
        val it = keys()
        while (it.hasNext()) {
            val k = it.next()
            if (keywords.all { kw -> k.contains(kw, ignoreCase = true) } && (exclude == null || !k.contains(exclude, ignoreCase = true))) {
                val v = optString(k).trim()
                if (v.isNotEmpty() && v != "-") return v
            }
        }
        return null
    }

    private fun num(s: String?): Double? = s?.replace(",", "")?.trim()?.toDoubleOrNull()?.takeIf { it > 0 }

    /** 民國 1151107、115/11/07、115年11月07日，或西元 20261107、2026-11-07 */
    private fun parseDate(s: String?): LocalDate? {
        if (s == null) return null
        val parts = s.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        return runCatching {
            if (parts.size >= 3) {
                val y = parts[0].toInt().let { if (it < 1000) it + 1911 else it }
                LocalDate.of(y, parts[1].toInt(), parts[2].toInt())
            } else {
                val d = parts.firstOrNull() ?: return null
                when (d.length) {
                    7 -> LocalDate.of(d.take(3).toInt() + 1911, d.substring(3, 5).toInt(), d.takeLast(2).toInt())
                    8 -> LocalDate.of(d.take(4).toInt(), d.substring(4, 6).toInt(), d.takeLast(2).toInt())
                    else -> null
                }
            }
        }.getOrNull()
    }

    /** 證交所除權除息預告表：代號 → Plan */
    private fun twse(): Map<String, Plan> {
        val arr = JSONArray(Http.get("https://openapi.twse.com.tw/v1/exchangeReport/TWT48U", timeoutMs = 25_000))
        val out = HashMap<String, Plan>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = (o.find("Code") ?: o.find("代號"))?.trim() ?: continue
            val ex = parseDate(o.find("Date", exclude = "Announce") ?: o.find("除權除息日")) ?: continue
            if (ex.isBefore(today())) continue
            val cash = num(o.find("CashDividend") ?: o.find("Cash", "Dividend") ?: o.find("現金股利"))
            val stock = num(o.find("StockDividend") ?: o.find("無償配股"))
            val kind = o.find("Exdividend") ?: o.find("權", "息")
            val type = when {
                kind?.contains("權") == true && kind.contains("息") -> "除權息"
                kind?.contains("權") == true -> "除權"
                kind?.contains("息") == true -> "除息"
                cash != null && stock != null -> "除權息"
                stock != null -> "除權"
                else -> "除息"
            }
            val prev = out[code]
            if (prev == null || ex.isBefore(prev.exDate)) {
                out[code] = Plan(code, ex, lastBuyDay(ex), null, cash, stock?.let { if (it >= 1) it / 1000.0 else it }, type, cash != null || stock != null, "證交所除權除息預告")
            }
        }
        return out
    }

    /** Yahoo：除息日、發放日；金額用上次配息估計 */
    private fun yahoo(q: Quote): Plan? {
        val sym = StockInfoRepo.yahooSymbol(q)
        val ev = runCatching { YahooExtra.events(sym) }.getOrNull() ?: return null
        val ex = ev.exDividend?.let { Instant.ofEpochMilli(it).atZone(TPE).toLocalDate() } ?: return null
        if (ex.isBefore(today())) return null
        val divs = runCatching { YahooExtra.dividends(sym) }.getOrDefault(emptyList())
        val last = divs.maxByOrNull { it.time }
        // 除息日就是上次配息那天，代表 Yahoo 還沒公布下一次
        if (last != null && Instant.ofEpochMilli(last.time).atZone(TPE).toLocalDate() == ex) return null
        val pay = ev.dividendPay?.let { Instant.ofEpochMilli(it).atZone(TPE).toLocalDate() }?.takeIf { !it.isBefore(ex) }
        return Plan(q.symbol, ex, lastBuyDay(ex), pay, last?.amount, null, "除息", false, "Yahoo Finance（金額以上次配息估計）")
    }

    /** App 開啟時呼叫：自選與持股的除權息資訊，一天更新一次 */
    suspend fun refresh(force: Boolean = false) {
        if (loading) return
        if (!force && day == today().toString() && AppState.divPlans.isNotEmpty()) return
        loading = true
        try {
            val symbols = (AppState.holdings.map { it.symbol } + AppState.quotes.map { it.symbol }).distinct()
            val quotes = symbols.mapNotNull { AppState.quote(it) }
            var ok = false
            val result = withContext(Dispatchers.IO) {
                val twR = runCatching { twse() }
                ok = twR.isSuccess
                val tw = twR.getOrDefault(emptyMap())
                val out = HashMap<String, Plan>()
                for (q in quotes) {
                    val official = tw[q.symbol]
                    val y = runCatching { yahoo(q) }.getOrNull()
                    val plan = when {
                        official != null -> official.copy(payDate = y?.payDate?.takeIf { y.exDate == official.exDate })
                        else -> y
                    } ?: continue
                    out[q.symbol] = plan
                }
                out
            }
            AppState.divPlans.clear()
            AppState.divPlans.putAll(result)
            // 證交所或任何一檔查到才算今天更新完成；網路失敗下次開 App 再試
            if (ok || result.isNotEmpty()) day = today().toString()
            AppState.divPlansUpdatedAt = System.currentTimeMillis()
        } finally {
            loading = false
        }
    }

    /** 依目前持股估算可領金額；台股單筆 ≥ 2 萬扣二代健保 2.11%，美股預扣 30% */
    fun estimate(p: Plan): Estimate? {
        val h = AppState.holdings.firstOrNull { it.symbol == p.symbol } ?: return null
        val q = AppState.quote(p.symbol)
        val us = q?.market == Market.US || p.symbol.firstOrNull()?.isDigit() == false
        val gross = (p.cash ?: 0.0) * h.shares
        val nhi = if (!us && gross >= 20_000) Math.floor(gross * 0.0211) else 0.0
        val wh = if (us) gross * 0.3 else 0.0
        // 配股：每股配發股數（每千股配 x 股時已換算成每股）
        val stockShares = Math.floor((p.stock ?: 0.0) * h.shares)
        return Estimate(gross, nhi, wh, gross - nhi - wh, stockShares)
    }
}
