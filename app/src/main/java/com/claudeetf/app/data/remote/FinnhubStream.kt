package com.claudeetf.app.data.remote

import android.os.Handler
import android.os.Looper
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.QuoteSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Finnhub WebSocket 美股逐筆成交串流（「即時」模式）。
 * 成交價先暫存，每 300 毫秒合併一次寫進報價，避免熱門股每秒數十筆成交讓畫面過度重繪。
 * 需要 Finnhub 金鑰；免費方案可訂閱約 50 檔。
 */
object FinnhubStream {
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ws: WebSocket? = null
    @Volatile private var key = ""
    @Volatile private var wanted = false
    @Volatile var connected = false
        private set
    private val subscribed = HashSet<String>()
    /** 待寫入的最新成交：代號 → (價格, 成交時間) */
    private val pending = ConcurrentHashMap<String, Pair<Double, Long>>()
    /** 每檔最後收到成交的時間 */
    private val lastTrade = ConcurrentHashMap<String, Long>()
    @Volatile private var retries = 0

    /** 30 秒內有收到成交 → 串流有效（沒有成交時改由輪詢補上） */
    fun fresh(): Boolean = connected && lastTrade.values.any { System.currentTimeMillis() - it < 30_000 }

    fun tradesLive(symbol: String): Boolean = connected && (lastTrade[symbol]?.let { System.currentTimeMillis() - it < 30_000 } ?: false)

    /** 驗證失敗（401/403）的金鑰：換金鑰前不再連線；否則每 250 毫秒的迴圈會不停重連 */
    @Volatile private var authFailedKey = ""
    /** 斷線後下次允許重連的時間（退避），ensure() 不可提早重連 */
    @Volatile private var nextAttemptAt = 0L

    fun ensure(enabled: Boolean, apiKey: String, symbols: List<String>) {
        if (!enabled || apiKey.isBlank()) { stop(); return }
        latestSymbols = symbols
        if (apiKey == authFailedKey) { if (ws != null) stop(); return }
        if (ws == null && key == apiKey && System.currentTimeMillis() < nextAttemptAt) { wanted = true; return }
        wanted = true
        if (ws == null || key != apiKey) {
            stop(); wanted = true
            key = apiKey
            connect()
            return
        }
        if (connected) syncSubscriptions(symbols)
        latestSymbols = symbols
    }

    @Volatile private var latestSymbols: List<String> = emptyList()

    private fun syncSubscriptions(symbols: List<String>) {
        val w = ws ?: return
        val want = symbols.map { it.uppercase() }.distinct().take(50).toSet()
        synchronized(subscribed) {
            (subscribed - want).forEach { w.send(JSONObject().put("type", "unsubscribe").put("symbol", it).toString()) }
            (want - subscribed).forEach { w.send(JSONObject().put("type", "subscribe").put("symbol", it).toString()) }
            subscribed.clear(); subscribed.addAll(want)
        }
    }

    fun stop() {
        wanted = false
        connected = false
        ws?.close(1000, "bye")
        ws = null
        synchronized(subscribed) { subscribed.clear() }
        main.removeCallbacks(flush)
    }

    private fun connect() {
        val req = Request.Builder().url("wss://ws.finnhub.io?token=$key").build()
        main.post { AppState.usStreamStatus = "Finnhub 串流連線中…" }
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                retries = 0
                synchronized(subscribed) { subscribed.clear() }
                val syms = latestSymbols.ifEmpty { AppState.quotes.filter { it.market == Market.US }.map { it.symbol } }
                syncSubscriptions(syms)
                main.post { AppState.usStreamStatus = "Finnhub 串流已連線（${syms.size.coerceAtMost(50)} 檔）" }
                main.removeCallbacks(flush); main.postDelayed(flush, 300)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (o.optString("type")) {
                    "trade" -> {
                        val arr = o.optJSONArray("data") ?: return
                        for (i in 0 until arr.length()) {
                            val d = arr.optJSONObject(i) ?: continue
                            val s = d.optString("s")
                            val p = d.optDouble("p")
                            if (s.isBlank() || p.isNaN() || p <= 0) continue
                            val t = d.optLong("t", System.currentTimeMillis())
                            val prev = pending[s]
                            if (prev == null || t >= prev.second) pending[s] = p to t
                            lastTrade[s] = System.currentTimeMillis()
                        }
                    }
                    "error" -> main.post { AppState.usStreamStatus = "Finnhub：" + o.optString("msg") }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 舊連線的失敗回呼不可清掉新連線（否則會同時開出兩條連線）
                if (webSocket !== ws) return
                connected = false
                ws = null
                val code = response?.code
                if (code == 401 || code == 403) authFailedKey = key
                main.post { AppState.usStreamStatus = if (code == 401 || code == 403) "Finnhub 金鑰驗證失敗，改用輪詢" else "Finnhub 串流中斷，重新連線…" }
                if (wanted && code != 401 && code != 403) {
                    retries++
                    val d = (2_000L * retries).coerceAtMost(30_000L)
                    nextAttemptAt = System.currentTimeMillis() + d
                    main.postDelayed({ if (wanted && ws == null) connect() }, d)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ws !== webSocket) return
                connected = false
                ws = null
                // 伺服器主動關閉：稍後由 ensure() 重新連線
                nextAttemptAt = System.currentTimeMillis() + 3_000L
            }
        })
    }

    /** 每 300 毫秒把暫存成交寫進報價 */
    private val flush: Runnable = object : Runnable {
        override fun run() {
            if (!wanted) return
            if (pending.isNotEmpty()) {
                // 逐鍵取出再移除：複製後整批 clear 會丟掉複製與清除之間剛收到的成交
                val batch = HashMap<String, Pair<Double, Long>>()
                for (k in pending.keys.toList()) pending.remove(k)?.let { batch[k] = it }
                val now = System.currentTimeMillis()
                batch.forEach { (s, v) ->
                    val q = AppState.quote(s) ?: return@forEach
                    if (q.prevClose <= 0) return@forEach
                    AppState.applyRemote(
                        RemoteQuote(q.symbol, null, v.first, q.prevClose, null, null, null),
                        QuoteSource.FINNHUB, minOf(v.second, now), live = true,
                    )
                }
                AppState.lastLiveAt = now
                AppState.usStreamStatus = "Finnhub 逐筆串流同步中（${synchronized(subscribed) { subscribed.size }} 檔）"
            }
            main.postDelayed(this, 300)
        }
    }
}
