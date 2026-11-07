package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.Quote
import com.claudeetf.app.data.WatchStyle
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.ChangePill
import com.claudeetf.app.ui.HGap
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.MarketTag
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.Sparkline
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt

/** 依版型縮放字級 */
fun WatchStyle.sp(base: Int): TextUnit = (base * when (density) { 0 -> 0.85f; 2 -> 1.2f; else -> 1f }).sp

/** 預覽可點選：點了回報目標；選中的加框 */
private class Tap(val sel: String?, val onTap: ((String) -> Unit)?)

private fun Modifier.tap(t: Tap?, id: String): Modifier =
    if (t?.onTap == null) this
    else this.clip(RoundedCornerShape(6.dp))
        .border(if (t.sel == id) 2.dp else 1.dp, if (t.sel == id) C.Primary else C.Border.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
        .clickable { t.onTap.invoke(id) }
        .padding(horizontal = 2.dp, vertical = 1.dp)

/** 名稱與代號（依格式） */
@Composable
fun WatchName(q: Quote, st: WatchStyle, base: Int, modifier: Modifier = Modifier, showCode: Boolean = false) {
    val name = q.name.ifBlank { q.symbol }
    when (st.nameMode) {
        1 -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            NumText(q.symbol, st.sp(base), C.Text, FontWeight.Bold); HGap(6.dp)
            Text(name, color = C.Text2, fontSize = st.sp(base - 2), maxLines = st.nameLines, overflow = TextOverflow.Ellipsis)
        }
        3 -> NumText(q.symbol, st.sp(base), C.Text, FontWeight.Bold, modifier)
        else -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f, fill = false), color = C.Text, fontSize = st.sp(base), maxLines = st.nameLines, overflow = TextOverflow.Ellipsis)
            if (showCode && st.nameMode == 0) { HGap(6.dp); NumText(q.symbol, st.sp(base - 2), C.Muted) }
        }
    }
}

/** 漲跌（依格式）。spread = true：金額靠左、百分比靠右 */
@Composable
fun WatchChange(q: Quote, st: WatchStyle, base: Int, modifier: Modifier = Modifier, spread: Boolean = false) {
    val c = Trend.fg(q.change)
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (spread && st.changeMode == 0) Arrangement.SpaceBetween else Arrangement.spacedBy(6.dp)) {
        if (st.changeMode != 1) NumText(Fmt.signed(q.change, 2), st.sp(base), c)
        if (st.changeMode != 2) {
            if (st.changeStyle == 1) ChangePill(q.changePct) else NumText(Fmt.pct(q.changePct), st.sp(base), c)
        }
    }
}

