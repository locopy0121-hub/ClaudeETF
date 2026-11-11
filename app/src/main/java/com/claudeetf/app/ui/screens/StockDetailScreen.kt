package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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

private enum class Tab(val label: String) { OVERVIEW("概覽"), TAPE("明細"), CHIPS("籌碼"), FIN("財務"), HOLD("成分"), DIV("股息"), NEWS("新聞"), CO("公司") }

private fun tabsFor(q: Quote): List<Tab> =
    (if (q.isEtf || (q.market == Market.TW && q.symbol.startsWith("00")))
        listOf(Tab.OVERVIEW, Tab.TAPE, Tab.CHIPS, Tab.HOLD, Tab.DIV, Tab.NEWS, Tab.CO)
    else listOf(Tab.OVERVIEW, Tab.TAPE, Tab.CHIPS, Tab.FIN, Tab.DIV, Tab.NEWS, Tab.CO))
        .filter { it != Tab.TAPE || q.market == Market.TW }

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
    var ovSub by remember { mutableIntStateOf(0) }
    val tabs = remember(q.symbol) { tabsFor(q) }
    val tab = tabs[tabIdx.coerceIn(0, tabs.lastIndex)]
    var chartType by remember { mutableStateOf(ChartType.LINE) }
    var mas by remember { mutableStateOf(setOf<Int>()) }
    var sub by remember { mutableStateOf(SubChart.VOLUME) }
    val held = AppState.holdings.any { it.symbol == q.symbol }

    // 走勢
    var history by remember { mutableStateOf<Load<YahooExtra.History>>(Load.Loading) }
    LaunchedEffect(q.symbol, range, AppState.usExtendedChart) {
        history = Load.Loading
        history = runCatching { StockInfoRepo.history(q, ranges[range].fetch) }
            .fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
        // 盤中：最新價格即時接到走勢最後一根（見 liveMerge）；每 2 分鐘與完整分 K 校正一次
        while (range == 0) {
            kotlinx.coroutines.delay(120_000)
            val open = if (q.market == Market.TW) com.claudeetf.app.data.MarketData.isTwIntraday() else com.claudeetf.app.data.MarketData.usLive()
            if (!open) continue
            runCatching { StockInfoRepo.history(q, ranges[range].fetch) }.onSuccess { history = Load.Ok(it) }
        }
    }
    // 台股：正在看的個股優先放進富果串流（超過訂閱上限時替換掉其他代號）
    if (q.market == Market.TW) {
        DisposableEffect(q.symbol) {
            com.claudeetf.app.data.remote.FugleStream.setFocus(q.symbol)
            onDispose { com.claudeetf.app.data.remote.FugleStream.setFocus("") }
        }
    }
    // 三關價（前一交易日高低）
    LaunchedEffect(q.symbol) {
        runCatching { StockInfoRepo.keyPrices(q) }.getOrNull()?.let { AppState.keyPrices[q.symbol] = it }
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
    var holdings by remember { mutableStateOf<Load<com.claudeetf.app.data.EtfComposition.Result>>(Load.Loading) }
    var peers by remember { mutableStateOf<Load<List<StockInfoRepo.Peer>>>(Load.Loading) }
    var peerExtras by remember { mutableStateOf<Map<String, PeerExtra>>(emptyMap()) }
    LaunchedEffect(q.symbol) {
        peers = runCatching { StockInfoRepo.peers(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
        // 同業的三關位置與法人買賣超
        (peers as? Load.Ok<List<StockInfoRepo.Peer>>)?.value?.let { list ->
            val inst = if (q.market == Market.TW) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.claudeetf.app.data.remote.TwInstitutional.latest() }.getOrDefault(emptyMap())
            } else emptyMap()
            val out = HashMap<String, PeerExtra>()
            for (p in list) {
                val pq = AppState.quote(p.symbol) ?: Quote(p.symbol, p.name, q.market, p.price, p.price / (1 + p.changePct / 100))
                val k = runCatching { StockInfoRepo.keyPrices(pq) }.getOrNull()
                out[p.symbol] = PeerExtra(k?.let { KeyLevelsUi.position(p.price, it) }, inst[p.symbol]?.total?.div(1000.0))
                peerExtras = HashMap(out)
            }
        }
    }
    LaunchedEffect(q.symbol, tab) {
        when (tab) {
            Tab.OVERVIEW -> {
                if (trading !is Load.Ok) {
                    trading = runCatching { StockInfoRepo.trading(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
                }
                // 串流尚未涵蓋時才以快照補（串流一接上，畫面改由推播同步，不再輪詢）
                while (q.market == Market.TW && com.claudeetf.app.data.MarketData.isTwIntraday() && AppState.fugleKey.isNotBlank()) {
                    kotlinx.coroutines.delay(3_000)
                    if (com.claudeetf.app.data.remote.FugleStream.books.containsKey(q.symbol)) continue
                    runCatching { StockInfoRepo.trading(q) }.onSuccess { trading = Load.Ok(it) }
                }
            }
            Tab.CHIPS -> if (chips !is Load.Ok) {
                chips = Load.Loading
                chips = runCatching { StockInfoRepo.chips(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
            }
            Tab.HOLD -> if (holdings !is Load.Ok) {
                holdings = Load.Loading
                holdings = runCatching { com.claudeetf.app.data.EtfComposition.load(q) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
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
            ScoreBadge(q)
            if (!held) {
                IconButton(onClick = { if (AppState.removeWatch(q.symbol)) nav.popBackStack() }) {
                    Icon(Icons.Filled.Close, contentDescription = "移出自選", tint = C.Muted)
                }
            }
            IconButton(onClick = { nav.navigate(Routes.alerts(q.symbol)) }) {
                Icon(Icons.Filled.Notifications, contentDescription = "設定提醒", tint = C.Text)
            }
            IconButton(onClick = { nav.navigate(Routes.trade(q.symbol)) }) {
                Icon(Icons.Filled.Add, contentDescription = "記一筆交易", tint = C.Text)
            }
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        Box(Modifier.weight(1f)) {
        LazyColumn(
            Modifier.fillMaxSize().pointerInput(tab) {
                if (tab != Tab.OVERVIEW) return@pointerInput
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = {
                        val th = 90.dp.toPx()
                        if (acc < -th && ovSub < OVERVIEW_SUBS.size - 1) ovSub++
                        else if (acc > th && ovSub > 0) ovSub--
                    },
                    onHorizontalDrag = { change, dx -> acc += dx; change.consume() },
                )
            },
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { PriceHeader(q, session) }
            item { LiveSignalStrip(q) { tabIdx = 0; ovSub = 3 } }
            // 關閉即時訊號時，K 棒摘要仍放在導覽列上方
            if (!AppState.showLiveSignal) item { KlineSummaryStrip(q) { tabIdx = 0; ovSub = 3 } }
            item { Segmented(tabs.map { it.label }, tabIdx, { tabIdx = it }) }
            when (tab) {
                Tab.OVERVIEW -> {
                    // 概覽再分子分頁：點選或在內容區左右滑動切換
                    item { OverviewSubTabs(ovSub) { ovSub = it } }
                    when (ovSub) {
                        0 -> {
                            item { LiveInfo(q) }
                            tradingSection(q, trading)
                            if (q.market == Market.TW && AppState.tape[q.symbol].orEmpty().isNotEmpty()) item { TapeCard(q) }
                            (history as? Load.Ok<YahooExtra.History>)?.value?.let { h ->
                                val opt = ranges[range]
                                val start = opt.days?.let { d ->
                                    val cutoff = System.currentTimeMillis() - d * 24 * 3600 * 1000
                                    h.times.indexOfFirst { it >= cutoff }.takeIf { it >= 0 }
                                } ?: 0
                                item { VolumeProfileCard(q, h, start, opt.label) }
                            }
                        }
                        1 -> {
                            // 圖表：區間、走勢／K 線、均線、副圖、三關價與漲跌停、走勢圖
                            item { Segmented(ranges.map { it.label }, range, { range = it }) }
                            item {
                                ChartControls(
                                    chartType, { chartType = it },
                                    mas, { m -> mas = if (m in mas) mas - m else mas + m },
                                    sub, { sub = it },
                                    ranges[range].intraday,
                                )
                            }
                            if (ranges[range].fetch == "1d") item { KeyLevelsBar(q, AppState.keyPrices[q.symbol]) }
                            item {
                                ChartBlock(
                                    liveMerge(history, range, q), range, q, chartType, mas, sub,
                                    levels = if (ranges[range].fetch == "1d" && AppState.showKeyLevels) AppState.keyPrices[q.symbol]?.let { KeyLevelsUi.lines(it) } ?: emptyList() else emptyList(),
                                    onKline = { tabIdx = 0; ovSub = 3 },
                                )
                            }
                        }
                        2 -> { item { AnalysisCard(q) }; peersSection(nav, peers, peerExtras) }
                        3 -> item { KlineAnalysisCard(q) }
                        4 -> item { PriceBandCard(q) }
                        5 -> { item { YearlyReturnsCard(q) }; item { DrawdownCard(q) }; item { NextDividendCard(q, compact = true) } }
                        6 -> overviewMine(q)
                        else -> overviewBasic(q, fund)
                    }
                }
                Tab.CHIPS -> chipsTab(q, chips)
                Tab.FIN -> financialsTab(q, fund)
                Tab.HOLD -> holdingsTab(q, holdings, nav)
                Tab.DIV -> dividendTab(q, divs)
                Tab.NEWS -> newsTab(news)
                Tab.CO -> companyTab(q, fund)
                Tab.TAPE -> tapeTab(q)
            }
        }
        // 進場動畫徽章：右上角；懸浮置頂時捲動也一直顯示，否則隨頁首捲走
        if (AppState.entryFloatOn) {
            val first = listState.firstVisibleItemIndex
            val off = listState.firstVisibleItemScrollOffset
            if (AppState.entryFloatPin || first == 0) PlacedEntryFloat(if (AppState.entryFloatPin) 0f else off.toFloat()) { m ->
                EntryFloatBadge(q, m) { tabIdx = 0; ovSub = 3 }
            }
        }
        }
    }
}

/** ETF 類型與配息方式標籤；點標籤看說明，點配息看紀錄與年化配息率 */
@Composable
private fun EtfTagRow(q: Quote) {
    val T = com.claudeetf.app.data.EtfTags
    // 股價光譜區間（與投資分析共用結果）：個股也顯示這個標籤
    LaunchedEffect(q.symbol) {
        if (com.claudeetf.app.data.InvestAnalysis.cached(q.symbol) == null) runCatching { com.claudeetf.app.data.InvestAnalysis.analyze(q) }
    }
    val band = com.claudeetf.app.data.InvestAnalysis.results[q.symbol]?.band
    if (!T.isEtf(q)) {
        // 個股：市場別、產業（美股含細分產業）、光譜；點產業看同產業的自選／持股
        LaunchedEffect(q.symbol) { runCatching { com.claudeetf.app.data.IndustryRepo.detail(q) } }
        val d = com.claudeetf.app.data.IndustryRepo.details[q.symbol]
        var peers by remember { mutableStateOf(false) }
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            d?.board?.let { EtfChip(it, C.Text2) }
            d?.sector?.let { EtfChip(it, androidx.compose.ui.graphics.Color(0xFF5B8DEF)) { peers = true } }
            d?.industry?.let { EtfChip(it, androidx.compose.ui.graphics.Color(0xFF26A69A)) { peers = true } }
            if (band != null) EtfChip("光譜 " + band.zoneName, zoneColor(band.zone)) { }
        }
        if (peers && d?.sector != null) {
            val same = (AppState.quotes.map { it.symbol } + AppState.holdings.map { it.symbol }).distinct()
                .filter { it != q.symbol && com.claudeetf.app.data.IndustryRepo.details[it]?.let { o -> o.sector == d.sector } ?: (AppState.industries[it]?.endsWith(d.sector) == true) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { peers = false }, containerColor = C.Card,
                title = { Text("產業：" + d.sector + (d.industry?.let { " · $it" } ?: ""), color = C.Text, fontSize = 16.sp) },
                text = { Text(if (same.isEmpty()) "自選與持股中沒有同產業的其他個股。" else "自選與持股中的同產業個股：\n" +
                    same.joinToString("\n") { s0 -> s0 + " " + (AppState.quote(s0)?.name ?: "") }, color = C.Text2, fontSize = 14.sp) },
                confirmButton = { Text("關閉", color = C.Primary, modifier = Modifier.clickable { peers = false }.padding(8.dp)) },
            )
        }
        return
    }
    LaunchedEffect(q.symbol) { T.ensureFreq(q) }
    val tags = T.typeTags(q)
    val freq = T.divFreq[q.symbol]
    var info by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        EtfChip("ETF", C.Primary) { info = "ETF" }
        tags.forEach { t ->
            EtfChip(t.text, when (t.kind) {
                com.claudeetf.app.data.EtfTags.Kind.STYLE -> if (t.text == "主動式") androidx.compose.ui.graphics.Color(0xFFFF9800) else C.Text2
                else -> androidx.compose.ui.graphics.Color(0xFF26A69A)
            }) { info = t.text }
        }
        val y = T.annualYield(q.symbol, q.price)
        band?.let { b -> EtfChip("光譜 " + b.zoneName, zoneColor(b.zone)) { info = "__band" } }
        EtfChip(
            (freq ?: "配息…") + (y?.let { " · 年化 " + Fmt.num(it, 1) + "%" } ?: ""),
            if (freq == null || freq == "不配息" || freq == "尚未配息") C.Muted else androidx.compose.ui.graphics.Color(0xFFE6C34A),
        ) { info = "__div" }
    }
    info?.let { key ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { info = null },
            containerColor = C.Card,
            title = { Text(if (key == "__div") "配息方式：" + (freq ?: "載入中") else if (key == "__band") "股價光譜：" + (band?.zoneName ?: "") else key, color = C.Text, fontSize = 16.sp) },
            text = {
                if (key == "__band") Text(band?.let { b ->
                    "股價位於近一年趨勢通道的「" + b.zoneName + "」區（偏離趨勢 " + (if (b.z >= 0) "+" else "") + Fmt.num(b.z, 1) + "σ）。" +
                        "趨勢價 " + Fmt.price(b.trend) + "；詳見 概覽 › 光譜。"
                } ?: "", color = C.Text2, fontSize = 14.sp)
                else if (key != "__div") Text(T.explain(key), color = C.Text2, fontSize = 14.sp)
                else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val list = T.divs[q.symbol].orEmpty()
                    val y = T.annualYield(q.symbol, q.price)
                    if (y != null) Text("年化配息率約 " + Fmt.num(y, 2) + "%（最近一次 × 每年 " + T.perYear(freq) + " 次 ÷ 現價）", color = C.Text, fontSize = 13.sp)
                    if (list.isEmpty()) Label(if (freq == null) "載入中…" else "查無配息紀錄")
                    val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd")
                    val zone = java.time.ZoneId.of(if (q.market == Market.TW) "Asia/Taipei" else "America/New_York")
                    list.take(12).forEach { d ->
                        Row {
                            Text("除息 " + java.time.Instant.ofEpochMilli(d.time).atZone(zone).format(fmt), Modifier.weight(1f), color = C.Text2, fontSize = 13.sp)
                            Text(q.market.currency + " " + Fmt.num(d.amount, 4).trimEnd('0').trimEnd('.'), color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Label("資料來源：Yahoo Finance；實際以投信公告為準。")
                }
            },
            confirmButton = { Text("關閉", color = C.Primary, fontSize = 14.sp, modifier = Modifier.clickable { info = null }.padding(12.dp)) },
        )
    }
}

@Composable
private fun EtfChip(text: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit = {}) {
    Text(
        text,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp)).clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
    )
}

/**
 * 除權息日期提醒：最後買進日 → 除權息日 → 發放日，依序顯示；
 * 已過的日期自動隱藏，三個都過了（或沒有公告）整列不顯示。
 */
@Composable
private fun DivDatesStrip(q: Quote) {
    LaunchedEffect(q.symbol) { runCatching { com.claudeetf.app.data.DividendCalendar.planFor(q) } }
    val p = AppState.divPlans[q.symbol] ?: return
    val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"))
    fun md(d: java.time.LocalDate) = "${d.monthValue}/${d.dayOfMonth}"
    fun left(d: java.time.LocalDate): String {
        val n = java.time.temporal.ChronoUnit.DAYS.between(today, d)
        return when (n) { 0L -> "今天"; 1L -> "明天"; else -> "${n} 天後" }
    }
    val items = buildList {
        if (!p.lastBuy.isBefore(today)) add(Triple("最後買進", p.lastBuy, if (p.lastBuy == today) C.Red else androidx.compose.ui.graphics.Color(0xFFE6C34A)))
        if (!p.exDate.isBefore(today)) add(Triple(p.type, p.exDate, androidx.compose.ui.graphics.Color(0xFF5B8DEF)))
        p.payDate?.takeIf { !it.isBefore(today) }?.let { add(Triple("發放", it, androidx.compose.ui.graphics.Color(0xFF26A69A))) }
    }
    if (items.isEmpty()) return
    var info by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()).clickable { info = true },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("📅", fontSize = 12.sp)
        items.forEach { (n, d, c) -> EtfChip(n + " " + md(d) + "（" + left(d) + "）", c) { info = true } }
        p.cash?.let { Label((if (p.announced) "每股 " else "約 ") + Fmt.num(it, 3) + (if (q.market == com.claudeetf.app.data.Market.US) " 美元" else " 元")) }
    }
    if (info) androidx.compose.material3.AlertDialog(
        onDismissRequest = { info = false }, containerColor = C.Card,
        title = { Text(q.symbol + " " + p.type + "資訊", color = C.Text, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("最後買進日：" + p.lastBuy + "（這天收盤前買進才參與" + p.type + "）", color = C.Text2, fontSize = 14.sp)
                Text(p.type + "日：" + p.exDate, color = C.Text2, fontSize = 14.sp)
                Text("發放日：" + (p.payDate?.toString() ?: "尚未公告"), color = C.Text2, fontSize = 14.sp)
                p.cash?.let { Text("現金股利：每股 " + Fmt.num(it, 3) + (if (p.announced) "（官方公告）" else "（以上次配息估計）"), color = C.Text2, fontSize = 14.sp) }
                p.stock?.let { Text("股票股利：每股 " + Fmt.num(it, 3) + " 股", color = C.Text2, fontSize = 14.sp) }
                com.claudeetf.app.data.DividendCalendar.estimate(p)?.let { e ->
                    Text("依目前持股預估實收：" + Fmt.num(e.net, if (q.market == com.claudeetf.app.data.Market.US) 2 else 0), color = C.Primary, fontSize = 14.sp)
                }
                Label("來源：" + p.source + "。最後買進日為除息前一個交易日（遇國定假日以券商公告為準）；日期過了會自動隱藏。")
            }
        },
        confirmButton = { Text("關閉", color = C.Primary, modifier = Modifier.clickable { info = false }.padding(8.dp)) },
    )
}

@Composable
private fun PriceHeader(q: Quote, session: com.claudeetf.app.util.UsSession.Info) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(q.name, color = C.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarketTag(q.market); HGap(6.dp)
            Label(StockInfoRepo.yahooSymbol(q) + " · " + q.market.currency)
        }
        EtfTagRow(q)
        DivDatesStrip(q)
        Row(verticalAlignment = Alignment.Bottom) {
            // 股價顏色依漲跌：漲紅、平黃、跌綠（依配色設定）
            NumText(if (q.price > 0) Fmt.price(q.price) else "—", 32.sp, if (q.price > 0 && q.prevClose > 0) Trend.price(q.change) else C.Text, FontWeight.Bold)
            HGap(10.dp)
            NumText(
                Fmt.signed(q.change, 2) + "（" + Fmt.pct(q.changePct) + "）", 14.sp, Trend.fg(q.change),
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
        if (q.market == Market.US) {
            AppState.extended[q.symbol]?.let { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.label, color = C.OnPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.Tw).padding(horizontal = 6.dp, vertical = 1.dp))
                    HGap(8.dp)
                    NumText(Fmt.price(e.price), 16.sp, weight = FontWeight.Bold)
                    HGap(8.dp)
                    NumText(Fmt.signedPct(e.changePct), 13.sp, Trend.fg(e.changePct))
                    if (e.time > 0) { HGap(8.dp); Label(Fmt.time(e.time)) }
                }
            }
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

/**
 * 行情價下方的即時 K 棒訊號：盤中每次報價更新就以「形成中的當日 K 棒」重算形態與進場建議，
 * 並標示當下時間；收盤後依設定顯示「收盤定案」的最終進場標籤，或自動隱藏。
 */
@Composable
private fun LiveSignalStrip(q: Quote, onClick: () -> Unit) {
    if (!AppState.showLiveSignal) return
    val tw = q.market == Market.TW
    val zone = java.time.ZoneId.of(if (tw) "Asia/Taipei" else "America/New_York")
    // 每 15 秒更新時間與盤中狀態
    var now by remember { mutableStateOf(java.time.ZonedDateTime.now(zone)) }
    LaunchedEffect(Unit) { while (true) { now = java.time.ZonedDateTime.now(zone); kotlinx.coroutines.delay(15_000) } }
    val weekday = now.dayOfWeek != java.time.DayOfWeek.SATURDAY && now.dayOfWeek != java.time.DayOfWeek.SUNDAY
    val t = now.toLocalTime()
    val live = if (tw) weekday && t >= java.time.LocalTime.of(9, 0) && t < java.time.LocalTime.of(13, 30)
        else com.claudeetf.app.util.UsSession.info().phase == com.claudeetf.app.util.UsSession.Phase.REGULAR
    val closedToday = weekday && if (tw) t >= java.time.LocalTime.of(13, 30) else t >= java.time.LocalTime.of(16, 0)
    if (!live && !AppState.showFinalSignal) return

    var r by remember(q.symbol) { mutableStateOf<com.claudeetf.app.data.KlineAnalysis.Result?>(null) }
    // 報價變動就重算（K 棒資料盤中 2 分鐘更新一次，取得當日最新高低點）
    LaunchedEffect(q.symbol, q.price, live) {
        r = runCatching { com.claudeetf.app.data.KlineAnalysis.load(q, false, if (live) 120_000L else 10 * 60_000L) }.getOrNull() ?: r
    }
    val res = r ?: return
    val last = res.candles.lastIndex
    val todayHits = res.latest.filter { it.idx == last }
    val lastDay = java.time.Instant.ofEpochMilli(res.candles[last].t).atZone(zone).toLocalDate()
    val c = entryColor(res.entry.level)
    val hm = now.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.10f))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                // 閃爍紅點表示即時
                val blink by androidx.compose.animation.core.rememberInfiniteTransition(label = "live").animateFloat(
                    0.25f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(800),
                        androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Red.copy(alpha = blink)))
                HGap(6.dp)
                Text("$hm 即時 K 棒訊號", color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            } else {
                Text(
                    (if (closedToday && lastDay == now.toLocalDate()) "收盤定案 " else "前一交易日收盤 ") +
                        lastDay.format(java.time.format.DateTimeFormatter.ofPattern("M/d")),
                    color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.weight(1f))
            EntryTag(res.entry)
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (live) "當日 K 棒（形成中）：" else "當日 K 棒：", color = C.Muted, fontSize = 12.sp)
            Text(
                if (todayHits.isEmpty()) "無明顯形態" else todayHits.distinctBy { it.pat }.joinToString("、") { it.pat.cn },
                Modifier.weight(1f), color = if (todayHits.isEmpty()) C.Text2 else entryColor(todayHits.sumOf { it.pat.dir.weight }.coerceIn(-1, 1)),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(res.dir.label + " " + res.strength, color = c, fontSize = 12.sp)
        }
        Text(res.entry.reason, color = C.Text2, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp))
        Label(if (live) "盤中訊號會隨價格變動，以收盤定案為準 · 點此看完整分析 ›" else "點此看完整 K 線分析 ›")
    }
}

