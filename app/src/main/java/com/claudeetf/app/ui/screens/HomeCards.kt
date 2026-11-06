package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.claudeetf.app.data.MarketData
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun CardTitle(title: String, trailing: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        trailing?.let { Label(it) }
    }
}

/** 今日損益：台股、美股分開 */
@Composable
fun PnlSplitCard() {
    fun pnl(m: Market) = AppState.holdings.sumOf { h ->
        val q = AppState.quote(h.symbol) ?: return@sumOf 0.0
        if (q.market != m) 0.0 else h.shares * q.change * AppState.fxFor(m)
    }
    val tw = pnl(Market.TW)
    val us = pnl(Market.US)
    Card {
        CardTitle("今日損益", "TWD")
        Row {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) { MarketTag(Market.TW); HGap(6.dp); Label("台股") }
                NumText(mask(Fmt.signed(tw)), 18.sp, Trend.fg(tw), weight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) { MarketTag(Market.US); HGap(6.dp); Label("美股") }
                NumText(mask(Fmt.signed(us)), 18.sp, Trend.fg(us), weight = FontWeight.Bold)
            }
        }
        Label("合計 " + mask(Fmt.signed(tw + us)), color = Trend.fg(tw + us))
    }
}

@Composable
private fun MiniQuoteRow(nav: NavController, symbol: String, extra: String? = null, extraColor: androidx.compose.ui.graphics.Color = C.Muted) {
    val q = AppState.quote(symbol) ?: return
    Row(
        Modifier.fillMaxWidth().clickable { nav.navigate(Routes.stock(q.symbol)) }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(q.name, color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarketTag(q.market); HGap(4.dp); NumText(q.symbol, 11.sp, C.Muted)
                extra?.let { HGap(6.dp); Text(it, color = extraColor, fontSize = 11.sp, maxLines = 1) }
            }
        }
        Sparkline(q.spark, Trend.fg(q.change), Modifier.size(width = 52.dp, height = 22.dp))
        HGap(8.dp)
        Column(horizontalAlignment = Alignment.End) {
            NumText(if (q.price > 0) Fmt.price(q.price) else "—", 13.sp)
            NumText(Fmt.signedPct(q.changePct), 11.sp, Trend.fg(q.change))
        }
    }
}

/** 自選前五檔 */
@Composable
fun WatchTopCard(nav: NavController) {
    Card {
        CardTitle("自選", "前五檔 ›") { nav.navigate(Routes.WATCH) }
        AppState.quotes.take(5).forEach { MiniQuoteRow(nav, it.symbol) }
    }
}

/** 持股市值前五大 */
@Composable
fun HoldingsTopCard(nav: NavController) {
    val total = AppState.totalMarketValueTwd().takeIf { it > 0 } ?: 1.0
    Card {
        CardTitle("持股前五大", "依市值 ›") { nav.navigate(Routes.PORTFOLIO) }
        val top = AppState.holdings.sortedByDescending { AppState.marketValueTwd(it) }.take(5)
        if (top.isEmpty()) Label("尚無持股")
        top.forEach { h -> MiniQuoteRow(nav, h.symbol, "占 " + Fmt.num(AppState.marketValueTwd(h) / total * 100, 1) + "%") }
    }
}

/** 持股三關位置 */
@Composable
fun KeysCard(nav: NavController) {
    val held = AppState.holdings.mapNotNull { AppState.quote(it.symbol) }
    LaunchedEffect(held.size) { KeyLevelsUi.load(held) }
    Card {
        CardTitle("持股三關位置")
        if (held.isEmpty()) Label("尚無持股")
        held.forEach { q ->
            val k = AppState.keyPrices[q.symbol]
            val (pos, color) = if (k != null) KeyLevelsUi.position(q.price, k) else ("計算中…" to C.Muted)
            MiniQuoteRow(nav, q.symbol, pos, color)
        }
    }
}

