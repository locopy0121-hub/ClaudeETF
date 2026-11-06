package com.claudeetf.app.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.claudeetf.app.MainActivity
import com.claudeetf.app.R
import com.claudeetf.app.data.AlertRule
import com.claudeetf.app.data.AlertType
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.CalendarRepo
import com.claudeetf.app.data.MarketData
import com.claudeetf.app.data.QuoteSource
import com.claudeetf.app.util.Fmt
import com.claudeetf.app.util.UsSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** 檢查提醒條件並發出通知；前景更新後與背景排程都會呼叫 */
object AlertEngine {
    private const val CHANNEL = "alerts"
    private val TPE = ZoneId.of("Asia/Taipei")

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "股價提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "價格、漲跌幅、財報、除息與開盤提醒"
                }
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Int, title: String, text: String) {
        if (!hasPermission(context)) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(1000 + id, n) }
    }

    /** 測試通知 */
    fun test(context: Context) = notify(context, 0, "提醒測試", "通知功能正常，觸發條件時會這樣通知你。")

    suspend fun check(context: Context) {
        val today = LocalDate.now(TPE)
        val todayKey = today.toString()
        val now = System.currentTimeMillis()
        dailySummaries(context, today)
        // 定期定額扣款日：每月通知一次
        val month = java.time.YearMonth.now(TPE).toString()
        for (p in AppState.dueDca()) {
            if (p.lastNotified == month) continue
            AppState.markDcaNotified(p.id)
            notify(context, 500 + p.id, "定期定額：${p.symbol}", "本月扣款日（每月 ${p.day} 日）已到，打開 App 一鍵記錄這筆買進。")
        }
        for (a in AppState.alerts.toList()) {
            if (!a.enabled) continue
            val result: Pair<String, String>? = when (a.type) {
                AlertType.US_OPEN -> usOpen(a, todayKey)
                AlertType.EARNINGS, AlertType.EXDIV -> event(a, today)
                else -> price(a, todayKey, today)
            }
            if (result != null) {
                val (key, text) = result
                AppState.markFired(a.id, key, text, now)
                notify(context, a.id, a.title, text)
            }
        }
    }

    private fun usOpen(a: AlertRule, todayKey: String): Pair<String, String>? {
        if (a.lastFired == todayKey) return null
        val s = UsSession.info()
        if (s.phase != UsSession.Phase.REGULAR || s.progress > 0.08f) return null // 開盤後約 30 分鐘內
        return todayKey to "美股已開盤（台灣時間 ${s.openTw}），收盤 ${s.closeTw}"
    }

    private fun price(a: AlertRule, todayKey: String, today: LocalDate): Pair<String, String>? {
        if (a.lastFired == todayKey) return null
        val q = AppState.quote(a.symbol) ?: return null
        if (q.source == QuoteSource.SAMPLE || q.updatedAt == 0L || q.price <= 0) return null
        val updated = Instant.ofEpochMilli(q.updatedAt).atZone(TPE).toLocalDate()
        if (ChronoUnit.DAYS.between(updated, today) > 3) return null // 資料太舊不觸發
        val hit = when (a.type) {
            AlertType.PRICE_ABOVE -> q.price >= a.value
            AlertType.PRICE_BELOW -> q.price <= a.value
            AlertType.CHANGE_UP -> q.changePct >= a.value
            AlertType.CHANGE_DOWN -> q.changePct <= -a.value
            else -> false
        }
        if (!hit) return null
        return todayKey to "${q.symbol} ${q.name} 現價 ${Fmt.price(q.price)}（${Fmt.signedPct(q.changePct)}）"
    }

    private suspend fun event(a: AlertRule, today: LocalDate): Pair<String, String>? {
        val q = AppState.quote(a.symbol) ?: return null
        val e = withContext(Dispatchers.IO) { CalendarRepo.eventsFor(q) } ?: return null
        val ms = (if (a.type == AlertType.EARNINGS) e.earnings else e.exDividend) ?: return null
        val d = Instant.ofEpochMilli(ms).atZone(TPE).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, d)
        if (days < 0 || days > a.value.toLong()) return null
        val key = "${a.type}-$d"
        if (a.lastFired == key) return null
        val what = if (a.type == AlertType.EARNINGS) "公布財報" else "除息"
        val whenText = if (days == 0L) "今天" else "$days 天後（${d.monthValue}/${d.dayOfMonth}）"
        return key to "${q.symbol} ${q.name} $whenText$what"
    }

    /** 台股 13:45 後、美股收盤後各推播一次當日摘要 */
    private fun dailySummaries(context: Context, today: LocalDate) {
        if (!AppState.dailySummary) return
        val now = java.time.ZonedDateTime.now(TPE)
        val weekday = now.dayOfWeek.value <= 5
        fun summary(market: com.claudeetf.app.data.Market, title: String, key: String) {
            if (AppState.summarySent(key)) return
            val hs = AppState.holdingsOf(market)
            if (hs.isEmpty()) return
            var pnl = 0.0
            val moves = hs.mapNotNull { h ->
                val q = AppState.quote(h.symbol) ?: return@mapNotNull null
                if (q.updatedAt == 0L || q.prevClose <= 0) return@mapNotNull null
                pnl += h.shares * q.change * AppState.fxFor(market)
                q
            }
            if (moves.isEmpty()) return
            val up = moves.count { it.change > 0 }
            val down = moves.count { it.change < 0 }
            val best = moves.maxBy { it.changePct }
            val worst = moves.minBy { it.changePct }
            val text = "今日損益 ${Fmt.signed(pnl)} 元 · 上漲 $up 檔、下跌 $down 檔\n" +
                "表現最好 ${best.symbol} ${Fmt.signedPct(best.changePct)}；最弱 ${worst.symbol} ${Fmt.signedPct(worst.changePct)}"
            AppState.markSummarySent(key)
            notify(context, if (market == com.claudeetf.app.data.Market.TW) 901 else 902, title, text)
        }
        // 台股：平日 13:45 之後
        if (weekday && now.toLocalTime() >= java.time.LocalTime.of(13, 45)) {
            summary(com.claudeetf.app.data.Market.TW, "台股收盤摘要", "TW-$today")
        }
        // 美股：紐約收盤後（台灣時間清晨），以紐約日期為準
        val ny = now.withZoneSameInstant(java.time.ZoneId.of("America/New_York"))
        val nyDay = ny.toLocalDate()
        val nyWeekday = ny.dayOfWeek.value <= 5
        if (nyWeekday && ny.toLocalTime() >= java.time.LocalTime.of(16, 15) && nyDay !in UsSession.holidayNames.keys) {
            summary(com.claudeetf.app.data.Market.US, "美股收盤摘要", "US-$nyDay")
        }
    }

    // ---------------- 背景排程 ----------------

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("alerts", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** 背景每 15 分鐘：更新有設提醒的報價後檢查 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppState.init(applicationContext)
        val summaries = AppState.dailySummary && AppState.holdings.isNotEmpty()
        if (AppState.alerts.none { it.enabled } && !summaries && AppState.dcaPlans.none { it.enabled }) return Result.success()
        runCatching {
            val TW = com.claudeetf.app.data.Market.TW
            val US = com.claudeetf.app.data.Market.US
            val needsTw = AppState.alerts.any { it.enabled && it.isPrice && it.market == TW } ||
                (summaries && AppState.holdingsOf(TW).isNotEmpty())
            val needsUs = AppState.alerts.any { it.enabled && it.isPrice && it.market == US } ||
                (summaries && AppState.holdingsOf(US).isNotEmpty())
            if (needsTw) MarketData.refreshTw()
            if (needsUs) MarketData.refreshUs()
            AppState.persist()
        }
        AlertEngine.check(applicationContext)
        runCatching { AppState.recordNav() }
        runCatching { com.claudeetf.app.widget.WidgetUpdater.update(applicationContext) }
        return Result.success()
    }
}