/** 附加資訊項目 */
@Composable
fun WatchExtraItem(q: Quote, id: String, st: WatchStyle, list: Boolean = false) {
    val small = st.sp(10)
    when (id) {
        "keys" -> AppState.keyPrices[q.symbol]?.let { k ->
            val (pos, c) = KeyLevelsUi.position(q.price, k)
            Text("三關：$pos", color = c, fontSize = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } ?: Text("三關：—", color = C.Muted, fontSize = small)
        "entry" -> EntryTagFor(q)
        "limit" -> if (q.market == Market.TW) {
            com.claudeetf.app.util.TwSession.limitState(q.price, q.prevClose, q.isEtf || q.symbol.startsWith("00"))?.let { s ->
                Text(s, color = Trend.fg(if (s == "漲停") 1.0 else -1.0), fontSize = st.sp(11), fontWeight = FontWeight.Bold)
            }
        }
        "market" -> Row(verticalAlignment = Alignment.CenterVertically) {
            MarketTag(q.market)
            if (list && (st.nameMode == 0 || st.nameMode == 2)) { HGap(6.dp); NumText(q.symbol, st.sp(12), C.Muted) }
        }
        "source" -> Text(sourceText(q.source, q.updatedAt), color = C.Muted, fontSize = small, maxLines = 1)
        "volume" -> AppState.dayStats[q.symbol]?.volume?.let { v ->
            Text("量 " + volumeText(v, q.market), color = C.Text2, fontSize = small, maxLines = 1)
        }
        "amp" -> AppState.dayStats[q.symbol]?.let { d ->
            if (d.high != null && d.low != null && q.prevClose > 0)
                Text("振幅 " + Fmt.num((d.high - d.low) / q.prevClose * 100, 2) + "%", color = C.Text2, fontSize = small, maxLines = 1)
        }
        "ext" -> AppState.extended[q.symbol]?.let { e ->
            Text(e.label + " " + Fmt.price(e.price) + " " + Fmt.signedPct(e.changePct), color = Trend.fg(e.changePct), fontSize = small, maxLines = 1)
        }
    }
}

/** 連續的「可並排」項目合成一列 */
private fun groups(ids: List<String>, inline: Set<String>): List<List<String>> {
    val out = ArrayList<MutableList<String>>()
    for (id in ids) {
        val last = out.lastOrNull()
        if (id in inline && last != null && last.last() in inline) last += id else out += mutableListOf(id)
    }
    return out
}

// ============================== 卡片 ==============================

@Composable
fun WatchCardBody(q: Quote, st: WatchStyle, flash: Int, modifier: Modifier, sel: String? = null, onTap: ((String) -> Unit)? = null) {
    val t = Tap(sel, onTap)
    val color = Trend.fg(q.change)
    val bg = when {
        flash != 0 -> Trend.fg(flash.toDouble()).copy(alpha = 0.18f)
        st.tintBg && q.change != 0.0 -> color.copy(alpha = 0.10f)
        else -> C.Card
    }
    val pad = when (st.density) { 0 -> 7.dp; 2 -> 12.dp; else -> 10.dp }
    val priceAlign = when (st.priceAlign) { 0 -> Alignment.Start; 2 -> Alignment.End; else -> Alignment.CenterHorizontally }
    val extraAlign = when (st.tagAlign) { 1 -> Alignment.CenterHorizontally; 2 -> Alignment.End; else -> Alignment.Start }
    val small = st.cardCols == 3
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(pad), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth().tap(t, "name"), verticalAlignment = Alignment.CenterVertically) {
            WatchName(q, st, if (small) 12 else 13, Modifier.weight(1f))
            if (st.tagPos == 1) EntryTagFor(q)
            else if (st.nameMode == 0 && !small) NumText(q.symbol, st.sp(11), C.Muted)
        }
        Row(Modifier.align(priceAlign).tap(t, "price"), verticalAlignment = Alignment.CenterVertically) {
            NumText(if (q.price > 0) Fmt.price(q.price) else "—", st.sp(if (small) 19 else 26), Trend.price(q.change), weight = FontWeight.Bold)
            if (st.tagPos == 2) { HGap(6.dp); EntryTagFor(q) }
        }
        val ids = st.items.filter { st.shows(it) && !(it == "entry" && st.tagPos != 0) }
        groups(ids, setOf("market", "source", "volume", "amp")).forEach { g ->
            when (g.first()) {
                "spark" -> if (q.spark.size >= 2) Sparkline(q.spark, color, Modifier.fillMaxWidth().height(if (st.density == 0) 20.dp else 26.dp).tap(t, "spark"), fill = true)
                "cols" -> {
                    // 行情頁「欄位／第二欄」選的資訊；走勢與卡片走勢圖重複不再顯示
                    val cols = listOf(AppState.watchColumn, AppState.watchColumn2).filter { it.isNotEmpty() && it != "spark" }.distinct()
                    if (cols.isNotEmpty()) Row(Modifier.align(extraAlign).tap(t, "cols"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cols.forEach { WatchCell(q, it) }
                    } else if (onTap != null) Text("欄位：未選（行情頁設定）", Modifier.tap(t, "cols"), color = C.Muted, fontSize = st.sp(10))
                }
                "change" -> WatchChange(q, st, if (small) 11 else 12,
                    (if (st.changeMode == 0 && st.tagAlign == 0) Modifier.fillMaxWidth() else Modifier.align(extraAlign)).tap(t, "change"), spread = st.tagAlign == 0)
                else -> Row(Modifier.align(extraAlign), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    g.forEach { id -> Box(Modifier.tap(t, id)) { WatchExtraItem(q, id, st) } }
                }
            }
        }
    }
}

