package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.MarketData
import com.claudeetf.app.data.QuoteSource
import kotlinx.coroutines.launch
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.Quote
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt

@Composable
fun WatchlistScreen(nav: NavController) {
    var filter by remember { mutableStateOf("all") } // all / tw / us / etf / g<id>
    var sort by remember { mutableIntStateOf(0) }
    var heat by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf<Quote?>(null) }
    quick?.let { qq -> QuickTradeDialog(qq) { quick = null } }
    var editing by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val session = rememberUsSession()
    val scope = rememberCoroutineScope()
    var addMsg by remember { mutableStateOf<String?>(null) }
    // 滑動：左滑移除（可復原）、右滑加入群組
    var undo by remember { mutableStateOf<Pair<Quote, Int>?>(null) }
    var undoGroup by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var groupPick by remember { mutableStateOf<Quote?>(null) }
    var swipeMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(swipeMsg) { if (swipeMsg != null) { kotlinx.coroutines.delay(6_000); swipeMsg = null; undo = null; undoGroup = null } }
    groupPick?.let { gq -> GroupPickDialog(gq) { groupPick = null } }
    val group = if (filter.startsWith("g")) AppState.groups.firstOrNull { "g" + it.id == filter } else null
    val sortNames = listOf("自訂", "漲幅", "跌幅", "代號", "價格", "成交量", "振幅")
    // 三關欄位或卡片版面：背景補抓各檔三關價
    LaunchedEffect(AppState.watchColumn, AppState.watchColumn2, AppState.watchLayout, AppState.quotes.size) {
        val cols = setOf(AppState.watchColumn, AppState.watchColumn2)
        if ("keys" in cols || AppState.watchLayout == "card") KeyLevelsUi.load(AppState.quotes.toList())
        if ("div" in cols) com.claudeetf.app.data.WatchExtras.loadDividends(AppState.quotes.toList())
        if ("inst" in cols) com.claudeetf.app.data.WatchExtras.loadInstitutional()
        if ("pe" in cols) com.claudeetf.app.data.WatchExtras.loadPe()
        if (cols.any { it in listOf("dt", "credit", "rev") }) com.claudeetf.app.data.WatchExtras.loadTwDaily()
    }
    val base = AppState.quotes.filter { q ->
        (editing && group != null) || when (filter) {
            "tw" -> q.market == Market.TW
            "us" -> q.market == Market.US
            "etf" -> q.isEtf || q.symbol.startsWith("00")
            "all" -> true
            else -> group?.symbols?.contains(q.symbol) == true
        }
    }.filter { q -> query.isBlank() || q.symbol.contains(query, true) || q.name.contains(query, true) }
    val list = when (sort) {
        1 -> base.sortedByDescending { it.changePct }
        2 -> base.sortedBy { it.changePct }
        3 -> base.sortedBy { it.symbol }
        4 -> base.sortedByDescending { it.price * AppState.fxFor(it.market) }
        5 -> base.sortedByDescending { (AppState.dayStats[it.symbol]?.volume ?: 0.0) * it.price * AppState.fxFor(it.market) }
        6 -> base.sortedByDescending { amplitude(it) ?: -1.0 }
        else -> base
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("自選行情") {
            Text(
                "比較", color = C.Primary, fontSize = 14.sp,
                modifier = Modifier.clickable { nav.navigate(Routes.compare(AppState.quotes.take(2).joinToString(",") { it.symbol })) }
                    .padding(horizontal = 6.dp, vertical = 12.dp),
            )
            Text(
                if (editing) "完成" else "編輯", color = C.Primary, fontSize = 14.sp,
                modifier = Modifier.clickable { editing = !editing; sort = 0 }.padding(horizontal = 6.dp, vertical = 12.dp),
            )
            IconButton(onClick = { scope.launch { MarketData.refreshAll() } }, enabled = !AppState.refreshing) {
                Icon(Icons.Filled.Refresh, contentDescription = "更新行情", tint = if (AppState.refreshing) C.Muted else C.Text)
            }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = "搜尋", tint = C.Text)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (searching) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("輸入代號或名稱，例如 2330、AAPL") },
                    colors = fieldColors(),
                )
                // 輸入時自動列出相關個股（停 0.3 秒後查詢），名稱後的 ＋ 直接加入自選
                val q = query.trim()
                var hits by remember { mutableStateOf<List<com.claudeetf.app.data.StockDirectory.Item>>(emptyList()) }
                var looking by remember { mutableStateOf(false) }
                LaunchedEffect(q) {
                    if (q.isEmpty()) { hits = emptyList(); return@LaunchedEffect }
                    kotlinx.coroutines.delay(300)
                    looking = true
                    hits = runCatching { com.claudeetf.app.data.StockDirectory.search(q) }.getOrDefault(emptyList())
                    looking = false
                }
                if (q.isNotEmpty()) Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(10.dp)).background(C.Card)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val up = q.uppercase()
                    // 目錄找不到、但看起來是代號：仍可直接加入
                    val rows = if (hits.none { it.symbol == up } && up.all { it.isLetterOrDigit() || it == '.' } && !looking && hits.isEmpty())
                        listOf(com.claudeetf.app.data.StockDirectory.Item(up, "（目錄查無名稱，仍可加入）", if (up.first().isDigit()) Market.TW else Market.US, ""))
                    else hits
                    if (looking && hits.isEmpty()) Label("搜尋中…", Modifier.padding(14.dp))
                    rows.forEach { it ->
                        val watched = AppState.isWatched(it.symbol)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (watched) nav.navigate(Routes.stock(it.symbol))
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MarketTag(it.market); HGap(8.dp)
                            NumText(it.symbol, 14.sp, C.Text, FontWeight.Bold); HGap(8.dp)
                            Text(it.name, Modifier.weight(1f), color = C.Text2, fontSize = 13.sp, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (it.tag.isNotEmpty()) { Label(it.tag); HGap(8.dp) }
                            if (watched) Text("✓", color = C.Muted, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
                            else Text("＋", color = C.Primary, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                                    scope.launch {
                                        val sym = it.symbol
                                        addMsg = MarketData.addAndFetch(sym) ?: "已加入 $sym"
                                        // 以目錄的中文名稱為準
                                        if (it.tag.isNotEmpty()) AppState.quote(sym)?.let { qq -> if (qq.name == qq.symbol) AppState.renameQuote(sym, it.name) }
                                        group?.let { g -> AppState.toggleInGroup(g.id, sym) }
                                    }
                                }.padding(horizontal = 10.dp, vertical = 2.dp))
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(C.Card2))
                    }
                }
                addMsg?.let { Label(it) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val chips = listOf("all" to "全部", "tw" to "台股", "us" to "美股", "etf" to "ETF") +
                    AppState.groups.map { ("g" + it.id) to it.name }
                chips.forEach { (k, label) -> FilterChipText(label, filter == k) { filter = k } }
                FilterChipText("＋ 群組", false) { newGroup = "" }
            }
            swipeMsg?.let { m ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(m, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
                    if (undo != null || undoGroup != null) Text("復原", color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            undo?.let { (q, i) -> AppState.restoreWatch(q, i) }
                            undoGroup?.let { (g, sym) -> AppState.toggleInGroup(g, sym) }
                            undo = null; undoGroup = null; swipeMsg = null
                        }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            newGroup?.let { name ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        name, { newGroup = it }, singleLine = true, modifier = Modifier.weight(1f),
                        placeholder = { Text("群組名稱，例如 高股息") }, colors = fieldColors(),
                    )
                    Text("建立", color = C.Primary, fontSize = 14.sp, modifier = Modifier.clickable {
                        val id = AppState.addGroup(name)
                        filter = "g$id"; editing = true; newGroup = null
                    }.padding(8.dp))
                    Text("取消", color = C.Muted, fontSize = 14.sp, modifier = Modifier.clickable { newGroup = null }.padding(8.dp))
                }
            }
            if (editing && group != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("勾選要放進「${group.name}」的股票", Modifier.weight(1f))
                    Text("刪除群組", color = C.Red, fontSize = 13.sp, modifier = Modifier.clickable {
                        AppState.deleteGroup(group.id); filter = "all"
                    }.padding(8.dp))
                }
            } else if (editing) {
                Label("用 ↑ ↓ 調整順序（排序為「自訂」時套用）")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(Market.TW, com.claudeetf.app.util.TwSession.info().label, Modifier.weight(1f))
                    HGap(8.dp)
                    StatusChip(Market.US, session.phase.label.take(4) + " · 收 " + session.closeTw, Modifier.weight(1f))
                }
                LiveBanner()
                // 版面、排序、欄位、第二欄：整合成一行下拉選單
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val card = AppState.watchLayout == "card"
                    DropPick("版面", if (card) "卡片" else "清單", listOf("card" to "卡片", "list" to "清單"), AppState.watchLayout,
                        Modifier.weight(0.9f)) { AppState.updateWatchLayout(it) }
                    DropPick("排序", if (heat) "熱度圖" else sortNames[sort], listOf("heat" to "熱度圖") + sortNames.mapIndexed { i, n -> i.toString() to n },
                        if (heat) "heat" else sort.toString(), Modifier.weight(1f)) { v -> if (v == "heat") heat = true else { sort = v.toInt(); heat = false } }
                    DropPick("欄位", watchColumns.firstOrNull { it.first == AppState.watchColumn }?.second ?: "—", watchColumns, AppState.watchColumn,
                        Modifier.weight(1f)) { AppState.updateWatchColumn(it) }
                    DropPick("第二欄", watchColumns.firstOrNull { it.first == AppState.watchColumn2 }?.second ?: "不顯示",
                        listOf("" to "不顯示") + watchColumns.filter { it.first != AppState.watchColumn }, AppState.watchColumn2,
                        Modifier.weight(1f)) { AppState.updateWatchColumn2(it) }
                }
                if (sort != 0 && !heat && list.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("套用為自訂順序", color = C.Primary, fontSize = 12.sp, modifier = Modifier.clickable {
                            AppState.applyQuoteOrder(list.map { it.symbol }); sort = 0
                        }.padding(vertical = 4.dp))
                        Text("存成新群組（${sortNames[sort]}）", color = C.Primary, fontSize = 12.sp, modifier = Modifier.clickable {
                            val id = AppState.groupFrom(sortNames[sort] + " " + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("M/d")), list.map { it.symbol })
                            filter = "g$id"
                        }.padding(vertical = 4.dp))
                    }
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            if (list.isEmpty()) item { Label(if (group != null) "群組是空的，按「編輯」勾選股票" else "沒有符合的股票", Modifier.padding(vertical = 20.dp)) }
            if (heat && !editing) {
                item {
                    Heatmap(
                        list.filter { it.price > 0 }.map { q ->
                            // 自選沒有市值資料，以漲跌幅大小加權，讓變動大的更顯眼
                            HeatItem(q.symbol, q.symbol, 1.0 + kotlin.math.abs(q.changePct), q.changePct)
                        },
                        height = 360.dp,
                    ) { nav.navigate(Routes.stock(it)) }
                }
                item { Label("方塊越大代表今日漲跌幅越大；顏色代表方向", Modifier.padding(top = 6.dp)) }
            }
            if (!heat && !editing && AppState.watchLayout == "card") {
                items(list.chunked(AppState.watchStyle.cardCols), key = { row -> row.joinToString(",") { it.symbol } }) { row ->
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { q -> QuoteCard(q, Modifier.weight(1f), onLongClick = { quick = q }) { nav.navigate(Routes.stock(q.symbol)) } }
                        repeat(AppState.watchStyle.cardCols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            } else if (!heat || editing) items(list, key = { it.symbol }) { q ->
                if (!editing) {
                    SwipeRow(
                        q,
                        removeLabel = if (group != null) "移出群組" else "移除",
                        onRemove = {
                            if (group != null) {
                                AppState.toggleInGroup(group.id, q.symbol); undoGroup = group.id to q.symbol; undo = null
                                swipeMsg = "已將 ${q.symbol} 移出「${group.name}」"
                            } else {
                                val idx = AppState.quotes.indexOfFirst { it.symbol == q.symbol }
                                if (AppState.removeWatch(q.symbol)) { undo = q to idx; undoGroup = null; swipeMsg = "已移除 ${q.symbol} ${q.name}" }
                                else { undo = null; undoGroup = null; swipeMsg = "${q.symbol} 仍有持股，無法移出自選" }
                            }
                        },
                        onGroup = { groupPick = q },
                    ) {
                        QuoteRow(q, onLongClick = { quick = q }) { nav.navigate(Routes.stock(q.symbol)) }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (group != null) {
                            val inGroup = q.symbol in group.symbols
                            Text(
                                if (inGroup) "☑" else "☐", color = if (inGroup) C.Primary else C.Muted, fontSize = 20.sp,
                                modifier = Modifier.clickable { AppState.toggleInGroup(group.id, q.symbol) }.padding(10.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(q.name, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) { MarketTag(q.market); HGap(6.dp); NumText(q.symbol, 12.sp, C.Muted) }
                        }
                        if (group == null) {
                            Text("↑", color = C.Text, fontSize = 18.sp, modifier = Modifier.clickable { AppState.moveQuote(q.symbol, -1) }.padding(12.dp))
                            Text("↓", color = C.Text, fontSize = 18.sp, modifier = Modifier.clickable { AppState.moveQuote(q.symbol, 1) }.padding(12.dp))
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Card2))
                }
            }
            item {
                Label(
                    "美股價格以 USD 顯示；長按個股可快速記帳",
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun FilterChipText(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (on) C.Text else C.Muted, fontSize = 13.sp,
        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(15.dp)).background(if (on) C.Selected else C.Card)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

fun twStatus(): String = com.claudeetf.app.util.TwSession.info().let { it.label + " · " + it.detail }

@Composable
private fun StatusChip(market: Market, text: String, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(C.Card).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarketTag(market); HGap(6.dp)
        Text(text, color = C.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun QuoteRow(q: Quote, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    // 價格跳動：上漲／下跌時以對應顏色閃一下
    var last by remember(q.symbol) { mutableStateOf(q.price) }
    var flash by remember(q.symbol) { mutableIntStateOf(0) }
    LaunchedEffect(q.price) {
        if (last != 0.0 && q.price != last) {
            flash = if (q.price > last) 1 else -1
            last = q.price
            kotlinx.coroutines.delay(700)
            flash = 0
        } else last = q.price
    }
    val flashColor by androidx.compose.animation.animateColorAsState(
        if (flash == 0) androidx.compose.ui.graphics.Color.Transparent else Trend.fg(flash.toDouble()).copy(alpha = 0.18f),
        label = "flash",
    )
    WatchRowBody(q, AppState.watchStyle, Modifier.background(flashColor).combinedClickable(onClick = onClick, onLongClick = onLongClick), true)
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Card2))
}


val watchColumns = listOf("spark" to "走勢", "keys" to "三關", "volume" to "成交量", "amp" to "振幅", "div" to "除權息", "inst" to "法人", "dt" to "當沖", "credit" to "信用", "rev" to "月營收", "pe" to "本益比", "pnl" to "持股損益")

/** 行情頁工具列的下拉選單：上方小字標題、下方目前值 ▾ */
@Composable
private fun DropPick(title: String, value: String, opts: List<Pair<String, String>>, sel: String, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card).clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(title, color = C.Muted, fontSize = 10.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, Modifier.weight(1f), color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("▾", color = C.Primary, fontSize = 12.sp)
            }
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = C.Card) {
            opts.forEach { (v, n) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text((if (v == sel) "✓ " else "　") + n, color = if (v == sel) C.Primary else C.Text, fontSize = 14.sp) },
                    onClick = { onPick(v); open = false },
                )
            }
        }
    }
}

/** 當日振幅 %：(最高 − 最低) ÷ 昨收 */
fun amplitude(q: Quote): Double? {
    val d = AppState.dayStats[q.symbol] ?: return null
    val hi = d.high ?: return null
    val lo = d.low ?: return null
    if (q.prevClose <= 0 || hi < lo) return null
    return (hi - lo) / q.prevClose * 100
}

/** 自選清單中間欄，依設定顯示不同資訊 */
@Composable
fun WatchCell(q: Quote, col: String) {
    // 高度依內容自動撐開（系統字體放大時不會截掉第二行），寬度 76～96dp
    val m = Modifier.widthIn(min = 76.dp, max = 96.dp).heightIn(min = 30.dp)
    when (col) {
        "volume" -> CellText(AppState.dayStats[q.symbol]?.volume?.let { volumeText(it, q.market) } ?: "—", "量", m)
        "amp" -> CellText(amplitude(q)?.let { Fmt.num(it, 2) + "%" } ?: "—", "振幅", m)
        "pe" -> {
            val etf = q.isEtf || (q.market == Market.TW && q.symbol.startsWith("00"))
            val pe = AppState.peMap[q.symbol]?.let { Fmt.num(it, 1) } ?: q.stats?.pe?.takeIf { it.isNotBlank() && it != "—" }
            CellText(pe ?: "—", if (etf && pe == null) "ETF 無" else "本益比", m)
        }
        "div" -> {
            val b = AppState.divBriefs[q.symbol]
            val md = java.time.format.DateTimeFormatter.ofPattern("M/d")
            fun d(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("Asia/Taipei")).format(md)
            when {
                b == null -> CellText("…", "除權息", m)
                b.nextEx != null -> CellText("除息 " + d(b.nextEx), "上次 " + (b.lastAmount?.let { Fmt.num(it, 2) } ?: "—"), m)
                b.lastTime != null -> CellText(Fmt.num(b.lastAmount ?: 0.0, 2), "上次 " + d(b.lastTime), m)
                else -> CellText("—", "無配息", m)
            }
        }
        "dt", "credit", "rev" -> {
            val d = AppState.twDaily[q.symbol]
            if (q.market != Market.TW) CellText("—", "美股", m)
            else when (col) {
                "dt" -> CellText(d?.dayTradePct?.let { Fmt.num(it, 1) + "%" } ?: "—",
                    d?.dayTradeLots?.let { "當沖 " + Fmt.num(it) + " 張" } ?: "當沖比（上市）", m)
                "credit" -> {
                    val mg = d?.margin
                    if (mg?.financing == null) CellText("—", "信用", m)
                    else {
                        val chg = mg.financingPrev?.let { mg.financing - it }
                        Column(m, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                            NumText("資 " + Fmt.num(mg.financing), 12.sp)
                            Text(
                                (chg?.let { Fmt.signed(it) + " " } ?: "") + "券 " + (mg.short?.let { Fmt.num(it) } ?: "—"),
                                color = chg?.let { Trend.fg(it) } ?: C.Muted, fontSize = 9.sp, maxLines = 1,
                            )
                        }
                    }
                }
                else -> {
                    val r = d?.revenue
                    val yoy = r?.yoyPct?.replace(",", "")?.toDoubleOrNull()
                    if (r == null) CellText("—", if (q.isEtf || q.symbol.startsWith("00")) "ETF 無營收" else "月營收", m)
                    else Column(m, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                        NumText("年 " + (yoy?.let { Fmt.signedPct(it, 1) } ?: "—"), 12.sp, yoy?.let { Trend.fg(it) } ?: C.Text)
                        Text(r.month + " 月 " + (r.momPct?.replace(",", "")?.toDoubleOrNull()?.let { Fmt.signedPct(it, 1) } ?: "—"),
                            color = C.Muted, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
        "inst" -> {
            val i = AppState.institutional[q.symbol]
            if (q.market != Market.TW) CellText("—", "美股", m)
            else if (i == null) CellText("—", "法人", m)
            else {
                val lots = i.total / 1000.0
                Column(m, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                    NumText(Fmt.signed(lots) + " 張", 12.sp, Trend.fg(lots))
                    Text("外" + Fmt.signed(i.foreign / 1000.0) + " 投" + Fmt.signed(i.trust / 1000.0), color = C.Muted, fontSize = 9.sp, maxLines = 1)
                }
            }
        }
        "keys" -> {
            val k = AppState.keyPrices[q.symbol]
            if (k == null) CellText("—", "三關", m)
            else {
                val (pos, color) = KeyLevelsUi.position(q.price, k)
                Column(m, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                    Text(pos, color = color, fontSize = 11.sp, maxLines = 1, fontWeight = FontWeight.Bold, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    // 上關、下關分兩行，價格較長也不會被截斷
                    NumText("上 " + Fmt.price(k.upper), 9.sp, KeyLevelColors.upper)
                    NumText("下 " + Fmt.price(k.lower), 9.sp, KeyLevelColors.lower)
                }
            }
        }
        "pnl" -> {
            val h = AppState.holdings.firstOrNull { it.symbol == q.symbol }
            if (h == null || q.price <= 0) CellText("—", "未持有", m)
            else {
                val pl = (q.price - h.avgCost) * h.shares
                val pct = if (h.avgCost > 0) (q.price / h.avgCost - 1) * 100 else 0.0
                Column(m, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                    NumText(com.claudeetf.app.ui.mask(Fmt.signed(pl, if (q.market == Market.US) 2 else 0)), 12.sp, Trend.fg(pl))
                    Text(Fmt.signedPct(pct), color = Trend.fg(pl), fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        else -> Box(Modifier.size(width = 64.dp, height = 28.dp), contentAlignment = Alignment.Center) {
            Sparkline(q.spark, Trend.fg(q.change), Modifier.size(width = 60.dp, height = 24.dp))
        }
    }
}

@Composable
private fun CellText(value: String, caption: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
        NumText(value, 12.sp)
        Text(caption, color = C.Muted, fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

fun sourceText(src: QuoteSource, at: Long): String {
    if (at == 0L) return src.label
    // 2 分鐘內的報價視為即時，顯示到秒
    return if (System.currentTimeMillis() - at < 120_000) {
        "● 即時 " + java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.of("Asia/Taipei"))
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
    } else src.label + " " + Fmt.time(at)
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = C.Text,
    unfocusedTextColor = C.Text,
    focusedBorderColor = C.Primary,
    unfocusedBorderColor = C.Border,
    focusedContainerColor = C.Card,
    unfocusedContainerColor = C.Card,
    cursorColor = C.Primary,
    focusedPlaceholderColor = C.Muted,
    unfocusedPlaceholderColor = C.Muted,
    focusedLabelColor = C.Primary,
    unfocusedLabelColor = C.Muted,
)

/** 長按自選跳出的快速買賣：現價、今天、預設帳戶與手續費自動帶入 */
@Composable
private fun QuickTradeDialog(q: Quote, onDismiss: () -> Unit) {
    val us = q.market == Market.US
    var sell by remember { mutableStateOf(false) }
    var shares by remember { mutableStateOf(if (us) "1" else "1000") }
    var price by remember { mutableStateOf(if (q.price > 0) Fmt.price(q.price).replace(",", "") else "") }
    var error by remember { mutableStateOf<String?>(null) }
    val acc = AppState.accountsFor(q.market).firstOrNull()
    val n = shares.toDoubleOrNull() ?: 0.0
    val p = price.toDoubleOrNull() ?: 0.0
    val amount = n * p
    val fee = AppState.feeFor(acc?.id ?: 0, q.market, amount)
    val etf = q.isEtf || q.symbol.startsWith("00")
    val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))
    val tax = if (sell && !us) com.claudeetf.app.data.TaxRules.sellTax(q.symbol, amount, today, com.claudeetf.app.data.TaxRules.looksLikeDayTrade(q.symbol, today)) else 0.0
    val held = AppState.holdings.firstOrNull { it.symbol == q.symbol }?.shares ?: 0.0
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        title = { Text("快速記帳：${q.symbol} ${q.name}", color = C.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(listOf("買進", "賣出"), if (sell) 1 else 0, { sell = it == 1; error = null }, background = C.Bg)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(shares, { shares = it; error = null }, label = { Text("股數") }, singleLine = true,
                        modifier = Modifier.weight(1f), colors = fieldColors(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    OutlinedTextField(price, { price = it; error = null }, label = { Text("價格") }, singleLine = true,
                        modifier = Modifier.weight(1f), colors = fieldColors(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                }
                if (!us) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("1 張" to 1000.0, "5 張" to 5000.0, "全部" to held).forEach { (label, v) ->
                            if (v > 0) Text(label, color = C.Primary, fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(C.Bg)
                                    .clickable { shares = Fmt.shares(v).replace(",", "") }.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
                Label((acc?.name ?: "未設定帳戶") + " · 手續費 " + Fmt.num(fee, if (us) 2 else 0) +
                    (if (tax > 0) " · 交易稅 " + Fmt.num(tax) else "") + " · 持有 " + Fmt.shares(held) + " 股")
                Label((if (us) "$" else "NT$") + Fmt.num(if (sell) amount - fee - tax else amount + fee, if (us) 2 else 0) +
                    (if (sell) " 入帳" else " 支出"))
                error?.let { Text(it, color = C.Red, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Text("儲存", color = C.Primary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable {
                    val date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                    val err = AppState.addTrade(
                        com.claudeetf.app.data.Trade(
                            if (sell) com.claudeetf.app.data.TradeType.SELL else com.claudeetf.app.data.TradeType.BUY,
                            q.symbol, date, n, p, if (us) AppState.usdTwd else 1.0, fee, tax, account = acc?.id ?: 0,
                        )
                    )
                    if (err == null) onDismiss() else error = err
                }.padding(12.dp))
        },
        dismissButton = {
            Text("取消", color = C.Muted, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp))
        },
    )
}

/** 盤中顯示「即時更新中」與最後更新時間 */
@Composable
fun LiveBanner() {
    if (!MarketData.anyMarketLive()) return
    val last = AppState.lastLiveAt
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(if (last > 0 && System.currentTimeMillis() - last < 30_000) C.Green else C.Tw, 8.dp)
        HGap(8.dp)
        val synced = com.claudeetf.app.data.remote.FugleStream.connected && MarketData.isTwIntraday()
        val parts = listOfNotNull(
            if (MarketData.isTwIntraday()) (if (synced) "台股富果串流同步" else "台股每 5 秒") else null,
            if (MarketData.usLive()) AppState.usRefreshLabel() else null,
        )
        Text(
            "即時更新中・" + parts.joinToString("、") + (if (last > 0) "・最後 " + java.time.Instant.ofEpochMilli(last)
                .atZone(java.time.ZoneId.of("Asia/Taipei")).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) else ""),
            Modifier.weight(1f), color = C.Text2, fontSize = 12.sp, maxLines = 2,
        )
        // 美股盤中可直接調整更新頻率
        if (MarketData.usLive()) {
            var menu by remember { mutableStateOf(false) }
            Box {
                Text("頻率 ▾", color = C.Primary, fontSize = 12.sp, modifier = Modifier.clickable { menu = true }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp))
                androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = C.Card) {
                    listOf(0L to "即時（Finnhub 逐筆）", 1_000L to "每 1 秒", 2_000L to "每 2 秒", 3_000L to "每 3 秒", 5_000L to "每 5 秒", 10_000L to "每 10 秒").forEach { (v, n) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text((if (AppState.usRefreshMs == v) "✓ " else "　") + n, color = C.Text, fontSize = 14.sp) },
                            onClick = { AppState.updateUsRefreshMs(v); menu = false },
                        )
                    }
                }
            }
        } else Label(listOfNotNull(if (MarketData.isTwIntraday()) "台股" else null).joinToString("・"))
    }
}

/** 卡片版面：依「自選個股顯示格式」排版（名稱、價格、漲跌、附加項目順序） */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun QuoteCard(q: Quote, modifier: Modifier, onLongClick: () -> Unit, onClick: () -> Unit) {
    var last by remember(q.symbol) { mutableStateOf(q.price) }
    var flash by remember(q.symbol) { mutableIntStateOf(0) }
    LaunchedEffect(q.price) {
        if (last != 0.0 && q.price != last) {
            flash = if (q.price > last) 1 else -1
            last = q.price
            kotlinx.coroutines.delay(700)
            flash = 0
        } else last = q.price
    }
    WatchCardBody(q, AppState.watchStyle, flash, modifier.clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick))
}



/** 自選列滑動：左滑移除、右滑加入群組（放開後列會彈回） */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(q: Quote, removeLabel: String, onRemove: () -> Unit, onGroup: () -> Unit, content: @Composable () -> Unit) {
    // confirmValueChange 可能連續呼叫，0.8 秒內只處理一次
    val last = remember { longArrayOf(0L) }
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            val now = System.currentTimeMillis()
            if (v != androidx.compose.material3.SwipeToDismissBoxValue.Settled && now - last[0] < 800) return@rememberSwipeToDismissBoxState false
            if (v != androidx.compose.material3.SwipeToDismissBoxValue.Settled) last[0] = now
            when (v) {
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> onRemove()
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> onGroup()
                else -> {}
            }
            false // 不真的滑掉，交給清單更新
        },
        positionalThreshold = { it * 0.35f },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val dir = state.dismissDirection
            val toRemove = dir == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))
                    .background(if (toRemove) C.Red.copy(alpha = 0.85f) else C.Primary.copy(alpha = 0.85f))
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (toRemove) Arrangement.End else Arrangement.Start,
            ) {
                Text(if (toRemove) removeLabel else "加入群組", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        },
    ) {
        Box(Modifier.background(C.Bg)) { content() }
    }
}

/** 快速加入／移出群組，也可直接建立新群組 */
@Composable
private fun GroupPickDialog(q: Quote, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        title = { Text("${q.symbol} ${q.name} 加入群組", color = C.Text, fontSize = 16.sp) },
        text = {
            Column {
                if (AppState.groups.isEmpty()) Label("還沒有群組，輸入名稱建立")
                AppState.groups.forEach { g ->
                    val inG = q.symbol in g.symbols
                    Row(
                        Modifier.fillMaxWidth().clickable { AppState.toggleInGroup(g.id, q.symbol) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (inG) "☑" else "☐", color = if (inG) C.Primary else C.Muted, fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(g.name, Modifier.weight(1f), color = C.Text, fontSize = 14.sp)
                        Label("${g.symbols.size} 檔")
                    }
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text("新群組名稱") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    colors = fieldColors(),
                )
            }
        },
        confirmButton = {
            Text(if (name.isBlank()) "完成" else "建立並加入", color = C.Primary, fontSize = 15.sp, modifier = Modifier.clickable {
                if (name.isNotBlank()) AppState.groupFrom(name, listOf(q.symbol))
                onDismiss()
            }.padding(12.dp))
        },
    )
}
