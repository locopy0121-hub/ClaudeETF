package com.claudeetf.app.data

import kotlin.math.max

/** 交易來源：一般交易、期初部位（不扣現金）、舊版紀錄（只保留不計算） */
object TradeOrigin {
    const val NORMAL = ""
    const val OPENING = "opening"
    const val LEGACY = "legacy"
}

/** 帳本的期初基準值（期初持股以 OPENING 交易表示） */
data class LedgerBase(
    val cash: Double,
    val year: Int,
    val realized: Double,
    val divTw: Double,
    val divUsGross: Double,
)

data class LedgerResult(
    val holdings: List<Holding>,
    val cash: Double,
    val realizedYtd: Double,
    val divTwYtd: Double,
    val divUsGrossYtd: Double,
    /** 每筆賣出的已實現損益（台幣） */
    val realizedById: Map<Long, Double>,
    /** 每筆股息的實收（台幣） */
    val dividendById: Map<Long, Double>,
)

/**
 * 由交易紀錄重算持股、現金、已實現損益與股息。
 * 依日期與編號排序後逐筆套用；賣超等不合理情況回傳錯誤訊息。
 */
object Ledger {
    fun sortKey(t: Trade) = t.date + "#" + t.id.toString().padStart(10, '0')

    fun compute(
        base: LedgerBase,
        trades: List<Trade>,
        year: Int,
        marketOf: (String) -> Market,
    ): Pair<LedgerResult?, String?> {
        val hs = LinkedHashMap<String, Holding>()
        var cash = base.cash
        var realized = if (base.year == year) base.realized else 0.0
        var divTw = if (base.year == year) base.divTw else 0.0
        var divUs = if (base.year == year) base.divUsGross else 0.0
        val realizedById = HashMap<Long, Double>()
        val dividendById = HashMap<Long, Double>()
        val y = year.toString()

        for (t in trades.filter { it.origin != TradeOrigin.LEGACY }.sortedBy { sortKey(it) }) {
            val market = marketOf(t.symbol)
            val isUs = market == Market.US
            val fx = if (isUs) t.fx else 1.0
            val thisYear = t.date.startsWith(y)
            val existing = hs[t.symbol]
            when (t.type) {
                TradeType.BUY -> {
                    val addCost = t.shares * t.price + t.fee
                    val oldShares = existing?.shares ?: 0.0
                    val oldCost = oldShares * (existing?.avgCost ?: 0.0)
                    val newShares = oldShares + t.shares
                    if (newShares <= 0) continue
                    val newAvg = (oldCost + addCost) / newShares
                    val newFx = if (isUs) {
                        val oldFxCost = oldCost * (existing?.avgFx ?: fx)
                        if (oldCost + addCost > 0) (oldFxCost + addCost * fx) / (oldCost + addCost) else fx
                    } else 1.0
                    hs[t.symbol] = Holding(t.symbol, newShares, newAvg, newFx)
                    if (t.origin != TradeOrigin.OPENING) cash -= addCost * fx
                }
                TradeType.SELL -> {
                    if (existing == null) return null to "${t.date} 賣出 ${t.symbol} 時沒有持股"
                    if (t.shares > existing.shares + 1e-9) {
                        return null to "${t.date} 賣出 ${t.symbol} ${t.shares} 股，超過當時持有的 ${existing.shares} 股"
                    }
                    val proceeds = t.shares * t.price - t.fee - t.tax
                    val costFx = if (isUs) existing.avgFx else 1.0
                    val r = proceeds * fx - t.shares * existing.avgCost * costFx
                    realizedById[t.id] = r
                    if (thisYear) realized += r
                    cash += proceeds * fx
                    val left = existing.shares - t.shares
                    if (left <= 1e-9) hs.remove(t.symbol) else hs[t.symbol] = existing.copy(shares = left)
                }
                TradeType.DIVIDEND -> {
                    val gross = t.shares * t.price
                    val netTwd = if (isUs) gross * (1 - TaxRules.US_WITHHOLD) * fx else gross - t.fee
                    dividendById[t.id] = netTwd
                    if (thisYear) { if (isUs) divUs += gross else divTw += gross - t.fee }
                    cash += netTwd
                }
                TradeType.SPLIT -> {
                    if (existing == null) return null to "${t.date} ${t.symbol} 拆股時沒有持股"
                    val ratio = t.price
                    if (ratio <= 0) return null to "${t.date} ${t.symbol} 拆股比例需大於 0"
                    hs[t.symbol] = existing.copy(shares = existing.shares * ratio, avgCost = existing.avgCost / ratio)
                }
            }
        }
        return LedgerResult(hs.values.toList(), max(cash, -1e12), realized, divTw, divUs, realizedById, dividendById) to null
    }
}
