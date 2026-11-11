package com.claudeetf.app.ui.screens

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
fun WallTile(h: Holding, st: WallStyle, modifier: Modifier = Modifier, demo: Int = 0, onClick: () -> Unit) {
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
    val entry = if (vis.any { it.id == "entry" } && q != null) entryResultFor(q) else null
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
        else -> C.Text2
    }
    fun level(i: WallItem): Color = when (i.id) {
        "entry" -> entryColor(entry?.entry?.level ?: 0)
        "band" -> band?.let { zoneColor(it.zone) } ?: trend
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
    }
    fun labelColor(i: WallItem, main: Color): Color = if (inv && i.bg == 0) Color.White.copy(alpha = 0.75f) else when (i.lc) {
        1 -> C.Muted; 2 -> C.Text; 3 -> GOLD; 4 -> C.Primary; 5 -> trend
        else -> main
    }
    @Composable fun Item(i: WallItem) {
        val (pre, v) = parts(i) ?: return
        val main = color(i)
        val bgc = bgColor(i)
        val ilh = if (i.lh > 0) i.lh / 100f else lh
        val txt = buildAnnotatedString {
            if (pre.isNotEmpty()) { withStyle(SpanStyle(color = labelColor(i, main), fontWeight = FontWeight.Normal)) { append(pre) }; append(" ") }
            append(v)
        }
        Text(txt, color = main, fontSize = i.size.sp, lineHeight = (i.size * 1.18f * ilh).sp,
            fontWeight = if (i.bold) FontWeight.Bold else FontWeight.Normal, fontFamily = com.claudeetf.app.ui.fontOf(i.font.ifEmpty { if (i.mono) "mono" else "" }),
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlignOf(i.align),
            modifier = Modifier.outer(i.mx, i.my)
                .then(if (bgc != null) Modifier.drawBehind { drawRoundRect(bgc, cornerRadius = CornerRadius(6.dp.toPx())) } else Modifier)
                .outer(i.px, i.py))
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
        modifier.clip(RoundedCornerShape(6.dp)).background(bodyBg)
            .then(if (st.border || inv) Modifier.border(1.dp, if (inv) lerp(trend, Color.White, 0.25f) else trend.copy(alpha = 0.55f), RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (headLines.isNotEmpty()) Lines(st.lineGap.coerceAtMost(0), extra = headLines.map { it.first().lg },
                modifier = Modifier.fillMaxWidth().background(headBg)
                    .padding(horizontal = pad, vertical = (pad.value * 0.6f).dp)) { headLines.forEach { Line(it) } }
            if (bodyLines.isNotEmpty()) Lines(st.lineGap, Modifier.fillMaxWidth().padding(horizontal = pad, vertical = pad), extra = bodyLines.map { it.first().lg }) { bodyLines.forEach { Line(it) } }
        }
        // 懸浮項目：限制在格內，依水平／垂直位置百分比擺放
        vis.filter { it.fl }.forEach { i ->
            Box(Modifier.matchParentSize().padding((pad.value * 0.5f).dp), contentAlignment = BiasAlignment(i.fx / 50f - 1f, i.fy / 50f - 1f)) { Item(i) }
        }
    }
}