/** 持股即將除息 */
@Composable
fun DivCalCard(nav: NavController) {
    val held = AppState.holdings.mapNotNull { AppState.quote(it.symbol) }
    LaunchedEffect(held.size) { com.claudeetf.app.data.WatchExtras.loadDividends(held) }
    val md = java.time.format.DateTimeFormatter.ofPattern("M/d")
    val tpe = java.time.ZoneId.of("Asia/Taipei")
    val upcoming = held.mapNotNull { q -> AppState.divBriefs[q.symbol]?.nextEx?.let { q to it } }.sortedBy { it.second }
    Card {
        CardTitle("持股除息行事曆")
        if (upcoming.isEmpty()) Label(if (held.all { AppState.divBriefs.containsKey(it.symbol) }) "近期沒有已公布的除息日" else "載入中…")
        upcoming.forEach { (q, t) ->
            val b = AppState.divBriefs[q.symbol]
            val h = AppState.holdings.firstOrNull { it.symbol == q.symbol }
            val est = if (b?.lastAmount != null && h != null) "，參考上次 " + Fmt.num(b.lastAmount, 2) + " 估 " + mask(Fmt.num(b.lastAmount * h.shares)) else ""
            MiniQuoteRow(nav, q.symbol, "除息 " + java.time.Instant.ofEpochMilli(t).atZone(tpe).format(md) + est, C.Primary)
        }
    }
}