/** 走勢圖上方的 K 線形態摘要，點了跳到「概覽 › K線」 */
@Composable
private fun KlineSummaryStrip(q: Quote, onClick: () -> Unit) {
    var r by remember(q.symbol) { mutableStateOf<com.claudeetf.app.data.KlineAnalysis.Result?>(null) }
    LaunchedEffect(q.symbol) { r = runCatching { com.claudeetf.app.data.KlineAnalysis.load(q, false) }.getOrNull() }
    val res = r ?: return
    val c = when { res.dir.bullish -> Trend.up; res.dir.bearish -> Trend.down; else -> Trend.flat }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.10f))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("K 線形態", color = C.Muted, fontSize = 11.sp); HGap(8.dp)
        Text(res.dir.label + " " + res.strength, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold); HGap(8.dp)
        Text(res.latest.firstOrNull()?.pat?.cn ?: "近 3 根無明顯形態", Modifier.weight(1f), color = C.Text2, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        EntryTag(res.entry); HGap(6.dp)
        Text("›", color = C.Primary, fontSize = 14.sp)
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
    levels: List<KeyLevel> = emptyList(),
    onKline: () -> Unit = {},
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
                // 1 日分時：X 軸固定為整個交易時段（台股 09:00–13:30、美股 09:30–16:00，5 分 K）
                val ext = q.market == Market.US && AppState.usExtendedChart
                // 美股含盤前盤後：04:00–20:00（美東）共 16 小時
                val slots = if (opt.fetch == "1d") (if (q.market == Market.TW) 55 else if (ext) 193 else 79) else 0
                val bounds = if (opt.fetch == "1d" && q.market == Market.TW && AppState.limitAxis && q.prevClose > 0) {
                    com.claudeetf.app.util.TwSession.limits(q.prevClose, q.isEtf || q.symbol.startsWith("00")).let { (u, d) -> u.toFloat() to d.toFloat() }
                } else null
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (opt.fetch == "1d" && q.market == Market.US) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(if (ext) "含盤前 04:00 與盤後至 20:00（美東）" else "僅正常交易時段 09:30–16:00（美東）", Modifier.weight(1f))
                        Text(
                            if (ext) "盤前盤後 ✓" else "盤前盤後", color = C.Primary, fontSize = 12.sp,
                            modifier = Modifier.clickable { AppState.updateUsExtendedChart(!ext) }.padding(4.dp),
                        )
                    }
                }
                if (opt.fetch == "1d" && q.market == Market.TW) {
                    val (u, d) = com.claudeetf.app.util.TwSession.limits(q.prevClose, q.isEtf || q.symbol.startsWith("00"))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("漲停 " + Fmt.price(u), color = Trend.fg(1.0)); HGap(10.dp)
                        Label("跌停 " + Fmt.price(d), color = Trend.fg(-1.0), modifier = Modifier.weight(1f))
                        val hasLimit = AppState.alerts.any { it.symbol == q.symbol && it.type == com.claudeetf.app.data.AlertType.PRICE_ABOVE && it.value == u }
                        Text(
                            if (hasLimit) "✓ 漲跌停提醒" else "＋漲跌停提醒", color = if (hasLimit) C.Muted else C.Primary, fontSize = 12.sp,
                            modifier = Modifier.clickable(enabled = !hasLimit) {
                                AppState.addAlert(q.symbol, com.claudeetf.app.data.AlertType.PRICE_ABOVE, u)
                                AppState.addAlert(q.symbol, com.claudeetf.app.data.AlertType.PRICE_BELOW, d)
                            }.padding(4.dp),
                        )
                        Text(
                            if (AppState.limitAxis) "漲跌停座標" else "自動座標", color = C.Primary, fontSize = 12.sp,
                            modifier = Modifier.clickable { AppState.updateLimitAxis(!AppState.limitAxis) }.padding(4.dp),
                        )
                    }
                }
                val myTrades = AppState.trades.filter {
                    it.symbol.equals(q.symbol, true) && (it.type == com.claudeetf.app.data.TradeType.BUY || it.type == com.claudeetf.app.data.TradeType.SELL) &&
                        it.origin != com.claudeetf.app.data.TradeOrigin.LEGACY
                }
                val marks = if (!AppState.showTradeMarks || opt.intraday) emptyList() else myTrades.mapNotNull { t ->
                    runCatching { java.time.LocalDate.parse(t.date.replace('/', '-').let { d -> d.split('-').let { "%04d-%02d-%02d".format(it[0].toInt(), it[1].toInt(), it[2].toInt()) } }) }
                        .getOrNull()?.let { com.claudeetf.app.ui.TradeMark(it, t.price.toFloat(), t.type == com.claudeetf.app.data.TradeType.BUY, t.shares) }
                }
                StockChart(h, start, type, mas, sub, base, opt.intraday, levels, slots, bounds, marks, q.market)
                if (myTrades.isNotEmpty() && !opt.intraday) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("● 買進", color = com.claudeetf.app.ui.MARK_BUY, fontSize = 11.sp); HGap(8.dp)
                        Text("● 賣出", color = com.claudeetf.app.ui.MARK_SELL, fontSize = 11.sp); HGap(8.dp)
                        Label("點 K 棒看當天成交", Modifier.weight(1f))
                        Text(
                            if (AppState.showTradeMarks) "我的買賣點 ✓" else "我的買賣點", color = C.Primary, fontSize = 12.sp,
                            modifier = Modifier.clickable { AppState.updateShowTradeMarks(!AppState.showTradeMarks) }.padding(4.dp),
                        )
                    }
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
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            row.forEach { KeyValue(it.label, it.value, modifier = Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** 基本資料最上方：目前行情價（跟著串流更新） */
@Composable
private fun LiveInfo(q0: Quote) {
    val q = AppState.quote(q0.symbol) ?: q0
    val ds = AppState.dayStats[q.symbol]
    val ccy = if (q.market == Market.US) "$" else ""
    val col = Trend.price(q.change)
    // 現價與漲跌獨立一列（大字），其餘兩欄排列，避免擠壓換行
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Label("現價")
                NumText(if (q.price > 0) ccy + Fmt.price(q.price) else "—", 22.sp, col, FontWeight.Bold)
            }
            if (q.price > 0 && q.prevClose > 0) Column(horizontalAlignment = Alignment.End) {
                NumText(Fmt.signed(q.change, 2), 14.sp, col)
                NumText(Fmt.pct(q.changePct), 13.sp, col)
            }
        }
        Spacer(Modifier.height(6.dp))
        val items = listOfNotNull(
            ds?.high?.let { InfoItem("最高", ccy + Fmt.price(it)) },
            ds?.low?.let { InfoItem("最低", ccy + Fmt.price(it)) },
            ds?.volume?.let { InfoItem("成交量", volumeText(it, q.market)) },
            if (q.prevClose > 0) InfoItem("昨收", ccy + Fmt.price(q.prevClose)) else null,
        )
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { KeyValue(it.label, it.value, modifier = Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Label(sourceText(q.source, q.updatedAt), Modifier.padding(top = 2.dp))
    }
}

private fun LazyListScope.infoList(items: List<InfoItem>) {
    items(items) { KeyValue(it.label, it.value) }
}

/** 概覽子分頁 */
private val OVERVIEW_SUBS = listOf("行情", "圖表", "分析", "K線", "光譜", "報酬", "我的", "基本")

@Composable
private fun OverviewSubTabs(sel: Int, onSelect: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OVERVIEW_SUBS.forEachIndexed { i, n ->
                val on = i == sel
                Text(n, color = if (on) C.OnPrimary else C.Text2, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (on) C.Primary else C.Card)
                        .clickable { onSelect(i) }.padding(vertical = 7.dp))
            }
        }
        // 位置點：提示可左右滑動
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
            OVERVIEW_SUBS.indices.forEach { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(if (i == sel) 14.dp else 5.dp, 5.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (i == sel) C.Primary else C.Border))
            }
        }
    }
}

