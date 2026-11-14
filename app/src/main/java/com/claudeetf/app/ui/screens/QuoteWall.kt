@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.claudeetf.app.ui.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.offset
import com.claudeetf.app.ui.textFx
import com.claudeetf.app.ui.theme.readable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.Holding
import com.claudeetf.app.data.WallItem
import com.claudeetf.app.data.WallStyle
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.mask
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import kotlin.math.abs

/** 直向排列，行距可為負值（各行重疊靠近） */
@Composable
private fun Lines(gapDp: Int, modifier: Modifier = Modifier, extra: List<Int> = emptyList(), content: @Composable () -> Unit) {
    Layout(content, modifier) { ms, c ->
        val gap = gapDp.dp.roundToPx()
        val ps = ms.map { it.measure(c.copy(minHeight = 0)) }
        val gaps = ps.indices.map { k -> if (k == 0) 0 else gap + (extra.getOrElse(k) { 0 }).dp.roundToPx() }
        val w = (ps.maxOfOrNull { it.width } ?: 0).coerceAtLeast(c.minWidth)
        val h = (ps.sumOf { it.height } + gaps.sum()).coerceAtLeast(0)
        layout(w, h) {
            var y = 0
            ps.forEachIndexed { k, p -> y += gaps[k]; p.place(0, y); y += p.height }
        }
    }
}

/** 外距（可負）：增減項目佔用的空間 */
private fun Modifier.outer(mxDp: Int, myDp: Int) = if (mxDp == 0 && myDp == 0) this else layout { m, c ->
    val mx = mxDp.dp.roundToPx(); val my = myDp.dp.roundToPx()
    val p = m.measure(c.offset(-2 * mx.coerceAtLeast(0), -2 * my.coerceAtLeast(0)).copy(minWidth = 0, minHeight = 0))
    val w = (p.width + 2 * mx).coerceIn(c.minWidth, c.maxWidth.coerceAtLeast(c.minWidth))
    val h = (p.height + 2 * my).coerceAtLeast(0).coerceIn(c.minHeight, if (c.hasBoundedHeight) c.maxHeight else Int.MAX_VALUE)
    layout(w, h) { p.place(mx, my) }
}

/** 依底色亮度選黑或白字 */
private val GOLD = Color(0xFFF2B544)
private fun onColor(c: Color) = if (c.luminance() > 0.55f) Color(0xFF111111) else Color.White

/** 顯示中的項目依「同一行」分組 */
private fun lines(items: List<WallItem>): List<List<WallItem>> {
    val out = ArrayList<MutableList<WallItem>>()
    items.forEach { if (out.isEmpty() || !it.inline) out += mutableListOf(it) else out.last() += it }
    return out
}

private fun alignOf(a: Int) = when (a) { 1 -> Alignment.Center; 2 -> Alignment.CenterEnd; else -> Alignment.CenterStart }
private fun textAlignOf(a: Int) = when (a) { 1 -> TextAlign.Center; 2 -> TextAlign.End; else -> TextAlign.Start }

