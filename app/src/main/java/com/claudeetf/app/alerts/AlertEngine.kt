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
        // 每週備份提醒：有持股、超過 7 天沒備份，每週最多一次
        if (AppState.holdings.isNotEmpty() && System.currentTimeMillis() - AppState.lastBackupAt > 7L * 86_400_000) {
            val week = today.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val key = "BK-${today.year}-$week"
            if (!AppState.summarySent(key)) {
                AppState.markSummarySent(key)
                val text = if (AppState.lastBackupAt == 0L) "還沒有備份過資料。到「我的 › 備份與匯入」匯出到 Google 雲端硬碟，換手機也不怕資料遺失。"
                else "距離上次備份已超過 7 天，建議到「我的 › 備份與匯入」再匯出一次。"
                notify(context, 904, "記得備份資料", text)
                AppState.logNotice("備份提醒：" + text)
            }
        }
        // 除息、拆股：發現尚未記錄的項目時通知一次
        runCatching {
            val fresh = com.claudeetf.app.data.CorporateActions.scan().filter { !AppState.actionNotified(it.key) }
            if (fresh.isNotEmpty()) {
                AppState.markActionNotified(fresh.map { it.key })
                fresh.forEach { AppState.logNotice("待記錄：" + it.text) }
                notify(
                    context, 903,
                    if (fresh.size == 1) "有一筆股息／拆股待記錄" else "有 ${fresh.size} 筆股息／拆股待記錄",
                    fresh.take(3).joinToString("\n") { it.text } + "\n打開持股頁一鍵記錄",
                )
            }
        }
        // 定期定額扣款日：每月通知一次
        val month = java.time.YearMonth.now(TPE).toString()
        for (p in AppState.dueDca()) {
            if (p.lastNotified == month) continue
            AppState.markDcaNotified(p.id)
            notify(context, 500 + p.id, "定期定額：${p.symbol}", "本月扣款日（每月 ${p.day} 日）已到，打開 App 一鍵記錄這筆買進。")
            AppState.logNotice("定期定額：${p.symbol} 本月扣款日已到")
        }
        for (a in AppState.alerts.toList()) {
            if (!a.enabled) continue
            val result: Pair<String, String>? = when (a.type) {
                AlertType.US_OPEN -> usOpen(a, todayKey)
                AlertType.EARNINGS, AlertType.EXDIV -> event(a, today)
                AlertType.RAPID -> null // 由 checkRapid 即時偵測
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
            AppState.logNotice(title + "：" + text.replace("\n", "；"))
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

    /** 急漲急跌：每次即時更新後呼叫，比較 5 分鐘前價格 */
    fun checkRapid(context: Context) {
        val now = System.currentTimeMillis()
        for (a in AppState.alerts.toList()) {
            if (!a.enabled || a.type != AlertType.RAPID) continue
            val targets = if (a.symbol.isBlank()) AppState.holdings.map { it.symbol } else listOf(a.symbol)
            for (sym in targets) {
                val q = AppState.quote(sym) ?: continue
                val past = AppState.priceMinutesAgo(sym, 5) ?: continue
                if (past <= 0 || q.price <= 0) continue
                val pct = (q.price / past - 1) * 100
                if (kotlin.math.abs(pct) < a.value) continue
                // lastFired 記錄「代號@時間」，同一檔 30 分鐘內不重複
                val marks = a.lastFired.split(";").filter { it.contains("@") }.associate { it.substringBefore("@") to (it.substringAfter("@").toLongOrNull() ?: 0L) }
                if (now - (marks[sym] ?: 0L) < 30 * 60_000) continue
                val key = (marks + (sym to now)).filter { now - it.value < 60 * 60_000 }.entries.joinToString(";") { it.key + "@" + it.value }
                val dir = if (pct > 0) "急漲" else "急跌"
                val text = "${q.symbol} ${q.name} 5 分鐘內$dir ${Fmt.signedPct(pct)}，現價 ${Fmt.price(q.price)}（今日 ${Fmt.signedPct(q.changePct)}）"
                AppState.markFired(a.id, key, text, now)
                notify(context, 2000 + (sym.hashCode() and 0xfff), "${q.symbol} $dir", text)
            }
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