/** 我的：持股損益、分批成本、標記、筆記 */
private fun LazyListScope.overviewMine(q: Quote) {
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
        item { LotsCard(q) }
    } else item { Label("尚未持有這檔，記一筆交易後這裡會顯示損益與分批成本", Modifier.padding(vertical = 6.dp)) }
    item { MarksCard(q) }
    item { NoteCard(q) }
}

/** 基本：Yahoo 概覽（本益比、殖利率、52 週區間…） */
private fun LazyListScope.overviewBasic(q: Quote, fund: Load<Fundamentals>) {
    when (fund) {
        is Load.Loading -> loading("載入基本資料中…")
        is Load.Err -> showError(fund.message)
        is Load.Ok -> {
            val f = fund.value
            // 當日價量（開高低、量、昨收）已在「行情」分頁；這裡只放估值與基本面
            val dayItems = setOf("開盤", "最高", "最低", "成交量", "昨收")
            val rest = f.overview.filter { it.label !in dayItems }
            item { SectionTitle("估值與基本面") }
            if (rest.isEmpty()) item { Label("暫無基本資料") } else infoGrid(rest)
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
    item { NextDividendCard(q) }
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
                    val filled = d.fillDays.values.filterNotNull()
                    if (d.fillDays.isNotEmpty()) {
                        KeyValue("填息率（近 2 年）", "${filled.size} / ${d.fillDays.size}")
                        KeyValue("平均填息天數", if (filled.isNotEmpty()) Fmt.num(filled.average(), 0) + " 天" else "—")
                    }
                }
            }
            if (d.history.isEmpty()) {
                item { Label("近 5 年沒有配息紀錄") }
            } else {
                item { SectionTitle("配息紀錄", "除息日 · 填息 · 每股") }
                items(d.history) { div ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        NumText(date(div.time), 13.sp, modifier = Modifier.weight(1f))
                        if (d.fillDays.containsKey(div.time)) {
                            val fd = d.fillDays[div.time]
                            Text(
                                if (fd == null) "尚未填息" else if (fd == 0) "當日填息" else "填息 $fd 天",
                                color = if (fd == null) C.Muted else C.Primary, fontSize = 12.sp,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                        }
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
    var open by remember { mutableStateOf(false) }
    if (open) NewsReader(n) { open = false }
    NewsItem(n) { open = true }
}

private fun LazyListScope.companyTab(q: Quote, fund: Load<Fundamentals>) {
    when (fund) {
        is Load.Loading -> loading("載入公司資料中…")
        is Load.Err -> showError(fund.message)
        is Load.Ok -> {
            val f = fund.value
            f.header?.let { h -> item { CompanyHeaderCard(h, q) } }
            if (f.profile.isEmpty() && f.summary == null && f.header == null) item { Label("暫無公司資料") }
            infoList(f.profile)
            f.summary?.let { s ->
                item { SectionTitle("公司簡介") }
                item { Text(s, color = C.Text2, fontSize = 13.sp, lineHeight = 20.sp) }
            }
        }
    }
}

/**
 * 公司分頁表頭：發行公司／投信與最新股本、市值。
 * 台股（含 ETF）以集保股權分散表的總股數（ETF 為受益權單位數）× 現價計算市值，
 * 個股股本取證交所／櫃買實收資本額；ETF 股本＝單位數 × 面額 10 元。Yahoo 淨資產僅作參考（常延遲）。
 */
@Composable
private fun CompanyHeaderCard(h: com.claudeetf.app.data.CompanyHeader, q0: Quote) {
    val q = AppState.quote(q0.symbol) ?: q0
    var tdcc by remember(q.symbol) { mutableStateOf<com.claudeetf.app.data.remote.TdccOpenData.Holders?>(null) }
    var tdccLoading by remember(q.symbol) { mutableStateOf(h.kind != "US") }
    LaunchedEffect(q.symbol) {
        if (h.kind != "US") { tdcc = runCatching { com.claudeetf.app.data.remote.TdccOpenData.of(q.symbol) }.getOrNull(); tdccLoading = false }
    }
    fun yi(v: Double) = Fmt.num(v / 1e8, 2) + " 億"
    // 股數：ETF 用集保單位數；個股優先用實收資本額 ÷ 10，沒有再用集保
    val shares: Double? = when (h.kind) {
        "ETF" -> tdcc?.shares
        // 面額不是 10 元的公司（如部分 KY 股），資本額 ÷ 10 會失真：與集保股數差 2 倍以上時改用集保
        "TW" -> h.capital?.let { c -> val t = tdcc?.shares; if (t != null && t > 0 && (c / 10 / t > 2 || t / (c / 10) > 2)) t else c / 10 } ?: tdcc?.shares
        else -> null
    }
    val capital: Double? = if (h.kind == "TW") h.capital ?: tdcc?.shares?.let { it * 10 } else shares?.let { it * 10 }
    val mcap: Double? = shares?.let { it * q.price }?.takeIf { q.price > 0 }
    val years = h.listed?.let { d ->
        runCatching { java.time.LocalDate.parse(d.replace('/', '-').take(10)) }.getOrNull()
    }?.let { java.time.Period.between(it, java.time.LocalDate.now()).let { p -> p.years + p.months / 12.0 } }

    Card(padding = 14.dp) {
        Label(h.role)
        Text(h.name, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
        if (h.kind == "US") {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(12.dp)) {
                Column { Label(h.capitalLabel + "（最新）"); NumText(h.capitalText, 22.sp, C.Primary, FontWeight.Bold) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Label(if (h.kind == "ETF") "股本（單位數 × 10）" else "股本（實收資本額）")
                    NumText(capital?.let { yi(it) } ?: if (tdccLoading) "載入中…" else "—", 20.sp, C.Primary, FontWeight.Bold)
                }
                Column(Modifier.weight(1f)) {
                    Label(if (h.kind == "ETF") "市值（規模）" else "市值")
                    NumText(mcap?.let { yi(it) } ?: if (tdccLoading) "載入中…" else "—", 20.sp, C.Text, FontWeight.Bold)
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                shares?.let { KeyValue(if (h.kind == "ETF") "受益權單位數" else "發行股數", Fmt.num(it / 1e8, 2) + " 億" + if (h.kind == "ETF") "單位" else "股") }
                mcap?.let { KeyValue("市值計算", Fmt.num(shares!! / 1e8, 2) + " 億 × " + Fmt.price(q.price)) }
                tdcc?.let { t -> if (t.people > 0) KeyValue(if (h.kind == "ETF") "受益人數" else "股東人數", Fmt.num(t.people.toDouble()) + " 人") }
                years?.let { KeyValue(if (h.kind == "ETF") "成立年數" else "掛牌年數", if (it < 1) "未滿 1 年" else Fmt.num(it, 1) + " 年") }
                if (h.kind == "ETF") h.yahooAum?.let { KeyValue("淨資產（Yahoo，可能延遲）", yi(it), C.Muted) }
            }
        }
        val tags = h.tags.filter { it.isNotBlank() && it != "null" }
        if (tags.isNotEmpty()) Row(Modifier.padding(top = 8.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { EtfChip(it, C.Text2) } }
        Label(
            when (h.kind) {
                "US" -> h.note
                else -> "股數：" + (if (h.kind == "TW" && h.capital != null) "證交所／櫃買實收資本額 ÷ 面額 10 元" else "集保結算所股權分散表") +
                    (tdcc?.date?.takeIf { it.length == 8 }?.let { "（集保 " + it.substring(0, 4) + "/" + it.substring(4, 6) + "/" + it.substring(6) + "）" } ?: "") +
                    "；市值＝股數 × 現價"
            },
            Modifier.padding(top = 6.dp),
        )
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
    // 富果串流：五檔、成交量值、內外盤即時推送
    if (q.market == Market.TW && com.claudeetf.app.data.remote.FugleStream.books.containsKey(q.symbol)) {
        item { SectionTitle("當日交易", "● 富果串流同步") }
        item { StreamTrading(q) }
        return
    }
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
                    item { Column {
                        Label("查無三大法人資料。上市來自證交所 T86、上櫃來自櫃買中心 OpenAPI（每日收盤後更新，上櫃只提供最新一日）。")
                        com.claudeetf.app.data.remote.TpexOpenData.lastError?.let { Label(it, color = C.Red) }
                    } }
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
                item { Label(if (c.days.first().otc) "資料來源：櫃買中心 OpenAPI 三大法人買賣超（上櫃只提供最新一日）" else "資料來源：證交所 三大法人買賣超日報（T86）", Modifier.padding(top = 6.dp)) }
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

private fun LazyListScope.holdingsTab(q: Quote, holdings: Load<com.claudeetf.app.data.EtfComposition.Result>, nav: NavController) {
    when (holdings) {
        is Load.Loading -> loading("載入成分股中…")
        is Load.Err -> showError(holdings.message)
        is Load.Ok -> EtfCompositionSection(this, q, holdings.value, nav)
    }
}

/** 點了不在自選的成分股：詢問是否加入自選後開啟 */
private val compAsk = mutableStateOf<Pair<String, String>?>(null)

/** 成分頁的子分頁與排序（跨重組保留） */
private val compSub = mutableStateMapOf<String, Int>()
private val compSort = mutableStateMapOf<String, Int>()

private val donutColors = listOf(
    androidx.compose.ui.graphics.Color(0xFFF5A33A), androidx.compose.ui.graphics.Color(0xFF29B6F6),
    androidx.compose.ui.graphics.Color(0xFF3F7BD9), androidx.compose.ui.graphics.Color(0xFFC77DEB),
    androidx.compose.ui.graphics.Color(0xFF9E9E9E),
)

/** 前 4 大＋其他 的甜甜圈圖與圖例 */
@Composable
private fun DonutWithLegend(parts: List<Pair<String, Double>>) {
    val top = parts.take(4)
    val rest = (100 - top.sumOf { it.second }).coerceAtLeast(0.0)
    val slices = top + if (rest > 0.05) listOf("其他" to rest) else emptyList()
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(120.dp)) {
            val stroke = size.minDimension * 0.22f
            var start = -90f
            val total = slices.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
            slices.forEachIndexed { i, (_, v) ->
                val sweep = (v / total * 360).toFloat()
                drawArc(donutColors[i.coerceAtMost(4)], start, sweep, useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                start += sweep
            }
        }
        HGap(16.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEachIndexed { i, (n, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(donutColors[i.coerceAtMost(4)]))
                    HGap(6.dp)
                    Text(n, Modifier.weight(1f), color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    NumText(Fmt.num(v, 2) + "%", 13.sp, C.Text, FontWeight.Bold)
                }
            }
        }
    }
}

private fun EtfCompositionSection(scope: LazyListScope, q: Quote, r: com.claudeetf.app.data.EtfComposition.Result, nav: NavController) = with(scope) {
    val sub = compSub[q.symbol] ?: 0
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Segmented(listOf("成分股", "產業分佈", "操作日報"), sub, { compSub[q.symbol] = it }, Modifier.weight(1f))
        }
    }
    item {
        val others = r.rows.filter { it.symbol.isBlank() }
        Row {
            Label("共 ${r.rows.count { it.symbol.isNotBlank() }} 檔個股" +
                (if (others.isNotEmpty()) "＋" + others.groupBy { it.industry }.entries.joinToString("、") { (k, l) -> k + " " + Fmt.num(l.sumOf { it.weight }, 2) + "%" } else ""),
                Modifier.weight(1f))
            r.asOf?.let { Label("資料日期 $it") }
        }
    }
    if (r.tw && sub == 0) item {
        // 成分總覽：今日貢獻、漲跌家數、集中度、揭露比例
        Card(padding = 14.dp) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Label("成分今日貢獻")
                    val c = r.contribution
                    NumText(c?.let { Fmt.signedPct(it) } ?: "—", 18.sp, c?.let { Trend.fg(it) } ?: C.Text, FontWeight.Bold)
                    Label("上漲 ${r.upCount}・下跌 ${r.downCount} 檔")
                }
                Column(Modifier.weight(1f)) {
                    Label("前十大集中度")
                    NumText(Fmt.num(r.top10, 2) + "%", 18.sp, C.Text, FontWeight.Bold)
                    Label(if (r.disclosed < 98) "已揭露 " + Fmt.num(r.disclosed, 1) + "%" else "完整持股")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Label("基金規模")
                    NumText(r.aum?.let { Fmt.num(it / 1e8, 1) + " 億" } ?: "—", 18.sp, C.Text, FontWeight.Bold)
                    Label(if (r.aum != null) "Yahoo 淨資產" else "暫無資料")
                }
            }
            Label("持股來源：" + r.source.ifBlank { "MoneyDJ" } + (r.asOf?.let { "（$it）" } ?: ""), Modifier.padding(top = 8.dp))
            if (r.disclosed < 98) Label("此 ETF 的資料來源只揭露前 ${r.rows.size} 項持股（合計 ${Fmt.num(r.disclosed, 1)}%），其餘部位未公開；圖中「其他」含未揭露部位。",
                Modifier.padding(top = 8.dp))
            if (r.rows.any { it.estimated }) Label("標「≈」的持股市值與張數是以「權重 × 基金規模 ÷ 收盤價」估算，僅供參考。", Modifier.padding(top = 4.dp))
        }
    }
    item {
        val ask = compAsk.value
        val scope = rememberCoroutineScope()
        if (ask != null) androidx.compose.material3.AlertDialog(
            onDismissRequest = { compAsk.value = null }, containerColor = C.Card,
            title = { Text(ask.first + " " + ask.second, color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = { Text("這檔還不在自選清單，要加入自選並開啟個股頁嗎？", color = C.Text2, fontSize = 14.sp) },
            confirmButton = { Text("加入並開啟", color = C.Primary, fontSize = 14.sp, modifier = Modifier.clickable {
                compAsk.value = null
                scope.launch { com.claudeetf.app.data.MarketData.addAndFetch(ask.first); nav.navigate(Routes.stock(ask.first)) }
            }.padding(12.dp)) },
            dismissButton = { Text("取消", color = C.Muted, fontSize = 14.sp, modifier = Modifier.clickable { compAsk.value = null }.padding(12.dp)) },
        )
    }
    when (sub) {
        0 -> {
            item { Card(padding = 14.dp) { DonutWithLegend(r.rows.sortedByDescending { it.weight }.map { (it.short ?: it.name) to it.weight }) } }
            val sort = compSort[q.symbol] ?: 0
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Label("排序")
                    listOf(0 to "權重", 1 to "市值", 2 to "漲幅", 3 to "跌幅").forEach { (v, n) ->
                        val on = v == sort
                        Text(n, color = if (on) C.Text else C.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) C.Selected else C.Card)
                                .clickable { compSort[q.symbol] = v }.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Label("標的", Modifier.weight(1.3f))
                    if (r.tw) Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.End) { Label("持股市值"); Label("持股張數") }
                    Label("權重", Modifier.weight(0.8f).wrapContentWidth(Alignment.End))
                    if (r.tw) Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { Label("股價"); Label("漲跌") }
                }
                Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(1.dp).background(C.Border))
            }
            val list = when (sort) {
                1 -> r.rows.sortedByDescending { it.mv ?: -1.0 }
                2 -> r.rows.sortedByDescending { it.chgPct ?: -999.0 }
                3 -> r.rows.sortedBy { it.chgPct ?: 999.0 }
                else -> r.rows.sortedByDescending { it.weight }
            }
            items(list, key = { "c" + it.key }) { row ->
                val maxW = list.maxOf { it.weight }.takeIf { it > 0 } ?: 1.0
                Row(Modifier.fillMaxWidth().clickable(enabled = row.symbol.isNotBlank()) {
                    if (AppState.quote(row.symbol) != null) nav.navigate(Routes.stock(row.symbol)) else compAsk.value = row.symbol to (row.short ?: row.name)
                }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1.3f)) {
                        Text(row.short ?: row.name, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(row.symbol.ifBlank { null }, row.industry.ifBlank { null }).joinToString(" · ").ifEmpty { row.name },
                            color = C.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // 權重比例條
                        Box(Modifier.padding(top = 3.dp).fillMaxWidth(0.9f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.Line)) {
                            Box(Modifier.fillMaxWidth((row.weight / maxW).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(
                                if (row.symbol.isBlank()) C.Muted else androidx.compose.ui.graphics.Color(0xFF3F7BD9)))
                        }
                    }
                    if (r.tw) Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.End) {
                        // 持股市值＝股數×收盤價（億元，捨去至小數第 2 位）；張數＝股數÷1,000，零股另列；≈ 為估算
                        val pre = if (row.estimated) "≈" else ""
                        NumText(row.mv?.let { pre + Fmt.numTrunc(it / 1e8, 2) + "億" } ?: "—", 13.sp, if (row.estimated) C.Text2 else C.Text)
                        NumText(row.shares?.let { sh ->
                            val lots = Math.floor(sh / 1000).toLong(); val odd = Math.round(sh - lots * 1000)
                            (if (row.estimated) "≈" else "") + Fmt.num(lots.toDouble()) + "張" + (if (odd > 0 && !row.estimated) " ${odd}股" else "")
                        } ?: "", 11.sp, C.Muted)
                    }
                    Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.End) {
                        NumText(Fmt.num(row.weight, 2) + "%", 13.sp)
                    }
                    if (r.tw) Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        val c = row.chgPct
                        val limit = c != null && kotlin.math.abs(c) >= 9.4
                        // 漲跌停：以色塊標示
                        NumText(row.price?.let { Fmt.price(it) } ?: "—", 13.sp, if (limit) androidx.compose.ui.graphics.Color.White else c?.let { Trend.fg(it) } ?: C.Text,
                            modifier = if (limit) Modifier.clip(RoundedCornerShape(4.dp)).background(Trend.fg(c!!)).padding(horizontal = 4.dp) else Modifier)
                        c?.let { NumText(Fmt.signedPct(it), 11.sp, Trend.fg(it)) }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
            }
        }
        1 -> {
            if (r.industries.isEmpty()) item { Label("暫無產業分佈資料") } else {
                item { Card(padding = 14.dp) { DonutWithLegend(r.industries) } }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) { Label("產業", Modifier.weight(1f)); Label("權重") }
                }
                val maxW = r.industries.maxOf { it.second }.takeIf { it > 0 } ?: 1.0
                items(r.industries, key = { "i" + it.first }) { (name, pct) ->
                    val n = r.rows.count { it.industry == name }
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(110.dp)) {
                            Text(name, color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (n > 0) Label("$n 檔")
                        }
                        NumText(Fmt.num(pct, 2) + "%", 13.sp, modifier = Modifier.width(64.dp))
                        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(C.Line)) {
                            Box(Modifier.fillMaxWidth((pct / maxW).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(androidx.compose.ui.graphics.Color(0xFF3F7BD9)))
                        }
                    }
                }
            }
        }
        else -> {
            val ch = r.changes
            if (!r.tw) item { Label("操作日報目前只支援台股 ETF") }
            else if (ch == null) item {
                Card(padding = 14.dp) {
                    Text("已記錄 ${r.asOf ?: "今天"} 的持股", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Label("操作日報需要比較前後兩個資料日的持股；下一個交易日的持股公布後再打開，就會顯示新增、刪除、加碼、減碼。")
                }
            } else {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Triple("新增", 0, androidx.compose.ui.graphics.Color(0xFFC9A227)), Triple("刪除", 1, C.Muted),
                            Triple("加碼", 2, Trend.fg(1.0)), Triple("減碼", 3, Trend.fg(-1.0))).forEach { (n, k, c) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.12f))
                                .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                                Text(n, color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.Bottom) {
                                    NumText(ch.count { it.kind == k }.toString(), 22.sp, C.Text, FontWeight.Bold); Text(" 檔", color = C.Muted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                item { Label("共 ${ch.size} 檔異動 · ${r.prevAsOf} → ${r.asOf}") }
                if (ch.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Label("標的", Modifier.weight(1.3f)); Label("狀態", Modifier.weight(0.8f))
                        Label("變動張數", Modifier.weight(0.9f).wrapContentWidth(Alignment.End)); Label("變動幅度", Modifier.weight(0.9f).wrapContentWidth(Alignment.End))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { Label("目前權重"); Label("變動%") }
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(1.dp).background(C.Border))
                }
                items(ch, key = { "d" + it.symbol + it.name }) { c ->
                    val color = when (c.kind) { 0 -> androidx.compose.ui.graphics.Color(0xFFC9A227); 1 -> C.Muted; 2 -> Trend.fg(1.0); else -> Trend.fg(-1.0) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1.3f)) {
                            Text(c.name, color = C.Text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (c.symbol.isNotBlank()) NumText(c.symbol, 11.sp, C.Muted)
                        }
                        Box(Modifier.weight(0.8f)) {
                            Text(listOf("新增", "刪除", "加碼", "減碼")[c.kind], color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                        NumText((if (c.deltaShares > 0) "+" else "") + Fmt.num(c.deltaShares / 1000, 0), 13.sp, color, modifier = Modifier.weight(0.9f).wrapContentWidth(Alignment.End))
                        NumText(c.deltaPct?.let { Fmt.num(it, 2) + "%" } ?: "新進", 13.sp, modifier = Modifier.weight(0.9f).wrapContentWidth(Alignment.End))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            NumText(Fmt.num(c.weight, 2) + "%", 13.sp)
                            NumText(Fmt.signed(c.weightDelta, 2) + "%", 11.sp, C.Muted)
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                }
            }
        }
    }
    item {
        Label(
            "資料來源：持股 " + r.source.ifBlank { if (r.tw) "MoneyDJ" else "Yahoo Finance" } + (if (r.tw) "；股價與產業別 證交所／櫃買" else "") + "。" +
                (if (r.tw) "成分只有公司全名時，以證交所／櫃買公司基本資料比對代號。點個股可開啟該股頁面。持股市值＝持有股數 × 最近收盤價；操作日報依 App 記錄的每日持股比較，主動式 ETF 請以投信公告為準。" else ""),
            Modifier.padding(top = 6.dp),
        )
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

private data class PeerExtra(val keyPos: Pair<String, androidx.compose.ui.graphics.Color>?, val instLots: Double?)

private fun LazyListScope.peersSection(nav: NavController, peers: Load<List<StockInfoRepo.Peer>>, extras: Map<String, PeerExtra>) {
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
                extras[p.symbol]?.let { e ->
                    Row {
                        e.keyPos?.let { (pos, c) -> Text("三關 $pos", color = c, fontSize = 10.sp); HGap(8.dp) }
                        e.instLots?.let { Text("法人 " + Fmt.signed(it) + " 張", color = Trend.fg(it), fontSize = 10.sp) }
                    }
                }
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

@Composable
private fun StreamTrading(q: Quote) {
    val book = com.claudeetf.app.data.remote.FugleStream.books[q.symbol]
    val tot = com.claudeetf.app.data.remote.FugleStream.totals[q.symbol]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                KeyValue("成交量", tot?.volumeLots?.let { Fmt.num(it) + " 張" } ?: "—")
                KeyValue("成交筆數", tot?.transactions?.let { Fmt.num(it) } ?: "—")
            }
            Column(Modifier.weight(1f)) {
                KeyValue("成交金額", tot?.value?.let { if (it >= 1e8) Fmt.num(it / 1e8, 2) + " 億" else Fmt.num(it / 1e4) + " 萬" } ?: "—")
                KeyValue("均價", tot?.avg?.let { Fmt.price(it) } ?: "—")
            }
        }
        if (tot?.atBid != null && tot.atAsk != null) InOutBar(tot.atBid, tot.atAsk)
        if (book != null && (book.first.isNotEmpty() || book.second.isNotEmpty())) OrderBook(book.first, book.second)
    }
}

