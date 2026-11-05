package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.DividendInfo
import com.claudeetf.app.data.Fundamentals
import com.claudeetf.app.data.InfoItem
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.Quote
import com.claudeetf.app.data.StockInfoRepo
import com.claudeetf.app.data.TaxKind
import com.claudeetf.app.data.TaxRules
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.data.remote.friendly
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 非同步載入狀態 */
sealed class Load<out T> {
    data object Loading : Load<Nothing>()
    data class Ok<T>(val value: T) : Load<T>()
    data class Err(val message: String) : Load<Nothing>()
}

private val TPE = ZoneId.of("Asia/Taipei")
private fun date(ms: Long, pattern: String = "yyyy/MM/dd"): String =
    if (ms <= 0) "—" else Instant.ofEpochMilli(ms).atZone(TPE).format(DateTimeFormatter.ofPattern(pattern))

private val ranges = listOf("1d" to "1日", "5d" to "5日", "1mo" to "1月", "6mo" to "6月", "1y" to "1年", "5y" to "5年")
private val tabs = listOf("概覽", "財務", "股息", "新聞", "公司")

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
    val held = AppState.holdings.any { it.symbol == q.symbol }

    // 走勢
    var history by remember { mutableStateOf<Load<YahooExtra.History>>(Load.Loading) }
    LaunchedEffect(q.symbol, range) {
        history = Load.Loading
        history = runCatching { StockInfoRepo.history(q, ranges[range].first) }
            .fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }
    // 基本面（概覽、財務、公司共用）
    var fund by remember { mutableStateOf<Load<Fundamentals>>(Load.Loading) }
    LaunchedEffect(q.symbol) {
        fund = runCatching { StockInfoRepo.fundamentals(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }
    // 股息、新聞：切到該分頁才載入
    var divs by remember { mutableStateOf<Load<DividendInfo>>(Load.Loading) }
    var news by remember { mutableStateOf<Load<List<YahooExtra.News>>>(Load.Loading) }
    LaunchedEffect(q.symbol, tab) {
        if (tab == 2 && divs !is Load.Ok) {
            divs = Load.Loading
            divs = runCatching { StockInfoRepo.dividends(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
        }
        if (tab == 3 && news !is Load.Ok) {
            news = Load.Loading
            news = runCatching { StockInfoRepo.news(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
        }
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar(q.symbol, onBack = { nav.popBackStack() }) {
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { PriceHeader(q, session) }
            item { Segmented(ranges.map { it.second }, range, { range = it }) }
            item { ChartBlock(history, range) }
            item { Segmented(tabs, tab, { tab = it }) }
            when (tab) {
                0 -> overviewTab(q, fund)
                1 -> financialsTab(q, fund)
                2 -> dividendTab(q, divs)
                3 -> newsTab(news)
                else -> companyTab(fund)
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

@Composable
private fun PriceHeader(q: Quote, session: com.claudeetf.app.util.UsSession.Info) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(q.name, color = C.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarketTag(q.market); HGap(6.dp)
            Label(StockInfoRepo.yahooSymbol(q) + " · " + q.market.currency)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            NumText(if (q.price > 0) Fmt.price(q.price) else "—", 32.sp, weight = FontWeight.Bold)
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
        Label("報價：" + sourceText(q.source, q.updatedAt))
    }
}

@Composable
private fun ChartBlock(history: Load<YahooExtra.History>, range: Int) {
    Column(
        Modifier.fillMaxWidth().height(170.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (history) {
            is Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label("載入走勢中…") }
            is Load.Err -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label("走勢載入失敗：" + history.message) }
            is Load.Ok -> {
                val h = history.value
                if (h.closes.size < 2) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Label("此區間沒有走勢資料") }
                } else {
                    val base = if (range == 0) (h.prevClose?.toFloat() ?: h.closes.first()) else h.closes.first()
                    val chg = h.closes.last() - base
                    val pct = if (base != 0f) chg / base * 100 else 0f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("區間漲跌 ", Modifier.weight(1f))
                        NumText(Fmt.signedPct(pct.toDouble()), 12.sp, Trend.fg(chg.toDouble()))
                    }
                    Sparkline(h.closes, Trend.fg(chg.toDouble()), Modifier.fillMaxWidth().weight(1f), fill = true)
                    val pattern = if (range <= 1) "M/d HH:mm" else "yyyy/M/d"
                    Row {
                        Label(date(h.times.first(), pattern), Modifier.weight(1f))
                        Label("高 " + Fmt.price(h.closes.max().toDouble()) + " · 低 " + Fmt.price(h.closes.min().toDouble()))
                        Label(date(h.times.last(), pattern), Modifier.weight(1f).wrapContentWidth(Alignment.End))
                    }
                }
            }
        }
    }
}

// ---------------- 分頁內容 ----------------

private fun LazyListScope.loading(text: String = "載入中…") {
    item { Label(text, Modifier.padding(vertical = 20.dp)) }
}

private fun LazyListScope.showError(msg: String) {
    item { Text("資料載入失敗：$msg", color = C.Red, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp)) }
}

private fun LazyListScope.infoGrid(items: List<InfoItem>) {
    items(items.chunked(2)) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            row.forEach { KeyValue(it.label, it.value, modifier = Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun LazyListScope.infoList(items: List<InfoItem>) {
    items(items) { KeyValue(it.label, it.value) }
}

private fun LazyListScope.overviewTab(q: Quote, fund: Load<Fundamentals>) {
    val holding = AppState.holdings.firstOrNull { it.symbol == q.symbol }
    if (holding != null && q.price > 0) {
        item {
            val pl = (q.price - holding.avgCost) * holding.shares
            val pct = (q.price / holding.avgCost - 1) * 100
            val ccy = if (q.market == Market.US) "$" else ""
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
    when (fund) {
        is Load.Loading -> loading("載入基本資料中…")
        is Load.Err -> showError(fund.message)
        is Load.Ok -> {
            val f = fund.value
            if (f.overview.isEmpty()) item { Label("暫無概覽資料") } else infoGrid(f.overview)
            if (f.analyst.isNotEmpty()) {
                item { SectionTitle("分析師") }
                infoGrid(f.analyst)
            }
            item { Label("資料來源：Yahoo Finance" + if (q.market == Market.TW) "、證交所 OpenAPI" else "", Modifier.padding(top = 6.dp)) }
        }
    }
}

private fun LazyListScope.financialsTab(q: Quote, fund: Load<Fundamentals>) {
    when (fund) {
        is Load.Loading -> loading("載入財務資料中…")
        is Load.Err -> showError(fund.message)
        is Load.Ok -> {
            val f = fund.value
            if (f.financials.isEmpty() && f.quarterly.isEmpty() && f.yearly.isEmpty()) {
                item { Label(if (q.isEtf) "ETF 沒有公司財務資料" else "暫無財務資料") }
                return
            }
            if (f.financials.isNotEmpty()) {
                item { SectionTitle("財務指標") }
                infoList(f.financials)
            }
            if (f.quarterly.isNotEmpty()) {
                item { SectionTitle("季度 EPS", "實際 / 預估") }
                items(f.quarterly) { (period, actual, est) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(period, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
                        NumText(actual, 13.sp, modifier = Modifier.width(90.dp))
                        NumText(est, 13.sp, C.Muted, modifier = Modifier.width(70.dp))
                    }
                }
            }
            if (f.yearly.isNotEmpty()) {
                item { SectionTitle("年度營收・淨利") }
                items(f.yearly) { (year, rev, earn) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(year, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
                        NumText(rev, 13.sp, modifier = Modifier.width(100.dp))
                        NumText(earn, 13.sp, C.Muted, modifier = Modifier.width(90.dp))
                    }
                }
            }
        }
    }
}

private fun LazyListScope.dividendTab(q: Quote, divs: Load<DividendInfo>) {
    item { TaxCard(q, (divs as? Load.Ok<DividendInfo>)?.value?.history?.firstOrNull()?.amount) }
    when (divs) {
        is Load.Loading -> loading("載入配息紀錄中…")
        is Load.Err -> showError(divs.message)
        is Load.Ok -> {
            val d = divs.value
            val ccy = if (q.market == Market.US) "$" else "NT$"
            item {
                Card(padding = 14.dp) {
                    KeyValue("近 12 個月配息", if (d.ttm > 0) ccy + Fmt.num(d.ttm, 4).trimEnd('0').trimEnd('.') else "—")
                    KeyValue("現價殖利率", d.yieldPct?.let { Fmt.num(it, 2) + "%" } ?: "—")
                    KeyValue("近 5 年配息次數", d.history.size.toString())
                }
            }
            if (d.history.isEmpty()) {
                item { Label("近 5 年沒有配息紀錄") }
            } else {
                item { SectionTitle("配息紀錄", "除息日 · 每股") }
                items(d.history) { div ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        NumText(date(div.time), 13.sp, modifier = Modifier.weight(1f))
                        NumText(ccy + Fmt.num(div.amount, 4).trimEnd('0').trimEnd('.'), 13.sp)
                    }
                }
            }
            item { Label("資料來源：Yahoo Finance", Modifier.padding(top = 6.dp)) }
        }
    }
}

private fun LazyListScope.newsTab(news: Load<List<YahooExtra.News>>) {
    when (news) {
        is Load.Loading -> loading("載入新聞中…")
        is Load.Err -> showError(news.message)
        is Load.Ok -> {
            if (news.value.isEmpty()) item { Label("目前沒有相關新聞") }
            items(news.value) { n -> NewsRow(n) }
        }
    }
}

@Composable
private fun NewsRow(n: YahooExtra.News) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)
            .clickable { runCatching { uri.openUri(n.link) } }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(n.title, color = C.Text, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Label(listOf(n.publisher, date(n.time, "M/d HH:mm")).filter { it.isNotBlank() && it != "—" }.joinToString(" · "))
    }
}

private fun LazyListScope.companyTab(fund: Load<Fundamentals>) {
    when (fund) {
        is Load.Loading -> loading("載入公司資料中…")
        is Load.Err -> showError(fund.message)
        is Load.Ok -> {
            val f = fund.value
            if (f.profile.isEmpty() && f.summary == null) item { Label("暫無公司資料") }
            infoList(f.profile)
            f.summary?.let { s ->
                item { SectionTitle("公司簡介") }
                item { Text(s, color = C.Text2, fontSize = 13.sp, lineHeight = 20.sp) }
            }
        }
    }
}

/** 配息稅務：類型（可變更）、應稅比例、以持股試算 */
@Composable
private fun TaxCard(q: Quote, lastDiv: Double?) {
    val kind = TaxRules.kindOf(q.symbol)
    val auto = TaxRules.isAutoDetected(q.symbol)
    val isTw = q.market == Market.TW
    var expanded by remember { mutableStateOf(false) }
    var ratioText by remember(q.symbol, kind) { mutableStateOf(Fmt.num(TaxRules.taxableRatio(q.symbol) * 100, 0)) }
    val uri = LocalUriHandler.current
    val holding = AppState.holdings.firstOrNull { it.symbol == q.symbol }

    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("配息稅務", Modifier.weight(1f), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (isTw) {
                Text(
                    if (expanded) "收合" else "變更類型", color = C.Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { expanded = !expanded }.padding(8.dp),
                )
            }
        }
        KeyValue("類型", kind.label + if (auto) "（自動判斷）" else "（已設定）")
        Label(kind.note)

        if (expanded && isTw) {
            TaxKind.entries.filter { it != TaxKind.US }.forEach { k ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (k == kind) C.Selected else C.Bg)
                        .clickable { AppState.setTaxKind(q.symbol, k); expanded = false }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(k.label, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
                    Label("預設應稅 " + Math.round(k.defaultTaxable * 100) + "%")
                }
            }
            if (!auto) {
                Text(
                    "恢復自動判斷", color = C.Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { AppState.setTaxKind(q.symbol, null); expanded = false }.padding(vertical = 8.dp),
                )
            }
        }

        if (isTw) {
            OutlinedTextField(
                value = ratioText,
                onValueChange = {
                    ratioText = it
                    it.toDoubleOrNull()?.let { v -> AppState.setTaxRatio(q.symbol, v / 100) }
                },
                label = { Text("應稅比例 %（股利所得占配息）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = fieldColors(),
            )
            Label("依投信每期公告的「收益分配組成」中，股利所得所占比例填寫；記錄股息時會自動帶入。")
            if (q.isEtf || q.symbol.startsWith("00")) {
                Text(
                    "查看證交所 ETF 配息組成 ›", color = C.Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { runCatching { uri.openUri(TaxRules.compositionUrl(q.symbol)) } }
                        .padding(vertical = 8.dp),
                )
            }
        }

        if (holding != null && lastDiv != null && lastDiv > 0) {
            val gross = holding.shares * lastDiv
            val bd = TaxRules.dividend(q.symbol, gross)
            val ccy = if (isTw) "NT$" else "$"
            val dec = if (isTw) 0 else 2
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
            Label("以最近一次配息 " + Fmt.num(lastDiv, 4).trimEnd('0').trimEnd('.') + " × 持有 " + Fmt.shares(holding.shares) + " 股試算")
            KeyValue("配息總額", ccy + Fmt.num(gross, dec))
            bd.lines.forEach { (k, v) -> KeyValue(k, v) }
            KeyValue("實收", ccy + Fmt.num(bd.net, dec), C.Primary)
        }
    }
}
