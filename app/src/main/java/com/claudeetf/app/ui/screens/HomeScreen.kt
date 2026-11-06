package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.IndexQuote
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.MarketData
import kotlinx.coroutines.launch
import com.claudeetf.app.data.SampleData
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.Dot
import com.claudeetf.app.ui.HGap
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.MarketTag
import com.claudeetf.app.ui.ChangePill
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.Routes
import com.claudeetf.app.ui.Segmented
import com.claudeetf.app.ui.Sparkline
import com.claudeetf.app.ui.StackBar
import com.claudeetf.app.ui.TopBar
import com.claudeetf.app.ui.mask
import com.claudeetf.app.ui.phaseColor
import com.claudeetf.app.ui.rememberUsSession
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt

@Composable
fun HomeScreen(nav: NavController) {
    val total = AppState.totalAssetsTwd()
    val today = AppState.todayPnlTwd()
    val mv = AppState.totalMarketValueTwd()
    val cost = AppState.totalCostTwd()
    val unreal = mv - cost
    val twMv = AppState.totalMarketValueTwd(Market.TW)
    val usMv = AppState.totalMarketValueTwd(Market.US)
    val usUsd = AppState.holdingsOf(Market.US).sumOf { AppState.marketValue(it) }
    val session = rememberUsSession()
    var range by remember { mutableIntStateOf(1) }
    val scope = rememberCoroutineScope()
    val navPoints = AppState.navSeries(listOf(7, 31, 92, 366, null)[range])
    val trend = navPoints.map { it.second.toFloat() }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("總覽") {
            IconButton(onClick = { nav.navigate(Routes.SEARCH) }) {
                Icon(androidx.compose.material.icons.Icons.Filled.Search, contentDescription = "搜尋", tint = C.Text)
            }
            IconButton(onClick = { scope.launch { MarketData.refreshAll() } }, enabled = !AppState.refreshing) {
                Icon(Icons.Filled.Refresh, contentDescription = "更新行情", tint = if (AppState.refreshing) C.Muted else C.Text)
            }
            IconButton(onClick = { AppState.hideAmounts = !AppState.hideAmounts }) {
                Icon(Icons.Filled.Lock, contentDescription = "隱藏金額", tint = if (AppState.hideAmounts) C.Primary else C.Text)
            }
            IconButton(onClick = { nav.navigate(Routes.ALERTS) }) {
                Icon(Icons.Filled.Notifications, contentDescription = "提醒中心", tint = C.Text)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppState.homeCards().forEach { card ->
                when (card) {
                    "assets" -> {
                    item {
                        Card {
                            Label("總資產（TWD）")
                            NumText(mask(Fmt.num(total)), 32.sp, weight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Label("今日"); HGap(8.dp)
                                NumText(mask(Fmt.signed(today)) + "（" + Fmt.pct(today / (total - today) * 100) + "）", 13.sp, Trend.fg(today))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Label("未實現"); HGap(8.dp)
                                NumText(mask(Fmt.signed(unreal)) + "（" + Fmt.pct(if (cost > 0) unreal / cost * 100 else 0.0) + "）", 13.sp, Trend.fg(unreal))
                            }
                            if (trend.size >= 2) {
                                val chg = trend.last() - trend.first()
                                Sparkline(trend, Trend.fg(chg.toDouble()), Modifier.fillMaxWidth().height(72.dp), fill = true)
                                Row {
                                    Label(navPoints.first().first, Modifier.weight(1f))
                                    NumText(
                                        "區間 " + mask(Fmt.signed(chg.toDouble())) + "（" + Fmt.pct(if (trend.first() > 0) chg / trend.first() * 100.0 else 0.0) + "）",
                                        12.sp, Trend.fg(chg.toDouble()),
                                    )
                                }
                            } else {
                                Label("每天開啟 App 會自動記錄總資產，累積兩天後顯示走勢", Modifier.padding(vertical = 18.dp))
                            }
                            Segmented(listOf("1週", "1月", "3月", "1年", "全部"), range, { range = it }, background = C.Bg)
                            Text(
                                "損益日曆 ›", color = C.Primary, fontSize = 13.sp,
                                modifier = Modifier.clickable { nav.navigate(Routes.PNL) }.padding(vertical = 6.dp),
                            )
                        }
                    }
                    }
                    "alloc" -> {
                    item {
                        Card {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("資產配置", Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Label("USD/TWD " + Fmt.num(AppState.usdTwd, 2))
                            }
                            StackBar(
                                listOf(
                                    (twMv / total).toFloat() to C.Tw,
                                    (usMv / total).toFloat() to C.Us,
                                    (AppState.cashTwd / total).toFloat() to C.Gray,
                                )
                            )
                            AllocRow({ MarketTag(Market.TW) }, "台股", twMv / total, mask(Fmt.num(twMv)))
                            AllocRow({ MarketTag(Market.US) }, "美股", usMv / total, mask(Fmt.num(usMv)) + " · $" + mask(Fmt.num(usUsd)))
                            AllocRow({ Label("現金") }, "", AppState.cashTwd / total, mask(Fmt.num(AppState.cashTwd)))
                        }
                    }
                    }
                    "session" -> {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(C.Card)
                                .clickable { nav.navigate(Routes.MARKET) }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Dot(phaseColor(session.phase))
                            HGap(8.dp)
                            Text("美股 " + session.phase.label, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
                            Label("收盤 ${session.closeTw}（${if (session.isDst) "夏令" else "冬令"}）›")
                        }
                    }
                    }
                    "source" -> {
                    item {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card)
                                .clickable { nav.navigate(Routes.SETTINGS) }.padding(12.dp),
                        ) {
                            Label("台股：" + MarketData.twSourcePlan())
                            Label("美股：" + (AppState.sourceStatus["US"] ?: "Yahoo Finance"))
                            Label("匯率：" + AppState.fxSource + (if (AppState.fxUpdatedAt > 0) " " + Fmt.time(AppState.fxUpdatedAt) else ""))
                        }
                    }
                    }
                    "movers" -> item { HoldingMovers(nav) }
                    "index" -> {
                    val idx = listOf(AppState.twIndices[0]) + AppState.usIndices.filter {
                        it.name in listOf("S&P 500", "Nasdaq", "費城半導體")
                    }
                    items(idx.chunked(2)) { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { IndexCard(it, Modifier.weight(1f)) }
                        }
                    }
                    }
                }
            }
            item {
                Text(
                    "自訂首頁卡片 ›", color = C.Muted, fontSize = 12.sp,
                    modifier = Modifier.clickable { nav.navigate(Routes.SETTINGS) }.padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun AllocRow(tag: @Composable () -> Unit, name: String, ratio: Double, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        tag(); HGap(6.dp)
        if (name.isNotEmpty()) { Text(name, color = C.Text, fontSize = 13.sp); HGap(6.dp) }
        NumText(Fmt.num(ratio * 100, 1) + "%", 13.sp, modifier = Modifier.weight(1f))
        NumText(value, 13.sp)
    }
}

@Composable
fun IndexCard(q: IndexQuote, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(C.Card)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Label(q.name)
        NumText(Fmt.num(q.value, 2), 16.sp, weight = FontWeight.Bold)
        NumText(Fmt.signedPct(q.changePct), 12.sp, Trend.fg(q.changePct))
    }
}

/** 今日持股漲跌前三 */
@Composable
private fun HoldingMovers(nav: NavController) {
    val rows = AppState.holdings.mapNotNull { h -> AppState.quote(h.symbol)?.takeIf { it.price > 0 && it.prevClose > 0 } }
        .sortedByDescending { kotlin.math.abs(it.changePct) }.take(3)
    Card {
        Text("今日持股漲跌", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (rows.isEmpty()) Label("尚無持股報價")
        rows.forEach { q ->
            Row(
                Modifier.fillMaxWidth().clickable { nav.navigate(Routes.stock(q.symbol)) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MarketTag(q.market); HGap(6.dp)
                Text(q.symbol + " " + q.name, Modifier.weight(1f), color = C.Text, fontSize = 13.sp, maxLines = 1)
                ChangePill(q.changePct)
            }
        }
    }
}
