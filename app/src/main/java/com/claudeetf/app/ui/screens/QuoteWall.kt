package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.Holding
import com.claudeetf.app.data.WallStyle
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

/** 行情牆的一格 */
@Composable
fun WallTile(h: Holding, st: WallStyle, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val q = AppState.quote(h.symbol)
    val chg = q?.change ?: 0.0
    val pct = q?.changePct ?: 0.0
    val col = Trend.fg(chg)
    val lh = st.lineHeight / 100f
    val pad = (10 + st.pad).coerceAtLeast(0).dp
    val name = q?.name ?: h.symbol
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(C.Card)
            .then(if (st.border) Modifier.border(1.dp, col.copy(alpha = 0.55f), RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        // 標題列：名稱＋代號
        Row(
            Modifier.fillMaxWidth().background(if (st.header) C.Bg.copy(alpha = 0.7f) else androidx.compose.ui.graphics.Color.Transparent)
                .padding(horizontal = pad, vertical = (pad.value * 0.6f).coerceAtLeast(0f).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, Modifier.weight(1f), color = C.Text, fontSize = st.nameSize.sp, lineHeight = (st.nameSize * 1.2f * lh).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (st.showCode) Text(h.symbol, color = C.Muted, fontSize = st.codeSize.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
        }
        Lines(st.lineGap, Modifier.fillMaxWidth().padding(horizontal = pad, vertical = pad)) {
            val price = q?.price?.takeIf { it > 0 }?.let { Fmt.price(it) } ?: "—"
            Text(price, Modifier.fillMaxWidth(), color = if (st.priceColor == 0) col else C.Text, fontSize = st.priceSize.sp,
                lineHeight = (st.priceSize * 1.15f * lh).sp, fontWeight = if (st.priceBold) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace, maxLines = 1,
                textAlign = if (st.priceAlign == 0) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.End)
            if (st.showChg || st.showPct) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (st.showArrow) Text(if (chg > 0) "▲" else if (chg < 0) "▼" else "－", color = col, fontSize = (st.chgSize * 0.8f).sp)
                Spacer(Modifier.weight(1f))
                if (st.showChg) Text(Fmt.signed(chg, if (abs(chg) < 10) 2 else 1), color = col, fontSize = st.chgSize.sp,
                    lineHeight = (st.chgSize * 1.2f * lh).sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                if (st.showPct) Text("  " + Fmt.num(abs(pct), 2) + "%", color = col, fontSize = st.chgSize.sp,
                    lineHeight = (st.chgSize * 1.2f * lh).sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            if (st.extra > 0) {
                val tw = AppState.fxFor(AppState.marketOf(h.symbol))
                val text = when (st.extra) {
                    1 -> "市值 " + mask(Fmt.num(AppState.marketValueTwd(h)))
                    2 -> "今日 " + mask(Fmt.signed(chg * h.shares * tw))
                    3 -> "報酬 " + (q?.price?.takeIf { it > 0 && h.avgCost > 0 }?.let { Fmt.signedPct((it / h.avgCost - 1) * 100) } ?: "—")
                    else -> Fmt.shares(h.shares) + " 股"
                }
                Text(text, Modifier.fillMaxWidth(), color = C.Text2, fontSize = st.extraSize.sp, lineHeight = (st.extraSize * 1.2f * lh).sp,
                    maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
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

/** 設定頁：行情牆樣式（含即時預覽） */
@Composable
fun WallStyleSettings() {
    val st = AppState.wallStyle
    fun set(f: (WallStyle) -> WallStyle) = AppState.updateWallStyle(f(AppState.wallStyle))
    fun P(label: String, min: Int, max: Int, step: Int, unit: String) =
        com.claudeetf.app.data.KlineConditions.Param(label, 0.0, min.toDouble(), max.toDouble(), step.toDouble(), unit)
    @Composable fun Step(label: String, v: Int, min: Int, max: Int, step: Int, unit: String, onSet: (Int) -> Unit) =
        WallStep(P(label, min, max, step, unit), v) { onSet(it) }
    @Composable fun Section(t: String) = Text(t, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
    @Composable fun Chips(title: String, opts: List<Pair<Int, String>>, sel: Int, onPick: (Int) -> Unit) {
        Text(title, color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 4.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            opts.forEach { (v, n) ->
                val on = v == sel
                Text(n, color = if (on) C.OnPrimary else C.Text2, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (on) C.Primary else C.Bg).clickable { onPick(v) }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
    com.claudeetf.app.ui.Card(padding = 14.dp) {
        Text("行情牆模式", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        com.claudeetf.app.ui.Label("持股頁「行情牆」的格子樣式：大字現價、漲跌與幅度；內距與行距可設負值讓排版更緊湊，字高調整行高。")
        // 即時預覽：前幾檔持股（沒有持股用示意）
        val samples = AppState.holdings.take(st.cols * 2).ifEmpty { listOf(Holding("0050", 1000.0, 85.0), Holding("0056", 1000.0, 36.0)) }
        Box(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(C.Bg).padding(8.dp)) {
            QuoteWall(samples, st) {}
        }
        Section("版面")
        Chips("每列格數", listOf(1 to "1 格", 2 to "2 格", 3 to "3 格", 4 to "4 格"), st.cols) { v -> set { it.copy(cols = v) } }
        Step("格內內距（可負）", st.pad, -10, 24, 1, " dp") { v -> set { it.copy(pad = v) } }
        Step("格與格間距", st.gap, 0, 16, 1, " dp") { v -> set { it.copy(gap = v) } }
        Step("行距（可負）", st.lineGap, -12, 16, 1, " dp") { v -> set { it.copy(lineGap = v) } }
        Step("字高（行高倍率）", st.lineHeight, 70, 170, 5, "%") { v -> set { it.copy(lineHeight = v) } }
        com.claudeetf.app.ui.screens.OptToggle("標題列底色", st.header) { v -> set { it.copy(header = v) } }
        com.claudeetf.app.ui.screens.OptToggle("外框依漲跌上色", st.border) { v -> set { it.copy(border = v) } }

        Section("名稱與代號")
        Step("名稱字級", st.nameSize, 9, 24, 1, " sp") { v -> set { it.copy(nameSize = v) } }
        com.claudeetf.app.ui.screens.OptToggle("顯示代號", st.showCode) { v -> set { it.copy(showCode = v) } }
        if (st.showCode) Step("代號字級", st.codeSize, 8, 20, 1, " sp") { v -> set { it.copy(codeSize = v) } }

        Section("現價")
        Step("現價字級", st.priceSize, 14, 56, 2, " sp") { v -> set { it.copy(priceSize = v) } }
        com.claudeetf.app.ui.screens.OptToggle("粗體", st.priceBold) { v -> set { it.copy(priceBold = v) } }
        Chips("對齊", listOf(0 to "置中", 1 to "靠右"), st.priceAlign) { v -> set { it.copy(priceAlign = v) } }
        Chips("顏色", listOf(0 to "依漲跌", 1 to "白色"), st.priceColor) { v -> set { it.copy(priceColor = v) } }

        Section("漲跌")
        com.claudeetf.app.ui.screens.OptToggle("顯示漲跌", st.showChg) { v -> set { it.copy(showChg = v) } }
        com.claudeetf.app.ui.screens.OptToggle("顯示漲跌幅", st.showPct) { v -> set { it.copy(showPct = v) } }
        com.claudeetf.app.ui.screens.OptToggle("顯示 ▲▼ 箭頭", st.showArrow) { v -> set { it.copy(showArrow = v) } }
        Step("漲跌字級", st.chgSize, 9, 28, 1, " sp") { v -> set { it.copy(chgSize = v) } }

        Section("附加資訊")
        Chips("第三行", listOf(0 to "不顯示", 1 to "持股市值", 2 to "今日損益", 3 to "報酬率", 4 to "股數"), st.extra) { v -> set { it.copy(extra = v) } }
        if (st.extra > 0) Step("附加字級", st.extraSize, 8, 18, 1, " sp") { v -> set { it.copy(extraSize = v) } }
        Text("全部恢復預設", color = C.Muted, fontSize = 12.sp, modifier = Modifier.clickable { AppState.updateWallStyle(WallStyle()) }.padding(vertical = 10.dp))
    }
}

/** 加減步進（可負值） */
@Composable
private fun WallStep(p: com.claudeetf.app.data.KlineConditions.Param, v: Int, set: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.label, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
        val step = p.step.toInt()
        Text("－", color = if (v > p.min) C.Primary else C.Border, fontSize = 18.sp,
            modifier = Modifier.clickable(enabled = v > p.min) { set((v - step).coerceAtLeast(p.min.toInt())) }.padding(horizontal = 12.dp))
        Text("$v${p.unit}", Modifier.width(72.dp), color = C.Text, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("＋", color = if (v < p.max) C.Primary else C.Border, fontSize = 18.sp,
            modifier = Modifier.clickable(enabled = v < p.max) { set((v + step).coerceAtMost(p.max.toInt())) }.padding(horizontal = 12.dp))
    }
}
