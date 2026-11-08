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
 * text 0 小、1 中、2 大；header 0 總資產、1 持股市值、2 今日損益、3 不顯示；
 * layout 0 清單、1 大字條列、2 方格（兩欄）、3 只顯示總覽；source 0 持股、1 自選；rows 0–8；
 * fields 每檔顯示的項目（複選）：price 價格、change 漲跌、pct 漲跌幅、pnl 今日損益、volume 成交量、entry 進場建議、band 光譜位階；
 * indices 指數（台股、美股複選）；sort 0 依清單順序、1 依漲跌幅大小。
 */
data class WidgetStyle(
    val theme: Int = 0, val alpha: Int = 92, val radius: Int = 2, val text: Int = 1,
    val header: Int = 0, val showToday: Boolean = true,
    val source: Int = 0, val rows: Int = 5, val sort: Int = 0, val showTime: Boolean = true,
    val layout: Int = 0,
    val fields: Set<String> = setOf("price", "pct"),
    val indices: List<String> = listOf("^TWII", "^IXIC"),
) {
    fun has(f: String) = f in fields

    fun toJson(): String = JSONObject().put("v", 2).put("theme", theme).put("alpha", alpha).put("radius", radius).put("text", text)
        .put("header", header).put("showToday", showToday).put("source", source)
        .put("rows", rows).put("sort", sort).put("showTime", showTime).put("layout", layout)
        .put("fields", org.json.JSONArray(fields.toList())).put("indices", org.json.JSONArray(indices)).toString()

    companion object {
        /** 可選項目（順序即顯示順序） */
        val FIELDS = linkedMapOf("price" to "價格", "change" to "漲跌", "pct" to "漲跌幅", "pnl" to "今日損益",
            "volume" to "成交量", "entry" to "進場建議", "band" to "光譜位階")
        val INDEX_NAMES = linkedMapOf("^TWII" to "加權", "^TWOII" to "櫃買", "^DJI" to "道瓊", "^GSPC" to "S&P",
            "^IXIC" to "那指", "^SOX" to "費半")

        fun fromJson(s: String?): WidgetStyle = runCatching {
            val o = JSONObject(s ?: return WidgetStyle())
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }
            // 舊版：rowMode、showIndex 轉成新的複選項目
            val fields = list("fields")?.toSet() ?: when (o.optInt("rowMode", 0)) { 1 -> setOf("pct"); 2 -> setOf("pnl", "pct"); else -> setOf("price", "pct") }
            val idx = list("indices") ?: if (o.optBoolean("showIndex", true)) listOf("^TWII", "^IXIC") else emptyList()
            WidgetStyle(o.optInt("theme", 0), o.optInt("alpha", 92).coerceIn(0, 100), o.optInt("radius", 2), o.optInt("text", 1),
                o.optInt("header", 0), o.optBoolean("showToday", true), o.optInt("source", 0),
                o.optInt("rows", 5).coerceIn(0, 8), o.optInt("sort", 0), o.optBoolean("showTime", true),
                o.optInt("layout", 0), fields, idx)
        }.getOrDefault(WidgetStyle())
    }
}

/** 單檔資料（各版型共用） */
data class WRow(
    val symbol: String, val name: String, val price: String, val change: String, val pct: String, val sign: Double,
    val pnl: String?, val volume: String?, val entry: String?, val entryLevel: Int, val band: String?,
)

/** 小工具顯示內容（RemoteViews 與 App 內預覽共用） */
data class WidgetModel(
    val label: String, val total: String, val today: String, val todaySign: Double,
    val index: String, val rows: List<WRow>, val time: String,
)

/** 主題色（ARGB）；cell 為方格底色 */
data class WidgetColors(val bg: Int, val text: Int, val muted: Int, val divider: Int, val cell: Int)

