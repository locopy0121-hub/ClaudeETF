package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.Trade
import com.claudeetf.app.data.TradeOrigin
import com.claudeetf.app.data.TradeType
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt

@Composable
fun TradesScreen(nav: NavController) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var tag by remember { mutableStateOf<String?>(null) }
    val allTags = AppState.trades.flatMap { it.tags }.distinct().sorted()
    val types = listOf<TradeType?>(null) + TradeType.entries
    val list = AppState.trades.filter { t ->
        t.origin != TradeOrigin.LEGACY &&
            (filter == 0 || t.type == types[filter]) &&
            (tag == null || tag in t.tags) &&
            (query.isBlank() || t.symbol.contains(query.trim(), true) ||
                (AppState.quote(t.symbol)?.name?.contains(query.trim(), true) == true))
    }
    val legacyCount = AppState.trades.count { it.origin == TradeOrigin.LEGACY }
    val realizedSum = list.filter { it.type == TradeType.SELL }.sumOf { AppState.realizedById[it.id] ?: 0.0 }
    val dividendSum = list.filter { it.type == TradeType.DIVIDEND }.sumOf { AppState.dividendById[it.id] ?: 0.0 }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("交易紀錄", onBack = { nav.popBackStack() }) {
            Text("年度報表", color = C.Primary, fontSize = 14.sp, modifier = Modifier.clickable { nav.navigate(Routes.REPORT) }.padding(horizontal = 8.dp, vertical = 12.dp))
            IconButton(onClick = { nav.navigate(Routes.trade()) }) {
                Icon(Icons.Filled.Add, contentDescription = "新增交易", tint = C.Text)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜尋代號或名稱") },
                    colors = fieldColors(),
                )
            }
            item { Segmented(types.map { it?.label ?: "全部" }, filter, { filter = it }) }
            if (allTags.isNotEmpty()) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (listOf<String?>(null) + allTags).forEach { tg ->
                            val on = tg == tag
                            Text(
                                tg?.let { "#$it" } ?: "全部標籤", color = if (on) C.Text else C.Muted, fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Selected else C.Card)
                                    .clickable { tag = tg }.padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
            item {
                Card(padding = 14.dp) {
                    KeyValue("筆數", list.size.toString())
                    KeyValue("已實現損益（篩選範圍）", mask(Fmt.signed(realizedSum)), Trend.fg(realizedSum))
                    KeyValue("股息實收（篩選範圍）", mask("NT$" + Fmt.num(dividendSum)))
                    KeyValue("今年已實現（全部）", mask(Fmt.signed(AppState.realizedTwdYtd)), Trend.fg(AppState.realizedTwdYtd))
                }
            }
            if (list.isEmpty()) item { Label("沒有符合的交易", Modifier.padding(vertical = 16.dp)) }
            list.groupBy { if (it.origin == TradeOrigin.OPENING) "期初持股" else it.date.take(7) }.forEach { (month, ts) ->
                item { Text(month, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp)) }
                items(ts, key = { it.id }) { t -> TradeRow(t) { nav.navigate(Routes.trade(t.symbol, t.id)) } }
            }
            if (legacyCount > 0) {
                item {
                    Label(
                        "另有 $legacyCount 筆舊版紀錄已併入「期初持股」，只保留不重複計算。",
                        Modifier.padding(top = 8.dp),
                    )
                }
            }
            item { Label("點一筆交易可以編輯或刪除；修改後持股、現金與損益會自動重算。", Modifier.padding(top = 4.dp)) }
            item {
                Text("資料健檢：找出異常或重複的交易 ›", color = C.Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { nav.navigate(Routes.HEALTH) }.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun TradeRow(t: Trade, onClick: () -> Unit) {
    val market = AppState.marketOf(t.symbol)
    val isUs = market == Market.US
    val ccy = if (isUs) "$" else ""
    val amount = when (t.type) {
        TradeType.BUY -> t.shares * t.price + t.fee
        TradeType.SELL -> t.shares * t.price - t.fee - t.tax
        TradeType.DIVIDEND -> t.shares * t.price
        TradeType.SPLIT -> 0.0
    }
    val typeColor = when (t.type) {
        TradeType.BUY -> Trend.fg(1.0)
        TradeType.SELL -> Trend.fg(-1.0)
        else -> C.Primary
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (t.origin == TradeOrigin.OPENING) "期初" else t.type.label, color = typeColor, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.Card2).padding(horizontal = 6.dp, vertical = 1.dp),
                )
                HGap(6.dp); MarketTag(market); HGap(6.dp)
                Text(
                    t.symbol + " " + (AppState.quote(t.symbol)?.name ?: ""), color = C.Text, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Label(
                (if (t.origin == TradeOrigin.OPENING) "" else t.date + " · ") +
                    when (t.type) {
                        TradeType.SPLIT -> "1 股拆成 " + Fmt.num(t.price, 2) + " 股"
                        TradeType.DIVIDEND -> Fmt.shares(t.shares) + " 股 × 每股 " + ccy + Fmt.num(t.price, 4).trimEnd('0').trimEnd('.')
                        else -> Fmt.shares(t.shares) + " 股 × " + ccy + Fmt.price(t.price)
                    },
            )
            val extra = listOfNotNull(
                t.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" },
                t.attachments.takeIf { it.isNotEmpty() }?.let { "附件 ${it.size}" },
                t.note.takeIf { it.isNotBlank() && t.origin != TradeOrigin.OPENING },
            )
            if (extra.isNotEmpty()) Text(extra.joinToString(" · "), color = C.Primary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (t.type != TradeType.SPLIT) NumText(mask(ccy + Fmt.num(amount, if (isUs) 2 else 0)), 14.sp)
            AppState.realizedById[t.id]?.let { r -> NumText(mask("損益 " + Fmt.signed(r)), 12.sp, Trend.fg(r)) }
            AppState.dividendById[t.id]?.let { d -> NumText(mask("實收 NT$" + Fmt.num(d)), 12.sp, C.Muted) }
        }
    }
}
