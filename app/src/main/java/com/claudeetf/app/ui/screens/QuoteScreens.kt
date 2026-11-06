package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
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
    val group = if (filter.startsWith("g")) AppState.groups.firstOrNull { "g" + it.id == filter } else null
    val sortNames = listOf("自訂", "漲幅", "跌幅", "代號", "價格", "成交量", "振幅")
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
                val q = query.trim().uppercase()
                if (q.isNotEmpty() && !AppState.isWatched(q) && q.all { it.isLetterOrDigit() || it == '.' }) {
                    Text(
                        "＋ 加入自選：$q",
                        color = C.Primary, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card)
                            .clickable {
                                scope.launch {
                                    addMsg = MarketData.addAndFetch(q) ?: "已加入 $q"
                                    group?.let { g -> AppState.toggleInGroup(g.id, q) }
                                    query = ""
                                }
                            }
                            .padding(14.dp),
                    )
                }
                addMsg?.let { Label(it) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val chips = listOf("all" to "全部", "tw" to "台股", "us" to "美股", "etf" to "ETF") +
                    AppState.groups.map { ("g" + it.id) to it.name }
                chips.forEach { (k, label) -> FilterChipText(label, filter == k) { filter = k } }
                FilterChipText("＋ 群組", false) { newGroup = "" }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("排序", Modifier.padding(end = 6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChipText("熱度圖", heat) { heat = !heat }
                        sortNames.forEachIndexed { i, n -> FilterChipText(n, sort == i && !heat) { sort = i; heat = false } }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("欄位", Modifier.padding(end = 6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        watchColumns.forEach { (id, n) -> FilterChipText(n, AppState.watchColumn == id) { AppState.updateWatchColumn(id) } }
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
            if (!heat || editing) items(list, key = { it.symbol }) { q ->
                if (!editing) {
                    QuoteRow(q, onLongClick = { quick = q }) { nav.navigate(Routes.stock(q.symbol)) }
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
    Row(
        Modifier
            .fillMaxWidth()
            .background(flashColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(q.name, color = C.Text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarketTag(q.market); HGap(6.dp); NumText(q.symbol, 12.sp, C.Muted)
                HGap(6.dp); Text(sourceText(q.source, q.updatedAt), color = C.Muted, fontSize = 10.sp, maxLines = 1)
            }
        }
        WatchCell(q)
        HGap(10.dp)
        Column(Modifier.widthIn(min = 96.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NumText(if (q.price > 0) Fmt.price(q.price) else "—", 15.sp)
            ChangePill(q.changePct)
            AppState.extended[q.symbol]?.let { e ->
                Text(e.label + " " + Fmt.price(e.price) + " " + Fmt.signedPct(e.changePct), color = Trend.fg(e.changePct), fontSize = 10.sp, maxLines = 1)
            }
            if (q.market == Market.TW) {
                com.claudeetf.app.util.TwSession.limitState(q.price, q.prevClose, q.isEtf || q.symbol.startsWith("00"))?.let { st ->
                    Text(st, color = Trend.fg(if (st == "漲停") 1.0 else -1.0), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Card2))
}

val watchColumns = listOf("spark" to "走勢", "volume" to "成交量", "amp" to "振幅", "pe" to "本益比", "pnl" to "持股損益")

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
private fun WatchCell(q: Quote) {
    val m = Modifier.size(width = 72.dp, height = 30.dp)
    when (AppState.watchColumn) {
        "volume" -> CellText(AppState.dayStats[q.symbol]?.volume?.let { volumeText(it, q.market) } ?: "—", "量", m)
        "amp" -> CellText(amplitude(q)?.let { Fmt.num(it, 2) + "%" } ?: "—", "振幅", m)
        "pe" -> CellText(q.stats?.pe?.takeIf { it.isNotBlank() } ?: "—", "本益比", m)
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
        else -> Sparkline(q.spark, Trend.fg(q.change), Modifier.size(width = 60.dp, height = 24.dp))
    }
}

@Composable
private fun CellText(value: String, caption: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
        NumText(value, 12.sp)
        Text(caption, color = C.Muted, fontSize = 10.sp, maxLines = 1)
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
    val tax = if (sell && !us) Math.round(amount * (if (etf) 0.001 else 0.003)).toDouble() else 0.0
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
        Text(
            (if (synced) "台股與交易所同步（富果串流）" else "即時更新中・每 5 秒") + (if (last > 0) "・最後 " + java.time.Instant.ofEpochMilli(last)
                .atZone(java.time.ZoneId.of("Asia/Taipei")).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")) else ""),
            color = C.Text2, fontSize = 12.sp,
        )
        Spacer(Modifier.weight(1f))
        Label(listOfNotNull(if (MarketData.isTwIntraday()) "台股" else null, if (MarketData.usLive()) "美股" else null).joinToString("・"))
    }
}