// ============================== 清單 ==============================

/**
 * 清單版面（兩層）：
 *  上層：名稱／代號／報價時間（左，占滿剩餘寬度）＋ 走勢圖（有選時）＋ 價格與漲跌（右）
 *  下層：其餘資訊（欄位、第二欄、三關、進場建議、成交量…）平均分配成表格，每列最多 3 格
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WatchRowBody(q: Quote, st: WatchStyle, modifier: Modifier, cells: Boolean, sel: String? = null, onTap: ((String) -> Unit)? = null) {
    val t = Tap(sel, onTap)
    val vpad = when (st.density) { 0 -> 6.dp; 2 -> 13.dp; else -> 10.dp }
    val c = Trend.fg(q.change)
    // 欄位、第二欄（行情頁選的）
    val cols = if (cells) listOf(AppState.watchColumn, AppState.watchColumn2).filter { it.isNotEmpty() }.distinct() else emptyList()
    val sparkInline = "spark" in cols || st.listShows("spark")
    val tableCols = cols.filter { it != "spark" }
    // 清單項目：市場標籤、報價時間留在名稱下方；其餘進表格（與欄位重複的不再顯示）
    Column(modifier.fillMaxWidth().padding(vertical = vpad, horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (st.density == 0) 2.dp else 4.dp)) {
                Row(Modifier.tap(t, "name"), verticalAlignment = Alignment.CenterVertically) {
                    WatchName(q, st, 15, Modifier.weight(1f, fill = false), showCode = !st.listShows("market"))
                    if (st.tagPos == 1) { HGap(6.dp); EntryTagFor(q) }
                }
                // 第二行：市場標籤、報價時間
                val entryHere = st.listShows("entry") && st.tagPos == 0 && AppState.showCardEntry
                if (st.listShows("market") || st.listShows("source")) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (st.listShows("market")) { Box(Modifier.tap(t, "market")) { WatchExtraItem(q, "market", st, list = true) }; HGap(8.dp) }
                    if (st.listShows("source")) Box(Modifier.tap(t, "source")) { WatchExtraItem(q, "source", st, list = true) }
                }
                // 第三行：進場建議標籤（K 棒訊號），在 TW／US 標籤的下一行
                if (entryHere) Row(Modifier.tap(t, "entry").padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    EntryTagFor(q)
                    // 標籤後接 K 棒說明：最新一根的形態（沒有形態時用建議理由）＋ 多空強度
                    com.claudeetf.app.data.KlineAnalysis.latestDaily[q.symbol]?.let { r ->
                        val last = r.candles.lastIndex
                        val pats = r.latest.filter { it.idx == last }.distinctBy { it.pat }.joinToString("、") { it.pat.cn }
                        val dc = when { r.dir.bullish -> Trend.up; r.dir.bearish -> Trend.down; else -> Trend.flat }
                        HGap(8.dp)
                        Text(
                            (if (pats.isNotEmpty()) pats else r.entry.reason.substringBefore("，").substringBefore("。")) + " · " + r.dir.label + " " + r.strength,
                            color = dc, fontSize = st.sp(11), maxLines = 1,
                            overflow = if (AppState.marqueeDesc) TextOverflow.Clip else TextOverflow.Ellipsis,
                            // 文字過長時以跑馬燈捲動（短的不動）
                            modifier = Modifier.weight(1f, fill = false).then(
                                if (AppState.marqueeDesc) Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1500, initialDelayMillis = 1200, velocity = 36.dp)
                                else Modifier),
                        )
                    }
                }
            }
            if (sparkInline && q.spark.size >= 2) {
                HGap(8.dp)
                Box(Modifier.tap(t, "spark")) { Sparkline(q.spark, c, Modifier.width(72.dp).height(28.dp), fill = true) }
            }
            HGap(10.dp)
            Column(Modifier.widthIn(min = 88.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.tap(t, "price"), verticalAlignment = Alignment.CenterVertically) {
                    if (st.tagPos == 2) { EntryTagFor(q); HGap(6.dp) }
                    NumText(if (q.price > 0) Fmt.price(q.price) else "—", st.sp(16), Trend.price(q.change), FontWeight.Bold)
                }
                Row(Modifier.tap(t, "change"), verticalAlignment = Alignment.CenterVertically) {
                    if (st.changeMode != 1) NumText(Fmt.signed(q.change, 2), st.sp(11), c)
                    if (st.changeMode == 0) HGap(6.dp)
                    if (st.changeMode != 2) { if (st.changeStyle == 1) ChangePill(q.changePct) else NumText(Fmt.pct(q.changePct), st.sp(11), c) }
                }
            }
        }
        // 下層：一項一行（左側名稱、右側數據橫向排開）；隱藏或沒有資料的項目整行不顯示
        val rowIds = st.listItems.filter { id -> id !in setOf("market", "source", "spark", "entry") && (st.listShows(id) || id in tableCols) } +
            tableCols.filter { it !in st.listItems }
        val lines = rowIds.mapNotNull { id -> infoLine(q, id, st)?.let { id to it } }
        if (lines.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Card2.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                lines.forEach { (id, line) ->
                    Row(Modifier.fillMaxWidth().tap(t, id), verticalAlignment = Alignment.CenterVertically) {
                        // 進場建議本身就是標籤，不另外顯示名稱
                        // 盤前／盤後依目前時段顯示名稱
                        val name = if (id == "ext") AppState.extended[q.symbol]?.label ?: "盤前盤後" else LINE_NAMES[id] ?: id
                        if (name.isNotEmpty()) Text(name, Modifier.width(52.dp), color = C.Muted, fontSize = st.sp(12), maxLines = 1)
                        // 數據太長時自動換行，不會被擠出畫面
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { line() }
                    }
                }
            }
        }
    }
}

private val LINE_NAMES = mapOf(
    "entry" to "", "keys" to "三關", "volume" to "成交量", "amp" to "振幅", "limit" to "漲跌停", "ext" to "盤前盤後",
    "div" to "除權息", "inst" to "法人", "dt" to "當沖", "credit" to "信用", "rev" to "月營收", "pe" to "本益比", "pnl" to "損益",
)

private fun line(c: @Composable RowScope.() -> Unit): @Composable RowScope.() -> Unit = c

/** 清單下層的一行數據（橫向）；沒有資料回傳 null，該行不顯示 */
private fun infoLine(q: Quote, id: String, st: WatchStyle): (@Composable RowScope.() -> Unit)? {
    val sz = st.sp(12)
    val tw = q.market == Market.TW
    val md = java.time.format.DateTimeFormatter.ofPattern("M/d")
    fun d(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("Asia/Taipei")).format(md)
    return when (id) {
        "entry" -> {
            if (!AppState.showCardEntry) null else line {
                EntryTagFor(q)
                com.claudeetf.app.data.KlineAnalysis.latestDaily[q.symbol]?.let { r ->
                    val c = when { r.dir.bullish -> Trend.up; r.dir.bearish -> Trend.down; else -> Trend.flat }
                    Text(r.dir.label + " " + r.strength, color = c, fontSize = sz, maxLines = 1)
                }
            }
        }
        "keys" -> AppState.keyPrices[q.symbol]?.let { k -> line {
            val (pos, color) = KeyLevelsUi.position(q.price, k)
            Text(pos, color = color, fontSize = sz, fontWeight = FontWeight.Bold, maxLines = 1)
            NumText("上 " + Fmt.price(k.upper), sz, KeyLevelColors.upper)
            NumText("下 " + Fmt.price(k.lower), sz, KeyLevelColors.lower)
        } }
        // 休市時沒有即時量：改用最後交易日的量（Yahoo 日 K）
        "volume" -> (AppState.dayStats[q.symbol]?.volume ?: com.claudeetf.app.data.KlineAnalysis.volumeOn(q))?.let { v -> line {
            NumText(volumeText(v, q.market), sz, C.Text)
            if (!com.claudeetf.app.data.MarketData.anyMarketLive()) Text("最後交易日", color = C.Muted, fontSize = st.sp(10))
        } }
        "amp" -> amplitude(q)?.let { a -> line { NumText(Fmt.num(a, 2) + "%", sz, C.Text) } }
        "limit" -> if (!tw) null else com.claudeetf.app.util.TwSession.limitState(q.price, q.prevClose, q.isEtf || q.symbol.startsWith("00"))?.let { s ->
            line { Text(s, color = Trend.fg(if (s == "漲停") 1.0 else -1.0), fontSize = sz, fontWeight = FontWeight.Bold) }
        }
        "ext" -> AppState.extended[q.symbol]?.let { e -> line {
            NumText(Fmt.price(e.price), sz, Trend.fg(e.changePct))
            NumText(Fmt.signedPct(e.changePct), sz, Trend.fg(e.changePct))
        } }
        "div" -> AppState.divBriefs[q.symbol]?.takeIf { it.lastTime != null || it.nextEx != null }?.let { b -> line {
            b.nextEx?.let { Text("下次除息 " + d(it), color = C.Primary, fontSize = sz) }
            b.lastAmount?.let { NumText(Fmt.num(it, 3), sz, C.Text, FontWeight.Bold) }
            b.lastTime?.let { Text("上次 " + d(it), color = C.Text2, fontSize = sz) }
        } }
        "inst" -> if (!tw) null else AppState.institutional[q.symbol]?.let { i -> line {
            val lots = i.total / 1000.0
            NumText(Fmt.signed(lots) + " 張", sz, Trend.fg(lots), FontWeight.Bold)
            Text("外資 " + Fmt.signed(i.foreign / 1000.0), color = Trend.fg(i.foreign.toDouble()), fontSize = sz, maxLines = 1)
            Text("投信 " + Fmt.signed(i.trust / 1000.0), color = Trend.fg(i.trust.toDouble()), fontSize = sz, maxLines = 1)
        } }
        "dt" -> if (!tw) null else AppState.twDaily[q.symbol]?.dayTradePct?.let { pct -> line {
            NumText(Fmt.num(pct, 1) + "%", sz, C.Text, FontWeight.Bold)
            AppState.twDaily[q.symbol]?.dayTradeLots?.let { Text("當沖 " + Fmt.num(it) + " 張", color = C.Text2, fontSize = sz) }
        } }
        "credit" -> if (!tw) null else AppState.twDaily[q.symbol]?.margin?.takeIf { it.financing != null }?.let { mg -> line {
            val chg = mg.financingPrev?.let { mg.financing!! - it }
            NumText("資 " + Fmt.num(mg.financing!!), sz, C.Text)
            chg?.let { NumText(Fmt.signed(it), sz, Trend.fg(it)) }
            NumText("券 " + (mg.short?.let { Fmt.num(it) } ?: "—"), sz, C.Text2)
        } }
        "rev" -> if (!tw) null else AppState.twDaily[q.symbol]?.revenue?.let { r -> line {
            val yoy = r.yoyPct?.replace(",", "")?.toDoubleOrNull()
            val mom = r.momPct?.replace(",", "")?.toDoubleOrNull()
            Text(r.month + " 月", color = C.Text2, fontSize = sz)
            NumText("年增 " + (yoy?.let { Fmt.signedPct(it, 1) } ?: "—"), sz, yoy?.let { Trend.fg(it) } ?: C.Text)
            NumText("月增 " + (mom?.let { Fmt.signedPct(it, 1) } ?: "—"), sz, mom?.let { Trend.fg(it) } ?: C.Text2)
        } }
        "pe" -> (AppState.peMap[q.symbol]?.let { Fmt.num(it, 1) } ?: q.stats?.pe?.takeIf { it.isNotBlank() && it != "—" })?.let { pe -> line {
            NumText(pe + " 倍", sz, C.Text)
        } }
        "pnl" -> AppState.holdings.firstOrNull { it.symbol == q.symbol && it.shares > 0 }?.takeIf { q.price > 0 }?.let { h -> line {
            val pl = (q.price - h.avgCost) * h.shares
            val pct = if (h.avgCost > 0) (q.price / h.avgCost - 1) * 100 else 0.0
            NumText(com.claudeetf.app.ui.mask(Fmt.signed(pl, if (q.market == Market.US) 2 else 0)), sz, Trend.fg(pl), FontWeight.Bold)
            NumText(Fmt.signedPct(pct), sz, Trend.fg(pl))
            Text(Fmt.shares(h.shares) + " 股", color = C.Text2, fontSize = sz)
        } }
        else -> null
    }
}

