package com.claudeetf.app.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 持股集中度、風險提示與交易統計 */
object Insights {

    /** 持股類型（與績效分析的配置一致） */
    fun typeOf(symbol: String): String {
        val q = AppState.quote(symbol)
        return when {
            q == null -> "其他"
            q.isEtf || symbol.startsWith("00") -> when (TaxRules.kindOf(symbol)) {
                TaxKind.TW_ETF_BOND -> "債券 ETF"
                TaxKind.TW_ETF_OVERSEAS -> "海外 ETF"
                TaxKind.TW_ETF_OPTION -> "收益型 ETF"
                else -> if (q.market == Market.US) "美國 ETF" else "台股 ETF"
            }
            q.market == Market.US -> "美股個股"
            else -> "台股個股"
        }
    }

    data class Concentration(
        val top: List<Pair<String, Double>>,
        val top1: Double,
        val top3: Double,
        val byType: List<Pair<String, Double>>,
        val usd: Double,
        val cash: Double,
        val warnings: List<String>,
        /** 赫芬達指數換算的「等效持股檔數」 */
        val effectiveN: Double,
    )

    fun concentration(): Concentration? {
        val hs = AppState.holdings.toList()
        val mv = hs.sumOf { AppState.marketValueTwd(it) }
        if (hs.isEmpty() || mv <= 0) return null
        val total = mv + AppState.cashTwd
        val weights = hs.map { it.symbol to AppState.marketValueTwd(it) / mv * 100 }.sortedByDescending { it.second }
        val top1 = weights.first().second
        val top3 = weights.take(3).sumOf { it.second }
        val byType = hs.groupBy { typeOf(it.symbol) }.map { (k, v) -> k to v.sumOf { AppState.marketValueTwd(it) } / mv * 100 }
            .sortedByDescending { it.second }
        val usd = AppState.totalMarketValueTwd(Market.US) / total * 100
        val cash = AppState.cashTwd / total * 100
        val hhi = weights.sumOf { (it.second / 100) * (it.second / 100) }
        val effN = if (hhi > 0) 1 / hhi else 0.0

        val w = ArrayList<String>()
        val first = weights.first()
        val name = AppState.quote(first.first)?.name ?: first.first
        val firstIsBroadEtf = typeOf(first.first).contains("ETF") && TaxRules.kindOf(first.first) != TaxKind.TW_ETF_OPTION
        if (top1 >= 25 && !firstIsBroadEtf) w += "單一個股「$name」占持股 ${"%.1f".format(top1)}%，個股風險集中；可考慮設定停損價或分散到其他標的。"
        if (top3 >= 70 && hs.size > 3) w += "前三大持股合計 ${"%.1f".format(top3)}%，組合表現高度取決於這三檔。"
        byType.firstOrNull()?.let { (t, p) -> if (p >= 70 && t.contains("個股")) w += "「$t」占 ${"%.1f".format(p)}%，可搭配市值型或債券 ETF 降低波動。" }
        if (usd >= 60) w += "美元資產占總資產 ${"%.1f".format(usd)}%，台幣升值時會產生匯兌損失。"
        if (cash < 3 && total > 0) w += "現金僅占 ${"%.1f".format(cash)}%，建議保留緊急預備金，避免急用時被迫賣股。"
        if (effN in 0.1..3.0 && hs.size >= 4) w += "持股權重集中，實際分散效果約等於 ${"%.1f".format(effN)} 檔平均持有。"
        return Concentration(weights.take(10), top1, top3, byType, usd, cash, w, effN)
    }

    data class TradeStats(
        val closed: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val avgWin: Double,
        val avgLoss: Double,
        val profitFactor: Double?,
        val best: Pair<String, Double>?,
        val worst: Pair<String, Double>?,
        val avgHoldDays: Double?,
        val totalRealized: Double,
    )

    /** 以每筆賣出為一次「平倉」，持有天數依先進先出加權計算（期初持股不計天數） */
    fun tradeStats(): TradeStats? {
        val trades = AppState.trades.filter { it.origin != TradeOrigin.LEGACY }.sortedBy { Ledger.sortKey(it) }
        data class L(var shares: Double, val date: LocalDate?)
        val lots = HashMap<String, ArrayList<L>>()
        val results = ArrayList<Pair<String, Double>>()
        var dayWeighted = 0.0
        var dayShares = 0.0
        fun parse(d: String) = runCatching { LocalDate.parse(d.replace('/', '-')) }.getOrNull()
        for (t in trades) {
            val list = lots.getOrPut(t.symbol) { ArrayList() }
            when (t.type) {
                TradeType.BUY -> list += L(t.shares, if (t.origin == TradeOrigin.OPENING) null else parse(t.date))
                TradeType.SPLIT -> if (t.price > 0) list.forEach { it.shares *= t.price }
                TradeType.SELL -> {
                    val sd = parse(t.date)
                    var left = t.shares
                    while (left > 1e-9 && list.isNotEmpty()) {
                        val l = list[0]
                        val take = minOf(left, l.shares)
                        if (sd != null && l.date != null) {
                            dayWeighted += take * ChronoUnit.DAYS.between(l.date, sd)
                            dayShares += take
                        }
                        l.shares -= take; left -= take
                        if (l.shares <= 1e-9) list.removeAt(0)
                    }
                    AppState.realizedById[t.id]?.let { results += t.symbol to it }
                }
                TradeType.DIVIDEND -> {}
            }
        }
        if (results.isEmpty()) return null
        val wins = results.filter { it.second > 0 }
        val losses = results.filter { it.second < 0 }
        val grossWin = wins.sumOf { it.second }
        val grossLoss = -losses.sumOf { it.second }
        return TradeStats(
            results.size, wins.size, losses.size, wins.size * 100.0 / results.size,
            if (wins.isNotEmpty()) grossWin / wins.size else 0.0,
            if (losses.isNotEmpty()) -grossLoss / losses.size else 0.0,
            if (grossLoss > 0) grossWin / grossLoss else null,
            results.maxByOrNull { it.second }, results.minByOrNull { it.second },
            if (dayShares > 0) dayWeighted / dayShares else null,
            results.sumOf { it.second },
        )
    }
}
