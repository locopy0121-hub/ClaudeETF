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

    /** 交易日 13:30–14:30：已收盤但證交所／櫃買日資料尚未更新成今天 */
    fun isTwTodayPending(now: ZonedDateTime = ZonedDateTime.now(TPE)): Boolean {
        val t = now.withZoneSameInstant(TPE)
        if (t.dayOfWeek == DayOfWeek.SATURDAY || t.dayOfWeek == DayOfWeek.SUNDAY) return false
        val time = t.toLocalTime()
        return time >= LocalTime.of(13, 30) && time < LocalTime.of(14, 30)
    }

    /** 收盤資料套用時記下的交易日 */
    private val closeDay = java.util.concurrent.ConcurrentHashMap<String, java.time.LocalDate>()

    /** 最近一個（已開盤的）交易日：平日 9:00 後為今天，否則往前找平日（不含國定假日） */
    fun lastTradingDay(now: ZonedDateTime = ZonedDateTime.now(TPE)): java.time.LocalDate {
        var d = now.toLocalDate()
        if (now.toLocalTime() < LocalTime.of(9, 0)) d = d.minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }

    /** 目前報價屬於哪個交易日：即時來源看成交時間，收盤資料看資料日期；不確定時回傳 null */
    fun quoteDay(symbol: String): java.time.LocalDate? {
        val q = AppState.quote(symbol) ?: return null
        if (q.price <= 0 || q.updatedAt <= 0) return null
        val live = q.source == QuoteSource.FUGLE || q.source == QuoteSource.FUGLE_STREAM || q.source == QuoteSource.TWSE_LIVE
        // 週末或盤前抓到的即時報價，時間是「現在」但資料屬於最後交易日：換算回當時的最後交易日
        return if (live) lastTradingDay(java.time.Instant.ofEpochMilli(q.updatedAt).atZone(TPE)) else closeDay[symbol]
    }

    /** 這檔今天已經有即時來源（富果、證交所即時）的價格 */
    fun hasTodayLive(symbol: String): Boolean {
        val q = AppState.quote(symbol) ?: return false
        if (q.price <= 0 || q.updatedAt <= 0) return false
        val live = q.source == QuoteSource.FUGLE || q.source == QuoteSource.FUGLE_STREAM || q.source == QuoteSource.TWSE_LIVE
        val day = java.time.Instant.ofEpochMilli(q.updatedAt).atZone(TPE).toLocalDate()
        return live && day == ZonedDateTime.now(TPE).toLocalDate()
    }

    fun twSourcePlan(): String = when {
        isTwTodayPending() -> "已收盤：沿用今日最後成交價，14:30 後改用證交所／櫃買收盤資料"
        !isTwIntraday() -> "證交所／櫃買收盤資料（盤後）"
        !AppState.twUseFugle -> "盤中以證交所即時為主（約每 5 秒）" + if (AppState.fugleKey.isNotBlank()) "，失敗時改用富果" else ""
        AppState.fugleKey.isBlank() -> "尚未填富果金鑰，暫用證交所即時（填入後盤中僅用富果）"
        com.claudeetf.app.data.remote.FugleStream.connected -> "盤中僅用富果：串流與交易所同步"
        else -> "盤中僅用富果：串流連線中，暫以富果 REST 輪流更新"
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
    private var lastFugleIndexAt = 0L

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
            val key = AppState.fugleKey
            val fugleOnly = AppState.twUseFugle && key.isNotBlank()

            // 富果 REST：串流未涵蓋（尚未推播、超出訂閱上限、串流斷線）的代號輪流更新
            suspend fun fugleRest(pending: List<String>) {
                if (key.isBlank() || pending.isEmpty()) return
                val batch = if (live) {
                    val n = minOf(FUGLE_PER_TICK, pending.size)
                    (0 until n).map { pending[(fugleCursor + it) % pending.size] }.also { fugleCursor = (fugleCursor + n) % pending.size }
                } else pending.take(40)
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

            // 證交所 MIS 即時（一次批次）
            suspend fun misBatch(list: List<String>) {
                if (list.isEmpty()) return
                val res = withContext(Dispatchers.IO) { runCatching { com.claudeetf.app.data.remote.TwseMisApi.quotes(list) } }
                res.getOrNull()?.forEach { (code, t) ->
                    if (code in list) {
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

            if (fugleOnly) {
                // 盤中一律僅用富果：串流同步的代號由推播更新，其餘用富果 REST，不混用證交所
                symbols.filter { com.claudeetf.app.data.remote.FugleStream.isStreaming(it) }.forEach { fugle += it }
                fugleRest(symbols.filter { it !in fugle })
                // 輪詢中尚未輪到的代號維持富果上一筆資料，不以證交所覆蓋
                symbols.filter { AppState.quote(it)?.source.let { src -> src == QuoteSource.FUGLE || src == QuoteSource.FUGLE_STREAM } }.forEach { fugle += it }
                // 指數也用富果（加權 IX0001、櫃買 IX0043），每 30 秒一次
                if (!live || now - lastFugleIndexAt > 30_000) {
                    lastFugleIndexAt = now
                    withContext(Dispatchers.IO) {
                        listOf("IX0001" to "^TWII", "IX0043" to "^TWOII").mapNotNull { (code, sym) ->
                            runCatching { FugleApi.quote(code, key) }.getOrNull()?.let { sym to it }
                        }
                    }.forEach { (sym, q) -> AppState.applyIndex(sym, q.price, q.prevClose, now) }
                }
            } else {
                // 證交所為主（或尚未填富果金鑰）
                misBatch(symbols)
                if (!AppState.twUseFugle || key.isBlank()) {
                    // 證交所沒有回應的代號，有金鑰時改用富果補
                    fugleRest(symbols.filter { it !in mis })
                }
                withContext(Dispatchers.IO) { runCatching { com.claudeetf.app.data.remote.TwseMisApi.fetch(listOf("tse_t00.tw", "otc_o00.tw")) } }
                    .getOrNull()?.forEach { (code, t) ->
                        val sym = if (code == "t00") "^TWII" else if (code == "o00") "^TWOII" else null
                        sym?.let { AppState.applyIndex(it, t.quote.price, t.quote.prevClose, now) }
                    }
            }
        }

        // 2b) 交易日收盤後到當晚：證交所日資料可能還沒更新，改抓今天的收盤價
        //     有富果金鑰用富果 REST，否則用證交所 MIS（收盤後仍保留今日資料到隔天早上）
        //     週末、開盤前也一樣：報價還不是「最近一個交易日」的，就補抓（MIS／富果在休市時保留最後交易日資料）
        val lastDay = lastTradingDay()
        if (!intraday && !live) {
            val need = symbols.filter { s -> quoteDay(s)?.let { it < lastDay } ?: true }
            if (need.isNotEmpty()) {
                val key = AppState.fugleKey
                if (key.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        need.take(40).map { s -> delay(120); s to runCatching { FugleApi.quote(s, key) } }
                    }.forEach { (s, r) -> r.getOrNull()?.let { AppState.applyRemote(it, QuoteSource.FUGLE, now, live = true); fugle += s } }
                }
                val still = need.filter { it !in fugle }
                if (still.isNotEmpty()) {
                    withContext(Dispatchers.IO) { runCatching { com.claudeetf.app.data.remote.TwseMisApi.quotes(still) } }.getOrNull()
                        ?.forEach { (code, t) -> if (code in still && t.quote.price > 0) { AppState.applyRemote(t.quote, QuoteSource.TWSE_LIVE, if (t.time > 0) minOf(t.time, now) else now - 60_000, live = true); mis += code } }
                }
            }
        }

        // 3) 盤後或即時來源都失敗：收盤資料
        //    13:30 收盤到證交所發布日資料（約 14:30）之間，OpenAPI 還是前一個交易日的資料，
        //    直接套用會把今天的最後成交價換成昨天的收盤價（總市值、漲跌都會跳掉），
        //    所以這段時間只補「今天還沒有即時價」的股票。
        // 證交所／櫃買 OpenAPI 日資料常到晚上甚至隔天才更新：今天已有即時價（收盤後即今日收盤價）就一律不覆蓋
        val rest = symbols.filter { it !in fugle && it !in mis }
            .filter { !hasTodayLive(it) }
        var closeCount = 0
        var closeError: String? = null
        if (rest.isNotEmpty() && (!live || !intraday)) {
            val result = withContext(Dispatchers.IO) { runCatching { closeData() } }
            val data = result.getOrNull()
            if (data != null) {
                // 收盤資料比目前報價舊（例如週六早上證交所還是週四資料，但已有週五即時價）就不覆蓋
                val applied = rest.filter { s ->
                    val (rq, src) = data[s] ?: return@filter false
                    val cur = quoteDay(s)
                    // 有日期：比目前報價舊就略過；沒日期：目前報價已是最近交易日的就不可能更新，也略過
                    if (cur != null && (if (rq.date != null) rq.date < cur else cur >= lastDay)) {
                        // 價格不覆蓋，但當日成交量／高低若還沒有（例如重開 App 後），且是同一交易日的資料就補上
                        // 同一交易日：日期相同，或沒有日期但收盤價與昨收都和目前報價一致
                        val q0 = AppState.quote(s)
                        val same = (rq.date != null && rq.date == cur) ||
                            (rq.date == null && q0 != null && kotlin.math.abs(rq.price - q0.price) < 1e-6 && kotlin.math.abs(rq.prevClose - q0.prevClose) < 1e-6)
                        val ds = AppState.dayStats[s]
                        if (same && (ds?.volume == null || ds.high == null)) AppState.dayStats[s] =
                            AppState.DayStat(ds?.high ?: rq.high, ds?.low ?: rq.low, ds?.volume ?: rq.volume, now)
                        return@filter false
                    }
                    AppState.applyRemote(rq, src, now)
                    rq.date?.let { closeDay[s] = it }
                    true
                }
                closeCount = applied.size
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
        val nowT = ZonedDateTime.now(TPE)
        val key = nowT.toLocalDate().toString() + "-" + (nowT.toLocalTime() >= LocalTime.of(14, 30))
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
    /** light = true：高頻輪詢用，批次報價失敗時不逐檔補抓（避免 1 秒一輪打爆 API）；回傳批次是否成功 */
    suspend fun refreshUs(live: Boolean = false, light: Boolean = false): Boolean {
        val symbols = AppState.quotes.filter { it.market == Market.US }.map { it.symbol }
        if (symbols.isEmpty()) return true
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
            AppState.lastLiveAt = now
            return true
        }
        if (light) return false

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
        return done.isNotEmpty() || finnOk > 0
    }

    /** 任一市場在交易時段 */
    fun anyMarketLive(): Boolean = isTwIntraday() || usLive()

    fun usLive(): Boolean = com.claudeetf.app.util.UsSession.info().phase.let {
        it == com.claudeetf.app.util.UsSession.Phase.PRE || it == com.claudeetf.app.util.UsSession.Phase.REGULAR ||
            it == com.claudeetf.app.util.UsSession.Phase.POST
    }

    /** 即時輪詢：只更新正在交易的市場報價，不寫入磁碟 */
    /** 盤中即時更新；美股由獨立迴圈依「美股更新頻率」處理（includeUs = false） */
    suspend fun refreshLive(includeUs: Boolean = false) {
        coroutineScope {
            val tw = async { if (isTwIntraday()) { runCatching { refreshTw(live = true) } }; Unit }
            val us = async { if (includeUs && usLive()) { runCatching { refreshUs(live = true) } }; Unit }
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