object WidgetUpdater {
    fun colors(st: WidgetStyle): WidgetColors {
        val dark = when (st.theme) { 1 -> true; 2 -> false; 3 -> true; else -> AppState.themeMode != 2 }
        return when {
            st.theme == 3 -> WidgetColors(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xCCFFFFFF.toInt(), 0x55FFFFFF, 0x33FFFFFF)
            dark -> WidgetColors(0xFF171B24.toInt(), 0xFFE8ECF2.toInt(), 0xFF9AA3B2.toInt(), 0x33FFFFFF, 0xFF242A36.toInt())
            else -> WidgetColors(0xFFFFFFFF.toInt(), 0xFF1B1F27.toInt(), 0xFF6B7380.toInt(), 0x22000000, 0xFFF1F3F6.toInt())
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
            3 -> "" to ""
            else -> "總資產（TWD）" to amt(total)
        }
        val todayLine = if (!st.showToday) "" else if (st.header == 2) "報酬 " + Fmt.pct(pct) + " · 市值 " + amt(mv)
            else "今日 " + (if (hide) "＊＊＊＊" else Fmt.signed(today)) + "（" + Fmt.pct(pct) + "）"
        val allIdx = AppState.twIndices + AppState.usIndices
        val idx = st.indices.mapNotNull { sym -> allIdx.firstOrNull { it.symbol == sym && it.value > 0 } }
            .joinToString("  ") { (WidgetStyle.INDEX_NAMES[it.symbol] ?: it.name) + " " + Fmt.num(it.value, 0) + " " + Fmt.pct(it.changePct) }
        val quotes = if (st.source == 1) AppState.quotes.toList()
            else AppState.holdings.map { it.symbol }.distinct().mapNotNull { AppState.quote(it) }
        val sorted = if (st.sort == 1) quotes.sortedByDescending { kotlin.math.abs(it.changePct) } else quotes
        val rows = sorted.take(if (st.layout == 3) 0 else st.rows).map { q ->
            val sh = AppState.holdings.filter { it.symbol == q.symbol }.sumOf { it.shares }
            val d = (q.price - q.prevClose) * sh * AppState.fxFor(q.market)
            val vol = (AppState.dayStats[q.symbol]?.volume ?: com.claudeetf.app.data.KlineAnalysis.volumeOn(q))?.takeIf { it > 0 }
                ?.let { v -> com.claudeetf.app.ui.screens.volumeText(v, q.market) }
            val e = com.claudeetf.app.data.KlineAnalysis.latestDaily[q.symbol]?.entry
            WRow(
                q.symbol, q.name.ifBlank { q.symbol },
                if (q.price > 0) Fmt.price(q.price) else "—", Fmt.signed(q.change, 2), Fmt.signedPct(q.changePct), q.change,
                if (sh > 0 && q.prevClose > 0) (if (hide) "＊＊＊" else Fmt.signed(d)) else null,
                vol, e?.label, e?.level ?: 0,
                com.claudeetf.app.data.InvestAnalysis.results[q.symbol]?.band?.zoneName,
            )
        }
        val time = if (!st.showTime) "" else "更新 " + ZonedDateTime.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
        return WidgetModel(label, big, todayLine, if (st.header == 2) pct else today, idx, rows, time)
    }

    /** 依勾選項目組出「數值」與「附加」文字 */
    fun valueText(st: WidgetStyle, r: WRow, withPrice: Boolean = true): String = buildList {
        if (withPrice && st.has("price")) add(r.price)
        if (st.has("change")) add(r.change)
        if (st.has("pct")) add(r.pct)
        if (st.has("pnl")) r.pnl?.let { add(it) }
    }.joinToString("  ")

    fun extraText(st: WidgetStyle, r: WRow): String = buildList {
        if (st.has("entry")) r.entry?.let { add(it) }
        if (st.has("band")) r.band?.let { add("光譜 $it") }
        if (st.has("volume")) r.volume?.let { add("量 $it") }
    }.joinToString(" · ")

