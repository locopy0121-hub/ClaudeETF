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

    /** 五檔（代號 → 買、賣），個股頁可直接使用 */
    val books = HashMap<String, Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>>>()

    fun isStreaming(symbol: String): Boolean {
        // 尚未收到第一筆推播前，仍由證交所即時補上
        val t = synchronized(lastData) { lastData[symbol] } ?: return false
        return authed && System.currentTimeMillis() - t < 10 * 60_000
    }

    val connected: Boolean get() = authed

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
        if (authed) subscribe(symbols.filter { it !in subscribed && it !in rejected })
    }

    fun stop() {
        wanted = false
        authed = false
        ws?.close(1000, "bye")
        ws = null
        synchronized(subscribed) { subscribed.clear() }
        main.post { AppState.streamStatus = "" }
    }

    private fun connect() {
        val req = Request.Builder().url(URL).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("event", "auth").put("data", JSONObject().put("apikey", key)).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
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
        synchronized(subscribed) { subscribed.clear() }
        main.post { AppState.streamStatus = msg }
        if (wanted) {
            // 斷線重連：2、4、8…最多 60 秒
            val delayMs = minOf(60_000L, 2_000L shl minOf(retry, 5))
            retry++
            main.postDelayed({ if (wanted && ws == null) connect() }, delayMs)
        }
    }

    private fun subscribe(symbols: List<String>) {
        val w = ws ?: return
        if (symbols.isEmpty()) return
        symbols.chunked(50).forEach { chunk ->
            w.send(
                JSONObject().put("event", "subscribe").put(
                    "data", JSONObject().put("channel", "aggregates").put("symbols", JSONArray(chunk)),
                ).toString()
            )
            synchronized(subscribed) { subscribed.addAll(chunk) }
        }
    }

    private fun handle(webSocket: WebSocket, o: JSONObject) {
        when (o.optString("event")) {
            "authenticated" -> {
                authed = true
                retry = 0
                val syms = AppState.quotes.filter { it.market == com.claudeetf.app.data.Market.TW }.map { it.symbol }
                main.post { AppState.streamStatus = "富果串流已連線" }
                subscribe(syms.filter { it !in rejected })
            }
            "error" -> {
                val msg = o.optJSONObject("data")?.optString("message") ?: "錯誤"
                if (msg.contains("auth", true) || msg.contains("credential", true)) {
                    wanted = false
                    main.post { AppState.streamStatus = "富果金鑰驗證失敗，改用證交所即時" }
                    webSocket.close(1000, "auth failed")
                } else {
                    // 多半是超過訂閱上限：記下尚未收到資料的代號，改由證交所即時補齊
                    synchronized(subscribed) {
                        subscribed.filter { s -> synchronized(lastData) { lastData[s] } == null }.forEach { rejected += it }
                    }
                    main.post { AppState.streamStatus = "富果：$msg（超出部分改用證交所即時）" }
                }
            }
            "data" -> {
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
                main.post {
                    books[sym] = book
                    AppState.applyRemote(rq, QuoteSource.FUGLE_STREAM, minOf(updated, System.currentTimeMillis()), live = true)
                    AppState.lastLiveAt = System.currentTimeMillis()
                    AppState.streamStatus = "富果串流同步中（${synchronized(lastData) { lastData.size }} 檔）"
                }
            }
            else -> {}
        }
    }
}