/** 逐筆成交明細：富果串流或證交所即時（每次輪詢的最新一筆） */
@Composable
private fun TapeCard(q: Quote) {
    val list = AppState.tape[q.symbol].orEmpty()
    var expanded by remember(q.symbol) { mutableStateOf(false) }
    val shown = if (expanded) list.take(60) else list.take(12)
    val streaming = com.claudeetf.app.data.remote.FugleStream.isStreaming(q.symbol)
    val tpe = java.time.ZoneId.of("Asia/Taipei")
    val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    Card(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("逐筆成交", Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Label(if (com.claudeetf.app.data.remote.FugleStream.tradesLive(q.symbol)) "● 富果逐筆" else if (streaming) "● 富果串流" else "證交所即時（約每 5 秒）")
        }
        Row {
            Label("時間", Modifier.weight(1f)); Label("價格", Modifier.weight(1f)); Label("張數", Modifier.weight(0.8f)); Label("內外", Modifier.weight(0.5f))
        }
        shown.forEach { t ->
            val color = Trend.fg(t.price - q.prevClose)
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                NumText(java.time.Instant.ofEpochMilli(t.time).atZone(tpe).format(fmt), 12.sp, C.Text2, modifier = Modifier.weight(1f))
                NumText(Fmt.price(t.price), 12.sp, color, modifier = Modifier.weight(1f))
                NumText(Fmt.num(t.lots), 12.sp, if (t.lots >= 50) C.Primary else C.Text, weight = if (t.lots >= 50) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(0.8f))
                Text(
                    when (t.side) { 1 -> "外"; -1 -> "內"; else -> "—" },
                    Modifier.weight(0.5f), fontSize = 12.sp,
                    color = when (t.side) { 1 -> Trend.fg(1.0); -1 -> Trend.fg(-1.0); else -> C.Muted },
                )
            }
        }
        val outer = list.filter { it.side == 1 }.sumOf { it.lots }
        val inner = list.filter { it.side == -1 }.sumOf { it.lots }
        Label("近 ${list.size} 筆：外盤 ${Fmt.num(outer)} 張 · 內盤 ${Fmt.num(inner)} 張 · 50 張以上為大單")
        if (list.size > 12) {
            Text(if (expanded) "收合" else "顯示更多", color = C.Primary, fontSize = 13.sp,
                modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp))
        }
    }
}

