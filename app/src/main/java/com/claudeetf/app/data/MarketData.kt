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
 * 台股：盤中（週一至五 08:30–13:35）富果即時優先，其餘用證交所 MIS 即時；
 *      盤後或即時來源失敗 → 證交所 OpenAPI 收盤資料（上櫃股票用櫃買中心）。
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
        !isTwIntraday() -> "證交所／櫃買收盤資料（盤後）"
        AppState.fugleKey.isBlank() -> "證交所即時（約每 5 秒；填富果金鑰可與交易所同步）"
        com.claudeetf.app.data.remote.FugleStream.connected -> "富果串流：與交易所同步"
        else -> "富果即時＋證交所即時（串流連線中）"
    }

    private fun clock(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atZone(TPE).format(HHMM)

    private fun clockSec(ms: Long): String =
        java.time.Instant.ofEpochMilli(ms).atZone(TPE).format(DateTimeFormatter.ofPattern("HH:mm:ss"))

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

    // 富果免費方案約每分鐘 60 次：即時輪詢每 5 秒最多送 4 檔，其餘由證交所 MIS 補齊
    private const val FUGLE_PER_TICK = 4
    private var fugleCursor = 0

    /**
     * 台股：盤中（08:30–13:35）富果優先、其餘或失敗改用證交所 MIS 即時；盤後用證交所／櫃買收盤資料。
     * live = true 時為即時輪詢（不寫入磁碟、富果只輪流抓幾檔）。
     */
    suspend fun refreshTw(live: Boolean = false) {
        val symbols = AppState.quotes.filter { it.market == Market.TW }.map { it.symbol }
        if (symbols.isEmpty()) return
        val now = System.currentTimeMillis()
        val intraday = isTwIntraday()
        val fugle = HashSet<String>()
        val mis = HashSet<String>()
        var fugleError: String? = null
        var misError: String? = null

        if (intraday) {
            // 0) 富果串流已同步的代號：由推播即時更新，不必輪詢
            symbols.filter { com.claudeetf.app.data.remote.FugleStream.isStreaming(it) }.forEach { fugle += it }
            // 1) 富果優先（串流未涵蓋的代號才用 REST）
            val pending = symbols.filter { it !in fugle }
            if (AppState.fugleKey.isNotBlank() && pending.isNotEmpty() && !com.claudeetf.app.data.remote.FugleStream.connected) {
                val key = AppState.fugleKey
                val symbols = pending
                val batch = if (live) {
                    val n = minOf(FUGLE_PER_TICK, symbols.size)
                    (0 until n).map { symbols[(fugleCursor + it) % symbols.size] }.also { fugleCursor = (fugleCursor + n) % symbols.size }
                } else symbols.take(40)
                val results = withContext(Dispatchers.IO) {
                    batch.map { s ->
                        val r = runCatching { FugleApi.quote(s, key) }
                        if (!live) delay(120)
                        s to r
                    }
                }
                results.forEach { (s, r) ->
                    r.getOrNull()?.let { AppState.applyRemote(it, QuoteSource.FUGLE, now, live = true); fugle += s }
                }
                fugleError = results.firstOrNull { it.second.isFailure }?.second?.exceptionOrNull()?.friendly()
            }
            // 2) 其餘全部用證交所 MIS 即時（一次批次）
            val rest = symbols.filter { it !in fugle }
            if (rest.isNotEmpty()) {
                val res = withContext(Dispatchers.IO) { runCatching { com.claudeetf.app.data.remote.TwseMisApi.quotes(rest) } }
                res.getOrNull()?.forEach { (code, t) ->
                    if (code in rest) {
                        AppState.applyRemote(t.quote, QuoteSource.TWSE_LIVE, maxOf(t.time, now - 60_000), live = true)
                        if (t.hasTrade && t.tradeLots != null) {
                            val side = when {
                                t.ask != null && t.quote.price >= t.ask -> 1
                                t.bid != null && t.quote.price <= t.bid -> -1
                                else -> 0
                            }
                            AppState.recordTape(code, com.claudeetf.app.data.TapeTick(t.time, t.quote.price, t.tradeLots, side))
                        }
                        mis += code
                    }
                }
                misError = res.exceptionOrNull()?.friendly()
            }
            // 加權、櫃買指數即時
            withContext(Dispatchers.IO) { runCatching { com.claudeetf.app.data.remote.TwseMisApi.fetch(listOf("tse_t00.tw", "otc_o00.tw")) } }
                .getOrNull()?.forEach { (code, t) ->
                    val sym = if (code == "t00") "^TWII" else if (code == "o00") "^TWOII" else null
                    sym?.let { AppState.applyIndex(it, t.quote.price, t.quote.prevClose, now) }
                }
        }

        // 3) 盤後或即時來源都失敗：收盤資料
        val rest = symbols.filter { it !in fugle && it !in mis }
        var closeCount = 0
        var closeError: String? = null
        if (rest.isNotEmpty() && (!live || !intraday)) {
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
                append(clockSec(now)).append(" ")
                val parts = listOfNotNull(
                    if (fugle.isNotEmpty()) "富果即時 ${fugle.size} 檔" else null,
                    if (mis.isNotEmpty()) "證交所即時 ${mis.size} 檔" else null,
                    if (closeCount > 0) "收盤資料 $closeCount 檔" else null,
                )
                append(parts.joinToString("，").ifEmpty { "無更新" })
                fugleError?.let { append("｜富果：").append(it) }
                misError?.let { append("｜即時：").append(it) }
                closeError?.let { append("｜").append(it) }
            },
        )
    }

    /** 全部盤後資料，需在 IO 執行緒呼叫 */
    fun closeAll(): Map<String, Pair<RemoteQuote, QuoteSource>> = runCatching { closeData() }.getOrDefault(emptyMap())

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

    /**
     * 美股：Yahoo 批次即時報價（一次請求全部自選）；批次失敗改逐檔走勢 API，
     * 個別失敗且有 Finnhub 金鑰時以 Finnhub 備援。live = true 時只做批次。
     */
    suspend fun refreshUs(live: Boolean = false) {
        val symbols = AppState.quotes.filter { it.market == Market.US }.map { it.symbol }
        if (symbols.isEmpty()) return
        val now = System.currentTimeMillis()
        val done = HashSet<String>()
        val batch = withContext(Dispatchers.IO) { runCatching { com.claudeetf.app.data.remote.YahooExtra.quotes(symbols) } }
        batch.getOrNull()?.forEach { q ->
            val sym = symbols.firstOrNull { it.equals(q.symbol, true) } ?: return@forEach
            AppState.applyRemote(
                RemoteQuote(sym, q.name, q.price, q.prevClose, q.open, q.high, q.low, q.volume),
                QuoteSource.YAHOO, if (q.time > 0) minOf(q.time, now) else now, live = true,
            )
            done += sym
            // 盤前（PRE）、盤後（POST、POSTPOST）價格另外保存
            val st = q.state.uppercase()
            val ext = when {
                st.startsWith("PRE") && q.prePrice != null -> AppState.ExtQuote("盤前", q.prePrice, q.preChangePct ?: 0.0, q.extTime)
                st.startsWith("POST") && q.postPrice != null -> AppState.ExtQuote("盤後", q.postPrice, q.postChangePct ?: 0.0, q.extTime)
                st == "CLOSED" && q.postPrice != null && q.postPrice != q.price -> AppState.ExtQuote("盤後", q.postPrice, q.postChangePct ?: 0.0, q.extTime)
                else -> null
            }
            if (ext != null) AppState.extended[sym] = ext else AppState.extended.remove(sym)
        }
        if (live && done.isNotEmpty()) {
            AppState.setStatus("US", clockSec(now) + " Yahoo 即時 ${done.size}/${symbols.size} 檔")
            return
        }

        val todo = symbols.filter { it !in done }
        val results = withContext(Dispatchers.IO) {
            todo.map { s ->
                val r = runCatching { YahooApi.chart(s) }
                delay(60)
                s to r
            }
        }
        // 走勢 API 也補上當日走勢圖
        results.forEach { (s, r) ->
            r.getOrNull()?.let { AppState.applyRemote(it.quote, QuoteSource.YAHOO, now, sparkOverride = it.spark); done += s }
        }
        if (!live) {
            withContext(Dispatchers.IO) {
                done.filter { it !in todo }.forEach { s ->
                    runCatching { YahooApi.chart(s) }.getOrNull()?.let { c ->
                        withContext(Dispatchers.Main) {
                            val q = AppState.quote(s) ?: return@withContext
                            if (c.spark.size >= 2) AppState.applyRemote(
                                RemoteQuote(s, null, q.price, q.prevClose, null, null, null), QuoteSource.YAHOO, q.updatedAt, sparkOverride = c.spark,
                            )
                        }
                    }
                }
            }
        }
        val failed = symbols.filter { it !in done }
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
            backup.forEach { r -> r.getOrNull()?.let { AppState.applyRemote(it, QuoteSource.FINNHUB, now, live = true); finnOk++ } }
        }
        val err = (batch.exceptionOrNull() ?: results.firstOrNull { it.second.isFailure }?.second?.exceptionOrNull())?.friendly()
        AppState.setStatus(
            "US",
            clockSec(now) + " Yahoo ${done.size}/${symbols.size} 檔" +
                (if (finnOk > 0) "，Finnhub 備援 $finnOk 檔" else "") +
                (if (err != null && done.size + finnOk < symbols.size) "｜$err" else ""),
        )
    }

    /** 任一市場在交易時段 */
    fun anyMarketLive(): Boolean = isTwIntraday() || usLive()

    fun usLive(): Boolean = com.claudeetf.app.util.UsSession.info().phase.let {
        it == com.claudeetf.app.util.UsSession.Phase.PRE || it == com.claudeetf.app.util.UsSession.Phase.REGULAR ||
            it == com.claudeetf.app.util.UsSession.Phase.POST
    }

    /** 即時輪詢：只更新正在交易的市場報價，不寫入磁碟 */
    suspend fun refreshLive() {
        coroutineScope {
            val tw = async { if (isTwIntraday()) { runCatching { refreshTw(live = true) } }; Unit }
            val us = async { if (usLive()) { runCatching { refreshUs(live = true) } }; Unit }
            tw.await(); us.await()
        }
        AppState.lastLiveAt = System.currentTimeMillis()
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