/** 最近通知 */
@Composable
fun AlertsCard(nav: NavController) {
    Card {
        CardTitle("最近通知", "提醒中心 ›") { nav.navigate(Routes.ALERTS) }
        val list = AppState.alertHistory.take(5)
        if (list.isEmpty()) Label("尚無通知")
        list.forEach { h ->
            Row(Modifier.padding(vertical = 3.dp)) {
                NumText(Fmt.time(h.time), 11.sp, C.Muted, modifier = Modifier.width(72.dp))
                Text(h.text, Modifier.weight(1f), color = C.Text2, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 匯率與總經 */
@Composable
fun FxCard(nav: NavController) {
    Card {
        CardTitle("匯率與總經", "›") { nav.navigate(Routes.FX) }
        KeyValue("USD/TWD", Fmt.num(AppState.usdTwd, 3))
        AppState.macro.forEach { m -> KeyValue(m.name, Fmt.num(m.value, 2) + "（" + Fmt.signedPct(m.changePct) + "）", Trend.fg(m.changePct)) }
    }
}

/** 單一指數大卡：數值、漲跌與當日走勢 */
@Composable
fun IndexBigCard(nav: NavController, symbol: String) {
    val ix = (AppState.twIndices + AppState.usIndices + AppState.macro).firstOrNull { it.symbol == symbol }
    var spark by remember(symbol) { mutableStateOf<List<Float>>(emptyList()) }
    var prev by remember(symbol) { mutableStateOf<Float?>(null) }
    LaunchedEffect(symbol) {
        while (true) {
            runCatching { withContext(Dispatchers.IO) { YahooExtra.history(symbol, "1d") } }.getOrNull()?.let { h ->
                spark = h.closes.filter { !it.isNaN() }; prev = h.prevClose?.toFloat()
            }
            // 盤中每分鐘更新走勢，收盤後不再重抓
            kotlinx.coroutines.delay(if (MarketData.anyMarketLive()) 60_000 else 30 * 60_000L)
        }
    }
    Card(Modifier.clickable { nav.navigate(Routes.index(symbol)) }) {
        CardTitle(ix?.name ?: symbol, "走勢 ›")
        Row(verticalAlignment = Alignment.Bottom) {
            NumText(ix?.let { Fmt.num(it.value, 2) } ?: "—", 24.sp, Trend.fg(ix?.changePct ?: 0.0), weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            ix?.let { ChangePill(it.changePct) }
        }
        if (spark.size >= 2) {
            Sparkline(spark, Trend.fg(ix?.changePct ?: (spark.last() - (prev ?: spark.first())).toDouble()), Modifier.fillMaxWidth().height(64.dp), fill = true)
        }
        ix?.takeIf { it.updatedAt > 0 }?.let { Label("更新 " + Fmt.time(it.updatedAt)) }
    }
}

/** 單一個股大卡：價格、走勢、三關、持股損益 */
@Composable
fun StockBigCard(nav: NavController, symbol: String) {
    val q = AppState.quote(symbol)
    if (q == null) {
        Card { Label("$symbol 不在自選清單，請到設定移除此卡片或重新加入自選") }
        return
    }
    LaunchedEffect(symbol) { KeyLevelsUi.load(listOf(q)) }
    val h = AppState.holdings.firstOrNull { it.symbol == symbol }
    Card(Modifier.clickable { nav.navigate(Routes.stock(symbol)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarketTag(q.market); HGap(6.dp)
            Text(q.name, Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            NumText(q.symbol, 12.sp, C.Muted)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            NumText(if (q.price > 0) Fmt.price(q.price) else "—", 26.sp, Trend.fg(q.change), weight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                NumText(Fmt.signed(q.change, 2), 13.sp, Trend.fg(q.change))
                ChangePill(q.changePct)
            }
        }
        if (q.spark.size >= 2) Sparkline(q.spark, Trend.fg(q.change), Modifier.fillMaxWidth().height(56.dp), fill = true)
        AppState.keyPrices[symbol]?.let { k ->
            val (pos, color) = KeyLevelsUi.position(q.price, k)
            Label("三關：$pos（上 ${Fmt.price(k.upper)}／中 ${Fmt.price(k.mid)}／下 ${Fmt.price(k.lower)}）", color = color)
        }
        if (h != null && q.price > 0) {
            val pl = (q.price - h.avgCost) * h.shares * AppState.fxFor(q.market)
            val day = q.change * h.shares * AppState.fxFor(q.market)
            Label("持有 " + Fmt.shares(h.shares) + " 股 · 今日 " + mask(Fmt.signed(day)) + " · 未實現 " + mask(Fmt.signed(pl)), color = Trend.fg(day))
        }
        Label(sourceText(q.source, q.updatedAt))
    }
}

/** 設定頁：新增指數或個股卡片 */
@Composable
fun AddHomeCardPicker(onDone: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.Card,
        title = { Text("新增首頁卡片", color = C.Text, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Segmented(listOf("指數", "個股"), tab, { tab = it }, background = C.Bg)
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    if (tab == 0) {
                        (AppState.twIndices + AppState.usIndices + AppState.macro).distinctBy { it.symbol }.filter { it.symbol.isNotBlank() }.forEach { ix ->
                            val id = "idx:" + ix.symbol
                            PickRow(ix.name, Fmt.num(ix.value, 2), id in AppState.homeCards()) { AppState.addHomeCard(id) }
                        }
                    } else {
                        if (AppState.quotes.isEmpty()) Label("自選清單是空的")
                        AppState.quotes.forEach { q ->
                            val id = "stk:" + q.symbol
                            PickRow(q.symbol + " " + q.name, Fmt.price(q.price), id in AppState.homeCards()) { AppState.addHomeCard(id) }
                        }
                    }
                }
                Label("新卡片會加在首頁最上方，可在設定調整順序")
            }
        },
        confirmButton = { Text("完成", color = C.Primary, fontSize = 15.sp, modifier = Modifier.clickable(onClick = onDone).padding(12.dp)) },
    )
}

@Composable
private fun PickRow(name: String, value: String, added: Boolean, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = !added, onClick = onAdd).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f), color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        NumText(value, 12.sp, C.Muted); HGap(8.dp)
        Text(if (added) "已加入" else "＋ 加入", color = if (added) C.Muted else C.Primary, fontSize = 12.sp)
    }
}
