package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    var filter by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val session = rememberUsSession()
    val scope = rememberCoroutineScope()
    var addMsg by remember { mutableStateOf<String?>(null) }
    val list = AppState.quotes.filter { q ->
        when (filter) {
            1 -> q.market == Market.TW
            2 -> q.market == Market.US
            3 -> q.isEtf
            else -> true
        } && (query.isBlank() || q.symbol.contains(query, true) || q.name.contains(query, true))
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("自選行情") {
            IconButton(onClick = { scope.launch { MarketData.refreshAll() } }, enabled = !AppState.refreshing) {
                Icon(Icons.Filled.Refresh, contentDescription = "更新行情", tint = if (AppState.refreshing) C.Muted else C.Text)
            }
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, contentDescription = "搜尋", tint = C.Text)
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                    query = ""
                                }
                            }
                            .padding(14.dp),
                    )
                }
                addMsg?.let { Label(it) }
            }
            Segmented(listOf("全部", "台股", "美股", "ETF"), filter, { filter = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(Market.TW, twStatus(), Modifier.weight(1f))
                StatusChip(Market.US, session.phase.label.take(4) + " · 收 " + session.closeTw, Modifier.weight(1f))
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(list, key = { it.symbol }) { q -> QuoteRow(q) { nav.navigate(Routes.stock(q.symbol)) } }
            item {
                Label(
                    "美股價格以 USD 顯示；點進個股可看台幣換算",
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }
}

private fun twStatus(): String {
    val now = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Taipei"))
    val t = now.toLocalTime()
    val weekend = now.dayOfWeek.value >= 6
    return when {
        weekend -> "休市"
        t >= java.time.LocalTime.of(9, 0) && t < java.time.LocalTime.of(13, 30) -> "交易中 · 收 13:30"
        else -> "已收盤 13:30"
    }
}

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
fun QuoteRow(q: Quote, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
        Sparkline(q.spark, Trend.fg(q.change), Modifier.size(width = 60.dp, height = 24.dp))
        HGap(10.dp)
        Column(Modifier.widthIn(min = 96.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            NumText(if (q.price > 0) Fmt.price(q.price) else "—", 15.sp)
            ChangePill(q.changePct)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Card2))
}

fun sourceText(src: QuoteSource, at: Long): String =
    if (at == 0L) src.label else src.label + " " + Fmt.time(at)

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

@Composable
fun StockDetailScreen(nav: NavController, symbol: String) {
    val q = AppState.quote(symbol)
    if (q == null) {
        Column(Modifier.fillMaxSize().background(C.Bg)) {
            TopBar(symbol, onBack = { nav.popBackStack() })
            Label("找不到 $symbol", Modifier.padding(16.dp))
        }
        return
    }
    val session = rememberUsSession()
    var range by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    val holding = AppState.holdings.firstOrNull { it.symbol == q.symbol }
    val ccy = if (q.market == Market.US) "$" else ""

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar(q.symbol, onBack = { nav.popBackStack() }) {
            val held = AppState.holdings.any { it.symbol == q.symbol }
            if (!held) {
                IconButton(onClick = { if (AppState.removeWatch(q.symbol)) nav.popBackStack() }) {
                    Icon(Icons.Filled.Close, contentDescription = "移出自選", tint = C.Muted)
                }
            }
            IconButton(onClick = { nav.navigate(Routes.ALERTS) }) {
                Icon(Icons.Filled.Notifications, contentDescription = "設定提醒", tint = C.Text)
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(q.name, color = C.Text, fontSize = 15.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MarketTag(q.market); HGap(6.dp)
                        Label((q.stats?.exchange ?: if (q.market == Market.TW) "TWSE" else "US") + " · " + q.market.currency)
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        NumText(Fmt.price(q.price), 32.sp, weight = FontWeight.Bold)
                        HGap(10.dp)
                        NumText(
                            Fmt.signed(q.change, 2) + "（" + Fmt.pct(q.changePct) + "）", 14.sp, Trend.fg(q.change),
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }
                    if (q.market == Market.US) {
                        Label("≈ NT$ " + Fmt.num(q.price * AppState.usdTwd) + " · 匯率 " + Fmt.num(AppState.usdTwd, 2))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Dot(phaseColor(session.phase), 7.dp); HGap(6.dp)
                            Label(session.phase.label + " · 收盤 " + session.closeTw + "（台灣時間）")
                        }
                    } else {
                        Label(twStatus())
                    }
                    Label("資料：" + sourceText(q.source, q.updatedAt))
                }
            }
            item { Segmented(listOf("1日", "5日", "1月", "6月", "1年", "5年"), range, { range = it }) }
            item {
                val pts = if (q.change >= 0) listOf(96f, 92f, 98f, 88f, 84f, 86f, 78f, 80f, 72f, 70f, 74f, 64f, 60f, 62f, 56f, 58f, 52f, 50f, 48f)
                    .map { 150f - it } else listOf(48f, 52f, 50f, 58f, 56f, 62f, 66f, 64f, 72f, 76f, 74f, 82f, 86f, 84f, 90f, 92f, 94f, 96f, 98f).map { 150f - it }
                Sparkline(pts, Trend.fg(q.change), Modifier.fillMaxWidth().height(140.dp))
            }
            item { Segmented(listOf("概覽", "財報", "股息", "新聞"), tab, { tab = it }) }
            item {
                val s = q.stats
                if (tab == 0 && s != null) {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Column(Modifier.weight(1f)) {
                                KeyValue("市值", s.marketCap); KeyValue("EPS (TTM)", s.eps)
                                KeyValue("52 週高", s.high52); KeyValue("Beta", s.beta)
                            }
                            Column(Modifier.weight(1f)) {
                                KeyValue("本益比", s.pe); KeyValue("殖利率", s.yieldPct)
                                KeyValue("52 週低", s.low52); KeyValue("下次財報", s.nextEarnings)
                            }
                        }
                        Label("基本面為示意資料，下一版接上資料來源", Modifier.padding(top = 6.dp))
                    }
                } else {
                    Label("此區塊將在下一版接上資料來源後顯示", Modifier.padding(vertical = 16.dp))
                }
            }
            if (holding != null) {
                item {
                    val pl = (q.price - holding.avgCost) * holding.shares
                    val pct = (q.price / holding.avgCost - 1) * 100
                    Card(padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Label("我的持股")
                                NumText(Fmt.shares(holding.shares) + " 股 · 均價 " + Fmt.price(holding.avgCost), 13.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                NumText(Fmt.signed(pl, 2, ccy), 14.sp, Trend.fg(pl), FontWeight.Bold)
                                NumText(Fmt.pct(pct), 12.sp, Trend.fg(pl))
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(C.Bg).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, C.Border, RoundedCornerShape(12.dp))
                    .clickable { nav.navigate(Routes.ALERTS) },
                contentAlignment = Alignment.Center,
            ) { Text("設提醒", color = C.Text, fontSize = 15.sp) }
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp)).background(C.Primary)
                    .clickable { nav.navigate(Routes.trade(q.symbol)) },
                contentAlignment = Alignment.Center,
            ) { Text("記一筆交易", color = C.OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
