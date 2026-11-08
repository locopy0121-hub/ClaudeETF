package com.claudeetf.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.claudeetf.app.MainActivity
import com.claudeetf.app.R
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.ColorMode
import com.claudeetf.app.util.Fmt
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 桌面小工具：版面與樣式由 App「我的 › 桌面小工具」控制（WidgetStyle） */
class AssetsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        AppState.init(context.applicationContext)
        ids.forEach { manager.updateAppWidget(it, WidgetUpdater.views(context)) }
    }
}

/**
 * 小工具樣式。
 * theme 0 跟隨 App、1 深色、2 淺色、3 透明；alpha 背景不透明度 0–100；radius 0 方角、1 圓角、2 大圓角；
 * text 0 小、1 中、2 大；header 0 總資產、1 持股市值、2 今日損益；source 0 持股、1 自選；
 * rows 列數 0–8；rowMode 0 價格＋漲跌%、1 只顯示漲跌%、2 今日損益（持股）；sort 0 依清單順序、1 依漲跌幅大小。
 */
data class WidgetStyle(
    val theme: Int = 0, val alpha: Int = 92, val radius: Int = 2, val text: Int = 1,
    val header: Int = 0, val showToday: Boolean = true, val showIndex: Boolean = true,
    val source: Int = 0, val rows: Int = 5, val rowMode: Int = 0, val sort: Int = 0, val showTime: Boolean = true,
) {
    fun toJson(): String = JSONObject().put("theme", theme).put("alpha", alpha).put("radius", radius).put("text", text)
        .put("header", header).put("showToday", showToday).put("showIndex", showIndex).put("source", source)
        .put("rows", rows).put("rowMode", rowMode).put("sort", sort).put("showTime", showTime).toString()

    companion object {
        fun fromJson(s: String?): WidgetStyle = runCatching {
            val o = JSONObject(s ?: return WidgetStyle())
            WidgetStyle(o.optInt("theme", 0), o.optInt("alpha", 92).coerceIn(0, 100), o.optInt("radius", 2), o.optInt("text", 1),
                o.optInt("header", 0), o.optBoolean("showToday", true), o.optBoolean("showIndex", true), o.optInt("source", 0),
                o.optInt("rows", 5).coerceIn(0, 8), o.optInt("rowMode", 0), o.optInt("sort", 0), o.optBoolean("showTime", true))
        }.getOrDefault(WidgetStyle())
    }
}

/** 小工具顯示內容（RemoteViews 與 App 內預覽共用） */
data class WidgetModel(
    val label: String, val total: String, val today: String, val todaySign: Double,
    val index: String, val rows: List<Triple<String, String, Double>>, val time: String,
)

/** 主題色（ARGB） */
data class WidgetColors(val bg: Int, val text: Int, val muted: Int, val divider: Int)

object WidgetUpdater {
    fun colors(st: WidgetStyle): WidgetColors {
        val dark = when (st.theme) { 1 -> true; 2 -> false; 3 -> true; else -> AppState.themeMode != 2 }
        return when {
            st.theme == 3 -> WidgetColors(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0x55FFFFFF)
            dark -> WidgetColors(0xFF171B24.toInt(), 0xFFE8ECF2.toInt(), 0xFF9AA3B2.toInt(), 0x33FFFFFF)
            else -> WidgetColors(0xFFFFFFFF.toInt(), 0xFF1B1F27.toInt(), 0xFF6B7380.toInt(), 0x22000000)
        }
    }

    /** 透明主題預設較低的不透明度 */
    fun bgAlpha(st: WidgetStyle): Int = (st.alpha * 255 / 100).coerceIn(0, 255)

    fun trendColor(v: Double, muted: Int): Int {
        val upRed = AppState.colorMode == ColorMode.TW
        return when {
            v > 0 -> if (upRed) 0xFFFF6B6B.toInt() else 0xFF3DD68C.toInt()
            v < 0 -> if (upRed) 0xFF3DD68C.toInt() else 0xFFFF6B6B.toInt()
            else -> muted
        }
    }

