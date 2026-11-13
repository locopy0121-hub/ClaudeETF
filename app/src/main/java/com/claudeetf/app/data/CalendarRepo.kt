package com.claudeetf.app.data

import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.util.UsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 財經行事曆：自選／持股的財報日、除息日、發放日（Yahoo），
 * 美股休市日與 FOMC 利率決議（固定日程，換算台灣時間）。
 */
object CalendarRepo {
    private val TPE = ZoneId.of("Asia/Taipei")
    private const val TTL = 60 * 60_000L

    /** 代號 → 事件日（毫秒），提醒引擎也會用到 */
    private val eventsCache = HashMap<String, Pair<Long, YahooExtra.Events>>()
    private var listCache: Pair<Long, List<CalendarEvent>>? = null

    private val weekNames = listOf("一", "二", "三", "四", "五", "六", "日")

    fun label(d: LocalDate): String {
        val today = LocalDate.now(TPE)
        val base = "${d.monthValue}/${d.dayOfMonth}（${weekNames[d.dayOfWeek.value - 1]}）"
        return when (d) {
            today -> "今天 $base"
            today.plusDays(1) -> "明天 $base"
            else -> if (d.year != today.year) "${d.year}/$base" else base
        }
    }

    private fun toDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(TPE).toLocalDate()

    /** 單一證券的事件日（快取 1 小時），需在 IO 執行緒呼叫 */
    fun eventsFor(q: Quote): YahooExtra.Events? {
        eventsCache[q.symbol]?.let { (t, e) -> if (System.currentTimeMillis() - t < TTL) return e }
        val e = runCatching { YahooExtra.events(StockInfoRepo.yahooSymbol(q)) }.getOrNull() ?: return null
        eventsCache[q.symbol] = System.currentTimeMillis() to e
        return e
    }

    suspend fun load(force: Boolean = false): List<CalendarEvent> = withContext(Dispatchers.IO) {
        listCache?.let { (t, l) -> if (!force && System.currentTimeMillis() - t < TTL) return@withContext l }
        val today = LocalDate.now(TPE)
        val horizon = today.plusDays(120)
        val out = ArrayList<CalendarEvent>()
        val held = AppState.holdings.map { it.symbol }.toSet()

        for (q in AppState.quotes.toList()) {
            val e = eventsFor(q) ?: continue
            val isHeld = q.symbol in held
            e.earnings?.let { ms ->
                val d = toDate(ms)
                if (d >= today && d <= horizon) out += CalendarEvent(
                    label(d), d, if (q.market == Market.US) "預估" else "—", "${q.symbol} ${q.name} 財報",
                    if (q.market == Market.US) "美股多在盤前或盤後公布（台灣時間約清晨或晚間）" else "財報公布日",
                    EventKind.EARNINGS, if (isHeld) 3 else 2, isHeld,
                )
            }
            e.exDividend?.let { ms ->
                val d = toDate(ms)
                if (d >= today && d <= horizon) out += CalendarEvent(
                    label(d), d, "全日", "${q.symbol} ${q.name} 除息", "前一個交易日收盤前持有才領得到",
                    EventKind.DIVIDEND, if (isHeld) 2 else 1, isHeld,
                )
            }
            e.dividendPay?.let { ms ->
                val d = toDate(ms)
                if (d >= today && d <= horizon) out += CalendarEvent(
                    label(d), d, "全日", "${q.symbol} 股息發放", "股息入帳日", EventKind.DIVIDEND, 1, isHeld,
                )
            }
        }

        UsSession.holidayNames.forEach { (d, name) ->
            if (d >= today && d <= horizon) out += CalendarEvent(
                label(d), d, "全日", "美股休市・$name", "紐約時間全日休市", EventKind.HOLIDAY,
            )
        }
        UsSession.earlyCloses.forEach { (d, name) ->
            val tw = UsSession.nyToTaipei(d, LocalTime.of(13, 0))
            val td = tw.toLocalDate()
            if (td >= today && td <= horizon) out += CalendarEvent(
                label(td), td, tw.format(DateTimeFormatter.ofPattern("HH:mm")), "美股提早收盤・$name",
                "美東 13:00 收盤（台灣時間 " + tw.format(DateTimeFormatter.ofPattern("M/d HH:mm")) + "），盤後交易至美東 17:00", EventKind.HOLIDAY,
            )
        }
        UsSession.fomcDecisionDays.forEach { d ->
            val tw = UsSession.nyToTaipei(d, LocalTime.of(14, 0))
            val td = tw.toLocalDate()
            if (td >= today && td <= horizon) out += CalendarEvent(
                label(td), td, tw.format(DateTimeFormatter.ofPattern("HH:mm")), "FOMC 利率決議",
                "聯準會公布利率決策（美東 14:00），30 分鐘後主席記者會", EventKind.ECONOMIC, 3,
            )
        }

        val sorted = out.sortedWith(compareBy<CalendarEvent>({ it.date }, { -it.importance }))
        listCache = System.currentTimeMillis() to sorted
        sorted
    }
}
