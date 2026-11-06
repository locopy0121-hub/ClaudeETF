package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt

/** 依版型縮放字級 */
fun WatchStyle.sp(base: Int): TextUnit = (base * when (density) { 0 -> 0.85f; 2 -> 1.2f; else -> 1f }).sp

/** 名稱與代號（依格式） */
@Composable
fun WatchName(q: Quote, st: WatchStyle, base: Int, modifier: Modifier = Modifier) {
    val name = q.name.ifBlank { q.symbol }
    when (st.nameMode) {
        1 -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            NumText(q.symbol, st.sp(base), C.Text, FontWeight.Bold); HGap(6.dp)
            Text(name, color = C.Text2, fontSize = st.sp(base - 2), maxLines = st.nameLines, overflow = TextOverflow.Ellipsis)
        }
        2 -> Text(name, modifier, color = C.Text, fontSize = st.sp(base), maxLines = st.nameLines, overflow = TextOverflow.Ellipsis)
        3 -> NumText(q.symbol, st.sp(base), C.Text, FontWeight.Bold, modifier)
        else -> Text(name, modifier, color = C.Text, fontSize = st.sp(base), maxLines = st.nameLines, overflow = TextOverflow.Ellipsis)
    }
}

/** 漲跌（依格式：金額／百分比，文字／色塊） */
@Composable
fun WatchChange(q: Quote, st: WatchStyle, base: Int) {
    val c = Trend.fg(q.change)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (st.changeMode != 1) NumText(Fmt.signed(q.change, 2), st.sp(base), c)
        if (st.changeMode == 0) HGap(8.dp)
        if (st.changeMode != 2) {
            if (st.changeStyle == 1) ChangePill(q.changePct) else NumText(Fmt.pct(q.changePct), st.sp(base), c)
        }
    }
}

/** 附加資訊項目（依使用者排定的順序，不含走勢圖） */
@Composable
fun WatchExtraItem(q: Quote, id: String, st: WatchStyle) {
    val small = st.sp(10)
    when (id) {
        "keys" -> AppState.keyPrices[q.symbol]?.let { k ->
            val (pos, c) = KeyLevelsUi.position(q.price, k)
            Text("三關：$pos", color = c, fontSize = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        "entry" -> if (st.tagPos == 0) EntryTagFor(q)
        "limit" -> if (q.market == Market.TW) {
            com.claudeetf.app.util.TwSession.limitState(q.price, q.prevClose, q.isEtf || q.symbol.startsWith("00"))?.let { s ->
                Text(s, color = Trend.fg(if (s == "漲停") 1.0 else -1.0), fontSize = st.sp(11), fontWeight = FontWeight.Bold)
            }
        }
        "market" -> if (st.nameMode != 1) Row(verticalAlignment = Alignment.CenterVertically) {
            MarketTag(q.market)
            if (st.nameMode == 0 || st.nameMode == 2) { HGap(6.dp); NumText(q.symbol, small, C.Muted) }
        } else MarketTag(q.market)
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

// ============================== 設定 ==============================

/** 我的 › 顯示 › 自選個股顯示格式（附卡片與清單即時預覽） */
@Composable
fun WatchStyleSettings() {
    val st = AppState.watchStyle
    fun set(f: (WatchStyle) -> WatchStyle) = AppState.updateWatchStyle(f(AppState.watchStyle))
    val sample = AppState.quotes.firstOrNull { it.price > 0 }
        ?: Quote("0050", "元大台灣50", Market.TW, 92.50, 97.15)
    Card(padding = 14.dp) {
        Text("自選個股顯示格式", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Label("套用在 行情 › 自選 的卡片與清單")

        // 預覽
        Text("預覽", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuoteCardPreview(sample, Modifier.weight(1f))
            if (st.cardCols == 3) QuoteCardPreview(sample, Modifier.weight(1f))
            if (st.cardCols == 2) Spacer(Modifier.weight(1f).height(1.dp))
        }
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(horizontal = 6.dp)) {
            QuoteRowPreview(sample)
        }

        Opt("版型") { Chips(listOf(0 to "精簡", 1 to "標準", 2 to "大字"), st.density) { v -> set { it.copy(density = v) } } }
        Opt("名稱與代號") { Chips(listOf(0 to "名稱在前", 1 to "代號在前", 2 to "只顯示名稱", 3 to "只顯示代號"), st.nameMode) { v -> set { it.copy(nameMode = v) } } }
        if (st.nameMode != 3) Opt("名稱行數") { Chips(listOf(1 to "1 行", 2 to "最多 2 行"), st.nameLines) { v -> set { it.copy(nameLines = v) } } }
        Opt("卡片價格位置") { Chips(listOf(0 to "靠左", 1 to "置中", 2 to "靠右"), st.priceAlign) { v -> set { it.copy(priceAlign = v) } } }
        Opt("漲跌顯示") { Chips(listOf(0 to "金額＋百分比", 1 to "只顯示百分比", 2 to "只顯示金額"), st.changeMode) { v -> set { it.copy(changeMode = v) } } }
        if (st.changeMode != 2) Opt("百分比樣式") { Chips(listOf(0 to "文字", 1 to "色塊"), st.changeStyle) { v -> set { it.copy(changeStyle = v) } } }
        Opt("卡片每列") { Chips(listOf(2 to "2 張", 3 to "3 張"), st.cardCols) { v -> set { it.copy(cardCols = v) } } }
        OptToggle("卡片底色依漲跌上色", st.tintBg) { v -> set { it.copy(tintBg = v) } }
        Opt("進場建議標籤位置") { Chips(listOf(0 to "依項目順序", 1 to "名稱旁", 2 to "價格旁"), st.tagPos) { v -> set { it.copy(tagPos = v) } } }

        // 顯示項目：開關＋排序
        Text("顯示項目與順序", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Label("開關決定是否顯示，↑↓ 調整在卡片／清單中的上下順序")
        st.items.forEachIndexed { i, id ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(WatchStyle.ITEM_NAMES[id] ?: id, Modifier.weight(1f), color = if (st.shows(id)) C.Text else C.Muted, fontSize = 13.sp)
                Text("↑", color = if (i > 0) C.Primary else C.Border, fontSize = 18.sp, modifier = Modifier.clickable(enabled = i > 0) {
                    set { s -> s.copy(items = s.items.toMutableList().also { l -> java.util.Collections.swap(l, i, i - 1) }) }
                }.padding(horizontal = 10.dp, vertical = 4.dp))
                Text("↓", color = if (i < st.items.lastIndex) C.Primary else C.Border, fontSize = 18.sp, modifier = Modifier.clickable(enabled = i < st.items.lastIndex) {
                    set { s -> s.copy(items = s.items.toMutableList().also { l -> java.util.Collections.swap(l, i, i + 1) }) }
                }.padding(horizontal = 10.dp, vertical = 4.dp))
                androidx.compose.material3.Switch(st.shows(id), { on ->
                    set { s -> s.copy(hidden = if (on) s.hidden - id else s.hidden + id) }
                })
            }
        }
        Text("恢復預設", color = C.Muted, fontSize = 12.sp, modifier = Modifier.clickable { AppState.updateWatchStyle(WatchStyle()) }.padding(top = 6.dp, bottom = 2.dp))
    }
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
