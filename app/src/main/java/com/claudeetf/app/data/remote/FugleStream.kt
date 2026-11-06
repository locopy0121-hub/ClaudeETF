package com.claudeetf.app.data.remote

import android.os.Handler
import android.os.Looper
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.QuoteSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 富果 WebSocket 即時串流（台股盤中與交易所同步）：
 * wss://api.fugle.tw/marketdata/v1.0/stock/streaming → auth → subscribe aggregates。
 * 每有成交或委買賣變動，伺服器即推送最新報價，App 立即更新畫面。
 */
object FugleStream {
    private const val URL = "wss://api.fugle.tw/marketdata/v1.0/stock/streaming"
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy {
        OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var authed = false
    @Volatile private var wanted = false
    @Volatile private var key = ""
    private val subscribed = HashSet<String>()
    private val rejected = HashSet<String>()
    /** 代號 → 最近一次收到推播的時間 */
    private val lastData = HashMap<String, Long>()
    private var retry = 0

    /** 五檔（代號 → 買、賣）與當日統計，個股頁直接觀察（只在主執行緒寫入） */
    val books = androidx.compose.runtime.mutableStateMapOf<String, Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>>>()

    data class Totals(val volumeLots: Double?, val value: Double?, val transactions: Double?, val atBid: Double?, val atAsk: Double?, val avg: Double?)
    val totals = androidx.compose.runtime.mutableStateMapOf<String, Totals>()

    // ---- 連線統計 ----
    @Volatile var messages = 0L
    @Volatile var bytes = 0L
    @Volatile var reconnects = 0
    @Volatile var connectedAt = 0L

    fun stats(): String {
        if (!authed) return if (reconnects > 0) "未連線 · 已重連 $reconnects 次" else "未連線"
        val mins = (System.currentTimeMillis() - connectedAt) / 60_000
        val kb = bytes / 1024.0
        return "已連線 $mins 分鐘 · 推播 $messages 筆 · " +
            (if (kb >= 1024) String.format("%.1f MB", kb / 1024) else String.format("%.0f KB", kb)) +
            " · 訂閱 ${synchronized(subscribed) { subscribed.size }} 檔" + (if (rejected.isNotEmpty()) "（${rejected.size} 檔超出上限）" else "") +
            (if (reconnects > 0) " · 重連 $reconnects 次" else "")
    }

    fun isStreaming(symbol: String): Boolean {
        // 尚未收到第一筆推播前，仍由證交所即時補上
        val t = synchronized(lastData) { lastData[symbol] } ?: return false
        return authed && System.currentTimeMillis() - t < 10 * 60_000
    }

    val connected: Boolean get() = authed

    /** 各代號串流狀態：(代號, 狀態文字, 是否同步中) */
    fun symbolStates(symbols: List<String>): List<Triple<String, String, Boolean>> {
        val now = System.currentTimeMillis()
        val tpe = java.time.ZoneId.of("Asia/Taipei")
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
        return synchronized(subscribed) {
            symbols.map { s ->
                val last = synchronized(lastData) { lastData[s] }
                when {
                    !authed -> Triple(s, "串流未連線", false)
                    s in subscribed && last != null && now - last < 10 * 60_000 ->
                        Triple(s, "● 同步中 · 最後推播 " + java.time.Instant.ofEpochMilli(last).atZone(tpe).format(fmt), true)
                    s in subscribed -> Triple(s, "已訂閱 · 等待第一筆推播", true)
                    s == awaiting -> Triple(s, "訂閱中…", false)
                    s in rejected -> Triple(s, "超出方案上限 · 以富果 REST 輪流更新", false)
                    s in queue -> Triple(s, "排隊訂閱", false)
                    else -> Triple(s, "未訂閱", false)
                }
            }
        }
    }

    /** 依目前狀態開始、更新訂閱或停止串流 */
    fun ensure(enabled: Boolean, apiKey: String, symbols: List<String>) {
        if (!enabled || apiKey.isBlank()) { stop(); return }
        wanted = true
        if (ws == null || key != apiKey) {
            stop(); wanted = true
            key = apiKey
            connect()
            return
        }
        if (authed) {
            synchronized(subscribed) {
                // 等待回應超過 10 秒：視為已訂閱，繼續下一檔
                if (awaiting != null && System.currentTimeMillis() - awaitingAt > 10_000) { subscribed += awaiting!!; awaiting = null }
                val add = prioritize(symbols).filter { it !in subscribed && it !in rejected && it !in queue && it != awaiting }
                queue.addAll(add)
            }
            focusSwap()
            pump()
            ensureTrades()
        }
    }

    // ---- 逐檔訂閱（依優先順序：正在看的個股 → 持股 → 自選），超過上限時保留最重要的 ----
    private val queue = ArrayDeque<String>()
    @Volatile private var awaiting: String? = null
    @Volatile private var awaitingAt = 0L
    @Volatile private var limitHit = false
    private val subIds = HashMap<String, String>()

    /** 個股頁正在看的代號：一定要在串流內 */
    @Volatile var focus: String = ""
        private set

    fun setFocus(symbol: String) {
        focus = symbol
        if (authed && symbol.isNotBlank()) {
            synchronized(subscribed) {
                if (symbol !in subscribed && symbol != awaiting) { queue.remove(symbol); queue.addFirst(symbol); rejected.remove(symbol) }
            }
            focusSwap(); pump()
        }
        ensureTrades()
    }

    private fun prioritize(symbols: List<String>): List<String> {
        val held = AppState.holdings.map { it.symbol }.toSet()
        return symbols.sortedBy { s -> if (s == focus) 0 else if (s in held) 1 else 2 }
    }

    /** 已達訂閱上限而正在看的個股不在串流內：退訂一檔非持股、非焦點的代號換進來 */
    private fun focusSwap() {
        val f = focus
        if (f.isBlank() || !limitHit) return
        val w = ws ?: return
        synchronized(subscribed) {
            if (f in subscribed || f == awaiting) return
            val held = AppState.holdings.map { it.symbol }.toSet()
            val victim = subscribed.filter { it != f && it !in held }.lastOrNull() ?: subscribed.lastOrNull { it != f } ?: return
            subIds[victim]?.let { id -> w.send(JSONObject().put("event", "unsubscribe").put("data", JSONObject().put("id", id)).toString()) }
                ?: w.send(JSONObject().put("event", "unsubscribe").put("data", JSONObject().put("channel", "aggregates").put("symbol", victim)).toString())
            subscribed -= victim; subIds.remove(victim); rejected += victim
            rejected.remove(f); queue.remove(f)
            awaiting = f; awaitingAt = System.currentTimeMillis()
            w.send(JSONObject().put("event", "subscribe").put("data", JSONObject().put("channel", "aggregates").put("symbol", f)).toString())
        }
    }

    /** 一次只送一檔，收到 subscribed／error 後再送下一檔，才能精準知道哪一檔被拒 */
    private fun pump() {
        val w = ws ?: return
        synchronized(subscribed) {
            if (awaiting != null) return
            if (limitHit) { rejected.addAll(queue); queue.clear(); return }
            val s = queue.removeFirstOrNull() ?: return
            awaiting = s; awaitingAt = System.currentTimeMillis()
            w.send(JSONObject().put("event", "subscribe").put("data", JSONObject().put("channel", "aggregates").put("symbol", s)).toString())
        }
    }

    private fun resetSubs() {
        synchronized(subscribed) {
            subscribed.clear(); queue.clear(); subIds.clear(); rejected.clear()
            awaiting = null; limitHit = false
            tradesSymbol = ""; tradesId = null; tradesPending = false; tradesFailed = ""
        }
    }

    // ---- 正在看的個股另訂 trades 頻道：每一筆成交都推播，逐筆明細不漏筆 ----
    @Volatile private var tradesSymbol = ""
    @Volatile private var tradesId: String? = null
    @Volatile private var tradesPending = false
    /** 訂閱 trades 失敗的代號（方案不支援或超過上限），不再重試 */
    @Volatile private var tradesFailed = ""

    /** 目前是否以 trades 頻道逐筆同步 */
    fun tradesLive(symbol: String): Boolean = authed && tradesSymbol == symbol && tradesId != null

    private fun ensureTrades() {
        val w = ws ?: return
        if (!authed) return
        val f = focus
        synchronized(subscribed) {
            if (awaiting != null || tradesPending) return
            if (tradesSymbol.isNotEmpty() && tradesSymbol != f) {
                tradesId?.let { id -> w.send(JSONObject().put("event", "unsubscribe").put("data", JSONObject().put("id", id)).toString()) }
                tradesSymbol = ""; tradesId = null
            }
            if (f.isBlank() || f == tradesSymbol || f == tradesFailed) return
            tradesSymbol = f; tradesPending = true
            w.send(JSONObject().put("event", "subscribe").put("data", JSONObject().put("channel", "trades").put("symbol", f)).toString())
        }
    }

    fun stop() {
        wanted = false
        authed = false
        ws?.close(1000, "bye")
        ws = null
        resetSubs()
        main.post { AppState.streamStatus = "" }
    }

    private fun connect() {
        val req = Request.Builder().url(URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("event", "auth").put("data", JSONObject().put("apikey", key)).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                messages++
                bytes += text.length
                runCatching { handle(webSocket, JSONObject(text)) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onClosedOrFailed(webSocket, "連線中斷：" + (t.message ?: t.javaClass.simpleName))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onClosedOrFailed(webSocket, "連線關閉")
            }
        })
        main.post { AppState.streamStatus = "富果串流連線中…" }
    }

