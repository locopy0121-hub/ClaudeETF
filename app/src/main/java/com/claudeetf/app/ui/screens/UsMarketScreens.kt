package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.CalendarEvent
import com.claudeetf.app.data.ColorMode
import com.claudeetf.app.data.EventKind
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.SampleData
import com.claudeetf.app.data.SectorMove
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.abs

// ============================== 市場 ==============================

@Composable
fun MarketScreen(nav: NavController) {
    var tab by remember { mutableIntStateOf(0) }
    val s = rememberUsSession()

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("市場") {
            IconButton(onClick = { nav.navigate(Routes.CALENDAR) }) {
                Icon(Icons.Filled.DateRange, contentDescription = "財經行事曆", tint = C.Text)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Segmented(listOf("美股", "台股"), tab, { tab = it }) }
            if (tab == 0) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { IndexCard(it, Modifier.weight(1f)) }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallCard("美 10 年債", Fmt.num(AppState.macro[0].value, 2) + "%", Modifier.weight(1f))
                        SmallCard("美元指數", Fmt.num(AppState.macro[1].value, 2), Modifier.weight(1f))
                        SmallCard("USD/TWD", Fmt.num(AppState.usdTwd, 2), Modifier.weight(1f))
                    }
                }
                AppState.sourceStatus["IDX"]?.let { st -> item { Label("指數來源 Yahoo Finance · $st") } }
                item { SectionTitle("板塊熱力圖", "示意資料") }
                items(SampleData.sectors.chunked(4)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { SectorTile(it, Modifier.weight(1f)) }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item { SectionTitle("漲幅排行", "示意資料") }
                items(SampleData.movers) { (sym, name, pct) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NumText(sym, 14.sp); HGap(8.dp)
                        Label(name, Modifier.weight(1f))
                        NumText(Fmt.signedPct(pct, 1), 14.sp, Trend.fg(pct))
                    }
                }
                item {
                    Text(
                        "財經行事曆 ›", color = C.Primary, fontSize = 14.sp,
                        modifier = Modifier.clickable { nav.navigate(Routes.CALENDAR) }.padding(vertical = 12.dp),
                    )
                }
            } else {
                items(AppState.twIndices.chunked(2)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { IndexCard(it, Modifier.weight(1f)) }
                    }
                }
                item { SectionTitle("我的台股") }
                items(AppState.quotes.filter { it.market == Market.TW }) { q ->
                    QuoteRow(q) { nav.navigate(Routes.stock(q.symbol)) }
                }
            }
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
        redSide && strong -> Color(0xFF7A2A2E)
        redSide -> Color(0xFF4A2428)
        strong -> Color(0xFF1D5A3C)
        else -> Color(0xFF1B3B2B)
    }
    val fg = if (redSide) Color(0xFFFFE3E3) else Color(0xFFE0FFF0)
    Column(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(m.name, color = fg, fontSize = 11.sp, maxLines = 1)
        NumText(Fmt.signedPct(m.changePct, 1), 11.sp, fg)
    }
}

// ============================== 財經行事曆 ==============================

@Composable
fun CalendarScreen(nav: NavController) {
    var filter by remember { mutableIntStateOf(0) }
    val kinds = listOf<EventKind?>(null) + EventKind.entries
    val today = LocalDate.now()
    val monday = today.with(DayOfWeek.MONDAY)
    var picked by remember { mutableStateOf(today) }
    val list: List<CalendarEvent> = SampleData.events.filter { filter == 0 || it.kind == kinds[filter] }
    val weekNames = listOf("一", "二", "三", "四", "五", "六", "日")

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("財經行事曆", onBack = { nav.popBackStack() }) {
            Label("${today.monthValue} 月", Modifier.padding(end = 12.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 0..6) {
                        val d = monday.plusDays(i.toLong())
                        val on = d == picked
                        Column(
                            Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) C.Primary else C.Card)
                                .clickable { picked = d },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(weekNames[i], color = if (on) C.OnPrimary else C.Muted, fontSize = 11.sp)
                            NumText("${d.dayOfMonth}", 16.sp, if (on) C.OnPrimary else C.Text, FontWeight.Bold)
                        }
                    }
                }
            }
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
            item { Label("時間皆為台灣時間（自動夏令／冬令）") }
            val grouped = list.groupBy { it.dateLabel }
            grouped.forEach { (date, events) ->
                item { Text(date, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
                items(events) { e -> EventRow(e) }
            }
            item {
                Label("經濟數據發布時間以官方公告為準；財報日期由資料來源更新。", Modifier.padding(top = 8.dp))
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
                Text(e.title, color = C.Text, fontSize = 14.sp)
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
        if (e.importance > 0) {
            Text("●".repeat(e.importance) + "○".repeat(3 - e.importance), color = C.Tw, fontSize = 10.sp)
        } else {
            MarketTag(if (e.title.firstOrNull()?.isDigit() == true) Market.TW else Market.US)
        }
    }
}
