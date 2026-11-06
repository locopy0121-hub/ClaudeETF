package com.claudeetf.app.data

import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 除息與拆股偵測：以 Yahoo 的配息、分割事件比對交易紀錄，找出尚未記錄的項目。
 * 只檢查目前持股、近 180 天內、且事件發生時確實持有的股票。
 */
object CorporateActions {
    enum class Kind { DIVIDEND, SPLIT }

    data class Pending(
        val key: String,
        val kind: Kind,
        val symbol: String,
        val date: String,
        /** 股息：每股金額；拆股：比例 */
        val value: Double,
        val shares: Double,
        val text: String,
    )

    private val TPE = ZoneId.of("Asia/Taipei")
    private val FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd")
    private const val TTL = 12 * 3600_000L

    private val divCache = HashMap<String, Pair<Long, List<YahooExtra.Dividend>>>()
    private val splitCache = HashMap<String, Pair<Long, List<YahooExtra.Split>>>()

    private fun day(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(TPE).toLocalDate()

    /** 某日（不含當日）之前的持股數 */
    private fun sharesBefore(symbol: String, date: String): Double {
        val y = LocalDate.now(TPE).year
        val ts = AppState.trades.filter { it.symbol == symbol && it.date < date }
        return Ledger.compute(LedgerBase(0.0, y, 0.0, 0.0, 0.0), ts, y) { AppState.marketOf(it) }
            .first?.holdings?.firstOrNull { it.symbol == symbol }?.shares ?: 0.0
    }

    suspend fun scan(force: Boolean = false): List<Pending> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val since = LocalDate.now(TPE).minusDays(180)
        val out = ArrayList<Pending>()
        for (h in AppState.holdings.toList()) {
            val q = AppState.quote(h.symbol) ?: continue
            val ys = StockInfoRepo.yahooSymbol(q)
            val divs = divCache[h.symbol]?.takeIf { !force && now - it.first < TTL }?.second
                ?: runCatching { YahooExtra.dividends(ys) }.getOrNull()?.also { divCache[h.symbol] = now to it } ?: emptyList()
            val splits = splitCache[h.symbol]?.takeIf { !force && now - it.first < TTL }?.second
                ?: runCatching { YahooExtra.splits(ys) }.getOrNull()?.also { splitCache[h.symbol] = now to it } ?: emptyList()

            for (d in divs) {
                val ex = day(d.time)
                if (ex.isBefore(since) || ex.isAfter(LocalDate.now(TPE))) continue
                val date = ex.format(FMT)
                val key = "D-${h.symbol}-$date"
                if (key in AppState.dismissedActions) continue
                // 除息日起 75 天內已有該股股息紀錄，就視為已記錄
                val recorded = AppState.trades.any {
                    it.symbol == h.symbol && it.type == TradeType.DIVIDEND &&
                        it.date >= date && it.date <= ex.plusDays(75).format(FMT)
                }
                if (recorded) continue
                val shares = sharesBefore(h.symbol, date)
                if (shares <= 0) continue
                val ccy = if (q.market == Market.US) "$" else "NT$"
                out += Pending(key, Kind.DIVIDEND, h.symbol, date, d.amount, shares,
                    "${h.symbol} ${q.name} ${ex.monthValue}/${ex.dayOfMonth} 除息，每股 $ccy${trim(d.amount)} × ${trimShares(shares)} 股")
            }
            for (s in splits) {
                val sd = day(s.time)
                if (sd.isBefore(since)) continue
                val date = sd.format(FMT)
                val key = "S-${h.symbol}-$date"
                if (key in AppState.dismissedActions) continue
                val recorded = AppState.trades.any { it.symbol == h.symbol && it.type == TradeType.SPLIT && it.date >= sd.minusDays(10).format(FMT) }
                if (recorded) continue
                val shares = sharesBefore(h.symbol, date)
                if (shares <= 0) continue
                out += Pending(key, Kind.SPLIT, h.symbol, date, s.ratio, shares,
                    "${h.symbol} ${q.name} ${sd.monthValue}/${sd.dayOfMonth} 股票分割 ${s.text.ifBlank { trim(s.ratio) + ":1" }}，持股 ${trimShares(shares)} → ${trimShares(shares * s.ratio)} 股")
            }
        }
        val sorted = out.sortedByDescending { it.date }
        AppState.pendingActions.clear()
        AppState.pendingActions.addAll(sorted)
        sorted
    }

    private fun trim(v: Double) = java.math.BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    private fun trimShares(v: Double) = java.math.BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** 依待記錄項目建立交易；股息依稅務規則計算二代健保 */
    fun record(p: Pending): String? {
        val market = AppState.marketOf(p.symbol)
        val us = market == Market.US
        val err = when (p.kind) {
            Kind.DIVIDEND -> {
                val gross = p.shares * p.value
                val nhi = if (us) 0.0 else TaxRules.dividend(p.symbol, gross).nhi
                AppState.addTrade(Trade(TradeType.DIVIDEND, p.symbol, p.date, p.shares, p.value,
                    if (us) AppState.usdTwd else 1.0, nhi, 0.0, note = "自動偵測除息"))
            }
            Kind.SPLIT -> AppState.addTrade(Trade(TradeType.SPLIT, p.symbol, p.date, 0.0, p.value, 1.0, 0.0, 0.0, note = "自動偵測分割"))
        }
        if (err == null) AppState.pendingActions.removeAll { it.key == p.key }
        return err
    }

    fun dismiss(p: Pending) {
        AppState.dismissAction(p.key)
        AppState.pendingActions.removeAll { it.key == p.key }
    }
}