    private fun onClosedOrFailed(webSocket: WebSocket, msg: String) {
        if (webSocket !== ws) return
        authed = false
        ws = null
        resetSubs()
        main.post { AppState.streamStatus = msg }
        if (wanted) {
            // 斷線重連：2、4、8…最多 60 秒
            val delayMs = minOf(60_000L, 2_000L shl minOf(retry, 5))
            retry++
            reconnects++
            main.postDelayed({ if (wanted && ws == null) connect() }, delayMs)
        }
    }

    private fun handle(webSocket: WebSocket, o: JSONObject) {
        when (o.optString("event")) {
            "authenticated" -> {
                authed = true
                retry = 0
                connectedAt = System.currentTimeMillis()
                val syms = AppState.quotes.filter { it.market == com.claudeetf.app.data.Market.TW }.map { it.symbol }
                main.post { AppState.streamStatus = "富果串流已連線" }
                synchronized(subscribed) { queue.addAll(prioritize(syms)) }
                pump()
            }
            "subscribed" -> {
                val d = o.optJSONObject("data")
                if (d?.optString("channel") == "trades" || (tradesPending && d?.optString("channel").isNullOrBlank() && awaiting == null)) {
                    synchronized(subscribed) { tradesPending = false; tradesId = d?.optString("id")?.ifBlank { null } ?: "?" }
                    return
                }
                val sym = d?.optString("symbol")?.ifBlank { null } ?: awaiting
                synchronized(subscribed) {
                    if (sym != null) {
                        subscribed += sym
                        d?.optString("id")?.ifBlank { null }?.let { subIds[sym] = it }
                    }
                    if (sym == awaiting || sym == null) awaiting = null
                }
                pump()
            }
            "unsubscribed" -> {}
            "error" -> {
                val msg = o.optJSONObject("data")?.optString("message") ?: "錯誤"
                if (msg.contains("auth", true) || msg.contains("credential", true)) {
                    wanted = false
                    main.post { AppState.streamStatus = "富果金鑰驗證失敗，改用證交所即時" }
                    webSocket.close(1000, "auth failed")
                } else if (tradesPending) {
                    // trades 頻道訂閱失敗：維持 aggregates 的最新成交，不影響報價同步
                    synchronized(subscribed) { tradesPending = false; tradesFailed = tradesSymbol; tradesSymbol = ""; tradesId = null }
                } else {
                    // 被拒的就是剛送出的那一檔；訊息提到上限時，其餘排隊的也不再送
                    val limit = listOf("limit", "exceed", "maximum", "上限", "超過").any { msg.contains(it, true) }
                    synchronized(subscribed) {
                        awaiting?.let { rejected += it }
                        awaiting = null
                        if (limit) limitHit = true
                    }
                    main.post {
                        AppState.streamStatus = "富果：$msg" + if (limit) "（已優先訂閱正在看的個股與持股，其餘以富果 REST 補）" else ""
                    }
                    focusSwap()
                    pump()
                }
            }
            "data" -> {
                if (o.optString("channel") == "trades") {
                    val d = o.optJSONObject("data") ?: return
                    val sym = d.optString("symbol").ifBlank { return }
                    fun n(k: String): Double? = d.optDouble(k, Double.NaN).takeIf { !it.isNaN() }
                    val p = n("price") ?: return
                    val size = n("size") ?: return
                    val tm = d.optLong("time", 0L).let { if (it > 100_000_000_000_000L) it / 1000 else it }.takeIf { it > 0 } ?: System.currentTimeMillis()
                    val bid = n("bid")?.takeIf { it > 0 }
                    val ask = n("ask")?.takeIf { it > 0 }
                    val side = when { ask != null && p >= ask -> 1; bid != null && p <= bid -> -1; else -> 0 }
                    main.post { AppState.recordTape(sym, com.claudeetf.app.data.TapeTick(tm, p, size, side)) }
                    return
                }
                if (o.optString("channel") != "aggregates") return
                val d = o.optJSONObject("data") ?: return
                val sym = d.optString("symbol").ifBlank { return }
                fun n(k: String): Double? = d.optDouble(k, Double.NaN).takeIf { !it.isNaN() }
                val prev = n("previousClose") ?: n("referencePrice") ?: return
                val price = n("lastPrice") ?: n("closePrice") ?: return
                val t = d.optJSONObject("total")
                val rq = RemoteQuote(
                    sym, d.optString("name").ifBlank { null }, price, prev,
                    n("openPrice"), n("highPrice"), n("lowPrice"),
                    t?.optDouble("tradeVolume", Double.NaN)?.takeIf { !it.isNaN() }?.times(1000),
                    t?.optDouble("tradeValue", Double.NaN)?.takeIf { !it.isNaN() },
                    t?.optDouble("transaction", Double.NaN)?.takeIf { !it.isNaN() },
                )
                fun levels(k: String) = d.optJSONArray(k)?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optDouble("price") to it.optDouble("size") } }
                } ?: emptyList()
                val book = levels("bids") to levels("asks")
                // lastUpdated 為微秒
                val updated = d.optLong("lastUpdated", 0L).let { if (it > 100_000_000_000_000L) it / 1000 else it }
                    .takeIf { it > 0 } ?: System.currentTimeMillis()
                synchronized(lastData) { lastData[sym] = System.currentTimeMillis() }
                val tot = Totals(
                    t?.optDouble("tradeVolume", Double.NaN)?.takeIf { !it.isNaN() },
                    t?.optDouble("tradeValue", Double.NaN)?.takeIf { !it.isNaN() },
                    t?.optDouble("transaction", Double.NaN)?.takeIf { !it.isNaN() },
                    t?.optDouble("tradeVolumeAtBid", Double.NaN)?.takeIf { !it.isNaN() },
                    t?.optDouble("tradeVolumeAtAsk", Double.NaN)?.takeIf { !it.isNaN() },
                    n("avgPrice"),
                )
                // 最新一筆成交 → 逐筆明細
                val tape = d.optJSONObject("lastTrade")?.let { lt ->
                    val p = lt.optDouble("price", Double.NaN).takeIf { !it.isNaN() } ?: return@let null
                    val size = lt.optDouble("size", Double.NaN).takeIf { !it.isNaN() } ?: return@let null
                    val tm = lt.optLong("time", 0L).let { if (it > 100_000_000_000_000L) it / 1000 else it }.takeIf { it > 0 } ?: updated
                    val bid = lt.optDouble("bid", Double.NaN).takeIf { !it.isNaN() && it > 0 }
                    val ask = lt.optDouble("ask", Double.NaN).takeIf { !it.isNaN() && it > 0 }
                    val side = when {
                        ask != null && p >= ask -> 1
                        bid != null && p <= bid -> -1
                        else -> 0
                    }
                    com.claudeetf.app.data.TapeTick(tm, p, size, side)
                }
                main.post {
                    if (!tradesLive(sym)) tape?.let { AppState.recordTape(sym, it) }
                    books[sym] = book
                    totals[sym] = tot
                    AppState.applyRemote(rq, QuoteSource.FUGLE_STREAM, minOf(updated, System.currentTimeMillis()), live = true)
                    AppState.lastLiveAt = System.currentTimeMillis()
                    AppState.streamStatus = "富果串流同步中（${synchronized(lastData) { lastData.size }} 檔）"
                }
            }
            else -> {}
        }
    }
}
