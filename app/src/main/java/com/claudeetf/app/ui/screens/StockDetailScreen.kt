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

/** 顯示區間：多抓一段歷史資料，讓均線、KD、MACD 從第一根就有值 */
private data class RangeOpt(val label: String, val fetch: String, val days: Long?, val intraday: Boolean = false)

private val ranges = listOf(
    RangeOpt("1日", "1d", null, true),
    RangeOpt("5日", "5d", null, true),
    RangeOpt("1月", "6mo", 31),
    RangeOpt("3月", "1y", 92),
    RangeOpt("6月", "1y", 183),
    RangeOpt("1年", "2y", 366),
    RangeOpt("5年", "5y", null),
)

private enum class Tab(val label: String) { OVERVIEW("概覽"), CHIPS("籌碼"), FIN("財務"), HOLD("成分"), DIV("股息"), NEWS("新聞"), CO("公司") }

private fun tabsFor(q: Quote): List<Tab> =
    if (q.isEtf || (q.market == Market.TW && q.symbol.startsWith("00")))
        listOf(Tab.OVERVIEW, Tab.CHIPS, Tab.HOLD, Tab.DIV, Tab.NEWS, Tab.CO)
    else listOf(Tab.OVERVIEW, Tab.CHIPS, Tab.FIN, Tab.DIV, Tab.NEWS, Tab.CO)

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
    var tabIdx by remember { mutableIntStateOf(0) }
    val tabs = remember(q.symbol) { tabsFor(q) }
    val tab = tabs[tabIdx.coerceIn(0, tabs.lastIndex)]
    var chartType by remember { mutableStateOf(ChartType.LINE) }
    var mas by remember { mutableStateOf(setOf<Int>()) }
    var sub by remember { mutableStateOf(SubChart.VOLUME) }
    val held = AppState.holdings.any { it.symbol == q.symbol }

    // 走勢
    var history by remember { mutableStateOf<Load<YahooExtra.History>>(Load.Loading) }
    LaunchedEffect(q.symbol, range) {
        history = Load.Loading
        history = runCatching { StockInfoRepo.history(q, ranges[range].fetch) }
            .fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
        // 盤中：1 日走勢每 30 秒自動更新
        while (range == 0) {
            kotlinx.coroutines.delay(30_000)
            val open = if (q.market == Market.TW) com.claudeetf.app.data.MarketData.isTwIntraday() else com.claudeetf.app.data.MarketData.usLive()
            if (!open) continue
            runCatching { StockInfoRepo.history(q, ranges[range].fetch) }.onSuccess { history = Load.Ok(it) }
        }
    }
    // 基本面（概覽、財務、公司共用）
    var fund by remember { mutableStateOf<Load<Fundamentals>>(Load.Loading) }
    LaunchedEffect(q.symbol) {
        fund = runCatching { StockInfoRepo.fundamentals(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }
    // 股息、新聞：切到該分頁才載入
    var divs by remember { mutableStateOf<Load<DividendInfo>>(Load.Loading) }
    var news by remember { mutableStateOf<Load<List<YahooExtra.News>>>(Load.Loading) }
    var trading by remember { mutableStateOf<Load<StockInfoRepo.Trading>>(Load.Loading) }
    var chips by remember { mutableStateOf<Load<StockInfoRepo.Chips>>(Load.Loading) }
    var holdings by remember { mutableStateOf<Load<YahooExtra.EtfHoldings>>(Load.Loading) }
    var peers by remember { mutableStateOf<Load<List<StockInfoRepo.Peer>>>(Load.Loading) }
    LaunchedEffect(q.symbol) {
        peers = runCatching { StockInfoRepo.peers(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }
    LaunchedEffect(q.symbol, tab) {
        when (tab) {
            Tab.OVERVIEW -> if (trading !is Load.Ok) {
                trading = runCatching { StockInfoRepo.trading(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
            }
            Tab.CHIPS -> if (chips !is Load.Ok) {
                chips = Load.Loading
                chips = runCatching { StockInfoRepo.chips(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
            }
            Tab.HOLD -> if (holdings !is Load.Ok) {
                holdings = Load.Loading
                holdings = runCatching { StockInfoRepo.holdings(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
            }
            Tab.DIV -> if (divs !is Load.Ok) {
                divs = Load.Loading
                divs = runCatching { StockInfoRepo.dividends(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
            }
            Tab.NEWS -> if (news !is Load.Ok) {
                news = Load.Loading
                news = runCatching { StockInfoRepo.news(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
            }
            else -> {}
        }
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar(q.symbol, onBack = { nav.popBackStack() }) {
            if (!held) {
                IconButton(onClick = { if (AppState.removeWatch(q.symbol)) nav.popBackStack() }) {
                    Icon(Icons.Filled.Close, contentDescription = "移出自選", tint = C.Muted)
                }
            }
            IconButton(onClick = { nav.navigate(Routes.alerts(q.symbol)) }) {
                Icon(Icons.Filled.Notifications, contentDescription = "設定提醒", tint = C.Text)
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { PriceHeader(q, session) }
            item { Segmented(ranges.map { it.label }, range, { range = it }) }
            item {
                ChartControls(
                    chartType, { chartType = it },
                    mas, { m -> mas = if (m in mas) mas - m else mas + m },
                    sub, { sub = it },
                    ranges[range].intraday,
                )
            }
            item { ChartBlock(history, range, q, chartType, mas, sub) }
            item { Segmented(tabs.map { it.label }, tabIdx, { tabIdx = it }) }
            when (tab) {
                Tab.OVERVIEW -> { tradingSection(q, trading); overviewTab(q, fund); peersSection(nav, peers) }
                Tab.CHIPS -> chipsTab(q, chips)
                Tab.FIN -> financialsTab(q, fund)
                Tab.HOLD -> holdingsTab(q, holdings)
                Tab.DIV -> dividendTab(q, divs)
                Tab.NEWS -> newsTab(news)
                Tab.CO -> companyTab(fund)
            }
        }
        Row(
            Modifier.fillMaxWidth().background(C.Bg).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                    .border(1.dp, C.Border, RoundedCornerShape(12.dp))
                    .clickable { nav.navigate(Routes.alerts(q.symbol)) },
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
            if (q.prevClose > 0) {
                val etf = q.isEtf || q.symbol.startsWith("00")
                val (up, down) = com.claudeetf.app.util.TwSession.limits(q.prevClose, etf)
                val st = com.claudeetf.app.util.TwSession.limitState(q.price, q.prevClose, etf)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("漲停 " + Fmt.price(up) + " · 跌停 " + Fmt.price(down) + " · 升降單位 " +
                        Fmt.num(com.claudeetf.app.util.TwSession.tick(q.price, etf), 2))
                    if (st != null) {
                        HGap(8.dp)
                        Text(
                            st, color = C.OnPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Trend.fg(if (st == "漲停") 1.0 else -1.0))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
        Label("報價：" + sourceText(q.source, q.updatedAt))
    }
}

@Composable
private fun ChartBlock(
    history: Load<YahooExtra.History>,
    range: Int,
    q: Quote,
    type: ChartType,
    mas: Set<Int>,
    sub: SubChart,
) {
    when (history) {
        is Load.Loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Label("載入走勢中…") }
        is Load.Err -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Label("走勢載入失敗：" + history.message) }
        is Load.Ok -> {
            val h = history.value
            if (h.closes.size < 2) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Label("此區間沒有走勢資料") }
            } else {
                val opt = ranges[range]
                val start = opt.days?.let { d ->
                    val cutoff = System.currentTimeMillis() - d * 24 * 3600 * 1000
                    h.times.indexOfFirst { it >= cutoff }.takeIf { it >= 0 }
                } ?: 0
                val base = if (range == 0) h.prevClose?.toFloat() else null
                StockChart(h, start, type, mas, sub, base, opt.intraday)
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
    if (holding != null) item { LotsCard(q) }
    item { MarksCard(q) }
    item { NoteCard(q) }
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

// ---------------- 當日交易 ----------------

private fun LazyListScope.tradingSection(q: Quote, t: Load<StockInfoRepo.Trading>) {
    item { SectionTitle("當日交易", (t as? Load.Ok<StockInfoRepo.Trading>)?.value?.source) }
    when (t) {
        is Load.Loading -> loading("載入當日交易中…")
        is Load.Err -> item { Label("當日交易：" + t.message) }
        is Load.Ok -> {
            val v = t.value
            infoGrid(v.items)
            v.inOut?.let { (inside, outside) -> item { InOutBar(inside, outside) } }
            if (v.bids.isNotEmpty() || v.asks.isNotEmpty()) item { OrderBook(v.bids, v.asks) }
            if (q.market == Market.TW && v.bids.isEmpty()) {
                item { Label("填入富果金鑰後，盤中可看五檔報價與內外盤") }
            }
        }
    }
    item { SectionTitle("基本資料") }
}

@Composable
private fun InOutBar(inside: Double, outside: Double) {
    val total = (inside + outside).takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text("內盤 " + Fmt.num(inside) + " 張（" + Fmt.num(inside / total * 100, 1) + "%）", Modifier.weight(1f), color = Trend.fg(-1.0), fontSize = 12.sp)
            Text("外盤 " + Fmt.num(outside) + " 張（" + Fmt.num(outside / total * 100, 1) + "%）", color = Trend.fg(1.0), fontSize = 12.sp)
        }
        StackBar(listOf((inside / total).toFloat() to Trend.fg(-1.0), (outside / total).toFloat() to Trend.fg(1.0)), 8.dp)
    }
}

@Composable
private fun OrderBook(bids: List<Pair<Double, Double>>, asks: List<Pair<Double, Double>>) {
    val maxSize = (bids + asks).maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).padding(10.dp)) {
        Row {
            Label("買量", Modifier.weight(1f)); Label("買價", Modifier.weight(1f).wrapContentWidth(Alignment.End))
            HGap(12.dp)
            Label("賣價", Modifier.weight(1f)); Label("賣量", Modifier.weight(1f).wrapContentWidth(Alignment.End))
        }
        for (k in 0 until 5) {
            val b = bids.getOrNull(k)
            val a = asks.getOrNull(k)
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(2f)) {
                    if (b != null) {
                        Box(
                            Modifier.align(Alignment.CenterEnd).fillMaxWidth((b.second / maxSize).toFloat().coerceIn(0f, 1f))
                                .height(18.dp).background(Trend.pillBg(-1.0)),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            NumText(Fmt.num(b.second), 12.sp, modifier = Modifier.weight(1f))
                            NumText(if (b.first == 0.0) "市價" else Fmt.price(b.first), 12.sp, Trend.fg(-1.0))
                        }
                    }
                }
                HGap(12.dp)
                Box(Modifier.weight(2f)) {
                    if (a != null) {
                        Box(
                            Modifier.align(Alignment.CenterStart).fillMaxWidth((a.second / maxSize).toFloat().coerceIn(0f, 1f))
                                .height(18.dp).background(Trend.pillBg(1.0)),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            NumText(if (a.first == 0.0) "市價" else Fmt.price(a.first), 12.sp, Trend.fg(1.0), modifier = Modifier.weight(1f))
                            NumText(Fmt.num(a.second), 12.sp)
                        }
                    }
                }
            }
        }
        Label("單位：張 · 富果即時五檔")
    }
}

// ---------------- 籌碼 ----------------

private fun lotsText(shares: Long): String {
    val lots = shares / 1000.0
    val sign = if (lots > 0) "+" else if (lots < 0) "−" else ""
    return sign + Fmt.num(kotlin.math.abs(lots))
}

private fun LazyListScope.chipsTab(q: Quote, chips: Load<StockInfoRepo.Chips>) {
    when (chips) {
        is Load.Loading -> loading("載入籌碼資料中…")
        is Load.Err -> showError(chips.message)
        is Load.Ok -> when (val c = chips.value) {
            is StockInfoRepo.Chips.Tw -> {
                if (c.days.isEmpty()) {
                    item { Label("查無三大法人資料。目前支援上市股票與 ETF（證交所 T86 日報，每日約 16:00 後更新）；上櫃證券尚未支援。") }
                    return
                }
                val d0 = c.days.first()
                item {
                    Card(padding = 14.dp) {
                        Row {
                            Text("三大法人買賣超", Modifier.weight(1f), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Label(d0.date.toString())
                        }
                        listOf("外資" to d0.foreign, "投信" to d0.trust, "自營商" to d0.dealer).forEach { (name, v) ->
                            ChipBar(name, v, listOf(d0.foreign, d0.trust, d0.dealer).maxOf { kotlin.math.abs(it) })
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                        KeyValue("合計", lotsText(d0.total) + " 張", Trend.fg(d0.total.toDouble()))
                    }
                }
                if (c.days.size > 1) {
                    item { SectionTitle("近 ${c.days.size} 個交易日", "單位：張") }
                    item {
                        Row(Modifier.fillMaxWidth()) {
                            Label("日期", Modifier.weight(1.2f))
                            listOf("外資", "投信", "自營", "合計").forEach { Label(it, Modifier.weight(1f).wrapContentWidth(Alignment.End)) }
                        }
                    }
                    items(c.days) { d ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            NumText(d.date.monthValue.toString() + "/" + d.date.dayOfMonth, 12.sp, modifier = Modifier.weight(1.2f))
                            listOf(d.foreign, d.trust, d.dealer, d.total).forEach { v ->
                                NumText(lotsText(v), 12.sp, Trend.fg(v.toDouble()), modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End))
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text("累計", Modifier.weight(1.2f), color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            listOf(c.days.sumOf { it.foreign }, c.days.sumOf { it.trust }, c.days.sumOf { it.dealer }, c.days.sumOf { it.total }).forEach { v ->
                                NumText(lotsText(v), 12.sp, Trend.fg(v.toDouble()), FontWeight.Bold, Modifier.weight(1f).wrapContentWidth(Alignment.End))
                            }
                        }
                    }
                }
                item { Label("資料來源：證交所 三大法人買賣超日報（T86）", Modifier.padding(top = 6.dp)) }
            }
            is StockInfoRepo.Chips.Us -> {
                val o = c.ownership
                if (o.breakdown.isEmpty() && o.top.isEmpty()) {
                    item { Label("暫無機構持股資料") }
                    return
                }
                item { SectionTitle("持股結構") }
                items(o.breakdown) { (k, v) -> KeyValue(k, v) }
                if (o.top.isNotEmpty()) {
                    item { SectionTitle("前十大機構", "持股比例 · 變動") }
                    items(o.top) { (org, pct, chg) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(org, Modifier.weight(1f), color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            NumText(pct, 12.sp, modifier = Modifier.width(64.dp).wrapContentWidth(Alignment.End))
                            NumText(chg, 12.sp, C.Muted, modifier = Modifier.width(64.dp).wrapContentWidth(Alignment.End))
                        }
                    }
                }
                item { Label("資料來源：Yahoo Finance（每季申報）", Modifier.padding(top = 6.dp)) }
            }
        }
    }
}

@Composable
private fun ChipBar(name: String, shares: Long, maxAbs: Long) {
    val ratio = if (maxAbs > 0) kotlin.math.abs(shares).toFloat() / maxAbs else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.width(52.dp), color = C.Text, fontSize = 13.sp)
        Box(Modifier.weight(1f).height(10.dp)) {
            Box(
                Modifier.fillMaxWidth(ratio.coerceIn(0.02f, 1f)).fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp)).background(Trend.fg(shares.toDouble())),
            )
        }
        NumText(lotsText(shares) + " 張", 12.sp, Trend.fg(shares.toDouble()), modifier = Modifier.width(96.dp).wrapContentWidth(Alignment.End))
    }
}

// ---------------- ETF 成分股 ----------------

private fun LazyListScope.holdingsTab(q: Quote, holdings: Load<YahooExtra.EtfHoldings>) {
    when (holdings) {
        is Load.Loading -> loading("載入成分股中…")
        is Load.Err -> showError(holdings.message)
        is Load.Ok -> {
            val h = holdings.value
            val top10 = h.holdings.take(10).sumOf { it.weightPct }
            item {
                Card(padding = 14.dp) {
                    KeyValue("成分股數（已揭露）", h.holdings.size.toString())
                    KeyValue("前十大合計", Fmt.num(top10, 2) + "%")
                    h.asOf?.let { KeyValue("資料日期", it) }
                }
            }
            if (h.holdings.isNotEmpty()) {
                item { SectionTitle("持股明細", "權重") }
                val maxW = h.holdings.maxOf { it.weightPct }.takeIf { it > 0 } ?: 1.0
                items(h.holdings) { s ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, Modifier.weight(1f), color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (s.symbol.isNotBlank()) { NumText(s.symbol, 11.sp, C.Muted); HGap(8.dp) }
                            NumText(Fmt.num(s.weightPct, 2) + "%", 13.sp)
                        }
                        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Line)) {
                            Box(Modifier.fillMaxWidth((s.weightPct / maxW).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(C.Primary))
                        }
                    }
                }
            }
            if (h.sectors.isNotEmpty()) {
                item { SectionTitle("產業權重") }
                items(h.sectors) { (name, pct) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.width(96.dp), color = C.Text, fontSize = 12.sp)
                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Line)) {
                            Box(Modifier.fillMaxWidth((pct / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(C.Us))
                        }
                        NumText(Fmt.num(pct, 1) + "%", 12.sp, modifier = Modifier.width(52.dp).wrapContentWidth(Alignment.End))
                    }
                }
            }
            item {
                Label(
                    "資料來源：" + (if (q.market == Market.TW) "MoneyDJ 持股明細、" else "") + "Yahoo Finance。" +
                        (if (q.market == Market.TW) "主動式 ETF 持股每日可能變動，請以投信公告為準。" else ""),
                    Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** 分批成本（FIFO） */
@Composable
private fun LotsCard(q: Quote) {
    val lots = AppState.lots(q.symbol)
    if (lots.isEmpty()) return
    val us = q.market == Market.US
    val ccy = if (us) "$" else ""
    var open by remember { mutableStateOf(false) }
    Card(padding = 12.dp) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("分批成本（先進先出）", Modifier.weight(1f), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Label("${lots.size} 批 " + if (open) "收合" else "展開")
        }
        if (open) {
            Row {
                Label("買入日", Modifier.weight(1.2f)); Label("股數", Modifier.weight(1f).wrapContentWidth(Alignment.End))
                Label("成本", Modifier.weight(1f).wrapContentWidth(Alignment.End)); Label("損益", Modifier.weight(1f).wrapContentWidth(Alignment.End))
            }
            lots.forEach { l ->
                val pl = (q.price - l.unitCost) * l.shares
                val pct = if (l.unitCost > 0) (q.price / l.unitCost - 1) * 100 else 0.0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NumText(l.date, 12.sp, modifier = Modifier.weight(1.2f))
                    NumText(Fmt.shares(l.shares), 12.sp, modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End))
                    NumText(ccy + Fmt.price(l.unitCost), 12.sp, modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End))
                    NumText(Fmt.pct(pct, 1), 12.sp, Trend.fg(pl), modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End))
                }
            }
            Label("成本含手續費；持股頁的均價為平均成本法，兩者合計相同。")
        }
    }
}

/** 目標價、停損價：設定後自動建立價格提醒 */
@Composable
private fun MarksCard(q: Quote) {
    val m = AppState.marks[q.symbol]
    var editing by remember { mutableStateOf(false) }
    var tText by remember(m) { mutableStateOf(m?.target?.let { Fmt.price(it).replace(",", "") } ?: "") }
    var sText by remember(m) { mutableStateOf(m?.stop?.let { Fmt.price(it).replace(",", "") } ?: "") }
    Card(padding = 12.dp) {
        Row(Modifier.fillMaxWidth().clickable { editing = !editing }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("目標價／停損價", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (m == null) Label("未設定，點此設定")
                else {
                    val parts = listOfNotNull(
                        m.target?.let { t -> "目標 " + Fmt.price(t) + (if (q.price > 0) "（距離 " + Fmt.pct((t / q.price - 1) * 100, 1) + "）" else "") },
                        m.stop?.let { st -> "停損 " + Fmt.price(st) + (if (q.price > 0) "（距離 " + Fmt.pct((st / q.price - 1) * 100, 1) + "）" else "") },
                    )
                    Label(parts.joinToString(" · "))
                }
            }
            Label(if (editing) "收合" else "編輯")
        }
        if (editing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(tText, { tText = it }, label = { Text("目標價") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors())
                OutlinedTextField(sText, { sText = it }, label = { Text("停損價") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    PrimaryButton("儲存並建立提醒") {
                        AppState.setMark(q.symbol, tText.toDoubleOrNull(), sText.toDoubleOrNull()); editing = false
                    }
                }
                if (m != null) Box(Modifier.weight(1f)) {
                    PrimaryButton("清除") { AppState.setMark(q.symbol, null, null); editing = false }
                }
            }
            Label("達到目標價或跌破停損價時會推播通知（提醒中心可管理）。")
        }
    }
}

private fun LazyListScope.peersSection(nav: NavController, peers: Load<List<StockInfoRepo.Peer>>) {
    val list = (peers as? Load.Ok<List<StockInfoRepo.Peer>>)?.value ?: return
    if (list.isEmpty()) return
    item { SectionTitle("相似股票", "今日 · 近一年") }
    items(list, key = { "peer-" + it.yahoo }) { p ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                if (!AppState.isWatched(p.symbol)) AppState.addWatch(p.symbol, p.name)
                nav.navigate(Routes.stock(p.symbol))
            }.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                NumText(p.symbol + " · " + Fmt.price(p.price), 11.sp, C.Muted)
            }
            ChangePill(p.changePct)
            HGap(8.dp)
            NumText(p.ret1y?.let { Fmt.pct(it, 1) } ?: "—", 12.sp, Trend.fg(p.ret1y ?: 0.0),
                modifier = Modifier.width(64.dp).wrapContentWidth(Alignment.End))
        }
    }
    item { Label("由 Yahoo Finance 依相關性推薦，點擊加入自選並開啟", Modifier.padding(top = 4.dp)) }
}

/** 個股筆記：投資理由、觀察重點、檢討；離開輸入框即儲存 */
@Composable
private fun NoteCard(q: Quote) {
    val saved = AppState.notes[q.symbol]
    var editing by remember { mutableStateOf(false) }
    var text by remember(q.symbol, saved) { mutableStateOf(saved?.first ?: "") }
    Card(padding = 12.dp) {
        Row(Modifier.fillMaxWidth().clickable { if (editing) AppState.setNote(q.symbol, text); editing = !editing },
            verticalAlignment = Alignment.CenterVertically) {
            Text("我的筆記", Modifier.weight(1f), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Label(if (editing) "完成" else if (saved == null) "新增" else "編輯")
        }
        if (editing) {
            OutlinedTextField(
                text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                placeholder = { Text("為什麼買？觀察什麼？什麼情況會賣？") }, colors = fieldColors(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { PrimaryButton("儲存") { AppState.setNote(q.symbol, text); editing = false } }
                Box(Modifier.weight(1f)) {
                    PrimaryButton("插入今天日期") {
                        text = (if (text.isBlank()) "" else text.trimEnd() + "\n\n") +
                            java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd")) + "："
                    }
                }
            }
        } else if (saved != null) {
            Text(saved.first, color = C.Text2, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 8,
                overflow = TextOverflow.Ellipsis)
            Label("更新於 " + Fmt.time(saved.second))
        } else {
            Label("記下買進理由與停利停損計畫，之後回顧更客觀。")
        }
    }
}
