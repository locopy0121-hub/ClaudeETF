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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
private fun Lines(gapDp: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { ms, c ->
        val gap = gapDp.dp.roundToPx()
        val ps = ms.map { it.measure(c.copy(minHeight = 0)) }
        val w = (ps.maxOfOrNull { it.width } ?: 0).coerceAtLeast(c.minWidth)
        val h = (ps.sumOf { it.height } + gap * (ps.size - 1).coerceAtLeast(0)).coerceAtLeast(0)
        layout(w, h) {
            var y = 0
            ps.forEach { p -> p.place(0, y); y += p.height + gap }
        }
    }
}

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
fun WallTile(h: Holding, st: WallStyle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val q = AppState.quote(h.symbol)
    val chg = q?.change ?: 0.0
    val pct = q?.changePct ?: 0.0
    val trend = Trend.fg(chg)
    val lh = st.lineHeight / 100f
    val pad = (10 + st.pad).coerceAtLeast(0).dp
    val vis = st.items.filter { it.show }
    // 進場建議、光譜需要分析結果（背景載入，快取共用）
    val entry = if (vis.any { it.id == "entry" } && q != null) entryResultFor(q) else null
    if (vis.any { it.id == "band" } && q != null) LaunchedEffect(q.symbol) {
        if (com.claudeetf.app.data.InvestAnalysis.cached(q.symbol) == null) runCatching { com.claudeetf.app.data.InvestAnalysis.analyze(q) }
    }
    val fx = AppState.fxFor(AppState.marketOf(h.symbol))
    val ret = q?.price?.takeIf { it > 0 && h.avgCost > 0 }?.let { (it / h.avgCost - 1) * 100 }
    val total = AppState.holdings.sumOf { AppState.marketValueTwd(it) }.takeIf { it > 0 }

    fun text(i: WallItem): String? {
        fun p(label: String) = if (i.label) "$label " else ""
        return when (i.id) {
            "name" -> q?.name ?: h.symbol
            "code" -> h.symbol
            "price" -> q?.price?.takeIf { it > 0 }?.let { Fmt.price(it) } ?: "—"
            "arrow" -> if (chg > 0) "▲" else if (chg < 0) "▼" else "－"
            "chg" -> Fmt.signed(chg, if (abs(chg) < 10) 2 else 1)
            "pct" -> Fmt.num(abs(pct), 2) + "%"
            "band" -> com.claudeetf.app.data.InvestAnalysis.results[h.symbol]?.band?.zoneName?.let { p("光譜") + it }
            "mv" -> p("市值") + mask(Fmt.num(AppState.marketValueTwd(h)))
            "day" -> p("今日") + mask(Fmt.signed(chg * h.shares * fx))
            "ret" -> ret?.let { p("報酬") + Fmt.signedPct(it) }
            "pl" -> q?.price?.takeIf { it > 0 }?.let { p("損益") + mask(Fmt.signed(AppState.marketValueTwd(h) - AppState.costTwd(h))) }
            "shares" -> Fmt.shares(h.shares) + " 股"
            "avg" -> p("均") + Fmt.price(h.avgCost)
            "cost" -> p("成本") + mask(Fmt.num(AppState.costTwd(h)))
            "weight" -> total?.let { p("占") + Fmt.num(AppState.marketValueTwd(h) / it * 100, 1) + "%" }
            "vol" -> AppState.dayStats[h.symbol]?.volume?.let { v -> p("量") + if (AppState.marketOf(h.symbol) == com.claudeetf.app.data.Market.TW) Fmt.num(v / 1000) + " 張" else Fmt.num(v) }
            else -> null
        }
    }
    fun color(i: WallItem): Color = when (i.color) {
        1 -> trend; 2 -> C.Text; 3 -> C.Muted; 4 -> Color(0xFFF2B544); 5 -> C.Primary
        else -> when (i.id) {
            "price", "arrow", "chg", "pct" -> trend
            "name" -> C.Text
            "code" -> C.Muted
            "day", "pl" -> Trend.fg(if (i.id == "day") chg else AppState.marketValueTwd(h) - AppState.costTwd(h))
            "ret" -> Trend.fg(ret ?: 0.0)
            else -> C.Text2
        }
    }
    @Composable fun Item(i: WallItem) {
        if (i.id == "entry") {
            val e = entry?.entry ?: return
            Text(e.label, color = Color.White, fontSize = i.size.sp, fontWeight = if (i.bold) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(entryColor(e.level)).padding(horizontal = 6.dp, vertical = 1.dp))
            return
        }
        val t = text(i) ?: return
        Text(t, color = color(i), fontSize = i.size.sp, lineHeight = (i.size * 1.18f * lh).sp,
            fontWeight = if (i.bold) FontWeight.Bold else FontWeight.Normal, fontFamily = if (i.mono) FontFamily.Monospace else null,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = textAlignOf(i.align))
    }
    @Composable fun Line(line: List<WallItem>) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            line.forEach { i -> Box(Modifier.weight(1f), contentAlignment = alignOf(i.align)) { Item(i) } }
        }
    }
    val headLines = lines(vis.filter { it.head })
    val bodyLines = lines(vis.filter { !it.head })
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(C.Card)
            .then(if (st.border) Modifier.border(1.dp, trend.copy(alpha = 0.55f), RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        if (headLines.isNotEmpty()) Lines(st.lineGap.coerceAtMost(0),
            Modifier.fillMaxWidth().background(if (st.header) C.Bg.copy(alpha = 0.7f) else Color.Transparent)
                .padding(horizontal = pad, vertical = (pad.value * 0.6f).dp)) { headLines.forEach { Line(it) } }
        if (bodyLines.isNotEmpty()) Lines(st.lineGap, Modifier.fillMaxWidth().padding(horizontal = pad, vertical = pad)) { bodyLines.forEach { Line(it) } }
    }
}