// ============================== 設定 ==============================

/** 我的 › 顯示 › 自選個股顯示格式：點預覽中的項目即可調整 */
@Composable
fun WatchStyleSettings() {
    val st = AppState.watchStyle
    fun set(f: (WatchStyle) -> WatchStyle) = AppState.updateWatchStyle(f(AppState.watchStyle))
    val sample = AppState.quotes.firstOrNull { it.price > 0 } ?: Quote("0050", "元大台灣50", Market.TW, 92.50, 97.15)
    // 點選的目標："c:項目" 卡片、"l:項目" 清單
    var target by remember { mutableStateOf<String?>(null) }

    Card(padding = 14.dp) {
        Text("自選個股顯示格式", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Label("點預覽中的名稱、價格、漲跌或任一項目，下方會出現該項設定")

        Text("卡片預覽", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val sel = target?.takeIf { it.startsWith("c:") }?.removePrefix("c:")
            WatchCardBody(sample, st, 0, Modifier.weight(1f).border(1.dp, C.Border, RoundedCornerShape(12.dp)), sel) { target = "c:$it" }
            if (st.cardCols == 3) WatchCardBody(sample, st, 0, Modifier.weight(1f).border(1.dp, C.Border, RoundedCornerShape(12.dp)))
            else Spacer(Modifier.weight(0.25f))
        }
        if (target?.startsWith("c:") == true) Editor(target!!, st, ::set) { target = null }

        Text("清單預覽", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(horizontal = 6.dp)) {
            WatchRowBody(sample, st, Modifier, false, target?.takeIf { it.startsWith("l:") }?.removePrefix("l:")) { target = "l:$it" }
        }
        if (target?.startsWith("l:") == true) Editor(target!!, st, ::set) { target = null }

        // ---- 共用設定 ----
        Section("共用設定")
        Opt("版型") { Chips(listOf(0 to "精簡", 1 to "標準", 2 to "大字"), st.density) { v -> set { it.copy(density = v) } } }
        Opt("名稱與代號") { Chips(listOf(0 to "名稱在前", 1 to "代號在前", 2 to "只顯示名稱", 3 to "只顯示代號"), st.nameMode) { v -> set { it.copy(nameMode = v) } } }
        if (st.nameMode != 3) Opt("名稱行數") { Chips(listOf(1 to "1 行", 2 to "最多 2 行"), st.nameLines) { v -> set { it.copy(nameLines = v) } } }
        Opt("漲跌顯示") { Chips(listOf(0 to "金額＋百分比", 1 to "只顯示百分比", 2 to "只顯示金額"), st.changeMode) { v -> set { it.copy(changeMode = v) } } }
        if (st.changeMode != 2) Opt("百分比樣式") { Chips(listOf(0 to "文字", 1 to "色塊"), st.changeStyle) { v -> set { it.copy(changeStyle = v) } } }
        Opt("進場建議標籤位置") { Chips(listOf(0 to "依項目順序", 1 to "名稱旁", 2 to "價格旁"), st.tagPos) { v -> set { it.copy(tagPos = v) } } }

        // ---- 卡片 ----
        Section("卡片")
        Opt("每列") { Chips(listOf(2 to "2 張", 3 to "3 張"), st.cardCols) { v -> set { it.copy(cardCols = v) } } }
        Opt("價格位置") { Chips(listOf(0 to "靠左", 1 to "置中", 2 to "靠右"), st.priceAlign) { v -> set { it.copy(priceAlign = v) } } }
        Opt("標籤與附加項目對齊") { Chips(listOf(0 to "靠左", 1 to "置中", 2 to "靠右"), st.tagAlign) { v -> set { it.copy(tagAlign = v) } } }
        OptToggle("底色依漲跌上色", st.tintBg) { v -> set { it.copy(tintBg = v) } }
        ItemList("卡片顯示項目與順序", st.items, WatchStyle.CARD_NAMES, st.hidden,
            onMove = { i, j -> set { s -> s.copy(items = s.items.toMutableList().also { java.util.Collections.swap(it, i, j) }) } },
            onToggle = { id, on -> set { s -> s.copy(hidden = if (on) s.hidden - id else s.hidden + id) } })

        // ---- 清單 ----
        Section("清單")
        ItemList("清單顯示項目與順序（價格與漲跌固定在右側）", st.listItems, WatchStyle.LIST_NAMES, st.listHidden,
            onMove = { i, j -> set { s -> s.copy(listItems = s.listItems.toMutableList().also { java.util.Collections.swap(it, i, j) }) } },
            onToggle = { id, on -> set { s -> s.copy(listHidden = if (on) s.listHidden - id else s.listHidden + id) } })
        Label("市場標籤、報價時間、成交量、振幅、漲跌停相鄰時會排在同一行")

        Text("恢復預設", color = C.Muted, fontSize = 12.sp, modifier = Modifier.clickable { AppState.updateWatchStyle(WatchStyle()); target = null }.padding(top = 8.dp, bottom = 2.dp))
    }
}

/** 點選預覽項目後的快速設定 */
@Composable
private fun Editor(target: String, st: WatchStyle, set: ((WatchStyle) -> WatchStyle) -> Unit, onClose: () -> Unit) {
    val card = target.startsWith("c:")
    val id = target.substringAfter(":")
    val names = if (card) WatchStyle.CARD_NAMES else WatchStyle.LIST_NAMES
    val title = when (id) { "name" -> "名稱與代號"; "price" -> "價格"; "change" -> "漲跌"; else -> names[id] ?: id }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.Primary.copy(alpha = 0.10f))
        .border(1.dp, C.Primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (card) "卡片 › " else "清單 › ") + title, Modifier.weight(1f), color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text("完成", color = C.Primary, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClose).padding(6.dp))
        }
        when (id) {
            "name" -> {
                Chips(listOf(0 to "名稱在前", 1 to "代號在前", 2 to "只顯示名稱", 3 to "只顯示代號"), st.nameMode) { v -> set { it.copy(nameMode = v) } }
                if (st.nameMode != 3) Chips(listOf(1 to "1 行", 2 to "最多 2 行"), st.nameLines) { v -> set { it.copy(nameLines = v) } }
                Chips(listOf(0 to "精簡", 1 to "標準", 2 to "大字"), st.density) { v -> set { it.copy(density = v) } }
            }
            "price" -> {
                if (card) Chips(listOf(0 to "靠左", 1 to "置中", 2 to "靠右"), st.priceAlign) { v -> set { it.copy(priceAlign = v) } }
                Chips(listOf(0 to "精簡", 1 to "標準", 2 to "大字"), st.density) { v -> set { it.copy(density = v) } }
            }
            "change" -> {
                Chips(listOf(0 to "金額＋百分比", 1 to "只顯示百分比", 2 to "只顯示金額"), st.changeMode) { v -> set { it.copy(changeMode = v) } }
                if (st.changeMode != 2) Chips(listOf(0 to "文字", 1 to "色塊"), st.changeStyle) { v -> set { it.copy(changeStyle = v) } }
                if (card) MoveHide(card, id, st, set)
            }
            else -> {
                if (id == "entry") Chips(listOf(0 to "依項目順序", 1 to "名稱旁", 2 to "價格旁"), st.tagPos) { v -> set { it.copy(tagPos = v) } }
                if (card) Chips(listOf(0 to "靠左", 1 to "置中", 2 to "靠右"), st.tagAlign) { v -> set { it.copy(tagAlign = v) } }
                MoveHide(card, id, st, set)
            }
        }
    }
}

