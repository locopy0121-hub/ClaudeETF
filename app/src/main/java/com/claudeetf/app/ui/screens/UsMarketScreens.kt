package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.CalendarEvent
import com.claudeetf.app.data.CalendarRepo
import com.claudeetf.app.data.ColorMode
import com.claudeetf.app.data.EventKind
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.MarketBoards
import com.claudeetf.app.data.MarketData
import com.claudeetf.app.data.SectorMove
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.data.remote.friendly
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.launch
import kotlin.math.abs

// ============================== 市場 ==============================

@Composable
fun MarketScreen(nav: NavController) {
    var tab by remember { mutableIntStateOf(0) }
    var moverTab by remember { mutableIntStateOf(0) }
    var pickSide by remember { mutableIntStateOf(0) }
    var pickBoard by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    val s = rememberUsSession()
    val scope = rememberCoroutineScope()
    var board by remember { mutableStateOf<Load<MarketBoards.Board>>(Load.Loading) }
    var global by remember { mutableStateOf<Load<List<Pair<String, MarketBoards.GlobalItem>>>>(Load.Loading) }

    LaunchedEffect(tab, reload) {
        if (tab == 1) com.claudeetf.app.data.TwPicks.start()
        if (tab == 2) {
            global = Load.Loading
            global = runCatching { MarketBoards.global(reload > 0) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
            return@LaunchedEffect
        }
        board = Load.Loading
        board = runCatching { if (tab == 0) MarketBoards.us(reload > 0) else MarketBoards.tw(reload > 0) }
            .fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }

    fun open(sym: String) {
        scope.launch {
            if (!AppState.isWatched(sym)) MarketData.addAndFetch(sym)
            nav.navigate(Routes.stock(sym))
        }
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("市場") {
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, contentDescription = "重新整理", tint = C.Text) }
            IconButton(onClick = { nav.navigate(Routes.CALENDAR) }) {
                Icon(Icons.Filled.DateRange, contentDescription = "財經行事曆", tint = C.Text)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Segmented(listOf("美股", "台股", "全球"), tab, { tab = it; moverTab = 0 }) }
            if (tab == 2) {
                when (val g = global) {
                    is Load.Loading -> item { Label("載入全球市場中…", Modifier.padding(vertical = 16.dp)) }
                    is Load.Err -> item { Text("全球市場載入失敗：" + g.message, color = C.Red, fontSize = 13.sp) }
                    is Load.Ok -> {
                        g.value.groupBy { it.first }.forEach { (group, rows) ->
                            item { SectionTitle(group) }
                            items(rows.map { it.second }.chunked(2)) { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    pair.forEach { gi ->
                                        Column(
                                            Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(C.Card).padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            Label(gi.name)
                                            NumText(Fmt.num(gi.price, if (gi.price >= 1000) 0 else 2), 16.sp, weight = FontWeight.Bold)
                                            NumText(Fmt.signedPct(gi.changePct), 12.sp, Trend.fg(gi.changePct))
                                        }
                                    }
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                        item { Label("資料來源：Yahoo Finance；期貨與加密貨幣為近乎 24 小時交易，漲跌以前一交易日收盤計算。", Modifier.padding(top = 6.dp)) }
                    }
                }
            } else if (tab == 0) {
                item {
                    Card(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(phaseColor(s.phase)); HGap(6.dp)
                            Text(s.phase.label, Modifier.weight(1f), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Label(s.countdown)
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Selected)) {
                            Box(Modifier.fillMaxWidth(s.progress.coerceIn(0f, 1f)).fillMaxHeight().background(C.Primary))
                        }
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Label("盤前 " + s.preTw); Label("開盤 " + s.openTw); Label("收盤 " + s.closeTw); Label("盤後至 " + s.postEndTw)
                        }
                        Label("台灣時間 · " + if (s.isDst) "夏令" else "冬令")
                    }
                }
                items(AppState.usIndices.chunked(3)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { ix -> IndexCard(ix, Modifier.weight(1f)) { nav.navigate(Routes.index(ix.symbol)) } } }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallCard("美 10 年債", Fmt.num(AppState.macro[0].value, 2) + "%", Modifier.weight(1f))
                        SmallCard("美元指數", Fmt.num(AppState.macro[1].value, 2), Modifier.weight(1f))
                        SmallCard("USD/TWD ›", Fmt.num(AppState.usdTwd, 2), Modifier.weight(1f).clickable { nav.navigate(Routes.FX) })
                    }
                }
            } else {
                items(AppState.twIndices.chunked(2)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { ix -> IndexCard(ix, Modifier.weight(1f)) { nav.navigate(Routes.index(ix.symbol)) } } }
                }
                marketChangesEntry(nav)
                twPicksSection(pickSide, { pickSide = it }, pickBoard, { pickBoard = it }, ::open)
            }
            if (tab != 2) boardSection(board, tab == 0, moverTab, { moverTab = it }, ::open)
            item {
                Text(
                    "財經行事曆 ›", color = C.Primary, fontSize = 14.sp,
                    modifier = Modifier.clickable { nav.navigate(Routes.CALENDAR) }.padding(vertical = 12.dp),
                )
            }
        }
    }
}

