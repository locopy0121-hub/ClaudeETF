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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
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
import com.claudeetf.app.data.MarketChanges
import com.claudeetf.app.data.MarketData
import com.claudeetf.app.data.StockDirectory
import com.claudeetf.app.data.TwPicks
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.launch

private fun kindColor(k: String): Color = when (k) {
    MarketChanges.NEW -> Trend.up
    MarketChanges.SOON -> Color(0xFFF2B544)
    MarketChanges.ADDED -> C.Primary
    MarketChanges.GONE -> Trend.down
    else -> C.Muted
}

@Composable
private fun KindChip(k: String) {
    Text(k, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(kindColor(k)).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
private fun EvRow(e: MarketChanges.Ev, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        KindChip(e.kind); HGap(8.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.name, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                HGap(6.dp); NumText(e.code, 12.sp, C.Muted); HGap(4.dp); Label(if (e.otc) "櫃" else "市")
            }
            Text(e.detail, color = C.Text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (e.kind != MarketChanges.GONE) Text("›", color = C.Muted, fontSize = 16.sp)
    }
}

/** 開啟 App 時：有未確認的市場異動就提示一次，按「知道了」後不再提醒 */
@Composable
fun MarketChangePrompt(onOpenLog: () -> Unit) {
    val list = MarketChanges.unseen
    if (list.isEmpty() || AppState.locked) return
    AlertDialog(
        onDismissRequest = { },
        containerColor = C.Card,
        title = { Text("台股市場異動（${list.size} 筆）", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Label("目錄更新時發現的新上市、即將上市、下市與更名。確認後不再提醒，之後可在「市場 › 台股 › 市場異動紀錄」查看。")
                list.groupBy { it.kind }.toSortedMap(compareBy { MarketChanges.KINDS.indexOf(it) }).forEach { (k, evs) ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        KindChip(k); HGap(6.dp); Label(evs.size.toString() + " 檔")
                    }
                    evs.take(12).forEach { e ->
                        Text(e.code + "  " + e.name + "　" + e.detail, color = C.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                    if (evs.size > 12) Label("…另 " + (evs.size - 12) + " 檔")
                }
            }
        },
        confirmButton = {
            Text("知道了", color = C.Primary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { MarketChanges.markSeen() }.padding(10.dp))
        },
        dismissButton = {
            Text("查看紀錄", color = C.Text2, fontSize = 15.sp,
                modifier = Modifier.clickable { MarketChanges.markSeen(); onOpenLog() }.padding(10.dp))
        },
    )
}

/** 市場異動紀錄頁 */
@Composable
fun MarketChangesScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var kind by remember { mutableIntStateOf(0) }
    val kinds = listOf("全部") + MarketChanges.KINDS
    fun open(code: String) = scope.launch {
        if (!AppState.isWatched(code)) MarketData.addAndFetch(code)
        nav.navigate(Routes.stock(code))
    }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("市場異動紀錄", onBack = { nav.popBackStack() }) {
            IconButton(onClick = { scope.launch { StockDirectory.refresh(force = true) } }, enabled = !MarketChanges.updating) {
                Icon(Icons.Filled.Refresh, contentDescription = "立即更新", tint = if (MarketChanges.updating) C.Muted else C.Text)
            }
        }
        val list = MarketChanges.events.filter { kind == 0 || it.kind == kinds[kind] }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Card(padding = 12.dp) {
                    Text(if (MarketChanges.updating) "更新台股目錄中…" else "台股目錄", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Label((if (MarketChanges.updatedAt > 0) "上次更新 " + java.time.Instant.ofEpochMilli(MarketChanges.updatedAt)
                        .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")) else "尚未更新") +
                        " · 每天自動更新，收盤（14:35）後再更新一次")
                    Label("來源：證交所、櫃買中心每日收盤行情＋上市櫃證券（ISIN）一覽表，可搜尋到剛掛牌、尚無成交的股票與 ETF。")
                    MarketChanges.lastError?.let { Text(it, color = C.Red, fontSize = 12.sp) }
                }
            }
            item {
                Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kinds.forEachIndexed { i, n ->
                        val c = if (i == 0) MarketChanges.events.size else MarketChanges.events.count { it.kind == n }
                        Text("$n $c", color = if (kind == i) C.OnPrimary else C.Text2, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (kind == i) C.Primary else C.Card).clickable { kind = i }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
            if (list.isEmpty()) item { Label("目前沒有異動紀錄。", Modifier.padding(vertical = 16.dp)) }
            list.groupBy { it.date }.forEach { (d, evs) ->
                item { SectionTitle(d.replace("-", "/"), evs.size.toString() + " 筆") }
                items(evs) { e -> EvRow(e) { if (e.kind != MarketChanges.GONE) open(e.code) } }
            }
        }
    }
}

/** 市場 › 台股：異動紀錄入口 */
fun LazyListScope.marketChangesEntry(nav: NavController) {
    item {
        val latest = MarketChanges.events.firstOrNull()
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card).clickable { nav.navigate(Routes.MARKET_CHANGES) }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("市場異動紀錄", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Label(latest?.let { "最新：" + it.kind + " " + it.code + " " + it.name + "（" + it.date.replace("-", "/") + "）" } ?: "新上市、即將上市、下市、更名")
            }
            val n = MarketChanges.unseen.size
            if (n > 0) Text("$n 新", color = Color.White, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Trend.up).padding(horizontal = 6.dp, vertical = 2.dp))
            HGap(6.dp); Text("›", color = C.Muted, fontSize = 18.sp)
        }
    }
}

