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
import kotlinx.coroutines.launch
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
 * 小工具樣式（v3：自由編輯）。
 * 外觀：theme 0 跟隨 App、1 深色、2 淺色、3 透明；alpha 背景不透明度；radius 0 方角、1 圓角、2 大圓角；text 整體字級 0 小、1 中、2 大。
 * 版面：header 0 總資產、1 持股市值、2 今日損益、3 不顯示；layout 0 清單、2 方格（兩欄）、3 只顯示總覽。
 * 每檔項目（items 順序、hidden 隱藏、zone 0 左／1 右、newline 從新的一行開始）＋單一項目樣式（字級、粗細、顏色）。
 * 間距：pad 外框內距、rowGap 檔與檔間距（行距）、rowPad 每檔上下內距（dp）。
 */
data class WidgetStyle(
    val theme: Int = 0, val alpha: Int = 92, val radius: Int = 2, val text: Int = 1,
    val header: Int = 0, val showToday: Boolean = true,
    val source: Int = 0, val rows: Int = 5, val sort: Int = 0, val showTime: Boolean = true,
    val layout: Int = 0,
    val indices: List<String> = listOf("^TWII", "^IXIC"),
    val items: List<String> = ITEMS.keys.toList(),
    val hidden: Set<String> = setOf("change", "pnl", "volume", "entry", "band"),
    val zone: Map<String, Int> = DEFAULT_ZONE,
    val newline: Set<String> = emptySet(),
    val styles: Map<String, com.claudeetf.app.data.ItemStyle> = emptyMap(),
    val pad: Int = 14, val rowGap: Int = 2, val rowPad: Int = 0,
    val headerHidden: Set<String> = emptySet(),
) {
    fun shows(id: String) = id !in hidden
    fun zoneOf(id: String) = zone[id] ?: DEFAULT_ZONE[id] ?: 0
    fun style(key: String) = styles[key] ?: com.claudeetf.app.data.ItemStyle()
    fun withStyle(key: String, st: com.claudeetf.app.data.ItemStyle) = copy(styles = if (st.isDefault) styles - key else styles + (key to st))

    fun toJson(): String = JSONObject().put("v", 3).put("theme", theme).put("alpha", alpha).put("radius", radius).put("text", text)
        .put("header", header).put("showToday", showToday).put("source", source)
        .put("rows", rows).put("sort", sort).put("showTime", showTime).put("layout", layout)
        .put("indices", org.json.JSONArray(indices)).put("items", org.json.JSONArray(items)).put("hidden", org.json.JSONArray(hidden.toList()))
        .put("zone", JSONObject(zone as Map<*, *>)).put("newline", org.json.JSONArray(newline.toList()))
        .put("styles", JSONObject().apply { styles.filterValues { !it.isDefault }.forEach { (k, v) -> put(k, v.toJson()) } })
        .put("pad", pad).put("rowGap", rowGap).put("rowPad", rowPad).put("headerHidden", org.json.JSONArray(headerHidden.toList()))
        .toString()

    /** 套用版型預設組合（之後仍可逐項調整） */
    fun preset(kind: Int): WidgetStyle = when (kind) {
        // 大字條列：名稱粗體、價格放大；第二行附加資訊與漲跌
        1 -> copy(layout = 0, hidden = setOf("change", "pnl", "volume", "band"),
            zone = DEFAULT_ZONE, newline = setOf("entry", "pct"),
            styles = mapOf("r:name" to com.claudeetf.app.data.ItemStyle(size = 115, weight = 700), "r:price" to com.claudeetf.app.data.ItemStyle(size = 145, weight = 700)),
            rowGap = 6)
        // 方格：全部靠左；名稱＋代號、大字價格、漲跌、附加資訊各一行
        2 -> copy(layout = 2, hidden = setOf("pnl", "volume", "band"),
            zone = ITEMS.keys.associateWith { 0 }, newline = setOf("price", "change", "entry"),
            styles = mapOf("r:code" to com.claudeetf.app.data.ItemStyle(size = 85), "r:price" to com.claudeetf.app.data.ItemStyle(size = 170, weight = 700)),
            rowGap = 4)
        3 -> copy(layout = 3)
        else -> copy(layout = 0, hidden = setOf("change", "pnl", "volume", "entry", "band"), zone = DEFAULT_ZONE, newline = emptySet(),
            styles = styles.filterKeys { it.startsWith("h:") }, rowGap = 2)
    }

    companion object {
        /** 每檔可用項目（預設順序） */
        val ITEMS = linkedMapOf("code" to "代號", "name" to "名稱", "price" to "價格", "change" to "漲跌", "pct" to "漲跌幅",
            "pnl" to "今日損益", "volume" to "成交量", "entry" to "進場建議", "band" to "光譜位階")
        val HEADER = linkedMapOf("label" to "標題", "total" to "主數字", "today" to "今日損益列", "index" to "指數列", "time" to "更新時間")
        val DEFAULT_ZONE = mapOf("price" to 1, "change" to 1, "pct" to 1, "pnl" to 1)
        val INDEX_NAMES = linkedMapOf("^TWII" to "加權", "^TWOII" to "櫃買", "^DJI" to "道瓊", "^GSPC" to "S&P",
            "^IXIC" to "那指", "^SOX" to "費半")
        /** 數字類項目用等寬字 */
        val MONO = setOf("code", "price", "change", "pct", "pnl", "volume")

        fun fromJson(s: String?): WidgetStyle = runCatching {
            val o = JSONObject(s ?: return WidgetStyle())
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }
            fun intMap(k: String) = o.optJSONObject(k)?.let { m -> m.keys().asSequence().associateWith { m.optInt(it) } }
            val idx = list("indices") ?: if (o.optBoolean("showIndex", true)) listOf("^TWII", "^IXIC") else emptyList()
            val base = WidgetStyle(o.optInt("theme", 0), o.optInt("alpha", 92).coerceIn(0, 100), o.optInt("radius", 2), o.optInt("text", 1),
                o.optInt("header", 0), o.optBoolean("showToday", true), o.optInt("source", 0),
                o.optInt("rows", 5).coerceIn(0, 12), o.optInt("sort", 0), o.optBoolean("showTime", true),
                o.optInt("layout", 0), idx)
            if (o.optInt("v", 1) >= 3) {
                val l = list("items")?.filter { it in ITEMS }.orEmpty()
                base.copy(
                    items = l + ITEMS.keys.filter { it !in l }, hidden = list("hidden")?.toSet() ?: base.hidden,
                    zone = intMap("zone") ?: DEFAULT_ZONE, newline = list("newline")?.toSet() ?: emptySet(),
                    styles = o.optJSONObject("styles")?.let { m -> m.keys().asSequence().associateWith { com.claudeetf.app.data.ItemStyle.fromJson(m.optJSONObject(it)) } } ?: emptyMap(),
                    pad = o.optInt("pad", 14), rowGap = o.optInt("rowGap", 2), rowPad = o.optInt("rowPad", 0),
                    headerHidden = list("headerHidden")?.toSet() ?: emptySet(),
                )
            } else {
                // v1／v2 → v3：舊的「每檔項目」複選轉為隱藏清單，舊版型轉為預設組合
                val fields = list("fields")?.toSet() ?: when (o.optInt("rowMode", 0)) { 1 -> setOf("pct"); 2 -> setOf("pnl", "pct"); else -> setOf("price", "pct") }
                val p = base.preset(if (base.layout == 1) 1 else base.layout)
                p.copy(hidden = ITEMS.keys.filter { it !in fields && it != "code" && it != "name" }.toSet())
            }
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
    /** 個股標籤快取：進場建議（標籤＋等級）、光譜位階 */
    data class Tag(val entry: String?, val level: Int, val band: String?)
    private val tags = java.util.concurrent.ConcurrentHashMap<String, Tag>()
    @Volatile private var tagsDirty = false
    @Volatile private var tagsLoaded = false
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun loadTags(context: Context) {
        if (tagsLoaded) return
        tagsLoaded = true
        runCatching {
            val o = JSONObject(context.getSharedPreferences("widget_tags", Context.MODE_PRIVATE).getString("t", "{}") ?: "{}")
            o.keys().forEach { k ->
                val t = o.getJSONObject(k)
                tags.putIfAbsent(k, Tag(t.optString("e").ifBlank { null }, t.optInt("l"), t.optString("b").ifBlank { null }))
            }
        }
    }

    private fun saveTags(context: Context) {
        if (!tagsDirty) return
        tagsDirty = false
        val o = JSONObject()
        tags.forEach { (k, t) -> o.put(k, JSONObject().put("e", t.entry ?: "").put("l", t.level).put("b", t.band ?: "")) }
        context.getSharedPreferences("widget_tags", Context.MODE_PRIVATE).edit().putString("t", o.toString()).apply()
    }

    /** 小工具要顯示的個股（依來源、排序、檔數） */
    fun rowQuotes(st: WidgetStyle): List<com.claudeetf.app.data.Quote> {
        val quotes = if (st.source == 1) AppState.quotes.toList()
            else AppState.holdings.map { it.symbol }.distinct().mapNotNull { AppState.quote(it) }
        val sorted = if (st.sort == 1) quotes.sortedByDescending { kotlin.math.abs(it.changePct) } else quotes
        return sorted.take(if (st.layout == 3) 0 else st.rows)
    }

    /**
     * 有開「進場建議／光譜位階」但個股尚未分析過時，在背景自動分析（每檔 10 分鐘最多一次），
     * 完成後再刷新小工具；不必先打開個股頁。
     */
    fun ensureAnalysis(context: Context, st: WidgetStyle) {
        val wantE = st.shows("entry"); val wantB = st.shows("band")
        if (!wantE && !wantB) return
        val now = System.currentTimeMillis()
        val need = rowQuotes(st).filter { q ->
            ((wantE && com.claudeetf.app.data.KlineAnalysis.latestDaily[q.symbol] == null) ||
                (wantB && com.claudeetf.app.data.InvestAnalysis.results[q.symbol]?.band == null)) &&
                now - (pending[q.symbol] ?: 0L) > 10 * 60_000L
        }
        if (need.isEmpty()) return
        need.forEach { pending[it.symbol] = now }
        val app = context.applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            need.forEach { q ->
                if (wantE && com.claudeetf.app.data.KlineAnalysis.latestDaily[q.symbol] == null) runCatching { com.claudeetf.app.data.KlineAnalysis.load(q, false) }
                if (wantB && com.claudeetf.app.data.InvestAnalysis.results[q.symbol]?.band == null) runCatching { com.claudeetf.app.data.InvestAnalysis.analyze(q) }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { update(app) }
        }
    }

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
        val rows = rowQuotes(st).map { q ->
            val sh = AppState.holdings.filter { it.symbol == q.symbol }.sumOf { it.shares }
            val d = (q.price - q.prevClose) * sh * AppState.fxFor(q.market)
            val vol = (AppState.dayStats[q.symbol]?.volume ?: com.claudeetf.app.data.KlineAnalysis.volumeOn(q))?.takeIf { it > 0 }
                ?.let { v -> com.claudeetf.app.ui.screens.volumeText(v, q.market) }
            val e = com.claudeetf.app.data.KlineAnalysis.latestDaily[q.symbol]?.entry
            val bandNow = com.claudeetf.app.data.InvestAnalysis.results[q.symbol]?.band?.zoneName
            // 有新結果就記下來（App 重開、背景程序重建後仍能顯示上次的標籤）
            val old = tags[q.symbol]
            val tag = Tag(e?.label ?: old?.entry, e?.level ?: old?.level ?: 0, bandNow ?: old?.band)
            if (tag != old && (e != null || bandNow != null)) { tags[q.symbol] = tag; tagsDirty = true }
            WRow(
                q.symbol, q.name.ifBlank { q.symbol },
                if (q.price > 0) Fmt.price(q.price) else "—", Fmt.signed(q.change, 2), Fmt.signedPct(q.changePct), q.change,
                if (sh > 0 && q.prevClose > 0) (if (hide) "＊＊＊" else Fmt.signed(d)) else null,
                vol, tag.entry, tag.level, tag.band,
            )
        }
        val time = if (!st.showTime) "" else "更新 " + ZonedDateTime.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("M/d HH:mm"))
        return WidgetModel(label, big, todayLine, if (st.header == 2) pct else today, idx, rows, time)
    }

    /** 每檔項目的文字；沒有資料回傳 null（不佔位置） */
    fun itemText(id: String, r: WRow): String? = when (id) {
        "code" -> r.symbol
        "name" -> r.name
        "price" -> r.price
        "change" -> r.change
        "pct" -> r.pct
        "pnl" -> r.pnl?.let { "今日 $it" }
        "volume" -> r.volume?.let { "量 $it" }
        "entry" -> r.entry
        "band" -> r.band
        else -> null
    }

    /** 項目基本字級（sp，乘上整體字級與單一項目字級） */
    fun baseSize(id: String, layout: Int): Float = when (id) {
        "price" -> if (layout == 2) 12f else 13f
        "code", "name" -> 12f
        "volume", "entry", "band" -> 10f
        else -> 12f
    }

    /** 項目顏色：自訂色 ＞ 漲跌色 ＞ 預設（價格類依漲跌、進場依等級、其餘依主題） */
    fun itemColor(st: WidgetStyle, key: String, id: String, r: WRow, c: WidgetColors): Int {
        val ist = st.style(key)
        if (ist.color != 0L) return ist.color.toInt()
        if (ist.trend) return trendColor(r.sign, c.text)
        return when (id) {
            "price", "change", "pct", "pnl" -> trendColor(r.sign, if (id == "price") c.text else c.muted)
            "entry" -> entryColor(r.entryLevel, c.muted)
            "volume", "band" -> c.muted
            else -> c.text
        }
    }

    /** 單一項目的樣式文字（字級、粗細、顏色、等寬字） */
    fun itemSpan(st: WidgetStyle, id: String, r: WRow, c: WidgetColors, k: Float): CharSequence? {
        val t = itemText(id, r) ?: return null
        val sb = android.text.SpannableString(t)
        val ist = st.style("r:$id")
        val sz = (baseSize(id, st.layout) * k * ist.size / 100f).toInt().coerceAtLeast(6)
        sb.setSpan(android.text.style.AbsoluteSizeSpan(sz, true), 0, sb.length, 0)
        sb.setSpan(android.text.style.ForegroundColorSpan(itemColor(st, "r:$id", id, r, c)), 0, sb.length, 0)
        val bold = if (ist.weight > 0) ist.weight >= 600 else id == "code"
        if (bold) sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, 0)
        if (id in WidgetStyle.MONO) sb.setSpan(android.text.style.TypefaceSpan("monospace"), 0, sb.length, 0)
        return sb
    }

    /** 依項目順序與「換行」分成多行（每行一串項目） */
    fun zoneLines(st: WidgetStyle, r: WRow, zoneSel: Int?): List<List<String>> {
        val lines = ArrayList<MutableList<String>>()
        st.items.filter { st.shows(it) && (zoneSel == null || st.zoneOf(it) == zoneSel) && itemText(it, r) != null }.forEach { id ->
            if (lines.isEmpty() || id in st.newline) lines += mutableListOf(id) else lines.last() += id
        }
        return lines
    }

    /**
     * 把某一區（左／右／方格整格）的項目逐一加到容器：每個項目一個 TextView，
     * 可各自設定行距（上方間距）、內距、背景色、欄寬與對齊。
     */
    private fun fillZone(context: Context, parent: RemoteViews, parentId: Int, st: WidgetStyle, r: WRow, zoneSel: Int?, c: WidgetColors, k: Float, end: Boolean) {
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        parent.removeAllViews(parentId)
        parent.setInt(parentId, "setGravity", if (end) android.view.Gravity.END else android.view.Gravity.START)
        zoneLines(st, r, zoneSel).forEach { line ->
            val lv = RemoteViews(context.packageName, R.layout.widget_line)
            lv.removeAllViews(R.id.w_line)
            val gap = line.maxOf { st.style("r:$it").gap }
            lv.setViewPadding(R.id.w_line, 0, px(gap), 0, 0)
            line.forEachIndexed { i, id ->
                if (i > 0) lv.addView(R.id.w_line, RemoteViews(context.packageName, R.layout.widget_space))
                val ist = st.style("r:$id")
                val iv = RemoteViews(context.packageName, R.layout.widget_item)
                iv.setTextViewText(R.id.w_it, itemSpan(st, id, r, c, k))
                val p = px(ist.pad)
                iv.setViewPadding(R.id.w_it, p, p / 2, p, p / 2)
                if (ist.bg != 0L) iv.setInt(R.id.w_it, "setBackgroundColor", ist.bg.toInt())
                if (ist.width > 0) iv.setInt(R.id.w_it, "setMinWidth", px(ist.width))
                iv.setInt(R.id.w_it, "setGravity", android.view.Gravity.CENTER_VERTICAL or when (ist.align) { 1 -> android.view.Gravity.CENTER_HORIZONTAL; 2 -> android.view.Gravity.END; else -> android.view.Gravity.START })
                lv.addView(R.id.w_line, iv)
            }
            parent.addView(parentId, lv)
        }
    }

    /** 標題區單一項目（標題、主數字、今日列、指數列、時間）的樣式文字 */
    fun headerText(st: WidgetStyle, id: String, text: String, base: Float, defColor: Int, sign: Double, c: WidgetColors, k: Float, defBold: Boolean = false): CharSequence {
        val ist = st.style("h:$id")
        val sb = android.text.SpannableString(text)
        val color = when { ist.color != 0L -> ist.color.toInt(); ist.trend -> trendColor(sign, c.text); else -> defColor }
        sb.setSpan(android.text.style.AbsoluteSizeSpan((base * k * ist.size / 100f).toInt().coerceAtLeast(6), true), 0, sb.length, 0)
        sb.setSpan(android.text.style.ForegroundColorSpan(color), 0, sb.length, 0)
        if (if (ist.weight > 0) ist.weight >= 600 else defBold) sb.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, sb.length, 0)
        if (id != "label") sb.setSpan(android.text.style.TypefaceSpan("monospace"), 0, sb.length, 0)
        return sb
    }

    /** 進場建議的顏色（可進場紅／綠依配色、觀望黃、不宜灰） */
    fun entryColor(level: Int, muted: Int): Int = when {
        level > 0 -> trendColor(1.0, muted)
        level == 0 -> 0xFFE6C34A.toInt()
        else -> muted
    }

    fun views(context: Context): RemoteViews {
        val st = AppState.widgetStyle
        val c = colors(st)
        loadTags(context)
        val m = model(st)
        saveTags(context)
        val v = RemoteViews(context.packageName, R.layout.widget_assets)
        // 背景：圓角圖層上色＋透明度；外框內距
        v.setImageViewResource(R.id.w_bg, when (st.radius) { 0 -> R.drawable.widget_shape_0; 1 -> R.drawable.widget_shape_12; else -> R.drawable.widget_shape_24 })
        v.setInt(R.id.w_bg, "setColorFilter", c.bg)
        v.setInt(R.id.w_bg, "setImageAlpha", bgAlpha(st))
        val dp = context.resources.displayMetrics.density
        val padPx = (st.pad.coerceIn(0, 32) * dp).toInt()
        v.setViewPadding(R.id.w_content, padPx, padPx, padPx, padPx)
        val k = when (st.text) { 0 -> 0.85f; 2 -> 1.2f; else -> 1f }
        fun head(id: Int, key: String, text: String, base: Float, def: Int, sign: Double, bold: Boolean = false) {
            val on = text.isNotBlank() && key !in st.headerHidden
            v.setViewVisibility(id, if (on) View.VISIBLE else View.GONE)
            if (on) {
                v.setTextViewText(id, headerText(st, key, text, base, def, sign, c, k, bold))
                val ist = st.style("h:$key")
                val p = (ist.pad * dp).toInt()
                v.setViewPadding(id, p, p / 2 + (ist.gap * dp).toInt(), p, p / 2)
                v.setInt(id, "setBackgroundColor", ist.bg.toInt())
                v.setInt(id, "setMinWidth", (ist.width * dp).toInt())
                v.setInt(id, "setGravity", when (ist.align) { 1 -> android.view.Gravity.CENTER_HORIZONTAL; 2 -> android.view.Gravity.END; else -> android.view.Gravity.START })
            }
        }
        head(R.id.w_label, "label", m.label, 12f, c.muted, m.todaySign)
        head(R.id.w_total, "total", m.total, 24f, if (st.header == 2) trendColor(m.todaySign, c.text) else c.text, m.todaySign, true)
        head(R.id.w_today, "today", m.today, 13f, trendColor(m.todaySign, c.muted), m.todaySign)
        head(R.id.w_index, "index", m.index, 11f, c.muted, 0.0)
        head(R.id.w_time, "time", m.time, 10f, c.muted, 0.0)
        v.setViewVisibility(R.id.w_div, if (m.rows.isEmpty() || (st.header == 3 && m.index.isBlank())) View.GONE else View.VISIBLE)
        v.setInt(R.id.w_div, "setBackgroundColor", c.divider)
        v.removeAllViews(R.id.w_list)
        val gapPx = (st.rowGap.coerceIn(0, 24) * dp).toInt()
        val rowPadPx = (st.rowPad.coerceIn(0, 24) * dp).toInt()
        if (st.layout == 2) {
            // 方格：兩欄，每格依項目順序與換行排列
            m.rows.chunked(2).forEach { pair ->
                val gr = RemoteViews(context.packageName, R.layout.widget_grid_row)
                gr.removeAllViews(R.id.w_c1); gr.removeAllViews(R.id.w_c2)
                pair.forEachIndexed { i, r ->
                    val cell = RemoteViews(context.packageName, R.layout.widget_cell)
                    cell.setInt(R.id.w_cbg, "setColorFilter", c.cell)
                    fillZone(context, cell, R.id.w_ct, st, r, null, c, k, false)
                    val p8 = (8 * dp).toInt() + rowPadPx
                    cell.setViewPadding(R.id.w_ct, p8, p8, p8, p8)
                    gr.addView(if (i == 0) R.id.w_c1 else R.id.w_c2, cell)
                }
                gr.setViewPadding(R.id.w_c1, 0, gapPx, 0, 0); gr.setViewPadding(R.id.w_c2, 0, gapPx, 0, 0)
                v.addView(R.id.w_list, gr)
            }
        } else m.rows.forEach { r ->
            // 清單：左區、右區各自依順序與換行排列
            val row = RemoteViews(context.packageName, R.layout.widget_row)
            fillZone(context, row, R.id.w_rl, st, r, 0, c, k, false)
            val hasRight = zoneLines(st, r, 1).isNotEmpty()
            row.setViewVisibility(R.id.w_rr, if (hasRight) View.VISIBLE else View.GONE)
            if (hasRight) fillZone(context, row, R.id.w_rr, st, r, 1, c, k, true)
            row.setViewPadding(R.id.w_row, 0, gapPx + rowPadPx, 0, rowPadPx)
            v.addView(R.id.w_list, row)
        }
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
        ensureAnalysis(context, AppState.widgetStyle)
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