private fun LazyListScope.boardSection(
    board: Load<MarketBoards.Board>,
    us: Boolean,
    moverTab: Int,
    onMoverTab: (Int) -> Unit,
    open: (String) -> Unit,
) {
    when (board) {
        is Load.Loading -> item { Label("載入類股與排行中…", Modifier.padding(vertical = 16.dp)) }
        is Load.Err -> item { Text("市場資料載入失敗：" + board.message, color = C.Red, fontSize = 13.sp) }
        is Load.Ok -> {
            val b = board.value
            if (b.sectors.isNotEmpty()) {
                item { SectionTitle(if (us) "類股熱力圖" else "類股漲跌", if (us) "S&P 500 類股 ETF" else "證交所類股指數") }
                item { Segmented(listOf("方塊", "排行"), if (AppState.sectorRanked) 1 else 0, { AppState.sectorRanked = it == 1 }) }
                if (AppState.sectorRanked) {
                    val sorted = b.sectors.sortedByDescending { it.changePct }
                    val maxAbs = sorted.maxOf { abs(it.changePct) }.takeIf { it > 0 } ?: 1.0
                    items(sorted) { m -> SectorRankRow(m, maxAbs) }
                } else {
                    items(b.sectors.chunked(4)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { SectorTile(it, Modifier.weight(1f)) }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            item { SectionTitle("排行", if (us) "Yahoo Finance" else "證交所收盤 · 成交 ≥ 500 張") }
            item { Segmented(listOf("漲幅", "跌幅", "成交量"), moverTab, onMoverTab) }
            val list = when (moverTab) { 0 -> b.gainers; 1 -> b.losers; else -> b.actives }
            if (list.isEmpty()) item { Label("目前沒有排行資料") }
            items(list) { m -> MoverRow(m, us) { open(m.symbol) } }
            b.notes.forEach { n -> item { Label(n) } }
        }
    }
}

@Composable
private fun MoverRow(m: YahooExtra.Mover, us: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.name, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarketTag(if (us) Market.US else Market.TW); HGap(6.dp)
                NumText(m.symbol, 12.sp, C.Muted); HGap(8.dp)
                Label("量 " + Fmt.num(m.volume) + if (us) "" else " 張")
            }
        }
        val lim = if (us || m.changePct > -9.0 && m.changePct < 9.0) 0
            else limitDir(m.price, m.price / (1 + m.changePct / 100), m.symbol)
        if (lim != 0) { LimitTag(lim); HGap(6.dp) }
        Column(horizontalAlignment = Alignment.End) {
            LimitPrice(Fmt.price(m.price), 14.sp, C.Text, lim, FontWeight.Normal)
            ChangePill(m.changePct)
        }
    }
}

