package com.claudeetf.app.data

import com.claudeetf.app.data.remote.TwInstitutional
import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 自選「除權息」「法人」欄位的資料（每天下載一次，存在記憶體） */
object WatchExtras {
    data class DivBrief(val lastTime: Long?, val lastAmount: Double?, val nextEx: Long?)

    private var divDay = ""
    private var instDay = ""

    private fun today() = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).toString()

    suspend fun loadDividends(quotes: List<Quote>) {
        if (divDay != today()) { AppState.divBriefs.clear(); divDay = today() }
        for (q in quotes) {
            if (AppState.divBriefs.containsKey(q.symbol)) continue
            val b = withContext(Dispatchers.IO) {
                val sym = StockInfoRepo.yahooSymbol(q)
                val list = runCatching { YahooExtra.dividends(sym) }.getOrDefault(emptyList())
                val last = list.maxByOrNull { it.time }
                val next = runCatching { YahooExtra.events(sym).exDividend }.getOrNull()
                    ?.takeIf { it > System.currentTimeMillis() - 86_400_000L && (last == null || it > last.time + 86_400_000L) }
                DivBrief(last?.time, last?.amount, next)
            }
            AppState.divBriefs[q.symbol] = b
        }
    }

    suspend fun loadInstitutional() {
        if (instDay == today() && AppState.institutional.isNotEmpty()) return
        val m = withContext(Dispatchers.IO) { runCatching { TwInstitutional.latest() }.getOrDefault(emptyMap()) }
        if (m.isNotEmpty()) {
            instDay = today()
            val mine = AppState.quotes.filter { it.market == Market.TW }.mapNotNull { q -> m[q.symbol]?.let { q.symbol to it } }
            AppState.institutional.clear(); AppState.institutional.putAll(mine)
        }
    }
}