@Composable
private fun MoveHide(card: Boolean, id: String, st: WatchStyle, set: ((WatchStyle) -> WatchStyle) -> Unit) {
    val list = if (card) st.items else st.listItems
    val i = list.indexOf(id)
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        fun move(d: Int) = set { s ->
            val l = (if (card) s.items else s.listItems).toMutableList()
            val a = l.indexOf(id); val b = a + d
            if (a < 0 || b !in l.indices) s else { java.util.Collections.swap(l, a, b); if (card) s.copy(items = l) else s.copy(listItems = l) }
        }
        SmallBtn("↑ 上移", i > 0) { move(-1) }
        SmallBtn("↓ 下移", i in 0 until list.lastIndex) { move(1) }
        SmallBtn("隱藏", true) { set { s -> if (card) s.copy(hidden = s.hidden + id) else s.copy(listHidden = s.listHidden + id) } }
    }
}

@Composable
private fun SmallBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(text, color = if (enabled) C.Primary else C.Border, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(C.Bg).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun ItemList(title: String, items: List<String>, names: Map<String, String>, hidden: Set<String>,
                     onMove: (Int, Int) -> Unit, onToggle: (String, Boolean) -> Unit) {
    Text(title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
    items.forEachIndexed { i, id ->
        val on = id !in hidden
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(names[id] ?: id, Modifier.weight(1f), color = if (on) C.Text else C.Muted, fontSize = 13.sp)
            Text("↑", color = if (i > 0) C.Primary else C.Border, fontSize = 18.sp,
                modifier = Modifier.clickable(enabled = i > 0) { onMove(i, i - 1) }.padding(horizontal = 10.dp, vertical = 4.dp))
            Text("↓", color = if (i < items.lastIndex) C.Primary else C.Border, fontSize = 18.sp,
                modifier = Modifier.clickable(enabled = i < items.lastIndex) { onMove(i, i + 1) }.padding(horizontal = 10.dp, vertical = 4.dp))
            androidx.compose.material3.Switch(on, { onToggle(id, it) })
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
    Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(1.dp).background(C.Border))
}

@Composable
private fun Opt(title: String, content: @Composable () -> Unit) {
    Text(title, color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    content()
}

@Composable
private fun Chips(opts: List<Pair<Int, String>>, sel: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEach { (v, n) ->
            val on = v == sel
            Text(n, color = if (on) C.OnPrimary else C.Text2, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Primary else C.Bg)
                    .clickable { onPick(v) }.padding(horizontal = 11.dp, vertical = 6.dp))
        }
    }
}