/**
 * 1 日分時與即時報價同步：每次收到新價格（串流推播或即時更新），
 * 直接更新最後一根 5 分 K 的收盤、高低與量，跨入下一個 5 分鐘就新增一根。
 */
private fun liveMerge(history: Load<YahooExtra.History>, range: Int, q: Quote): Load<YahooExtra.History> {
    val h = (history as? Load.Ok<YahooExtra.History>)?.value ?: return history
    if (ranges[range].fetch != "1d" || q.price <= 0 || q.updatedAt <= 0 || h.times.isEmpty()) return history
    val live = if (q.market == Market.TW) com.claudeetf.app.data.MarketData.isTwIntraday() else com.claudeetf.app.data.MarketData.usLive()
    if (!live) return history
    val zone = ZoneId.of(if (q.market == Market.TW) "Asia/Taipei" else "America/New_York")
    val lastT = h.times.last()
    if (Instant.ofEpochMilli(lastT).atZone(zone).toLocalDate() != Instant.ofEpochMilli(q.updatedAt).atZone(zone).toLocalDate()) return history
    if (q.updatedAt < lastT) return history
    val p = q.price.toFloat()
    val bar = 5 * 60_000L
    val n = h.closes.size
    val dayVol = AppState.dayStats[q.symbol]?.volume
    fun volFor(prevSum: Double): Float? = dayVol?.let { (it - prevSum).coerceAtLeast(0.0).toFloat() }
    return if (q.updatedAt >= lastT + bar) {
        val start = lastT + (q.updatedAt - lastT) / bar * bar
        val prevSum = h.volumes.sumOf { it.toDouble() }
        Load.Ok(
            h.copy(
                times = h.times + start,
                closes = h.closes + p,
                opens = if (h.opens.size == n) h.opens + p else h.opens,
                highs = if (h.highs.size == n) h.highs + p else h.highs,
                lows = if (h.lows.size == n) h.lows + p else h.lows,
                volumes = if (h.volumes.size == n) h.volumes + (volFor(prevSum) ?: 0f) else h.volumes,
            )
        )
    } else {
        val prevSum = h.volumes.dropLast(1).sumOf { it.toDouble() }
        Load.Ok(
            h.copy(
                closes = h.closes.dropLast(1) + p,
                highs = if (h.highs.size == n) h.highs.dropLast(1) + maxOf(h.highs.last(), p) else h.highs,
                lows = if (h.lows.size == n) h.lows.dropLast(1) + minOf(h.lows.last(), p) else h.lows,
                volumes = if (h.volumes.size == n) h.volumes.dropLast(1) + (volFor(prevSum)?.let { maxOf(it, h.volumes.last()) } ?: h.volumes.last()) else h.volumes,
            )
        )
    }
}

