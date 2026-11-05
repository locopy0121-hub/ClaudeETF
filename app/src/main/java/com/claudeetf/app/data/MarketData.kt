package com.claudeetf.app.data

import com.claudeetf.app.data.remote.BotFxApi
import com.claudeetf.app.data.remote.FinnhubApi
import com.claudeetf.app.data.remote.FugleApi
import com.claudeetf.app.data.remote.RemoteQuote
import com.claudeetf.app.data.remote.TpexApi
import com.claudeetf.app.data.remote.TwseApi
import com.claudeetf.app.data.remote.YahooApi
import com.claudeetf.app.data.remote.friendly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 行情核心：決定每個市場用哪個來源，抓資料後在主執行緒合併進 AppState。
 *
 * 台股：盤中（週一至五 08:30–13:35）且已填富果金鑰 → 富果即時；
 *      盤後、沒有金鑰、或富果失敗 → 證交所 OpenAPI（上櫃股票用櫃買中心）。
 * 美股與指數：Yahoo Finance（免金鑰）；個別失敗且有 Finnhub 金鑰時以 Finnhub 備援。
 * 匯率：台灣銀行即期買賣中價。
 */
object MarketData {
    private val TPE: ZoneId = ZoneId.of("Asia/Taipei")
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // 證交所／櫃買為盤後日資料，同一天內不重複下載
    private var closeCache: Map<String, Pair<RemoteQuote, QuoteSource>> = emptyMap()
    private var closeCacheKey = ""

    fun isTwIntraday(now: ZonedDateTime = ZonedDateTime.now(TPE)): Boolean {
        val t = now.withZoneSameInstant(TPE)
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = t.toLocalTime()
        return time >= LocalTime.of(8, 30) && time < LocalTime.of(13, 35)
    }

    fun twSourcePlan(): String = when {
        AppState.fugleKey.isBlank() -> "證交所 OpenAPI（尚未填富果金鑰）"
        isTwIntraday() -> "富果即時（盤中）"
        else -> "證交所 OpenAPI（盤後）"
    }

