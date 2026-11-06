package com.claudeetf.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 配息現金流（下一筆預估入帳、每月入帳明細、配息試算）。
 *
 * 每檔持股取：
 *  1. 已除息、尚未發放（或本月剛發放）的那一次：金額取 Yahoo 實際配息；
 *  2. 已公告、尚未除息的下一次：金額取證交所公告（沒有則以上次配息估計）。
 * 發放日：Yahoo calendarEvents 有就用，沒有以除息日＋常見天數推估（台股 ETF 約 24 天、個股約 30 天、美股約 14 天），標示「預估」。
 * 資格：以「最後買進日」收盤時的持股計算（依交易紀錄回推），除息日後才買的不列入。
 */
object DividendFlow {
    private val TPE: ZoneId = ZoneId.of("Asia/Taipei")

    data class Event(
        val symbol: String,
        val name: String,
        val market: Market,
        val exDate: LocalDate,
        val lastBuy: LocalDate,
        val payDate: LocalDate,
        /** 發放日為推估（非公告） */
        val payEstimated: Boolean,
        /** 每單位配息（原幣） */
        val perUnit: Double,
        /** 金額為官方公告／實際配發（false = 以上次配息估計） */
        val amountConfirmed: Boolean,
        /** 符合資格的股數 */
        val eligibleShares: Double,
        /** 目前持股（比較用） */
        val currentShares: Double,
        val gross: Double,
        /** 二代健保（台股單筆 ≥ 2 萬扣 2.11%） */
        val nhi: Double,
        /** 美股預扣 30% */
        val withholding: Double,
        val net: Double,
        /** 已記錄到交易紀錄 */
        val recorded: Boolean,
        /** 首次買進日（用於顯示「起持有」） */
        val holdSince: LocalDate?,
    ) {
        val usd get() = market == Market.US
        /** 換算台幣淨額 */
        val netTwd get() = net * if (usd) AppState.usdTwd else 1.0
        fun daysToPay(today: LocalDate = LocalDate.now(TPE)) = ChronoUnit.DAYS.between(today, payDate)
        val paid get() = !payDate.isAfter(LocalDate.now(TPE))
    }

    val events = mutableStateListOf<Event>()
    var loading by mutableStateOf(false)
        private set
    var updatedAt by mutableStateOf(0L)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var day = ""

    private fun isEtf(q: Quote) = q.isEtf || (q.market == Market.TW && q.symbol.startsWith("00"))

    /** 除息到發放的常見天數 */
    fun payLag(q: Quote): Long = when {
        q.market == Market.US -> 14
        isEtf(q) -> 24
        else -> 30
    }

    /** 最後買進日收盤時持有的股數（依交易紀錄回推；沒有紀錄時用目前持股） */
    fun sharesOn(symbol: String, date: LocalDate): Double? {
        val ts = AppState.trades.filter { it.symbol == symbol && it.origin != TradeOrigin.LEGACY }
        if (ts.isEmpty()) return null
        var shares = 0.0
        ts.sortedBy { it.date }.forEach { t ->
            val d = runCatching { LocalDate.parse(t.date.replace('/', '-').take(10)) }.getOrNull() ?: return@forEach
            if (d.isAfter(date)) return@forEach
            when (t.type) {
                TradeType.BUY -> shares += t.shares
                TradeType.SELL -> shares -= t.shares
                TradeType.SPLIT -> if (t.price > 0) shares *= t.price
                else -> {}
            }
        }
        return shares.coerceAtLeast(0.0)
    }

    private fun firstBuy(symbol: String): LocalDate? = AppState.trades
        .filter { it.symbol == symbol && it.type == TradeType.BUY && it.origin != TradeOrigin.LEGACY }
        .mapNotNull { runCatching { LocalDate.parse(it.date.replace('/', '-').take(10)) }.getOrNull() }.minOrNull()

    private fun make(q: Quote, h: Holding, ex: LocalDate, pay: LocalDate?, perUnit: Double, confirmed: Boolean): Event {
        val lastBuy = DividendCalendar.lastBuyDay(ex)
        val eligible = sharesOn(h.symbol, lastBuy) ?: h.shares
        val gross = perUnit * eligible
        val us = q.market == Market.US
        val nhi = if (!us && gross >= 20_000) Math.floor(gross * 0.0211) else 0.0
        val wh = if (us) gross * 0.3 else 0.0
        val payDate = pay ?: ex.plusDays(payLag(q))
        val recorded = AppState.trades.any { t ->
            t.symbol == h.symbol && t.type == TradeType.DIVIDEND &&
                runCatching { LocalDate.parse(t.date.replace('/', '-').take(10)) }.getOrNull()?.let { !it.isBefore(ex) && !it.isAfter(payDate.plusDays(20)) } == true
        }
        return Event(h.symbol, q.name, q.market, ex, lastBuy, payDate, pay == null, perUnit, confirmed,
            eligible, h.shares, gross, nhi, wh, gross - nhi - wh, recorded, firstBuy(h.symbol))
    }