/** 市場 › 台股：分析推薦排行（看漲／看跌各十檔） */
fun LazyListScope.twPicksSection(side: Int, onSide: (Int) -> Unit, board: Int, onBoard: (Int) -> Unit, open: (String) -> Unit) {
    item { SectionTitle("分析推薦排行", (if (board == 0) "上市成交值前 ${TwPicks.UNIVERSE_TSE} 檔" else "上櫃成交值前 ${TwPicks.UNIVERSE_OTC} 檔") + " · 日 K＋動能＋光譜") }
    item { Segmented(listOf("上市", "上櫃"), board, onBoard) }
    item { Segmented(listOf("看漲・值得投資", "看跌・避坑"), side, onSide) }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    TwPicks.running -> Label("分析中 " + TwPicks.done + "／" + TwPicks.total + " 檔…")
                    TwPicks.at > 0 -> Label("資料日 " + TwPicks.dataDate.replace("-", "/") + " · 分析於 " + java.time.Instant.ofEpochMilli(TwPicks.at)
                        .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")))
                    else -> Label("尚未分析")
                }
                TwPicks.error?.let { Text(it, color = C.Red, fontSize = 12.sp) }
            }
            Text(if (TwPicks.running) "分析中" else "重新分析", color = if (TwPicks.running) C.Muted else C.Primary, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !TwPicks.running) { TwPicks.start(force = true) }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        if (TwPicks.running && TwPicks.total > 0) Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Selected)) {
            Box(Modifier.fillMaxWidth(TwPicks.done.toFloat() / TwPicks.total).fillMaxHeight().background(C.Primary))
        }
    }
    val list = TwPicks.list(otc = board == 1, bull = side == 0)
    if (list.isEmpty() && !TwPicks.running && TwPicks.at > 0) item { Label((if (board == 0) "上市" else "上櫃") + (if (side == 0) "目前沒有明顯看漲的個股。" else "目前沒有明顯看跌的個股。")) }
    list.forEachIndexed { i, p -> item(key = "pick" + board + side + p.symbol) { PickCard(i + 1, p, side == 0) { open(p.symbol) } } }
    item { Label("依公開行情計算的參考排序（K 線形態、量價、支撐壓力、均線趨勢、近 20 日動能、一年股價光譜），不是投資建議，請自行判斷風險。") }
}

