package com.claudeetf.app.alerts

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.claudeetf.app.MainActivity
import com.claudeetf.app.R
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.MarketData
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 盤中持股監控（前景服務）：台股盤中（可加美股時段）每 N 秒更新報價，
 * 檢查所有提醒規則＋持股專屬提醒（個股漲跌幅分級、漲跌停、今日總損益），
 * 並在常駐通知顯示即時持股摘要。收盤後自動停止，並排程下一個交易日開盤前自動啟動。
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var lastAnalysis = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppState.init(applicationContext)
            // 使用者手動停止：今天不再自動啟動
            stoppedToday = LocalDate.now(TPE)
            stopSelf(); return START_NOT_STICKY
        }
        AppState.init(applicationContext)
        ensureChannel(this)
        val n = buildNotification(this, "盤中監控啟動中…", "")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTI_ID, n)
        running = true
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        while (scope.isActive) {
            if (!AppState.monOn || !inSession()) break
            runCatching { MarketData.refreshAll() }
            runCatching { AlertEngine.check(applicationContext) }
            runCatching { checkHoldings(applicationContext) }
            // 每 5 分鐘替持股做一次 K 線與光譜分析，讓小工具能顯示進場建議與光譜位階
            if (System.currentTimeMillis() - lastAnalysis > 5 * 60_000L) {
                lastAnalysis = System.currentTimeMillis()
                AppState.holdings.map { it.symbol }.distinct().mapNotNull { AppState.quote(it) }.forEach { q ->
                    runCatching { com.claudeetf.app.data.KlineAnalysis.load(q, false) }
                    runCatching { com.claudeetf.app.data.InvestAnalysis.analyze(q) }
                }
            }
            runCatching { com.claudeetf.app.widget.WidgetUpdater.update(applicationContext) }
            updateNotification()
            AppState.flush()
            delay(AppState.monInterval.coerceIn(10, 300) * 1000L)
        }
        schedule(applicationContext)
        stopSelf()
    }

    private fun updateNotification() {
        val today = AppState.todayPnlTwd()
        val total = AppState.totalAssetsTwd()
        val base = total - today
        val pct = if (base > 0) today / base * 100 else 0.0
        val hide = AppState.hideAmounts
        val title = "今日 " + (if (hide) "＊＊＊" else Fmt.signed(today)) + "（" + Fmt.pct(pct) + "）· 市值 " + (if (hide) "＊＊＊" else Fmt.num(AppState.totalMarketValueTwd()))
        val movers = AppState.holdings.mapNotNull { h -> AppState.quote(h.symbol)?.takeIf { it.price > 0 && it.prevClose > 0 } }
            .distinctBy { it.symbol }.sortedByDescending { kotlin.math.abs(it.changePct) }.take(5)
        val body = movers.joinToString("\n") { q -> "${q.symbol} ${q.name.take(6)}  ${Fmt.price(q.price)}  ${Fmt.signedPct(q.changePct)}" }
        val time = ZonedDateTime.now(TPE).let { "%02d:%02d:%02d".format(it.hour, it.minute, it.second) }
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTI_ID, buildNotification(this, title, (if (body.isBlank()) "" else "$body\n") + "更新 $time"))
        }
    }

    override fun onDestroy() {
        running = false
        loop?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private val TPE = ZoneId.of("Asia/Taipei")
        private const val CHANNEL = "monitor"
        private const val NOTI_ID = 7100
        const val ACTION_STOP = "com.claudeetf.app.MONITOR_STOP"
        @Volatile var running = false
            private set
        @Volatile private var stoppedToday: LocalDate? = null

        /** 是否在監控時段：台股盤中（08:59 起～13:35），可加美股盤中 */
        fun inSession(now: ZonedDateTime = ZonedDateTime.now(TPE)): Boolean {
            val wd = now.dayOfWeek.value <= 5
            val m = now.hour * 60 + now.minute
            val tw = wd && m in (8 * 60 + 59)..(13 * 60 + 35)
            val us = AppState.monUs && MarketData.usLive()
            return tw || us
        }

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "盤中持股監控", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "盤中常駐顯示持股即時損益（不響鈴）"; setShowBadge(false)
                })
        }

        private fun buildNotification(context: Context, title: String, text: String): Notification {
            val open = PendingIntent.getActivity(context, 7101,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(context, 7102, Intent(context, MonitorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(title)
                .setContentText(text.lineSequence().firstOrNull() ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setContentIntent(open)
                .addAction(0, "停止今日監控", stop)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }

        /** 啟動監控（在時段內才會真的執行）；從背景啟動被系統擋下時，改發「點此啟動」通知 */
        fun start(context: Context, fromUser: Boolean = false) {
            if (!AppState.monOn) return
            if (!fromUser && stoppedToday == LocalDate.now(TPE)) return
            if (running || !inSession()) { schedule(context); return }
            try {
                context.startForegroundService(Intent(context, MonitorService::class.java))
            } catch (e: Exception) {
                val pi = PendingIntent.getForegroundService(context, 7103, Intent(context, MonitorService::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                ensureChannel(context)
                val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_alert)
                    .setContentTitle("盤中監控待啟動").setContentText("系統限制背景自動啟動，點此開始今日盤中監控")
                    .setContentIntent(pi).setAutoCancel(true).build()
                runCatching { NotificationManagerCompat.from(context).notify(NOTI_ID + 1, n) }
            }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_STOP)) }
        }

        /** 下一次自動啟動時間：下一個台股交易日 08:59（開美股時另加 21:25） */
        fun nextStart(now: ZonedDateTime = ZonedDateTime.now(TPE)): ZonedDateTime {
            val cands = ArrayList<ZonedDateTime>()
            var d = now.toLocalDate()
            repeat(10) {
                if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
                    cands += d.atTime(8, 59).atZone(TPE)
                    if (AppState.monUs) cands += d.atTime(21, 25).atZone(TPE)
                }
                d = d.plusDays(1)
            }
            return cands.filter { it.isAfter(now.plusMinutes(1)) }.minOrNull() ?: now.plusDays(1)
        }

        /** 排程下一次自動啟動：可精準鬧鐘時用 setAlarmClock（系統允許啟動前景服務），否則用省電鬧鐘 */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(context, 7104, Intent(context, MonitorAlarmReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (!AppState.monOn) { am.cancel(pi); return }
            val at = nextStart().toInstant().toEpochMilli()
            runCatching {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    val show = PendingIntent.getActivity(context, 7105, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
                } else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }

        fun canExact(context: Context): Boolean =
            Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        // ================= 持股專屬提醒 =================
        private fun sentKey(k: String): Boolean {
            val p = context0?.getSharedPreferences("claudeetf_monitor", Context.MODE_PRIVATE) ?: return false
            val day = LocalDate.now(TPE).toString()
            val set = p.getStringSet(day, emptySet()) ?: emptySet()
            if (k in set) return true
            p.edit().clear().putStringSet(day, set + k).apply()   // 只保留今天
            return false
        }
        private var context0: Context? = null

        /**
         * 個股：今日漲跌幅每跨過一個級距（例如 3%、6%、9%）通知一次；漲跌停另外通知。
         * 組合：今日總損益每跨過設定金額的整數倍通知一次。
         */
        fun checkHoldings(context: Context) {
            context0 = context.applicationContext
            val today = LocalDate.now(TPE)
            val step = AppState.monStepPct
            val seen = HashSet<String>()
            for (h in AppState.holdings) {
                if (!seen.add(h.symbol)) continue
                val q = AppState.quote(h.symbol) ?: continue
                if (q.price <= 0 || q.prevClose <= 0) continue
                if (MarketData.quoteDay(q.symbol) != today && q.market == Market.TW) continue
                val pct = q.changePct
                val shares = AppState.holdings.filter { it.symbol == h.symbol }.sumOf { it.shares }
                val day = (q.price - q.prevClose) * shares * AppState.fxFor(q.market)
                if (step > 0) {
                    val lv = (kotlin.math.abs(pct) / step).toInt()
                    if (lv >= 1 && !sentKey("${q.symbol}:${if (pct > 0) "U" else "D"}$lv")) {
                        AlertEngine.notify(context, 7200 + (q.symbol.hashCode() and 0xff),
                            "${q.symbol} ${q.name} " + (if (pct > 0) "上漲" else "下跌") + " " + Fmt.num(kotlin.math.abs(pct), 2) + "%",
                            "現價 ${Fmt.price(q.price)}（${Fmt.signed(q.change, 2)}），持有 ${Fmt.shares(shares)} 股，今日 " +
                                (if (AppState.hideAmounts) "＊＊＊" else Fmt.signed(day)) + " 元")
                        AppState.logNotice("持股異動：${q.symbol} ${Fmt.signedPct(pct)}")
                    }
                }
                if (AppState.monLimit && q.market == Market.TW && kotlin.math.abs(pct) >= 9.5) {
                    if (!sentKey("${q.symbol}:L${if (pct > 0) "U" else "D"}"))
                        AlertEngine.notify(context, 7300 + (q.symbol.hashCode() and 0xff),
                            "${q.symbol} ${q.name} " + (if (pct > 0) "漲停" else "跌停"),
                            "現價 ${Fmt.price(q.price)}（${Fmt.signedPct(pct)}），持有 ${Fmt.shares(shares)} 股")
                }
            }
            val pnlStep = AppState.monPnlStep
            if (pnlStep > 0) {
                val t = AppState.todayPnlTwd()
                val lv = (kotlin.math.abs(t) / pnlStep).toInt()
                if (lv >= 1 && !sentKey("PNL:${if (t > 0) "U" else "D"}$lv"))
                    AlertEngine.notify(context, 7400, "今日持股" + (if (t > 0) "獲利" else "虧損") + "超過 " + Fmt.num(lv * pnlStep) + " 元",
                        "目前今日損益 " + (if (AppState.hideAmounts) "＊＊＊" else Fmt.signed(t)) + " 元")
            }
        }
    }
}

/** 開盤前鬧鐘：啟動盤中監控 */
class MonitorAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AppState.init(context.applicationContext)
        MonitorService.start(context.applicationContext)
        MonitorService.schedule(context.applicationContext)
    }
}

/** 開機後重新排程 */
class MonitorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppState.init(context.applicationContext)
        MonitorService.schedule(context.applicationContext)
    }
}