@Composable
private fun SmallCard(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(C.Card).padding(10.dp)) {
        Label(label)
        NumText(value, 13.sp)
    }
}

@Composable
private fun SectorTile(m: SectorMove, modifier: Modifier) {
    val upIsRed = AppState.colorMode == ColorMode.TW
    val strong = abs(m.changePct) >= 1.0
    val isUp = m.changePct >= 0
    val redSide = isUp == upIsRed
    val bg = when {
        abs(m.changePct) < 0.05 -> C.Card2
        redSide && strong -> Color(0xFF7A2A2E)
        redSide -> Color(0xFF4A2428)
        strong -> Color(0xFF1D5A3C)
        else -> Color(0xFF1B3B2B)
    }
    val fg = if (abs(m.changePct) < 0.05) C.Text2 else if (redSide) Color(0xFFFFE3E3) else Color(0xFFE0FFF0)
    Column(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(m.name, color = fg, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        NumText(Fmt.signedPct(m.changePct, 2), 11.sp, fg)
    }
}

// ============================== 財經行事曆 ==============================

@Composable
fun CalendarScreen(nav: NavController) {
    var filter by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    val kinds = listOf<EventKind?>(null) + EventKind.entries
    var events by remember { mutableStateOf<Load<List<CalendarEvent>>>(Load.Loading) }
    LaunchedEffect(reload) {
        events = Load.Loading
        events = runCatching { CalendarRepo.load(reload > 0) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("財經行事曆", onBack = { nav.popBackStack() }) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, contentDescription = "重新整理", tint = C.Text) }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kinds.forEachIndexed { i, k ->
                        val on = i == filter
                        Text(
                            k?.label ?: "全部",
                            modifier = Modifier.clip(RoundedCornerShape(17.dp))
                                .background(if (on) C.Selected else C.Card)
                                .clickable { filter = i }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            color = if (on) C.Text else C.Text2, fontSize = 12.sp,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
            item { Label("未來 120 天 · 時間皆為台灣時間 · 包含自選與持股") }
            when (val e = events) {
                is Load.Loading -> item { Label("載入行事曆中…（每檔查詢一次，自選越多越久）", Modifier.padding(vertical = 16.dp)) }
                is Load.Err -> item { Text("行事曆載入失敗：" + e.message, color = C.Red, fontSize = 13.sp) }
                is Load.Ok -> {
                    val list = e.value.filter { filter == 0 || it.kind == kinds[filter] }
                    if (list.isEmpty()) item { Label("這段期間沒有事件", Modifier.padding(vertical = 16.dp)) }
                    list.groupBy { it.dateLabel }.forEach { (date, evs) ->
                        item { Text(date, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
                        items(evs) { ev -> EventRow(ev) }
                    }
                }
            }
            item {
                Label("財報與除息日來自 Yahoo Finance，公司可能調整；FOMC 依聯準會公告日程。", Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun EventRow(e: CalendarEvent) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        NumText(e.time, 13.sp, C.Text2, modifier = Modifier.width(48.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title, Modifier.weight(1f, fill = false), color = C.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (e.isHolding) {
                    HGap(6.dp)
                    Text(
                        "持股", color = Color(0xFF9CC3FF), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E3350)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Label(e.detail)
        }
        HGap(6.dp)
        Text(e.kind.label, color = C.Muted, fontSize = 11.sp)
    }
}

/** 類股排行列：以長條長度表示漲跌幅大小 */
@Composable
private fun SectorRankRow(m: SectorMove, maxAbs: Double) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(m.name, Modifier.width(96.dp), color = C.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.weight(1f).height(10.dp)) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth((abs(m.changePct) / maxAbs).toFloat().coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(3.dp)).background(Trend.fg(m.changePct)),
            )
        }
        HGap(6.dp)
        NumText(Fmt.signedPct(m.changePct), 12.sp, Trend.fg(m.changePct), modifier = Modifier.width(64.dp))
    }
}
