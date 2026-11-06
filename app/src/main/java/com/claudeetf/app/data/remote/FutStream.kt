package com.claudeetf.app.data.remote

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * 台指期（台灣加權指數期貨 TXF）：富果期權行情。
 * 近月合約代號 = TXF + 月份字母（A=1 月 … L=12 月）+ 年尾數，結算日（每月第三個週三）後換下個月。
 * 一般盤 08:45–13:45、盤後 15:00–次日 05:00；首頁卡片顯示時以 WebSocket 串流同步。
 */
object FutStream {
    private const val BASE = "https://api.fugle.tw/marketdata/v1.0/futopt"
    private const val WS = "wss://api.fugle.tw/marketdata/v1.0/futopt/streaming"
    private val TPE = ZoneId.of("Asia/Taipei")
    private val main = Handler(Looper.getMainLooper())
    private val client by lazy {
        OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    data class FutQuote(
        val symbol: String, val price: Double, val ref: Double,
        val open: Double?, val high: Double?, val low: Double?, val volume: Double?, val time: Long, val afterHours: Boolean,
    ) {
        val change get() = price - ref
        val changePct get() = if (ref > 0) change / ref * 100 else 0.0
    }

    var quote by mutableStateOf<FutQuote?>(null)
    var spark by mutableStateOf<List<Float>>(emptyList())
    var status by mutableStateOf("")
    var live by mutableStateOf(false)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var users = 0
    private var retry = 0

    /** 近月合約代號 */
    fun nearMonth(now: ZonedDateTime = ZonedDateTime.now(TPE)): String {
        var d = now.toLocalDate()
        fun settle(x: LocalDate) = x.withDayOfMonth(1).with(TemporalAdjusters.dayOfWeekInMonth(3, DayOfWeek.WEDNESDAY))
        // 結算日 13:30 後改為下個月（盤後交易也已是次月合約）
        val s = settle(d)
        if (d.isAfter(s) || (d == s && now.toLocalTime() >= LocalTime.of(13, 30))) d = d.plusMonths(1)
        return com.claudeetf.app.data.AppState.futProduct + ('A' + (d.monthValue - 1)) + (d.year % 10)
    }

    /** 目前交易時段：REGULAR、AFTERHOURS 或 null（休市） */
    fun session(now: ZonedDateTime = ZonedDateTime.now(TPE)): String? {
        val t = now.toLocalTime()
        val dow = now.dayOfWeek
        val weekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY
        return when {
            weekday && t >= LocalTime.of(8, 45) && t < LocalTime.of(13, 45) -> "REGULAR"
            weekday && t >= LocalTime.of(15, 0) -> "AFTERHOURS"
            // 週二至週六凌晨屬前一個交易日的盤後
            t < LocalTime.of(5, 0) && dow != DayOfWeek.SUNDAY && dow != DayOfWeek.MONDAY -> "AFTERHOURS"
            else -> null
        }
    }

    private fun num(o: JSONObject, k: String): Double? = o.optDouble(k, Double.NaN).takeIf { !it.isNaN() && it != 0.0 }

    private fun parse(o: JSONObject, symbol: String, after: Boolean): FutQuote? {
        val ref = num(o, "previousClose") ?: num(o, "referencePrice") ?: return null
        val price = num(o, "lastPrice") ?: num(o, "closePrice") ?: ref
        val total = o.optJSONObject("total")
        val t = o.optLong("lastUpdated", 0L).let { if (it > 100_000_000_000_000L) it / 1000 else it }.takeIf { it > 0 } ?: System.currentTimeMillis()
        return FutQuote(symbol, price, ref, num(o, "openPrice"), num(o, "highPrice"), num(o, "lowPrice"),
            total?.optDouble("tradeVolume", Double.NaN)?.takeIf { !it.isNaN() }, t, after)
    }

    /** REST 快照與 5 分 K（卡片開啟時先顯示，再由串流接手） */
    fun snapshot(key: String) {
        val sym = nearMonth()
        val after = session() == "AFTERHOURS"
        val q = "?session=" + if (after) "afterhours" else "regular"
        val body = Http.get("$BASE/intraday/quote/$sym" + (if (after) q else ""), mapOf("X-API-KEY" to key))
        val fq = parse(JSONObject(body), sym, after)
        val cb = runCatching { Http.get("$BASE/intraday/candles/$sym?timeframe=5" + (if (after) "&session=afterhours" else ""), mapOf("X-API-KEY" to key)) }.getOrNull()
        val closes = cb?.let { b ->
            val arr = JSONObject(b).optJSONArray("data")
            (0 until (arr?.length() ?: 0)).mapNotNull { i -> arr?.optJSONObject(i)?.optDouble("close", Double.NaN)?.takeIf { !it.isNaN() }?.toFloat() }
        } ?: emptyList()
        main.post {
            if (fq != null) quote = fq
            if (closes.size >= 2) spark = closes
            status = if (fq != null) "富果期貨 $sym" else "查無 $sym 報價"
        }
    }

    /** 卡片顯示時呼叫 start、離開時 stop；多張卡片共用一條連線 */
    fun start(key: String) {
        users++
        if (ws != null || key.isBlank()) return
        val sess = session() ?: run { main.post { status = "期貨休市中（一般盤 08:45–13:45、盤後 15:00–05:00）" }; return }
        val sym = nearMonth()
        ws = client.newWebSocket(Request.Builder().url(WS).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("event", "auth").put("data", JSONObject().put("apikey", key)).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (o.optString("event")) {
                    "authenticated" -> {
                        retry = 0
                        val d = JSONObject().put("channel", "aggregates").put("symbol", sym)
                        if (sess == "AFTERHOURS") d.put("afterHours", true)
                        webSocket.send(JSONObject().put("event", "subscribe").put("data", d).toString())
                        main.post { live = true; status = "富果期貨串流同步 $sym" }
                    }
                    "data" -> {
                        val d = o.optJSONObject("data") ?: return
                        val fq = parse(d, sym, sess == "AFTERHOURS") ?: return
                        main.post {
                            quote = fq
                            spark = (if (spark.isNotEmpty() && spark.last() == fq.price.toFloat()) spark else spark + fq.price.toFloat()).takeLast(400)
                        }
                    }
                    "error" -> {
                        val msg = o.optJSONObject("data")?.optString("message") ?: "錯誤"
                        main.post { live = false; status = "富果期貨：$msg" }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket, key)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket, key)
        })
    }

    private fun closed(w: WebSocket, key: String) {
        if (w !== ws) return
        ws = null
        main.post { live = false }
        if (users > 0) {
            val delay = minOf(60_000L, 2_000L shl minOf(retry, 5)); retry++
            main.postDelayed({ if (users > 0 && ws == null) { users--; start(key) } }, delay)
        }
    }

    val products = listOf("TXF" to "台指期", "MXF" to "小台指", "EXF" to "電子期", "FXF" to "金融期")

    fun stop() {
        users = maxOf(0, users - 1)
        if (users == 0) { ws?.close(1000, "bye"); ws = null; live = false }
    }
}