    /** 進場建議的顏色（可進場紅／綠依配色、觀望黃、不宜灰） */
    fun entryColor(level: Int, muted: Int): Int = when {
        level > 0 -> trendColor(1.0, muted)
        level == 0 -> 0xFFE6C34A.toInt()
        else -> muted
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
        val headerOn = st.header != 3
        v.setViewVisibility(R.id.w_label, if (headerOn) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.w_total, if (headerOn) View.VISIBLE else View.GONE)
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
        when (st.layout) {
            // 方格：兩欄，每格名稱／大字價格／漲跌／附加
            2 -> m.rows.chunked(2).forEach { pair ->
                val gr = RemoteViews(context.packageName, R.layout.widget_grid_row)
                gr.removeAllViews(R.id.w_c1); gr.removeAllViews(R.id.w_c2)
                pair.forEachIndexed { i, r ->
                    val cell = RemoteViews(context.packageName, R.layout.widget_cell)
                    cell.setInt(R.id.w_cbg, "setColorFilter", c.cell)
                    cell.setTextViewText(R.id.w_cn, r.name + " " + r.symbol); cell.setTextColor(R.id.w_cn, c.text)
                    cell.setTextViewText(R.id.w_cp, r.price); cell.setTextColor(R.id.w_cp, trendColor(r.sign, c.text))
                    cell.setTextViewText(R.id.w_cc, valueText(st, r, withPrice = false)); cell.setTextColor(R.id.w_cc, trendColor(r.sign, c.muted))
                    val x = extraText(st, r)
                    cell.setViewVisibility(R.id.w_cx, if (x.isBlank()) View.GONE else View.VISIBLE)
                    cell.setTextViewText(R.id.w_cx, x); cell.setTextColor(R.id.w_cx, if (st.has("entry") && r.entry != null) entryColor(r.entryLevel, c.muted) else c.muted)
                    cell.setTextViewTextSize(R.id.w_cn, TypedValue.COMPLEX_UNIT_SP, 11f * k)
                    cell.setTextViewTextSize(R.id.w_cp, TypedValue.COMPLEX_UNIT_SP, 20f * k)
                    cell.setTextViewTextSize(R.id.w_cc, TypedValue.COMPLEX_UNIT_SP, 11f * k)
                    cell.setTextViewTextSize(R.id.w_cx, TypedValue.COMPLEX_UNIT_SP, 10f * k)
                    gr.addView(if (i == 0) R.id.w_c1 else R.id.w_c2, cell)
                }
                v.addView(R.id.w_list, gr)
            }
            // 大字條列：名稱＋大字價格，下排附加資訊＋漲跌
            1 -> m.rows.forEach { r ->
                val b = RemoteViews(context.packageName, R.layout.widget_row_big)
                b.setTextViewText(R.id.w_bn, r.name + " " + r.symbol); b.setTextColor(R.id.w_bn, c.text)
                b.setTextViewText(R.id.w_bp, if (st.has("price")) r.price else ""); b.setTextColor(R.id.w_bp, trendColor(r.sign, c.text))
                b.setTextViewText(R.id.w_bc, valueText(st, r, withPrice = false)); b.setTextColor(R.id.w_bc, trendColor(r.sign, c.muted))
                b.setTextViewText(R.id.w_bx, extraText(st, r))
                b.setTextColor(R.id.w_bx, if (st.has("entry") && r.entry != null) entryColor(r.entryLevel, c.muted) else c.muted)
                b.setTextViewTextSize(R.id.w_bn, TypedValue.COMPLEX_UNIT_SP, 14f * k)
                b.setTextViewTextSize(R.id.w_bp, TypedValue.COMPLEX_UNIT_SP, 18f * k)
                b.setTextViewTextSize(R.id.w_bc, TypedValue.COMPLEX_UNIT_SP, 12f * k)
                b.setTextViewTextSize(R.id.w_bx, TypedValue.COMPLEX_UNIT_SP, 11f * k)
                v.addView(R.id.w_list, b)
            }
            // 清單：名稱、附加、數值同一行
            else -> m.rows.forEach { r ->
                val row = RemoteViews(context.packageName, R.layout.widget_row)
                row.setTextViewText(R.id.w_rn, r.symbol + " " + r.name.take(6)); row.setTextColor(R.id.w_rn, c.text)
                val x = extraText(st, r)
                row.setViewVisibility(R.id.w_rx, if (x.isBlank()) View.GONE else View.VISIBLE)
                row.setTextViewText(R.id.w_rx, x); row.setTextColor(R.id.w_rx, if (st.has("entry") && r.entry != null) entryColor(r.entryLevel, c.muted) else c.muted)
                row.setTextViewText(R.id.w_rv, valueText(st, r)); row.setTextColor(R.id.w_rv, trendColor(r.sign, c.muted))
                row.setTextViewTextSize(R.id.w_rn, TypedValue.COMPLEX_UNIT_SP, 12f * k)
                row.setTextViewTextSize(R.id.w_rx, TypedValue.COMPLEX_UNIT_SP, 10f * k)
                row.setTextViewTextSize(R.id.w_rv, TypedValue.COMPLEX_UNIT_SP, 12f * k)
                v.addView(R.id.w_list, row)
            }
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