    fun model(st: WidgetStyle = AppState.widgetStyle): WidgetModel {
        val hide = AppState.hideAmounts
        fun amt(v: Double) = if (hide) "＊＊＊＊" else Fmt.num(v)
        val total = AppState.totalAssetsTwd()
        val mv = AppState.totalMarketValueTwd()
        val today = AppState.todayPnlTwd()
        val base = mv - today
        val pct = if (base > 0) today / base * 100 else 0.0
        val (label, big) = when (st.header) {
            1 -> "持股市值（TWD）" to amt(mv)
            2 -> "今日損益（TWD）" to (if (hide) "＊＊＊＊" else Fmt.signed(today))
            else -> "總資產（TWD）" to amt(total)
        }
        val todayLine = if (!st.showToday) "" else if (st.header == 2) "報酬 " + Fmt.pct(pct) + " · 市值 " + amt(mv)
            else "今日 " + (if (hide) "＊＊＊＊" else Fmt.signed(today)) + "（" + Fmt.pct(pct) + "）"
        val idx = if (!st.showIndex) "" else (AppState.twIndices + AppState.usIndices)
            .filter { it.symbol == "^TWII" || it.symbol == "^IXIC" }.filter { it.value > 0 }
            .joinToString("  ") { (if (it.symbol == "^TWII") "加權 " else "那指 ") + Fmt.num(it.value, 0) + " " + Fmt.pct(it.changePct) }
        val quotes = if (st.source == 1) AppState.quotes.toList()
            else AppState.holdings.map { it.symbol }.distinct().mapNotNull { AppState.quote(it) }
        val sorted = if (st.sort == 1) quotes.sortedByDescending { kotlin.math.abs(it.changePct) } else quotes
        val rows = sorted.take(st.rows).map { q ->
            val name = q.symbol + " " + q.name.take(6)
            when (st.rowMode) {
                1 -> Triple(name, if (q.price > 0) Fmt.signedPct(q.changePct) else "—", q.change)
                2 -> {
                    val sh = AppState.holdings.filter { it.symbol == q.symbol }.sumOf { it.shares }
                    val d = (q.price - q.prevClose) * sh * AppState.fxFor(q.market)
                    Triple(name, if (sh <= 0 || q.prevClose <= 0) Fmt.signedPct(q.changePct) else (if (hide) "＊＊＊" else Fmt.signed(d)) + " " + Fmt.signedPct(q.changePct), q.change)
                }
                else -> Triple(name, if (q.price > 0) Fmt.price(q.price) + "  " + Fmt.signedPct(q.changePct) else "—", q.change)
            }
        }
        val time = if (!st.showTime) "" else "更新 " + ZonedDateTime.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
        return WidgetModel(label, big, todayLine, if (st.header == 2) pct else today, idx, rows, time)
    }

    fun views(context: Context): RemoteViews {
        val st = AppState.widgetStyle
        val c = colors(st)
        val m = model(st)
        val v = RemoteViews(context.packageName, R.layout.widget_assets)
        // 背景：圓角圖層上色＋透明度
        v.setImageViewResource(R.id.w_bg, when (st.radius) { 0 -> R.drawable.widget_shape_0; 1 -> R.drawable.widget_shape_12; else -> R.drawable.widget_shape_24 })
        v.setInt(R.id.w_bg, "setColorFilter", c.bg)
        v.setInt(R.id.w_bg, "setImageAlpha", bgAlpha(st))
        val k = when (st.text) { 0 -> 0.85f; 2 -> 1.2f; else -> 1f }
        fun size(id: Int, sp: Float) = v.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp * k)
        v.setTextViewText(R.id.w_label, m.label); v.setTextColor(R.id.w_label, c.muted); size(R.id.w_label, 12f)
        v.setTextViewText(R.id.w_total, m.total); size(R.id.w_total, 24f)
        v.setTextColor(R.id.w_total, if (st.header == 2) trendColor(m.todaySign, c.text) else c.text)
        v.setViewVisibility(R.id.w_today, if (m.today.isBlank()) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.w_today, m.today); v.setTextColor(R.id.w_today, trendColor(m.todaySign, c.muted)); size(R.id.w_today, 13f)
        v.setViewVisibility(R.id.w_index, if (m.index.isBlank()) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.w_index, m.index); v.setTextColor(R.id.w_index, c.muted); size(R.id.w_index, 11f)
        v.setViewVisibility(R.id.w_div, if (m.rows.isEmpty()) View.GONE else View.VISIBLE)
        v.setInt(R.id.w_div, "setBackgroundColor", c.divider)
        v.removeAllViews(R.id.w_list)
        m.rows.forEach { (name, value, sign) ->
            val r = RemoteViews(context.packageName, R.layout.widget_row)
            r.setTextViewText(R.id.w_rn, name); r.setTextColor(R.id.w_rn, c.text)
            r.setTextViewText(R.id.w_rv, value); r.setTextColor(R.id.w_rv, trendColor(sign, c.muted))
            r.setTextViewTextSize(R.id.w_rn, TypedValue.COMPLEX_UNIT_SP, 12f * k)
            r.setTextViewTextSize(R.id.w_rv, TypedValue.COMPLEX_UNIT_SP, 12f * k)
            v.addView(R.id.w_list, r)
        }
        v.setViewVisibility(R.id.w_time, if (m.time.isBlank()) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.w_time, m.time); v.setTextColor(R.id.w_time, c.muted); size(R.id.w_time, 10f)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return v
    }

    private var lastUpdate = 0L

    /** 盤中即時更新時呼叫，最多每 15 秒刷新一次，避免耗電 */
    fun updateThrottled(context: Context) {
        if (System.currentTimeMillis() - lastUpdate < 15_000) return
        update(context)
    }

    /** 行情更新或樣式改變後呼叫，刷新所有已放到桌面的小工具 */
    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, AssetsWidget::class.java))
        lastUpdate = System.currentTimeMillis()
        if (ids.isEmpty()) return
        val views = views(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    fun count(context: Context): Int =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, AssetsWidget::class.java)).size

    /** 請桌面加入小工具（支援的啟動器會跳出確認） */
    fun requestPin(context: Context): Boolean {
        val mgr = AppWidgetManager.getInstance(context)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        return mgr.requestPinAppWidget(ComponentName(context, AssetsWidget::class.java), null, null)
    }
}