// ---------------- 明細：逐筆成交與分價成交 ----------------

private fun LazyListScope.tapeTab(q: Quote) {
    val list = AppState.tape[q.symbol].orEmpty()
    val streaming = com.claudeetf.app.data.remote.FugleStream.isStreaming(q.symbol)
    val tradesLive = com.claudeetf.app.data.remote.FugleStream.tradesLive(q.symbol)
    item { SectionTitle("成交明細", if (tradesLive) "● 富果逐筆推播（不漏筆）" else if (streaming) "● 富果串流（每次推播的最新成交）" else "證交所／富果即時") }
    if (list.isEmpty()) {
        item { Label("盤中開啟 App 後開始累積逐筆成交；目前尚無資料。", Modifier.padding(vertical = 12.dp)) }
        return
    }
    val outer = list.filter { it.side == 1 }.sumOf { it.lots }
    val inner = list.filter { it.side == -1 }.sumOf { it.lots }
    val total = list.sumOf { it.lots }
    val big = list.count { it.lots >= 50 }
    item {
        Card(padding = 12.dp) {
            Row {
                Column(Modifier.weight(1f)) { Label("筆數"); NumText(Fmt.num(list.size.toDouble()), 15.sp, weight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) { Label("張數"); NumText(Fmt.num(total), 15.sp, weight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) { Label("大單 ≥50 張"); NumText(big.toString(), 15.sp, weight = FontWeight.Bold) }
            }
            if (outer + inner > 0) InOutBar(inner, outer)
            Label("統計範圍：App 開啟後收到的最近 ${list.size} 筆")
        }
    }
    // 分價成交：依價格累積張數，外盤比例
    val byPrice = list.groupBy { it.price }.map { (p, ts) ->
        Triple(p, ts.sumOf { it.lots }, ts.filter { it.side == 1 }.sumOf { it.lots })
    }.sortedByDescending { it.first }
    val maxLots = byPrice.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    item { SectionTitle("分價成交", "價格 · 張數 · 外盤比") }
    item {
        Card(padding = 12.dp) {
            byPrice.take(20).forEach { (p, lots, out) ->
                Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumText(Fmt.price(p), 12.sp, Trend.fg(p - q.prevClose), modifier = Modifier.width(72.dp))
                    Box(Modifier.weight(1f).height(10.dp)) {
                        Row(Modifier.fillMaxHeight().fillMaxWidth((lots / maxLots).toFloat().coerceIn(0.02f, 1f)).clip(RoundedCornerShape(3.dp))) {
                            val o = (out / lots).toFloat().coerceIn(0f, 1f)
                            if (o > 0f) Box(Modifier.fillMaxHeight().weight(o).background(Trend.fg(1.0).copy(alpha = 0.7f)))
                            if (o < 1f) Box(Modifier.fillMaxHeight().weight(1f - o).background(Trend.fg(-1.0).copy(alpha = 0.7f)))
                        }
                    }
                    NumText(Fmt.num(lots), 12.sp, modifier = Modifier.width(56.dp).padding(start = 6.dp))
                    NumText(Fmt.num(out / lots * 100, 0) + "%", 11.sp, C.Muted, modifier = Modifier.width(40.dp))
                }
            }
            if (byPrice.size > 20) Label("另有 ${byPrice.size - 20} 個價位")
        }
    }
    item { SectionTitle("逐筆", "最新在上") }
    item { Segmented(listOf("全部", "大單 ≥50", "外盤", "內盤"), AppState.tapeFilter, { AppState.tapeFilter = it }) }
    val shown = when (AppState.tapeFilter) {
        1 -> list.filter { it.lots >= 50 }
        2 -> list.filter { it.side == 1 }
        3 -> list.filter { it.side == -1 }
        else -> list
    }
    if (shown.isEmpty()) item { Label("沒有符合的成交", Modifier.padding(vertical = 8.dp)) }
    val tpe = ZoneId.of("Asia/Taipei")
    val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    items(shown) { t ->
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            NumText(Instant.ofEpochMilli(t.time).atZone(tpe).format(fmt), 12.sp, C.Text2, modifier = Modifier.weight(1f))
            NumText(Fmt.price(t.price), 12.sp, Trend.fg(t.price - q.prevClose), modifier = Modifier.weight(1f))
            NumText(Fmt.num(t.lots), 12.sp, if (t.lots >= 50) C.Primary else C.Text,
                weight = if (t.lots >= 50) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(0.8f))
            Text(when (t.side) { 1 -> "外"; -1 -> "內"; else -> "—" }, Modifier.weight(0.4f), fontSize = 12.sp,
                color = when (t.side) { 1 -> Trend.fg(1.0); -1 -> Trend.fg(-1.0); else -> C.Muted })
        }
    }
}
