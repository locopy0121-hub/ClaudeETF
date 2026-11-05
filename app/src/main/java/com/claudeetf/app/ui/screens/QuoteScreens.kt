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
            Text(
                "比較", color = C.Primary, fontSize = 14.sp,
                modifier = Modifier.clickable { nav.navigate(Routes.compare(AppState.quotes.take(2).joinToString(",") { it.symbol })) }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            )
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

fun twStatus(): String {
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
