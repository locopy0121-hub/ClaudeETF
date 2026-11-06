package com.claudeetf.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.claudeetf.app.MainActivity
import com.claudeetf.app.R
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.ColorMode
import com.claudeetf.app.util.Fmt
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 桌面小工具：總資產、今日損益與自選前五檔（盤中隨即時報價刷新） */
class AssetsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        AppState.init(context.applicationContext)
        ids.forEach { manager.updateAppWidget(it, WidgetUpdater.views(context)) }
    }
}

object WidgetUpdater {
    fun views(context: Context): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_assets)
        val total = AppState.totalAssetsTwd()
        val today = AppState.todayPnlTwd()
        val base = total - today
        val pct = if (base > 0) today / base * 100 else 0.0
        val hide = AppState.hideAmounts
        v.setTextViewText(R.id.w_total, if (hide) "＊＊＊＊" else Fmt.num(total))
        v.setTextViewText(R.id.w_today, "今日 " + (if (hide) "＊＊＊＊" else Fmt.signed(today)) + "（" + Fmt.pct(pct) + "）")
        val upRed = AppState.colorMode == ColorMode.TW
        v.setTextColor(R.id.w_today, trendColor(today, upRed))
        val time = ZonedDateTime.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
        v.setTextViewText(R.id.w_time, "更新 $time")
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        v.setOnClickPendingIntent(R.id.widget_root, pi)
        // 自選前五檔
        val rows = AppState.quotes.take(5)
        val symIds = intArrayOf(R.id.w_s1, R.id.w_s2, R.id.w_s3, R.id.w_s4, R.id.w_s5)
        val pxIds = intArrayOf(R.id.w_p1, R.id.w_p2, R.id.w_p3, R.id.w_p4, R.id.w_p5)
        val rowIds = intArrayOf(R.id.w_r1, R.id.w_r2, R.id.w_r3, R.id.w_r4, R.id.w_r5)
        for (k in 0 until 5) {
            val q = rows.getOrNull(k)
            if (q == null) { v.setViewVisibility(rowIds[k], android.view.View.GONE); continue }
            v.setViewVisibility(rowIds[k], android.view.View.VISIBLE)
            v.setTextViewText(symIds[k], q.symbol + " " + q.name.take(6))
            v.setTextViewText(pxIds[k], if (q.price > 0) Fmt.price(q.price) + "  " + Fmt.signedPct(q.changePct) else "—")
            v.setTextColor(pxIds[k], trendColor(q.change, upRed))
        }
        return v
    }

    private fun trendColor(v: Double, upRed: Boolean): Int = when {
        v > 0 -> if (upRed) 0xFFFF6B6B.toInt() else 0xFF3DD68C.toInt()
        v < 0 -> if (upRed) 0xFF3DD68C.toInt() else 0xFFFF6B6B.toInt()
        else -> 0xFF9AA3B2.toInt()
    }

    private var lastUpdate = 0L

    /** 盤中即時更新時呼叫，最多每 15 秒刷新一次，避免耗電 */
    fun updateThrottled(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 15_000) return
        update(context)
    }

    /** 行情更新後呼叫，刷新所有已放到桌面的小工具 */
    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, AssetsWidget::class.java))
        lastUpdate = System.currentTimeMillis()
        if (ids.isEmpty()) return
        val views = views(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}