/** 行情牆：依欄數排成網格 */
@Composable
fun QuoteWall(list: List<Holding>, st: WallStyle = AppState.wallStyle, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(st.gap.dp)) {
        list.chunked(st.cols).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(st.gap.dp)) {
                row.forEach { h -> WallTile(h, st, Modifier.weight(1f).fillMaxHeight()) { onOpen(h.symbol) } }
                repeat(st.cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ============================== 設定 ==============================

/** 加減步進（可負值） */
@Composable
private fun WStep(label: String, v: Int, min: Int, max: Int, step: Int, unit: String, set: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
        Text("－", color = if (v > min) C.Primary else C.Border, fontSize = 18.sp,
            modifier = Modifier.clickable(enabled = v > min) { set((v - step).coerceAtLeast(min)) }.padding(horizontal = 12.dp))
        Text("$v$unit", Modifier.width(64.dp), color = C.Text, fontSize = 13.sp, textAlign = TextAlign.Center)
        Text("＋", color = if (v < max) C.Primary else C.Border, fontSize = 18.sp,
            modifier = Modifier.clickable(enabled = v < max) { set((v + step).coerceAtMost(max)) }.padding(horizontal = 12.dp))
    }
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
private val LABELED = setOf("band", "mv", "day", "ret", "pl", "avg", "cost", "weight", "vol")

/** 設定頁：行情牆樣式（即時預覽＋百葉窗分區） */
@Composable
fun WallStyleSettings() {
    val st = AppState.wallStyle
    fun set(f: (WallStyle) -> WallStyle) = AppState.updateWallStyle(f(AppState.wallStyle))
    var open by remember { mutableStateOf<String?>("items") }
    var editing by remember { mutableStateOf<String?>(null) }
    fun toggle(k: String) { open = if (open == k) null else k }
    Card(padding = 14.dp) {
        Text("行情牆模式", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Label("持股頁「行情牆」的格子：每個項目都能選擇顯示、位置、同行、對齊、字級、粗細與顏色；內距與行距可設負值，字高調整行高。點分區標題展開或收合。")
        // 即時預覽（一直顯示）
        val samples = AppState.holdings.take(st.cols * 2).ifEmpty { listOf(Holding("0050", 1000.0, 85.0), Holding("0056", 1000.0, 36.0)) }
        Box(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(C.Bg).padding(8.dp)) {
            QuoteWall(samples, st) {}
        }

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
                            Text((if (i.head) "標題列" else "內容") + (if (i.inline) " · 同行" else "") + " · " + ALIGN_NAMES[i.align] + " · ${i.size}sp" + (if (i.bold) " · 粗" else ""),
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
                            WChips("位置", listOf(1 to "標題列", 0 to "內容區"), if (i.head) 1 else 0) { v -> upd { it.copy(head = v == 1) } }
                            WToggle("與上一項同一行", i.inline) { v -> upd { it.copy(inline = v) } }
                            WChips("對齊", listOf(0 to "靠左", 1 to "置中", 2 to "靠右"), i.align) { v -> upd { it.copy(align = v) } }
                            WStep("字級", i.size, 8, 60, 1, " sp") { v -> upd { it.copy(size = v) } }
                            WToggle("粗體", i.bold) { v -> upd { it.copy(bold = v) } }
                            if (i.id != "entry") WChips("顏色", COLOR_OPTS, i.color) { v -> upd { it.copy(color = v) } }
                            if (i.id in setOf("price", "chg", "pct", "mv", "day", "pl", "cost", "avg", "shares", "vol", "weight", "ret"))
                                WToggle("等寬數字", i.mono) { v -> upd { it.copy(mono = v) } }
                            if (i.id in LABELED) WToggle("顯示前綴文字（例如「市值」）", i.label) { v -> upd { it.copy(label = v) } }
                            if (i.id == "entry") Label("進場建議依 K 線分析（與個股頁相同），未分析過的個股會在背景分析後顯示。")
                        }
                    }
                }
            }
        }
        Text("全部恢復預設", color = C.Muted, fontSize = 12.sp, modifier = Modifier.clickable { AppState.updateWallStyle(WallStyle()); editing = null }.padding(vertical = 10.dp))
    }
}