/** 行情牆：依欄數排成網格 */
@Composable
fun QuoteWall(list: List<Holding>, st: WallStyle = AppState.wallStyle, demo: Int = 0, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(st.gap.dp)) {
        list.chunked(st.cols).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(st.gap.dp)) {
                row.forEach { h -> WallTile(h, st, Modifier.weight(1f).fillMaxHeight(), demo) { onOpen(h.symbol) } }
                repeat(st.cols - row.size) { Spacer(Modifier.weight(1f)) }
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
private fun RepeatBtn(t: String, enabled: Boolean, onStep: () -> Unit) {
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
    val st = AppState.wallStyle
    val samples = AppState.holdings.take(st.cols * 2).ifEmpty { listOf(Holding("0050", 1000.0, 85.0), Holding("0056", 1000.0, 36.0)) }
    var demo by remember { mutableStateOf(0) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).border(1.dp, C.Border, RoundedCornerShape(12.dp)).padding(8.dp)) {
        QuoteWall(samples, st, demo) {}
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("預覽", color = C.Muted, fontSize = 11.sp)
            listOf(0 to "一般", 1 to "漲停", -1 to "跌停").forEach { (v, n) ->
                Text(n, color = if (demo == v) C.OnPrimary else C.Text2, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (demo == v) C.Primary else C.Bg).clickable { demo = v }.padding(horizontal = 9.dp, vertical = 3.dp))
            }
        }
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
            Label("開關決定是否顯示；↑↓ 調整順序；點項目名稱展開該項目的詳細設定。")
            st.items.forEachIndexed { idx, i ->
                val name = WallStyle.NAMES[i.id] ?: i.id
                val isEd = editing == i.id
                Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (isEd) C.Primary.copy(alpha = 0.08f) else C.Card)) {
                    Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { editing = if (isEd) null else i.id }.padding(vertical = 8.dp)) {
                            Text(name + if (isEd) "  ▲" else "  ▼", color = if (i.show) C.Text else C.Muted, fontSize = 14.sp)
                            Text((if (i.fl) "懸浮 ${i.fx}%,${i.fy}%" else if (i.head) "標題列" else "內容") + (if (i.inline) " · 同行" else "") + " · " + ALIGN_NAMES[i.align] + " · ${i.size}sp" + (if (i.bold) " · 粗" else "") + (if (i.bg != 0) " · 底色" else "") +
                                (if (i.mx != 0 || i.my != 0 || i.px != 0 || i.py != 0 || i.lg != 0 || i.lh != 0) " · 間距自訂" else ""),
                                color = C.Muted, fontSize = 11.sp)
                        }
                        Text("↑", color = if (idx > 0) C.Primary else C.Border, fontSize = 18.sp, modifier = Modifier.clickable(enabled = idx > 0) {
                            set { s -> s.copy(items = s.items.toMutableList().also { l -> l.add(idx - 1, l.removeAt(idx)) }) } }.padding(horizontal = 8.dp, vertical = 6.dp))
                        Text("↓", color = if (idx < st.items.lastIndex) C.Primary else C.Border, fontSize = 18.sp, modifier = Modifier.clickable(enabled = idx < st.items.lastIndex) {
                            set { s -> s.copy(items = s.items.toMutableList().also { l -> l.add(idx + 1, l.removeAt(idx)) }) } }.padding(horizontal = 8.dp, vertical = 6.dp))
                        Switch(i.show, { v -> set { it.withItem(i.copy(show = v)) } },
                            colors = SwitchDefaults.colors(checkedTrackColor = C.Primary, checkedThumbColor = C.OnPrimary, uncheckedTrackColor = C.Border))
                        Spacer(Modifier.width(6.dp))
                    }
                    AnimatedVisibility(isEd, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                            fun upd(f: (WallItem) -> WallItem) = set { it.withItem(f(it.item(i.id))) }
                            WChips("位置", listOf(1 to "標題列", 0 to "內容區", 2 to "懸浮（自由位置）"), if (i.fl) 2 else if (i.head) 1 else 0) { v ->
                                upd { it.copy(fl = v == 2, head = if (v == 2) it.head else v == 1) } }
                            if (i.fl) {
                                Label("懸浮項目不佔版面、置於最上層，只能在格子範圍內移動。水平 0% 最左、100% 最右；垂直 0% 最上、100% 最下。每次 1%，按住 ＋／－ 可連續調整。")
                                WStep("水平位置", i.fx, 0, 100, 1, "%") { v -> upd { it.copy(fx = v) } }
                                WStep("垂直位置", i.fy, 0, 100, 1, "%") { v -> upd { it.copy(fy = v) } }
                            } else WToggle("與上一項同一行", i.inline) { v -> upd { it.copy(inline = v) } }
                            WChips("對齊", listOf(0 to "靠左", 1 to "置中", 2 to "靠右"), i.align) { v -> upd { it.copy(align = v) } }
                            WStep("字級", i.size, 8, 60, 1, " sp") { v -> upd { it.copy(size = v) } }
                            WToggle("粗體", i.bold) { v -> upd { it.copy(bold = v) } }
                            WChips("字體", com.claudeetf.app.ui.FONT_OPTS.mapIndexed { k, p -> k to p.second },
                                com.claudeetf.app.ui.FONT_OPTS.indexOfFirst { it.first == i.font.ifEmpty { if (i.mono) "mono" else "" } }.coerceAtLeast(0)) { k ->
                                val key = com.claudeetf.app.ui.FONT_OPTS[k].first
                                upd { it.copy(font = key, mono = key == "mono") } }

                            WSub("間距與字高（外距、內距、行距皆可設負值）")
                            WStep("外距・左右", i.mx, -8, 24, 1, " dp") { v -> upd { it.copy(mx = v) } }
                            WStep("外距・上下", i.my, -12, 24, 1, " dp") { v -> upd { it.copy(my = v) } }
                            WStep("內距・左右", i.px, -10, 20, 1, " dp") { v -> upd { it.copy(px = v) } }
                            WStep("內距・上下", i.py, -10, 16, 1, " dp") { v -> upd { it.copy(py = v) } }
                            if (!i.inline && !i.fl) WStep("行距・與上一行", i.lg, -12, 16, 1, " dp") { v -> upd { it.copy(lg = v) } }
                            WStep("字高（行高倍率）", i.lh, 0, 170, 5, "%", show = { if (it == 0) "跟隨" else "$it%" }) { v ->
                                upd { it.copy(lh = if (v in 1..69) (if (v > it.lh) 70 else 0) else v) } }

                            WSub("配色")
                            WChips("文字顏色", COLOR_OPTS + (if (i.id == "entry" || i.id == "band") listOf(6 to "依等級") else emptyList()), i.color) { v -> upd { it.copy(color = v) } }
                            WChips("標籤底色", BG_OPTS.filter { it.first != 2 || i.id == "entry" || i.id == "band" }, i.bg) { v -> upd { it.copy(bg = v) } }
                            if (i.bg != 0) WStep("底色濃度", i.ba, 10, 100, 5, "%") { v -> upd { it.copy(ba = v) } }
                            if (i.id in LABELED) {
                                WToggle("顯示前綴文字（例如「市值」）", i.label) { v -> upd { it.copy(label = v) } }
                                if (i.label) WChips("前綴文字顏色", LABEL_COLOR_OPTS, i.lc) { v -> upd { it.copy(lc = v) } }
                            }
                            Text("此項目恢復預設", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).clickable {
                                val d = WallStyle.DEFAULT_ITEMS.first { it.id == i.id }
                                upd { it.copy(head = d.head, inline = d.inline, align = d.align, size = d.size, bold = d.bold, color = d.color, mono = d.mono, font = d.font, label = d.label,
                                    fl = d.fl, fx = d.fx, fy = d.fy, mx = d.mx, my = d.my, px = d.px, py = d.py, lg = d.lg, lh = d.lh, bg = d.bg, ba = d.ba, lc = d.lc) } }.padding(vertical = 4.dp))
                            if (i.id == "entry") Label("進場建議依 K 線分析（與個股頁相同），未分析過的個股會在背景分析後顯示。")
                        }
                    }
                }
            }
        }
        Text("全部恢復預設", color = C.Muted, fontSize = 12.sp, modifier = Modifier.clickable { AppState.updateWallStyle(WallStyle()); editing = null }.padding(vertical = 10.dp))
    }
}