    /** 開 App 自動更新（一天一次）；force = 重新整理 */
    suspend fun refresh(force: Boolean = false) {
        if (loading) return
        val today = LocalDate.now(TPE)
        if (!force && day == today.toString() && events.isNotEmpty()) return
        loading = true
        error = null
        try {
            runCatching { DividendCalendar.refresh(force) }
            val holds = AppState.holdings.filter { it.shares > 0 }
            val out = withContext(Dispatchers.IO) {
                val list = ArrayList<Event>()
                for (h in holds) {
                    val q = AppState.quote(h.symbol) ?: continue
                    val sym = StockInfoRepo.yahooSymbol(q)
                    val zone = if (q.market == Market.US) ZoneId.of("America/New_York") else TPE
                    fun d(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
                    val divs = runCatching { YahooExtra.dividends(sym) }.getOrDefault(emptyList()).sortedByDescending { it.time }
                    val ev = runCatching { YahooExtra.events(sym) }.getOrNull()
                    val evEx = ev?.exDividend?.let { d(it) }
                    val evPay = ev?.dividendPay?.let { d(it) }
                    // 1) 最近一次已除息：發放日在 45 天內（未發放或本月剛發放）
                    divs.firstOrNull()?.let { last ->
                        val ex = d(last.time)
                        val pay = if (evEx == ex) evPay?.takeIf { !it.isBefore(ex) } else null
                        val payDate = pay ?: ex.plusDays(payLag(q))
                        if (!payDate.isBefore(today.minusDays(45)) && ChronoUnit.DAYS.between(ex, today) <= 75)
                            list += make(q, h, ex, pay, last.amount, true)
                    }
                    // 2) 已公告、尚未除息的下一次
                    val plan = AppState.divPlans[h.symbol]
                    val nextEx = plan?.exDate ?: evEx?.takeIf { !it.isBefore(today) }
                    if (nextEx != null && !nextEx.isBefore(today) && list.none { it.symbol == h.symbol && it.exDate == nextEx }) {
                        val amount = plan?.cash ?: divs.firstOrNull()?.amount
                        if (amount != null && amount > 0) {
                            val pay = plan?.payDate ?: evPay?.takeIf { evEx == nextEx && !it.isBefore(nextEx) }
                            list += make(q, h, nextEx, pay, amount, plan?.announced == true)
                        }
                    }
                }
                list
            }
            events.clear(); events.addAll(out.sortedBy { it.payDate })
            updatedAt = System.currentTimeMillis()
            day = today.toString()
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
        }
    }

    /** 下一筆（今天以後、符合資格）預估入帳 */
    fun next(): Event? {
        val today = LocalDate.now(TPE)
        return events.filter { !it.payDate.isBefore(today) && it.eligibleShares > 0 }.minByOrNull { it.payDate }
    }

    fun ofMonth(ym: java.time.YearMonth): List<Event> =
        events.filter { java.time.YearMonth.from(it.payDate) == ym && it.eligibleShares > 0 }.sortedBy { it.payDate }

    /** 配息試算：單次目標配息（台幣）→ 需要股數（整張時向上取整到 1,000 股） */
    data class Sim(val shares: Double, val lots: Double, val gross: Double, val capital: Double, val have: Double, val need: Double)

    fun simulate(q: Quote, target: Double, perUnit: Double, wholeLots: Boolean): Sim? {
        if (perUnit <= 0 || target <= 0) return null
        val fx = if (q.market == Market.US) AppState.usdTwd else 1.0
        val raw = Math.ceil(target / (perUnit * fx))
        val shares = if (wholeLots && q.market == Market.TW) Math.ceil(raw / 1000) * 1000 else raw
        val have = AppState.holdings.firstOrNull { it.symbol == q.symbol }?.shares ?: 0.0
        return Sim(shares, shares / 1000, shares * perUnit * fx, shares * q.price * fx, have,
            ((shares - have).coerceAtLeast(0.0)).let { if (wholeLots && q.market == Market.TW) Math.ceil(it / 1000) * 1000 else it })
    }
}