/** 行情牆的一格 */
@Composable
fun WallTile(h: Holding, st: WallStyle, modifier: Modifier = Modifier, demo: Int = 0,
             editSel: String? = null, onPickItem: ((String) -> Unit)? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    // 拖曳懸浮項目：格子尺寸、吸附參考線（% 座標）
    var tileSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var guideX by remember { mutableStateOf<Int?>(null) }
    var guideY by remember { mutableStateOf<Int?>(null) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    /** 編輯模式：項目可點選，選中的加框；選中的懸浮項目可直接拖曳（靠近中線或其他懸浮項目自動吸附並輕震） */
    fun Modifier.pick(id: String): Modifier = if (onPickItem == null) this else this
        .border(if (editSel == id) 2.dp else 1.dp, if (editSel == id) C.Primary else C.Primary.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
        .then(if (editSel == id && st.item(id).fl) Modifier.pointerInput(id, tileSize) {
            var fx = 0f; var fy = 0f
            detectDragGestures(
                onDragStart = { val it0 = AppState.wallStyle.item(id); fx = it0.fx.toFloat(); fy = it0.fy.toFloat(); Hist.wall.mark() },
                onDragEnd = { guideX = null; guideY = null },
                onDragCancel = { guideX = null; guideY = null },
            ) { change, d ->
                change.consume()
                if (tileSize.width <= 0 || tileSize.height <= 0) return@detectDragGestures
                fx = (fx + d.x / tileSize.width * 100f).coerceIn(0f, 100f)
                fy = (fy + d.y / tileSize.height * 100f).coerceIn(0f, 100f)
                val others = AppState.wallStyle.items.filter { it.fl && it.show && it.id != id }
                fun snap(v: Float, targets: List<Int>): Int? = targets.minByOrNull { kotlin.math.abs(it - v) }?.takeIf { kotlin.math.abs(it - v) <= 2.5f }
                val sx = snap(fx, listOf(50) + others.map { it.fx })
                val sy = snap(fy, listOf(50) + others.map { it.fy })
                if ((sx != null && sx != guideX) || (sy != null && sy != guideY)) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                guideX = sx; guideY = sy
                val nx = sx ?: fx.roundToInt(); val ny = sy ?: fy.roundToInt()
                Hist.wall.live { s -> s.withItem(s.item(id).copy(fx = nx, fy = ny)) }
            }
        } else Modifier)
        .clickable { onPickItem(id) }
    val q = AppState.quote(h.symbol)
    val chg = q?.change ?: 0.0
    val pct = q?.changePct ?: 0.0
    val trend = if (demo != 0) Trend.fg(demo.toDouble()) else Trend.fg(chg)
    val lh = st.lineHeight / 100f
    val pad = (10 + st.pad).coerceAtLeast(0).dp
    val vis = st.items.filter { it.show }
    val limit = if (st.limitFill && q != null && AppState.marketOf(h.symbol) == com.claudeetf.app.data.Market.TW)
        com.claudeetf.app.util.TwSession.limitState(q.price, q.prevClose, q.isEtf || q.symbol.startsWith("00")) else null
    val inv = limit != null || (demo != 0 && st.limitFill)
    val limitText = if (demo != 0) (if (demo > 0) "漲停" else "跌停") else if (q != null && AppState.marketOf(h.symbol) == com.claudeetf.app.data.Market.TW)
        com.claudeetf.app.util.TwSession.limitState(q.price, q.prevClose, q.isEtf || q.symbol.startsWith("00")) else null
    // 進場建議、光譜需要分析結果（背景載入，快取共用）
    val entry = if (vis.any { it.id == "entry" } && q != null) adviceFor(q) else null
    if (vis.any { it.id == "band" } && q != null) LaunchedEffect(q.symbol) {
        if (com.claudeetf.app.data.InvestAnalysis.cached(q.symbol) == null) runCatching { com.claudeetf.app.data.InvestAnalysis.analyze(q) }
    }
    val fx = AppState.fxFor(AppState.marketOf(h.symbol))
    val ret = q?.price?.takeIf { it > 0 && h.avgCost > 0 }?.let { (it / h.avgCost - 1) * 100 }
    val total = AppState.holdings.sumOf { AppState.marketValueTwd(it) }.takeIf { it > 0 }

    fun sg(v: Double, d: Int) = (if (v > 0) "+" else if (v < 0) "−" else "") + if (d > 0) Fmt.numTrunc(abs(v), d) else Fmt.num(abs(v))
    val band = com.claudeetf.app.data.InvestAnalysis.results[h.symbol]?.band
    /** 前綴文字＋數值 */
    fun parts(i: WallItem): Pair<String, String>? {
        fun p(label: String) = if (i.label) label else ""
        return when (i.id) {
            "name" -> "" to (q?.name ?: h.symbol)
            "code" -> "" to h.symbol
            "price" -> "" to (q?.price?.takeIf { it > 0 }?.let { Fmt.price(it) } ?: "—")
            "arrow" -> "" to if (chg > 0) "▲" else if (chg < 0) "▼" else "－"
            "chg" -> "" to sg(chg, if (abs(chg) < 10) 2 else 1)
            "pct" -> "" to Fmt.num(abs(pct), 2) + "%"
            "entry" -> entry?.entry?.label?.let { "" to it }
            "limit" -> limitText?.let { "" to it }
            "rank" -> com.claudeetf.app.data.DailyReport.rankOf(h.symbol)?.let { "" to (if (it.bull) "看漲 " else "看跌 ") + it.rank }
            "band" -> band?.zoneName?.let { p("光譜") to it }
            "mv" -> p("市值") to mask(Fmt.num(AppState.marketValueTwd(h)))
            "day" -> p("今日") to mask(sg(chg * h.shares * fx, 0))
            "ret" -> ret?.let { p("報酬") to Fmt.signedPct(it) }
            "pl" -> q?.price?.takeIf { it > 0 }?.let { p("損益") to mask(sg(AppState.marketValueTwd(h) - AppState.costTwd(h), 0)) }
            "shares" -> "" to Fmt.shares(h.shares) + " 股"
            "avg" -> p("均") to Fmt.price(h.avgCost)
            "cost" -> p("成本") to mask(Fmt.num(AppState.costTwd(h)))
            "weight" -> total?.let { p("占") to Fmt.num(AppState.marketValueTwd(h) / it * 100, 1) + "%" }
            "vol" -> AppState.dayStats[h.symbol]?.volume?.let { v -> p("量") to if (AppState.marketOf(h.symbol) == com.claudeetf.app.data.Market.TW) Fmt.num(v / 1000) + " 張" else Fmt.num(v) }
            else -> null
        }
    }
    fun natural(i: WallItem): Color = when (i.id) {
        "price", "arrow", "chg", "pct" -> trend
        "name" -> C.Text
        "code" -> C.Muted
        "day", "pl" -> Trend.fg(if (i.id == "day") chg else AppState.marketValueTwd(h) - AppState.costTwd(h))
        "ret" -> Trend.fg(ret ?: 0.0)
        "entry" -> entryColor(entry?.entry?.level ?: 0)
        "band" -> band?.let { zoneColor(it.zone) } ?: C.Text2
        "rank" -> Trend.fg(if (com.claudeetf.app.data.DailyReport.rankOf(h.symbol)?.bull == false) -1.0 else 1.0)
        else -> C.Text2
    }
    fun level(i: WallItem): Color = when (i.id) {
        "entry" -> entryColor(entry?.entry?.level ?: 0)
        "band" -> band?.let { zoneColor(it.zone) } ?: trend
        "rank" -> Trend.fg(if (com.claudeetf.app.data.DailyReport.rankOf(h.symbol)?.bull == false) -1.0 else 1.0)
        else -> trend
    }
    // 反白格子裡的漲跌停標籤（底色依漲跌）改成白底、漲跌色字，才不會和格子同色
    fun limitOnFill(i: WallItem) = inv && i.id == "limit" && (i.bg == 1 || i.bg == 2)
    fun bgColor(i: WallItem): Color? = if (limitOnFill(i)) Color.White else when (i.bg) {
        1 -> trend; 2 -> level(i); 3 -> C.Muted; 4 -> GOLD; 5 -> C.Primary; 6 -> Color.White; 7 -> C.Bg
        else -> null
    }?.copy(alpha = i.ba / 100f)
    fun color(i: WallItem): Color = if (limitOnFill(i)) trend else if (inv && i.bg == 0) (if (i.id == "code") Color.White.copy(alpha = 0.7f) else Color.White) else when (i.color) {
        1 -> trend; 2 -> C.Text; 3 -> C.Muted; 4 -> GOLD; 5 -> C.Primary; 6 -> level(i)
        else -> bgColor(i)?.takeIf { it.alpha >= 0.6f }?.let { onColor(it) } ?: natural(i)
    }.let { if (i.bg == 0 && !inv) it.readable() else it }
    fun labelColor(i: WallItem, main: Color): Color = if (inv && i.bg == 0) Color.White.copy(alpha = 0.75f) else when (i.lc) {
        1 -> C.Muted; 2 -> C.Text; 3 -> GOLD; 4 -> C.Primary; 5 -> trend
        else -> main
    }
    /** 即時 K 棒：當日開、高、低、現價畫成一根蠟燭；字級＝高度 */
    @Composable fun KBar(i: WallItem) {
        val qq = q ?: return
        if (qq.price <= 0) return
        val ds = AppState.dayStats[h.symbol]
        val k = com.claudeetf.app.data.KlineAnalysis.latestDaily[h.symbol]?.candles?.lastOrNull()
        val open = ds?.open ?: k?.o?.takeIf { k.c == qq.price } ?: qq.prevClose
        val hi = maxOf(ds?.high ?: qq.price, qq.price, open)
        val lo = minOf(ds?.low ?: qq.price, qq.price, open)
        val ref = qq.prevClose.takeIf { it > 0 } ?: open
        val up = qq.price >= open
        val col = when (i.color) { 0 -> Trend.fg(qq.price - open); 6 -> Trend.fg(if (up) 1.0 else -1.0); else -> color(i) }
        val bgc = bgColor(i)
        val hDp = (i.size * 1.6f).dp
        val wDp = (i.size * 0.55f).coerceAtLeast(6f).dp
        // 以昨收 ±（今日振幅與 2% 取大者）為刻度，K 棒高度反映實際漲跌幅度
        val span = maxOf(hi - lo, ref * 0.02, kotlin.math.abs(hi - ref), kotlin.math.abs(lo - ref)).coerceAtLeast(1e-9)
        val top = maxOf(hi, ref + span * 0.5); val bot = minOf(lo, ref - span * 0.5)
        androidx.compose.foundation.Canvas(Modifier.pick(i.id).outer(i.mx, i.my)
            .then(if (bgc != null) Modifier.drawBehind { drawRoundRect(bgc, cornerRadius = CornerRadius(4.dp.toPx())) } else Modifier)
            .outer(i.px, i.py).size(wDp, hDp)) {
            fun y(p: Double) = (size.height * (1 - (p - bot) / (top - bot))).toFloat()
            val cx = size.width / 2
            // 昨收參考線
            drawLine(col.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(0f, y(ref)), androidx.compose.ui.geometry.Offset(size.width, y(ref)), 1.dp.toPx())
            drawLine(col, androidx.compose.ui.geometry.Offset(cx, y(hi)), androidx.compose.ui.geometry.Offset(cx, y(lo)), 1.5.dp.toPx())
            val bTop = y(maxOf(open, qq.price)); val bBot = y(minOf(open, qq.price))
            drawRect(col, androidx.compose.ui.geometry.Offset(size.width * 0.15f, bTop),
                androidx.compose.ui.geometry.Size(size.width * 0.7f, (bBot - bTop).coerceAtLeast(1.5.dp.toPx())))
        }
    }
    @Composable fun Item(i: WallItem) {
        if (i.id == "kbar") { KBar(i); return }
        val (pre, v) = parts(i) ?: return
        val main = color(i)
        val bgc = bgColor(i)
        val ilh = if (i.lh > 0) i.lh / 100f else lh
        val txt = buildAnnotatedString {
            if (pre.isNotEmpty()) { withStyle(SpanStyle(color = labelColor(i, main), fontWeight = FontWeight.Normal)) { append(pre) }; append(" ") }
            append(v)
        }
        // 欄寬（格寬 %）、欄高（dp）；適寬字體：字級自動放大或縮小到剛好填滿欄寬／欄高
        val density = androidx.compose.ui.platform.LocalDensity.current
        val boxW = if (i.bw > 0 && tileSize.width > 0) with(density) { (tileSize.width * i.bw / 100f).toDp() } else null
        val boxH = if (i.bh > 0) i.bh.dp else null
        val fs = if (i.fit && (boxW != null || boxH != null)) {
            val tm = androidx.compose.ui.text.rememberTextMeasurer()
            val ff = com.claudeetf.app.ui.fontOf(i.font.ifEmpty { if (i.mono) "mono" else "" })
            val base = remember(txt, i.size, ff, i.bold) {
                tm.measure(txt, androidx.compose.ui.text.TextStyle(fontSize = i.size.sp, fontFamily = ff, fontWeight = if (i.bold) FontWeight.Bold else FontWeight.Normal), maxLines = 1).size
            }
            with(density) {
                val padX = (2 * i.px.coerceAtLeast(0)).dp.toPx(); val padY = (2 * i.py.coerceAtLeast(0)).dp.toPx()
                val sw = boxW?.let { (it.toPx() - padX) / base.width.coerceAtLeast(1) } ?: Float.MAX_VALUE
                val sh = boxH?.let { (it.toPx() - padY) / (base.height.coerceAtLeast(1) / 1.0f) } ?: Float.MAX_VALUE
                (i.size * minOf(sw, sh) * 0.98f).coerceIn(6f, 200f)
            }
        } else i.size.toFloat()
        Text(txt, color = main, fontSize = fs.sp, lineHeight = (fs * 1.18f * ilh).sp,
            fontWeight = if (i.bold) FontWeight.Bold else FontWeight.Normal, fontFamily = com.claudeetf.app.ui.fontOf(i.font.ifEmpty { if (i.mono) "mono" else "" }),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlignOf(i.align),
            fontStyle = if (i.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
            textDecoration = com.claudeetf.app.ui.decoOf(i.under, i.strike),
            style = if (i.tnum) androidx.compose.material3.LocalTextStyle.current.copy(fontFeatureSettings = "tnum") else androidx.compose.material3.LocalTextStyle.current,
            modifier = Modifier
                .then(if (i.ox != 0 || i.oy != 0) Modifier.offset(i.ox.dp, i.oy.dp) else Modifier)
                .pick(i.id)
                .then(if (i.an > 0 && (q != null || com.claudeetf.app.ui.LocalFxDemo.current)) Modifier.textFx(i.an, i.at, i.asp, i.apw, q?.price ?: 0.0, if (q != null) Trend.fg(q.change) else trend) else Modifier)
                .outer(i.mx, i.my)
                .then(if (boxW != null) Modifier.width(boxW) else Modifier)
                .then(if (boxH != null) Modifier.height(boxH) else Modifier)
                .then(if (bgc != null) Modifier.drawBehind { drawRoundRect(bgc, cornerRadius = CornerRadius(6.dp.toPx())) } else Modifier)
                .outer(i.px, i.py)
                .then(if (boxH != null) Modifier.wrapContentHeight(Alignment.CenterVertically) else Modifier))
    }
    @Composable fun Line(line: List<WallItem>) {
        // 左、中、右三組：右組貼齊右緣、中組以整格寬度置中、左組使用剩下的寬度（不足時省略）
        val groups = (0..2).map { a -> line.filter { it.align == a } }
        Layout(content = {
            groups.forEach { g ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) { g.forEach { Item(it) } }
            }
        }, modifier = Modifier.fillMaxWidth()) { ms, c ->
            val w = c.maxWidth
            val gap = 5.dp.roundToPx()
            val free = c.copy(minWidth = 0, minHeight = 0)
            val r = ms[2].measure(free)
            val ce = ms[1].measure(free.copy(maxWidth = (w - (if (r.width > 0) r.width + gap else 0)).coerceAtLeast(0)))
            val used = (if (r.width > 0) r.width + gap else 0) + (if (ce.width > 0) ce.width + gap else 0)
            val l = ms[0].measure(free.copy(maxWidth = (w - used).coerceAtLeast(0)))
            val hh = maxOf(l.height, ce.height, r.height)
            layout(w, hh) {
                l.place(0, (hh - l.height) / 2)
                r.place(w - r.width, (hh - r.height) / 2)
                if (ce.width > 0) {
                    val lo = if (l.width > 0) l.width + gap else 0
                    val hi = w - (if (r.width > 0) r.width + gap else 0) - ce.width
                    ce.place(((w - ce.width) / 2).coerceIn(lo, hi.coerceAtLeast(lo)), (hh - ce.height) / 2)
                }
            }
        }
    }
    val flow = vis.filter { !it.fl }
    val headLines = lines(flow.filter { it.head })
    val bodyLines = lines(flow.filter { !it.head })
    // 漲停、跌停：整格依漲跌上色反白（標題列較亮、內容較深）
    val headBg = if (inv) lerp(trend, Color.Black, 0.12f) else if (st.header) C.Bg.copy(alpha = 0.7f) else Color.Transparent
    val bodyBg = if (inv) lerp(trend, Color.Black, 0.45f) else C.Card
    Box(
        modifier.onSizeChanged { tileSize = it }.clip(RoundedCornerShape(6.dp)).background(bodyBg)
            .then(if (st.border || inv) Modifier.border(1.dp, if (inv) lerp(trend, Color.White, 0.25f) else trend.copy(alpha = 0.55f), RoundedCornerShape(6.dp)) else Modifier)
            .then(if (onPickItem != null) Modifier
                else if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else Modifier.clickable(onClick = onClick)),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (headLines.isNotEmpty()) Lines(st.lineGap.coerceAtMost(0), extra = headLines.map { it.first().lg },
                modifier = Modifier.fillMaxWidth().background(headBg)
                    .padding(horizontal = pad, vertical = (pad.value * 0.6f).dp)) { headLines.forEach { Line(it) } }
            if (bodyLines.isNotEmpty()) Lines(st.lineGap, Modifier.fillMaxWidth().padding(horizontal = pad, vertical = pad), extra = bodyLines.map { it.first().lg }) { bodyLines.forEach { Line(it) } }
        }
        // 懸浮項目：限制在格內，依水平／垂直位置百分比擺放
        vis.filter { it.fl }.forEach { i ->
            // 依基準點擺放：座標＝格內百分比，基準點（左緣／中心／右緣）落在該座標上，方便上下項目精準對齊
            Layout(content = { Item(i) }, modifier = Modifier.matchParentSize().padding((pad.value * 0.5f).dp)) { ms, c ->
                // 項目沒有資料時不會產生內容（例如尚無報價），不擺放任何東西
                // 量測固有高度時（IntrinsicSize）最大值為無限大：懸浮項目不影響格子高度，回報 0
                val W = if (c.hasBoundedWidth) c.maxWidth else 0
                val H = if (c.hasBoundedHeight) c.maxHeight else 0
                val m0 = ms.firstOrNull() ?: return@Layout layout(W, H) {}
                if (W == 0 || H == 0) return@Layout layout(W, H) {}
                val pl = m0.measure(c.copy(minWidth = 0, minHeight = 0))
                val ax = when (i.fa) { 0 -> 0f; 1 -> 0.5f; 2 -> 1f; else -> i.fx / 100f }
                val ay = when (i.fva) { 0 -> 0f; 1 -> 0.5f; 2 -> 1f; else -> i.fy / 100f }
                val x = (W * i.fx / 100f - pl.width * ax).toInt().coerceIn(0, (W - pl.width).coerceAtLeast(0))
                val y = (H * i.fy / 100f - pl.height * ay).toInt().coerceIn(0, (H - pl.height).coerceAtLeast(0))
                layout(W, H) { pl.place(x, y) }
            }
        }
        // 拖曳時的吸附參考線
        if (guideX != null || guideY != null) androidx.compose.foundation.Canvas(Modifier.matchParentSize().padding((pad.value * 0.5f).dp)) {
            guideX?.let { gx -> val x = size.width * gx / 100f; drawLine(C.Primary, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
            guideY?.let { gy -> val y = size.height * gy / 100f; drawLine(C.Primary, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
        }
    }
}

/** 行情牆：依欄數排成網格 */
@Composable
fun QuoteWall(list: List<Holding>, st: WallStyle = AppState.wallStyle, demo: Int = 0,
              editSel: String? = null, onPickItem: ((String) -> Unit)? = null, onLongPress: ((String) -> Unit)? = null, onOpen: (String) -> Unit) {
    val land = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val cols = st.colsFor(land)
    Column(verticalArrangement = Arrangement.spacedBy(st.gap.dp)) {
        list.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(st.gap.dp)) {
                row.forEach { h -> WallTile(h, st, Modifier.weight(1f).fillMaxHeight(), demo, editSel, onPickItem,
                    onLongPress?.let { f -> { f(h.symbol) } }) { onOpen(h.symbol) } }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ============================== 設定 ==============================

/** 加減步進（可負值） */
@Composable
private fun WStep(label: String, v: Int, min: Int, max: Int, step: Int, unit: String, show: (Int) -> String = { "$it$unit" }, set: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
        RepeatBtn("－", v > min) { set((v - step).coerceAtLeast(min)) }
        Text(show(v), Modifier.width(64.dp), color = C.Text, fontSize = 13.sp, textAlign = TextAlign.Center)
        RepeatBtn("＋", v < max) { set((v + step).coerceAtMost(max)) }
    }
}

/** 點一下調一格；按住不放連續調整（先停 0.4 秒，之後每 0.06 秒一格） */
@Composable
fun RepeatBtn(t: String, enabled: Boolean, onStep: () -> Unit) {
    val cur by rememberUpdatedState(onStep)
    val en by rememberUpdatedState(enabled)
    Text(t, color = if (enabled) C.Primary else C.Border, fontSize = 18.sp,
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onPress = {
                if (!en) return@detectTapGestures
                kotlinx.coroutines.coroutineScope {
                    val job = launch {
                        cur(); kotlinx.coroutines.delay(400)
                        while (en) { cur(); kotlinx.coroutines.delay(60) }
                    }
                    tryAwaitRelease(); job.cancel()
                }
            })
        }.padding(horizontal = 12.dp, vertical = 4.dp))
}

@Composable
private fun WChips(title: String?, opts: List<Pair<Int, String>>, sel: Int, onPick: (Int) -> Unit) {
    if (title != null) Text(title, color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEach { (v, n) ->
            val on = v == sel
            Text(n, color = if (on) C.OnPrimary else C.Text2, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                .background(if (on) C.Primary else C.Bg).clickable { onPick(v) }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun WSub(title: String) {
    Text(title, color = C.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun WToggle(title: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = C.Primary, checkedThumbColor = C.OnPrimary, uncheckedTrackColor = C.Border))
    }
}

/** 百葉窗式分區：點標題展開／收合，一次只展開一區 */
@Composable
private fun Blind(key: String, title: String, summary: String, open: String?, onToggle: (String) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val isOpen = open == key
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(C.Bg)) {
        Row(Modifier.fillMaxWidth().clickable { onToggle(key) }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (!isOpen) Text(summary, color = C.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (isOpen) "▲" else "▼", color = C.Muted, fontSize = 12.sp)
        }
        AnimatedVisibility(isOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), content = content)
        }
    }
}

private val ALIGN_NAMES = listOf("左", "中", "右")
private val COLOR_OPTS = listOf(0 to "預設", 1 to "依漲跌", 2 to "白", 3 to "灰", 4 to "金", 5 to "主色")
private val BG_OPTS = listOf(0 to "無", 2 to "依等級", 1 to "依漲跌", 3 to "灰", 4 to "金", 5 to "主色", 6 to "白", 7 to "深色")
private val LABEL_COLOR_OPTS = listOf(0 to "同文字", 1 to "灰", 2 to "白", 3 to "金", 4 to "主色", 5 to "依漲跌")
private val LABELED = setOf("band", "mv", "day", "ret", "pl", "avg", "cost", "weight", "vol")

/** 行情牆即時預覽（設定頁置頂用） */
@Composable
fun WallPreview(modifier: Modifier = Modifier) {
    val st = Hist.wall.peek ?: AppState.wallStyle
    val samples = AppState.holdings.take(2).ifEmpty { listOf(Holding("0050", 1000.0, 85.0), Holding("0056", 1000.0, 36.0)) }
    var demo by remember { mutableStateOf(0) }
    com.claudeetf.app.ui.FxDemo {
    Column(modifier.fillMaxWidth().trackPreviewEdge().clip(RoundedCornerShape(12.dp)).background(C.Card).border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(8.dp)) {
        // 預覽即編輯：點格子裡的任一項目即選取，下方出現快速工具列，✎ 叫出完整編輯工具
        QuoteWall(samples, st, demo, editSel = if (EditorMode.on) WallEdit.sel else null, onPickItem = if (EditorMode.on) ({ k -> WallEdit.select(k) }) else null) {}
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("預覽", color = C.Muted, fontSize = 11.sp)
            listOf(0 to "一般", 1 to "漲停", -1 to "跌停").forEach { (v, n) ->
                Text(n, color = if (demo == v) C.OnPrimary else C.Text2, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (demo == v) C.Primary else C.Bg).clickable { demo = v }.padding(horizontal = 9.dp, vertical = 3.dp))
            }
            Spacer(Modifier.weight(1f))
            if (EditorMode.on) WallUndoRedo()
        }
        if (!EditorMode.on) Text(EDITOR_OFF_HINT, color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        else if (!WallEdit.sheet) Text("點格子裡的任一項目即可編輯", color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
    if (EditorMode.on) WallEditSheet()
    }
}

/** 設定頁：行情牆樣式（即時預覽＋百葉窗分區） */
@Composable
fun WallStyleSettings(showPreview: Boolean = true) {
    val st = AppState.wallStyle
    fun set(f: (WallStyle) -> WallStyle) = AppState.updateWallStyle(f(AppState.wallStyle))
    var open by remember { mutableStateOf<String?>("items") }
    var editing by remember { mutableStateOf<String?>(null) }
    fun toggle(k: String) { open = if (open == k) null else k }
    Card(padding = 14.dp) {
        Text("行情牆模式", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Label("持股頁「行情牆」的格子：每個項目都能選擇顯示、位置、同行、對齊、字級、字體、粗細、外距、內距、行距、字高與文字／底色／前綴配色；內距與行距可設負值，字高調整行高。點分區標題展開或收合。")
        if (showPreview) WallPreview(Modifier.padding(top = 10.dp))

        Blind("preset", "預設組合", "標準、精簡、完整資訊、大字看盤", open, ::toggle) {
            WChips(null, listOf(0 to "標準", 1 to "精簡", 2 to "完整資訊", 3 to "大字看盤"), -1) { k -> AppState.updateWallStyle(WallStyle.preset(k)); editing = null }
            Label("套用後仍可逐項調整。", Modifier.padding(top = 6.dp))
        }

        Blind("layout", "版面", "每列 ${st.cols} 格 · 內距 ${st.pad} · 行距 ${st.lineGap} · 字高 ${st.lineHeight}%", open, ::toggle) {
            WChips("每列格數", listOf(1 to "1 格", 2 to "2 格", 3 to "3 格", 4 to "4 格"), st.cols) { v -> set { it.copy(cols = v) } }
            WChips("橫向每列格數", listOf(0 to "自動", 2 to "2 格", 3 to "3 格", 4 to "4 格", 5 to "5 格", 6 to "6 格"), st.landCols) { v -> set { it.copy(landCols = v) } }
            WStep("格內內距（可負）", st.pad, -10, 24, 1, " dp") { v -> set { it.copy(pad = v) } }
            WStep("格與格間距", st.gap, 0, 16, 1, " dp") { v -> set { it.copy(gap = v) } }
            WStep("行距（可負）", st.lineGap, -12, 16, 1, " dp") { v -> set { it.copy(lineGap = v) } }
            WStep("字高（行高倍率）", st.lineHeight, 70, 170, 5, "%") { v -> set { it.copy(lineHeight = v) } }
            WToggle("標題列底色", st.header) { v -> set { it.copy(header = v) } }
            WToggle("外框依漲跌上色", st.border) { v -> set { it.copy(border = v) } }
            WToggle("漲停／跌停整格反白（台股）", st.limitFill) { v -> set { it.copy(limitFill = v) } }
        }

        val shown = st.items.count { it.show }
        Blind("items", "項目（顯示、順序、對齊、字級）", "顯示 $shown／${st.items.size} 項：" + st.items.filter { it.show }.joinToString("、") { WallStyle.NAMES[it.id] ?: it.id },
            open, ::toggle) {
            Label("點項目名稱展開細部設定；或直接點上方預覽格子裡的項目叫出編輯工具。")
            st.items.forEachIndexed { idx, i ->
                CollapsibleItem(WallStyle.NAMES[i.id] ?: i.id, i.show, editing == i.id, idx > 0, idx < st.items.lastIndex,
                    onName = { if (EditorMode.on) editing = if (editing == i.id) null else i.id },
                    onShow = { v -> set { it.withItem(i.copy(show = v)) } },
                    onUp = { set { s -> s.copy(items = s.items.toMutableList().also { l -> l.add(idx - 1, l.removeAt(idx)) }) } },
                    onDown = { set { s -> s.copy(items = s.items.toMutableList().also { l -> l.add(idx + 1, l.removeAt(idx)) }) } },
                    summary = (if (i.fl) "懸浮" else if (i.head) "標題列" else "內容") + " · ${i.size}sp") {
                    WallItemEditor(i, st, ::set)
                }
            }
        }
        Text("全部恢復預設", color = C.Muted, fontSize = 12.sp, modifier = Modifier.clickable { AppState.updateWallStyle(WallStyle()); editing = null }.padding(vertical = 10.dp))
    }
}


/** 行情牆單一項目的完整編輯內容：設定頁的項目清單與「編輯工具」面板共用；分頁與行情卡片、持股卡片一致（排版、色彩、版面、動效） */
@Composable
fun WallItemEditor(i: WallItem, st: WallStyle, set: ((WallStyle) -> WallStyle) -> Unit) {
    fun upd(f: (WallItem) -> WallItem) = set { it.withItem(f(it.item(i.id))) }
    val tabs = listOf("⚡ 常用", "排印", "版面", "色彩", "動態")
    val tab = DeckTab.tab.coerceIn(0, tabs.lastIndex)
    // 閱讀對比（以上漲色示意依漲跌的項目）
    val bgc = when (i.bg) { 1 -> Trend.fg(1.0); 2 -> Trend.fg(1.0); 3 -> C.Muted; 4 -> GOLD; 5 -> C.Primary; 6 -> Color.White; 7 -> C.Bg; else -> null }
        ?.copy(alpha = i.ba / 100f)?.compositeOver(C.Card) ?: C.Card
    val fgc = when (i.color) { 1, 6 -> Trend.fg(1.0); 2 -> C.Text; 3 -> C.Muted; 4 -> GOLD; 5 -> C.Primary
        else -> if (i.bg != 0 && i.ba >= 60) onColor(bgc) else if (i.id in setOf("price", "arrow", "chg", "pct", "day", "pl", "ret")) Trend.fg(1.0) else C.Text }
    val fixContrast = { upd { it.copy(color = if (it.bg != 0 && it.ba >= 60) 0 else 2) } }
    Column(Modifier.fillMaxWidth()) {
        DeckTabs(tabs, tab) { DeckTab.tab = it }
        // 摘要：一眼看出目前設定
        Label(listOf(
            if (i.fl) "懸浮 X ${i.fx}%・Y ${i.fy}%" else if (i.head) "標題列" else "內容區",
            "${i.size} sp" + (if (i.bold) "・粗" else "") + (if (i.italic) "・斜" else "") + (if (i.under) "・底線" else "") + (if (i.strike) "・刪除線" else ""),
            "動畫：" + (if (i.an == 0) "無" else com.claudeetf.app.ui.TextFx.nameOf(i.an)),
        ).joinToString("　"))

        if (tab == 0) {
            SliderRow("欄寬比例", i.bw, 0, 100, 5, "%", sub = "0＝自動", show = { if (it == 0) "自動" else "$it%" }) { v -> upd { it.copy(bw = v) } }
            SliderRow("欄高尺寸", i.bh, 0, 160, 2, " dp", sub = "0＝自動", show = { if (it == 0) "自動" else "$it dp" }) { v -> upd { it.copy(bh = v) } }
            SwitchRow("適寬字體", if (i.bw == 0 && i.bh == 0) "開啟時自動設欄寬 50%" else "自動縮放填滿欄寬或欄高", i.fit) { v ->
                upd { it.copy(fit = v, bw = if (v && it.bw == 0 && it.bh == 0) 50 else it.bw) } }
            PropertyRow("字體樣式") {
                StyleToggles(i.bold, i.italic, i.under, i.strike, i.tnum,
                    onBold = { v -> upd { it.copy(bold = v) } }, onItalic = { v -> upd { it.copy(italic = v) } },
                    onUnder = { v -> upd { it.copy(under = v) } }, onStrike = { v -> upd { it.copy(strike = v) } }, onTnum = { v -> upd { it.copy(tnum = v) } })
            }
            ChipsRow("對齊", listOf(0 to "靠左", 1 to "置中", 2 to "靠右"), i.align) { v -> upd { it.copy(align = v) } }
            SliderRow("字級微調", i.size, 8, 60, 1, " sp", sub = if (i.fit) "適寬時為基準" else null) { v -> upd { it.copy(size = v) } }
            ChipsRow("氛圍色票", COLOR_OPTS, i.color) { v -> upd { it.copy(color = v) } }
            PropertyRow("閱讀對比") { ContrastBadge(fgc, bgc, fixContrast) }
            if (i.fl) {
                NudgeRow("水平位置 X", i.fx, "%", "◀", "▶", listOf(1, 5), { d -> upd { it.copy(fx = (it.fx + d).coerceIn(0, 100)) } }) { upd { it.copy(fx = 50) } }
                NudgeRow("垂直位置 Y", i.fy, "%", "▲", "▼", listOf(1, 5), { d -> upd { it.copy(fy = (it.fy + d).coerceIn(0, 100)) } }) { upd { it.copy(fy = 50) } }
            } else {
                NudgeRow("水平微調 X", i.ox, "dp", "◀", "▶", listOf(1, 4, 8), { d -> upd { it.copy(ox = (it.ox + d).coerceIn(-60, 60)) } }) { upd { it.copy(ox = 0) } }
                NudgeRow("垂直微調 Y", i.oy, "dp", "▲", "▼", listOf(1, 4, 8), { d -> upd { it.copy(oy = (it.oy + d).coerceIn(-40, 40)) } }) { upd { it.copy(oy = 0) } }
            }
            MotionRow(i.an, i.asp, i.apw) { sp, pw -> upd { it.copy(asp = sp, apw = pw) } }
        } else when (when (tab) { 1 -> 0; 3 -> 1; 2 -> 2; else -> 3 }) {
            0 -> {
                DeckGroup("文字樣式") {
                    StyleToggles(i.bold, i.italic, i.under, i.strike, i.tnum,
                        onBold = { v -> upd { it.copy(bold = v) } }, onItalic = { v -> upd { it.copy(italic = v) } },
                        onUnder = { v -> upd { it.copy(under = v) } }, onStrike = { v -> upd { it.copy(strike = v) } },
                        onTnum = { v -> upd { it.copy(tnum = v) } })
                }
                DeckGroup("字級與字體") {
                    StepperSlider("字級", i.size, 8, 60, 1, " sp") { v -> upd { it.copy(size = v) } }
                    WChips("字體", com.claudeetf.app.ui.FONT_OPTS.mapIndexed { k, p -> k to p.second },
                        com.claudeetf.app.ui.FONT_OPTS.indexOfFirst { it.first == i.font.ifEmpty { if (i.mono) "mono" else "" } }.coerceAtLeast(0)) { k ->
                        val key = com.claudeetf.app.ui.FONT_OPTS[k].first
                        upd { it.copy(font = key, mono = key == "mono") } }
                }
                if (i.id in LABELED) DeckGroup("前綴文字") {
                    WToggle("顯示前綴（例如「市值」）", i.label) { v -> upd { it.copy(label = v) } }
                }
            }
            1 -> {
                val graded = i.id == "entry" || i.id == "band" || i.id == "rank" || i.id == "kbar"
                DeckGroup("文字顏色") {
                    WChips(null, COLOR_OPTS + (if (graded) listOf(6 to "依等級") else emptyList()), i.color) { v -> upd { it.copy(color = v) } }
                    ContrastBadge(fgc, bgc, fixContrast)
                }
                DeckGroup("標籤底色") {
                    WChips(null, BG_OPTS.filter { it.first != 2 || graded }, i.bg) { v -> upd { it.copy(bg = v) } }
                    if (i.bg != 0) StepperSlider("濃度", i.ba, 10, 100, 5, "%") { v -> upd { it.copy(ba = v) } }
                }
                if (i.id in LABELED && i.label) DeckGroup("前綴文字顏色") {
                    WChips(null, LABEL_COLOR_OPTS, i.lc) { v -> upd { it.copy(lc = v) } }
                }
            }
            2 -> {
                DeckGroup("擺放區域") {
                    WChips(null, listOf(1 to "標題列", 0 to "內容區", 2 to "懸浮（自由位置）"), if (i.fl) 2 else if (i.head) 1 else 0) { v ->
                        upd { it.copy(fl = v == 2, head = if (v == 2) it.head else v == 1) } }
                    if (i.fl) Label("懸浮項目也可以在上方預覽直接拖曳，靠近中線或其他項目會自動吸附。")
                }
                if (i.fl) {
                    DeckGroup("座標（%）") {
                        DPad(i.fx, i.fy, "%", listOf(1, 5),
                            onMove = { dx, dy -> upd { it.copy(fx = (it.fx + dx).coerceIn(0, 100), fy = (it.fy + dy).coerceIn(0, 100)) } },
                            onReset = { upd { it.copy(fx = 50, fy = 50) } })
                        StepperSlider("X", i.fx, 0, 100, 1, "%") { v -> upd { it.copy(fx = v) } }
                        StepperSlider("Y", i.fy, 0, 100, 1, "%") { v -> upd { it.copy(fy = v) } }
                    }
                    DeckGroup("基準點") {
                        WChips("水平", listOf(0 to "左緣", 1 to "中心", 2 to "右緣", 3 to "自動"), i.fa) { v -> upd { it.copy(fa = v) } }
                        WChips("垂直", listOf(0 to "上緣", 1 to "中線", 2 to "下緣", 3 to "自動"), i.fva) { v -> upd { it.copy(fva = v) } }
                        Label("基準點落在座標上：兩個項目都設「右緣」且 X 相同，右邊就會切齊。")
                    }
                    val others = st.items.filter { it.fl && it.show && it.id != i.id }
                    if (others.isNotEmpty()) DeckGroup("對齊到其他懸浮項目") {
                        others.forEach { o ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text((WallStyle.NAMES[o.id] ?: o.id) + "（${o.fx}%, ${o.fy}%）", Modifier.weight(1f), color = C.Text2, fontSize = 12.sp, maxLines = 1)
                                listOf("左" to 0, "中" to 1, "右" to 2).forEach { (n, an) ->
                                    Text(n, color = C.Primary, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.Card).clickable {
                                        set { s -> s.withItem(s.item(o.id).copy(fa = an)).let { s2 -> s2.withItem(s2.item(i.id).copy(fa = an, fx = o.fx)) } }
                                    }.padding(horizontal = 8.dp, vertical = 4.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text("同列", color = C.Primary, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.Card).clickable {
                                    set { s -> s.withItem(s.item(o.id).copy(fva = 1)).let { s2 -> s2.withItem(s2.item(i.id).copy(fva = 1, fy = o.fy)) } }
                                }.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                        Label("左／中／右：上下切齊（同一 X）；同列：中線對齊（同一 Y）。")
                    }
                } else {
                    DeckGroup("排列") {
                        WToggle("與上一項同一行", i.inline) { v -> upd { it.copy(inline = v) } }
                        WChips("對齊", listOf(0 to "靠左", 1 to "置中", 2 to "靠右"), i.align) { v -> upd { it.copy(align = v) } }
                    }
                    DeckGroup("位置微調（dp）") {
                        DPad(i.ox, i.oy, "dp", listOf(1, 4, 8),
                            onMove = { dx, dy -> upd { it.copy(ox = (it.ox + dx).coerceIn(-60, 60), oy = (it.oy + dy).coerceIn(-40, 40)) } },
                            onReset = { upd { it.copy(ox = 0, oy = 0) } })
                    }
                }
                DeckGroup("欄寬與欄高") {
                    StepperSlider("欄寬", i.bw, 0, 100, 5, "%", show = { if (it == 0) "自動" else "$it%" }) { v -> upd { it.copy(bw = v) } }
                    StepperSlider("欄高", i.bh, 0, 160, 2, " dp", show = { if (it == 0) "自動" else "$it dp" }) { v -> upd { it.copy(bh = v) } }
                    WToggle("適寬字體（字級自動填滿欄寬／欄高）", i.fit) { v -> upd { it.copy(fit = v) } }
                    Label(if (i.fit && i.bw == 0 && i.bh == 0) "請先設定欄寬或欄高，適寬字體才會作用。"
                        else "欄寬以格子寬度的百分比計算，跨裝置與橫向都一致；欄內文字依「對齊」靠左、置中或靠右。")
                }
                DeckGroup("間距（可負）") {
                    StepperSlider("外距左右", i.mx, -8, 24, 1, " dp") { v -> upd { it.copy(mx = v) } }
                    StepperSlider("外距上下", i.my, -12, 24, 1, " dp") { v -> upd { it.copy(my = v) } }
                    StepperSlider("內距左右", i.px, -10, 20, 1, " dp") { v -> upd { it.copy(px = v) } }
                    StepperSlider("內距上下", i.py, -10, 16, 1, " dp") { v -> upd { it.copy(py = v) } }
                    if (!i.inline && !i.fl) StepperSlider("行距", i.lg, -12, 16, 1, " dp") { v -> upd { it.copy(lg = v) } }
                    StepperSlider("字高", i.lh, 0, 170, 5, "%", show = { if (it == 0) "跟隨" else "$it%" }) { v ->
                        upd { it.copy(lh = if (v in 1..69) (if (v > it.lh) 70 else 0) else v) } }
                }
            }
            else -> {
                MotionRow(i.an, i.asp, i.apw) { sp, pw -> upd { it.copy(asp = sp, apw = pw) } }
                DeckGroup {
                    com.claudeetf.app.ui.FxPicker(i.an, i.at, i.asp, i.apw) { f, t, sp, pw -> upd { it.copy(an = f, at = t, asp = sp, apw = pw) } }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (i.show) "👁 顯示中" else "◌ 已隱藏", color = if (i.show) C.Text2 else C.Muted, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.Bg).clickable { upd { it.copy(show = !it.show) } }.padding(horizontal = 10.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text("此項目恢復預設", color = C.Muted, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                val d = WallStyle.DEFAULT_ITEMS.first { it.id == i.id }
                upd { it.copy(head = d.head, inline = d.inline, align = d.align, size = d.size, bold = d.bold, color = d.color, mono = d.mono, font = d.font, label = d.label,
                    fl = d.fl, fx = d.fx, fy = d.fy, mx = d.mx, my = d.my, px = d.px, py = d.py, lg = d.lg, lh = d.lh, bg = d.bg, ba = d.ba, lc = d.lc,
                    fa = d.fa, fva = d.fva, ox = d.ox, oy = d.oy, an = d.an, at = d.at, asp = d.asp, apw = d.apw, italic = d.italic, under = d.under, strike = d.strike, tnum = d.tnum, bw = d.bw, bh = d.bh, fit = d.fit) } }
                .padding(horizontal = 10.dp, vertical = 6.dp))
        }
        if (i.id == "entry") Label("進場建議依 K 線分析（與個股頁相同），未分析過的個股會在背景分析後顯示。")
    }
}