    private fun clock(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atZone(TPE).format(HHMM)

    /** 更新全部市場；同時只跑一次 */
    suspend fun refreshAll() {
        if (AppState.refreshing) return
        AppState.refreshing = true
        try {
            coroutineScope {
                val tw = async { refreshTw() }
                val us = async { refreshUs() }
                val fx = async { refreshFx() }
                val idx = async { refreshIndices() }
                tw.await(); us.await(); fx.await(); idx.await()
            }
            AppState.persist()
        } finally {
            AppState.refreshing = false
        }
    }

    suspend fun refreshTw() {
        val symbols = AppState.quotes.filter { it.market == Market.TW }.map { it.symbol }
        if (symbols.isEmpty()) return
        val now = System.currentTimeMillis()
        var done = emptySet<String>()
        var fugleError: String? = null

        // 1) 盤中且有金鑰：富果優先
        if (AppState.fugleKey.isNotBlank() && isTwIntraday()) {
            val key = AppState.fugleKey
            val results = withContext(Dispatchers.IO) {
                symbols.map { s ->
                    val r = runCatching { FugleApi.quote(s, key) }
                    delay(120) // 保持在免費方案的頻率限制內
                    s to r
                }
            }
            results.forEach { (_, r) -> r.getOrNull()?.let { AppState.applyRemote(it, QuoteSource.FUGLE, now) } }
            done = results.filter { it.second.isSuccess }.map { it.first }.toSet()
            fugleError = results.firstOrNull { it.second.isFailure }?.second?.exceptionOrNull()?.friendly()
        }

        // 2) 其餘（盤後、無金鑰、富果失敗）：證交所／櫃買收盤資料
        val rest = symbols.filter { it !in done }
        var closeError: String? = null
        var closeCount = 0
        if (rest.isNotEmpty()) {
            val result = withContext(Dispatchers.IO) { runCatching { closeData() } }
            val data = result.getOrNull()
            if (data != null) {
                rest.forEach { s -> data[s]?.let { (rq, src) -> AppState.applyRemote(rq, src, now) } }
                closeCount = rest.count { data[it] != null }
                val missing = rest.filter { data[it] == null }
                if (missing.isNotEmpty()) closeError = "查無 " + missing.joinToString("、")
            } else {
                closeError = result.exceptionOrNull()?.friendly()
            }
        }

        AppState.setStatus(
            "TW",
            buildString {
                append(clock(now)).append(" ")
                append(if (done.isNotEmpty()) "富果即時 ${done.size} 檔" else "")
                if (rest.isNotEmpty()) {
                    if (done.isNotEmpty()) append("，")
                    append("證交所收盤 $closeCount 檔")
                }
                fugleError?.let { append("｜富果：").append(it) }
                closeError?.let { append("｜").append(it) }
            },
        )
    }

    /** 盤後資料中的單一證券（含成交量），需在 IO 執行緒呼叫 */
    fun closeRecord(symbol: String): Pair<RemoteQuote, QuoteSource>? = runCatching { closeData()[symbol] }.getOrNull()

    @Synchronized
    private fun closeData(): Map<String, Pair<RemoteQuote, QuoteSource>> {
        val key = ZonedDateTime.now(TPE).toLocalDate().toString() + "-" + (ZonedDateTime.now(TPE).hour >= 14)
        if (key == closeCacheKey && closeCache.isNotEmpty()) return closeCache
        val twse = TwseApi.dayAll().mapValues { it.value to QuoteSource.TWSE }
        val tpex = runCatching { TpexApi.dayAll().mapValues { it.value to QuoteSource.TPEX } }.getOrDefault(emptyMap())
        closeCache = tpex + twse
        closeCacheKey = key
        return closeCache
    }

    /** 美股：Yahoo Finance 為主（免金鑰）；個別失敗且有 Finnhub 金鑰時改用 Finnhub */
    suspend fun refreshUs() {
        val symbols = AppState.quotes.filter { it.market == Market.US }.map { it.symbol }
        if (symbols.isEmpty()) return
        val now = System.currentTimeMillis()
        val results = withContext(Dispatchers.IO) {
            symbols.map { s ->
                val r = runCatching { YahooApi.chart(s) }
                delay(60)
                s to r
            }
        }
        var yahooOk = 0
        results.forEach { (_, r) ->
            r.getOrNull()?.let {
                AppState.applyRemote(it.quote, QuoteSource.YAHOO, now, sparkOverride = it.spark)
                yahooOk++
            }
        }
        val failed = results.filter { it.second.isFailure }.map { it.first }
        val yahooErr = results.firstOrNull { it.second.isFailure }?.second?.exceptionOrNull()?.friendly()

        var finnOk = 0
        val key = AppState.finnhubKey
        if (failed.isNotEmpty() && key.isNotBlank()) {
            val backup = withContext(Dispatchers.IO) {
                failed.map { s ->
                    val r = runCatching { FinnhubApi.quote(s, key) }
                    delay(80)
                    r
                }
            }
            backup.forEach { r -> r.getOrNull()?.let { AppState.applyRemote(it, QuoteSource.FINNHUB, now); finnOk++ } }
        }
        AppState.setStatus(
            "US",
            clock(now) + " Yahoo $yahooOk/${symbols.size} 檔" +
                (if (finnOk > 0) "，Finnhub 備援 $finnOk 檔" else "") +
                (if (yahooErr != null && yahooOk + finnOk < symbols.size) "｜$yahooErr" else ""),
        )
    }

    /** 指數、10 年債、美元指數：Yahoo Finance */
    suspend fun refreshIndices() {
        val symbols = AppState.indexSymbols()
        val now = System.currentTimeMillis()
        val results = withContext(Dispatchers.IO) {
            symbols.map { s ->
                val r = runCatching { YahooApi.chart(s) }
                delay(60)
                s to r
            }
        }
        results.forEach { (s, r) -> r.getOrNull()?.let { AppState.applyIndex(s, it.quote.price, it.quote.prevClose, now) } }
        val ok = results.count { it.second.isSuccess }
        AppState.setStatus("IDX", clock(now) + " 指數 $ok/${symbols.size}")
    }

    suspend fun refreshFx() {
        val now = System.currentTimeMillis()
        withContext(Dispatchers.IO) { runCatching { BotFxApi.usd() } }
            .onSuccess {
                AppState.setFx(it.mid, "台灣銀行即期", now)
                AppState.setStatus("FX", clock(now) + " 台銀即期 買 ${it.spotBuy} / 賣 ${it.spotSell}")
            }
            .onFailure { AppState.setStatus("FX", "台銀匯率失敗：" + it.friendly()) }
    }

    /** 新增自選：先查名稱再抓一次報價 */
    suspend fun addAndFetch(symbol: String): String? {
        val s = symbol.trim().uppercase()
        if (s.isEmpty()) return "請輸入代號"
        if (AppState.isWatched(s)) return "$s 已在自選清單"
        val isTw = s.first().isDigit()
        var name: String? = null
        if (!isTw) {
            name = withContext(Dispatchers.IO) { runCatching { YahooApi.chart(s).quote.name }.getOrNull() }
            if (name == null && AppState.finnhubKey.isNotBlank()) {
                val key = AppState.finnhubKey
                name = withContext(Dispatchers.IO) { FinnhubApi.name(s, key) }
            }
        }
        AppState.addWatch(s, name)
        if (isTw) refreshTw() else refreshUs()
        AppState.persist()
        return null
    }

    /** 設定頁的「測試連線」 */
    suspend fun testFugle(key: String): String = withContext(Dispatchers.IO) {
        runCatching { FugleApi.quote("2330", key.trim()) }
            .fold({ "連線成功：${it.name ?: "2330"} ${it.price}" }, { "失敗：" + it.friendly() })
    }

    suspend fun testFinnhub(key: String): String = withContext(Dispatchers.IO) {
        runCatching { FinnhubApi.quote("AAPL", key.trim()) }
            .fold({ "連線成功：AAPL ${it.price}" }, { "失敗：" + it.friendly() })
    }

    suspend fun testYahoo(): String = withContext(Dispatchers.IO) {
        runCatching { YahooApi.chart("AAPL") }
            .fold({ "連線成功：AAPL ${it.quote.price}" }, { "失敗：" + it.friendly() })
    }
}
