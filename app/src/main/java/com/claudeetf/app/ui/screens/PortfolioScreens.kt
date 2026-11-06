package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.Holding
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.SampleData
import com.claudeetf.app.data.TaxKind
import com.claudeetf.app.data.Analytics
import com.claudeetf.app.data.CalendarEvent
import com.claudeetf.app.data.CalendarRepo
import com.claudeetf.app.data.EventKind
import com.claudeetf.app.data.TradeOrigin
import com.claudeetf.app.data.Ledger
import com.claudeetf.app.data.LedgerBase
import com.claudeetf.app.data.remote.friendly
import androidx.compose.material.icons.filled.Refresh
import com.claudeetf.app.data.TaxRules
import com.claudeetf.app.data.Trade
import com.claudeetf.app.data.TradeType
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToLong

// ============================== 持股 ==============================

@Composable
fun PortfolioScreen(nav: NavController) {
    var inTwd by remember { mutableStateOf(true) }
    var heat by remember { mutableStateOf(false) }
    var actionMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { com.claudeetf.app.data.CorporateActions.scan() } }
    var accFilter by remember { mutableIntStateOf(-1) } // -1 = 全部帳戶
    val usedAccounts = AppState.trades.filter { it.origin != TradeOrigin.LEGACY }.map { it.account }.toSet()
    val accChoices = listOf(-1) + AppState.accounts.map { it.id }.filter { it in usedAccounts } +
        (if (0 in usedAccounts && AppState.accounts.isNotEmpty()) listOf(0) else emptyList())
    val hs: List<Holding> = if (accFilter == -1) AppState.holdings.toList() else {
        val y = LocalDate.now().year
        Ledger.compute(
            LedgerBase(0.0, y, 0.0, 0.0, 0.0),
            AppState.trades.filter { it.account == accFilter },
            y,
        ) { AppState.marketOf(it) }.first?.holdings ?: emptyList()
    }
    val mv = hs.sumOf { AppState.marketValueTwd(it) }
    val cost = hs.sumOf { AppState.costTwd(it) }
    val unreal = mv - cost
    val tw = hs.filter { AppState.marketOf(it.symbol) == Market.TW }
    val us = hs.filter { AppState.marketOf(it.symbol) == Market.US }
    val usUsd = us.sumOf { AppState.marketValue(it) }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("持股") {
            IconButton(onClick = { nav.navigate(Routes.trade()) }) {
                Icon(Icons.Filled.Add, contentDescription = "新增交易", tint = C.Text)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("持股市值（TWD）", Modifier.weight(1f))
                        Segmented(listOf("台幣", "原幣"), if (inTwd) 0 else 1, { inTwd = it == 0 },
                            Modifier.width(140.dp), background = C.Bg)
                    }
                    NumText(mask(Fmt.num(mv)), 26.sp, weight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            KeyValue("總成本", mask(Fmt.num(cost)))
                            KeyValue("今年已實現", mask(Fmt.signed(AppState.realizedTwdYtd)), Trend.fg(AppState.realizedTwdYtd))
                        }
                        Column(Modifier.weight(1f)) {
                            KeyValue("未實現", mask(Fmt.signed(unreal)), Trend.fg(unreal))
                            KeyValue("報酬率", Fmt.pct(if (cost > 0) unreal / cost * 100 else 0.0), Trend.fg(unreal))
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Shortcut("記一筆", Icons.Filled.Edit, Modifier.weight(1f)) { nav.navigate(Routes.trade()) }
                    Shortcut("股息", Icons.Filled.DateRange, Modifier.weight(1f)) { nav.navigate(Routes.DIVIDEND) }
                    Shortcut("績效分析", Icons.Filled.Star, Modifier.weight(1f)) { nav.navigate(Routes.ANALYSIS) }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val due = AppState.dueDca().size
                    Shortcut(if (due > 0) "定期定額（$due）" else "定期定額", Icons.Filled.Refresh, Modifier.weight(1f)) { nav.navigate(Routes.DCA) }
                    Shortcut("再平衡", Icons.Filled.Settings, Modifier.weight(1f)) { nav.navigate(Routes.REBALANCE) }
                    Shortcut("比較", Icons.Filled.Search, Modifier.weight(1f)) {
                        nav.navigate(Routes.compare(AppState.holdings.take(3).joinToString(",") { it.symbol }))
                    }
                }
            }
            if (accChoices.size > 2) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        accChoices.forEach { id ->
                            val on = id == accFilter
                            Text(
                                when (id) { -1 -> "全部帳戶"; 0 -> "未指定"; else -> AppState.account(id)?.name ?: "帳戶 $id" },
                                color = if (on) C.Text else C.Muted, fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clip(RoundedCornerShape(15.dp)).background(if (on) C.Selected else C.Card)
                                    .clickable { accFilter = id }.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            if (AppState.pendingActions.isNotEmpty() || actionMsg != null) {
                item {
                    Card(padding = 14.dp) {
                        Text("待記錄的股息與拆股", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Label("依 Yahoo 公告比對你的交易紀錄自動找出，記錄前可再到交易紀錄修改。")
                        AppState.pendingActions.toList().forEach { p ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(p.text, color = C.Text2, fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(C.Primary)
                                            .clickable { actionMsg = com.claudeetf.app.data.CorporateActions.record(p) ?: "已記錄 ${p.symbol}" },
                                        contentAlignment = Alignment.Center,
                                    ) { Text("記錄", color = C.OnPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                                    Box(
                                        Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp)).background(C.Selected)
                                            .clickable { com.claudeetf.app.data.CorporateActions.dismiss(p) },
                                        contentAlignment = Alignment.Center,
                                    ) { Text("略過", color = C.Text, fontSize = 13.sp) }
                                }
                            }
                        }
                        actionMsg?.let { Label(it, color = C.Primary) }
                    }
                }
            }
            item { Segmented(listOf("清單", "熱度圖"), if (heat) 1 else 0, { heat = it == 1 }) }
            if (heat) {
                item {
                    Heatmap(
                        hs.map { h ->
                            val q = AppState.quote(h.symbol)
                            HeatItem(h.symbol, q?.name?.takeIf { it.length <= 6 } ?: h.symbol, AppState.marketValueTwd(h), q?.changePct ?: 0.0)
                        },
                        height = 320.dp,
                    ) { nav.navigate(Routes.stock(it)) }
                }
                item { Label("方塊大小＝持股市值（台幣），顏色＝今日漲跌，點方塊開個股頁") }
            }
            if (!heat) item { GroupHeader(Market.TW, tw.size, "NT$" + mask(Fmt.num(tw.sumOf { AppState.marketValueTwd(it) }))) }
            if (!heat) {
                items(tw, key = { "tw-" + it.symbol }) { h -> HoldingRow(h, true) { nav.navigate(Routes.stock(h.symbol)) } }
                item { GroupHeader(Market.US, us.size, "US$" + mask(Fmt.num(usUsd, 2))) }
                items(us, key = { "us-" + it.symbol }) { h -> HoldingRow(h, inTwd) { nav.navigate(Routes.stock(h.symbol)) } }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)
                        .clickable { nav.navigate(Routes.TRADES) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("交易紀錄", Modifier.weight(1f), color = C.Text, fontSize = 14.sp)
                    Label("${AppState.trades.count { it.origin != com.claudeetf.app.data.TradeOrigin.LEGACY }} 筆 ›")
                }
            }
        }
    }
}

@Composable
private fun Shortcut(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(56.dp).clip(RoundedCornerShape(12.dp)).background(C.Card).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = C.Primary, modifier = Modifier.size(20.dp))
        Text(label, color = C.Text, fontSize = 12.sp)
    }
}

@Composable
private fun GroupHeader(market: Market, count: Int, total: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MarketTag(market); HGap(6.dp)
        Text((if (market == Market.TW) "台股" else "美股") + " · $count 檔", Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
        NumText(total, 13.sp)
    }
}

@Composable
private fun HoldingRow(h: Holding, inTwd: Boolean, onClick: () -> Unit) {
    val q = AppState.quote(h.symbol)
    val market = AppState.marketOf(h.symbol)
    val isUs = market == Market.US
    val price = q?.price ?: h.avgCost
    val plOrig = (price - h.avgCost) * h.shares
    val pct = if (h.avgCost > 0) (price / h.avgCost - 1) * 100 else 0.0
    val showTwd = !isUs || inTwd
    val fx = if (isUs && inTwd) AppState.usdTwd else 1.0
    val mvText = if (isUs && !inTwd) "$" + Fmt.num(AppState.marketValue(h), 2)
    else (if (isUs) "NT$" else "") + Fmt.num(AppState.marketValue(h) * fx)
    val plText = if (showTwd) Fmt.signed(plOrig * fx) else Fmt.signed(plOrig, 2, "$")

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                (q?.name ?: h.symbol) + "  " + h.symbol, color = C.Text, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Label(Fmt.shares(h.shares) + " 股 · 均價 " + (if (isUs) "$" else "") + Fmt.price(h.avgCost))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            NumText(mask(mvText), 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumText(mask(plText), 12.sp, Trend.fg(plOrig)); HGap(6.dp)
                ChangePill(pct)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Card2))
}

// ============================== 新增交易 ==============================

@Composable
fun AddTradeScreen(nav: NavController, initialSymbol: String, editId: Long = -1L) {
    val editing = remember(editId) { if (editId > 0) AppState.trade(editId) else null }
    fun plain(v: Double) = java.math.BigDecimal(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    var type by remember { mutableIntStateOf(editing?.type?.ordinal ?: 0) }
    var symbol by remember { mutableStateOf(editing?.symbol ?: initialSymbol) }
    var date by remember { mutableStateOf(editing?.date ?: LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))) }
    var shares by remember { mutableStateOf(editing?.let { plain(it.shares) } ?: "") }
    var price by remember {
        mutableStateOf(editing?.let { plain(it.price) } ?: AppState.quote(initialSymbol)?.price?.let { Fmt.price(it).replace(",", "") } ?: "")
    }
    var fx by remember { mutableStateOf(editing?.let { plain(it.fx) } ?: Fmt.num(AppState.usdTwd, 2)) }
    var feeText by remember { mutableStateOf(editing?.let { plain(it.fee) } ?: "") }
    var feeEdited by remember { mutableStateOf(editing != null && editing.type != TradeType.DIVIDEND) }
    var confirmDelete by remember { mutableStateOf(false) }
    var accountId by remember { mutableIntStateOf(editing?.account ?: 0) }
    var note by remember { mutableStateOf(editing?.note ?: "") }
    var tagsText by remember { mutableStateOf(editing?.tags?.joinToString("、") ?: "") }
    var attachments by remember { mutableStateOf(editing?.attachments ?: emptyList()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) com.claudeetf.app.util.Attachments.import(ctx, uri)?.let { attachments = attachments + it } }
    var ratioText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val tType = TradeType.entries[type]
    val sym = symbol.trim().uppercase()
    val market = AppState.marketOf(sym)
    val isUs = market == Market.US
    val isEtf = AppState.quote(sym)?.isEtf ?: sym.startsWith("00")
    val q = shares.toDoubleOrNull() ?: 0.0
    val p = price.toDoubleOrNull() ?: 0.0
    val amount = q * p
    val accOptions = AppState.accountsFor(market)
    val effAccount = if (accOptions.any { it.id == accountId }) accountId else accOptions.firstOrNull()?.id ?: 0
    val taxKind = TaxRules.kindOf(sym)
    val ratio = ratioText.toDoubleOrNull()?.div(100) ?: TaxRules.taxableRatio(sym)
    val divBd = TaxRules.dividend(sym, amount, ratio)
    val autoFee = when (tType) {
        TradeType.BUY, TradeType.SELL -> AppState.feeFor(effAccount, market, amount)
        TradeType.DIVIDEND -> if (isUs) 0.0 else divBd.nhi
        TradeType.SPLIT -> 0.0
    }
    val fee = if (feeEdited && tType != TradeType.DIVIDEND) feeText.toDoubleOrNull() ?: 0.0 else autoFee
    val tax = if (tType == TradeType.SELL && !isUs) (amount * (if (isEtf) 0.001 else 0.003)).roundToLong().toDouble() else 0.0
    val total = when (tType) {
        TradeType.BUY -> amount + fee
        TradeType.SELL -> amount - fee - tax
        TradeType.DIVIDEND -> divBd.net
        TradeType.SPLIT -> 0.0
    }
    val cur = if (isUs) "$" else "NT$"
    val dec = if (isUs) 2 else 0

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar(if (editing != null) "編輯交易" else "新增交易", onBack = { nav.popBackStack() }) {
            if (editing != null) {
                Text(
                    if (confirmDelete) "確定刪除？" else "刪除", color = C.Red, fontSize = 14.sp,
                    modifier = Modifier.clickable {
                        if (!confirmDelete) confirmDelete = true
                        else {
                            val err = AppState.deleteTrade(editing.id)
                            if (err == null) nav.popBackStack() else { error = "無法刪除：$err"; confirmDelete = false }
                        }
                    }.padding(12.dp),
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Segmented(TradeType.entries.map { it.label }, type, { type = it; error = null }) }
            item {
                Column {
                    Field("股票代號", symbol, { symbol = it }, keyboard = KeyboardType.Text)
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        MarketTag(market); HGap(6.dp)
                        Label((AppState.quote(sym)?.name ?: "新股票") + " · 幣別 " + market.currency + (if (isEtf) " · ETF" else ""))
                    }
                }
            }
            if (accOptions.isNotEmpty() && tType != TradeType.SPLIT) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label("帳戶", Modifier.align(Alignment.CenterVertically))
                        accOptions.forEach { a ->
                            val on = a.id == effAccount
                            Text(
                                a.name, color = if (on) C.Text else C.Muted, fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clip(RoundedCornerShape(15.dp)).background(if (on) C.Selected else C.Card)
                                    .clickable { accountId = a.id; feeEdited = false }.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("日期", date, { date = it }, Modifier.weight(1f), KeyboardType.Text)
                    Field(
                        when (tType) {
                            TradeType.DIVIDEND -> "配息股數"
                            TradeType.SPLIT -> "（拆股免填）"
                            else -> if (isUs) "股數（可零股）" else "股數"
                        },
                        shares, { shares = it }, Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(
                        when (tType) {
                            TradeType.DIVIDEND -> "每股股息（${market.currency}）"
                            TradeType.SPLIT -> "拆股比例（1 股變幾股）"
                            else -> "成交價（${market.currency}）"
                        },
                        price, { price = it }, Modifier.weight(1f),
                    )
                    if (isUs) Field("匯率 USD/TWD", fx, { fx = it }, Modifier.weight(1f))
                }
            }
            if (isUs) item { Label("匯率預設台灣銀行即期，可修改") }
            if (tType == TradeType.DIVIDEND) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label("稅務類型：" + taxKind.label + (if (TaxRules.isAutoDetected(sym)) "（自動判斷）" else "（已設定）"))
                        if (!isUs) {
                            Field(
                                "本期應稅比例 %（股利所得占配息）",
                                if (ratioText.isEmpty()) Fmt.num(TaxRules.taxableRatio(sym) * 100, 0) else ratioText,
                                { ratioText = it },
                            )
                        }
                        Label(taxKind.note)
                    }
                }
            }
            if (tType == TradeType.BUY || tType == TradeType.SELL) {
                item {
                    Column {
                    Field(
                        (if (tType == TradeType.DIVIDEND) "二代健保／費用" else "手續費") + "（${market.currency}）",
                        if (feeEdited) feeText else Fmt.num(autoFee, dec).replace(",", ""),
                        { feeText = it; feeEdited = true },
                    )
                    Label(
                        (AppState.account(effAccount)?.let { a ->
                            if (isUs) "${a.name}：手續費 ${Fmt.num(a.usRate * 100, 3)}%，最低 $${Fmt.num(a.usMinFee, 2)}"
                            else "${a.name}：0.1425% × ${Fmt.num(a.discount * 10, 2)} 折，最低 ${Fmt.num(a.minFee)} 元"
                        } ?: "") + if (isUs) "；美股免交易稅" else "；賣出證交稅 股票 0.3%、ETF 0.1%",
                        Modifier.padding(top = 4.dp),
                    )
                    }
                }
            }
            if (tType != TradeType.SPLIT) {
                item {
                    Card(padding = 14.dp) {
                        if (tType == TradeType.DIVIDEND) {
                            KeyValue("配息總額", cur + Fmt.num(amount, dec))
                            divBd.lines.forEach { (k, v) -> KeyValue(k, v) }
                        } else {
                            KeyValue("成交金額", cur + Fmt.num(amount, dec))
                            KeyValue("手續費", cur + Fmt.num(fee, dec))
                            KeyValue("交易稅", if (isUs) "美股免" else cur + Fmt.num(tax, 0))
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                        KeyValue(
                            when (tType) { TradeType.BUY -> "總成本"; else -> "實收金額" },
                            cur + Fmt.num(total, dec),
                        )
                        if (isUs) Label("≈ NT$" + Fmt.num(total * (fx.toDoubleOrNull() ?: AppState.usdTwd)), Modifier.align(Alignment.End))
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("備註", note, { note = it }, keyboard = KeyboardType.Text)
                    Field("標籤（用頓號或逗號分隔，例：存股、高股息）", tagsText, { tagsText = it }, keyboard = KeyboardType.Text)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        attachments.forEach { name -> AttachmentThumb(name) { attachments = attachments - name } }
                        Box(
                            Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(C.Card)
                                .clickable { picker.launch("image/*") },
                            contentAlignment = Alignment.Center,
                        ) { Text("＋ 照片", color = C.Primary, fontSize = 12.sp) }
                    }
                    Label("可附上對帳單或成交回報截圖，照片只存在這支手機。")
                }
            }
            error?.let { msg -> item { Text(msg, color = C.Red, fontSize = 13.sp) } }
        }
        Box(
            Modifier.fillMaxWidth().padding(16.dp).height(50.dp).clip(RoundedCornerShape(12.dp)).background(C.Primary)
                .clickable {
                    val t = Trade(
                        tType, sym, date.trim(),
                        if (tType == TradeType.SPLIT) 0.0 else q, p,
                        if (isUs) fx.toDoubleOrNull() ?: AppState.usdTwd else 1.0,
                        fee, tax,
                    )
                    val tags = tagsText.split('、', ',', '，', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    val tt = t.copy(account = effAccount, note = note.trim(), tags = tags, attachments = attachments)
                    val err = if (editing != null) AppState.updateTrade(tt.copy(id = editing.id, origin = editing.origin))
                    else AppState.addTrade(tt)
                    if (err == null) nav.popBackStack() else error = err
                },
            contentAlignment = Alignment.Center,
        ) { Text("儲存交易", color = C.OnPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Decimal,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier, singleLine = true,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = fieldColors(),
    )
}

// ============================== 股息 ==============================

@Composable
fun DividendScreen(nav: NavController) {
    val year = LocalDate.now().year
    val gross = AppState.dividendUsGrossYtd
    val usNet = gross * 0.7
    val usNetTwd = usNet * AppState.usdTwd
    val total = AppState.dividendTwYtd + usNetTwd
    val months = Analytics.monthlyDividends(year)
    val maxV = months.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val divTrades = AppState.trades.filter { it.type == TradeType.DIVIDEND && it.origin != TradeOrigin.LEGACY }
    var upcoming by remember { mutableStateOf<Load<List<CalendarEvent>>>(Load.Loading) }
    LaunchedEffect(Unit) {
        upcoming = runCatching {
            val held = AppState.holdings.map { it.symbol }.toSet()
            CalendarRepo.load().filter { it.kind == EventKind.DIVIDEND && it.isHolding }
                .filter { e -> held.any { e.title.startsWith(it + " ") } }
        }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("股息與稅務", onBack = { nav.popBackStack() }) {
            IconButton(onClick = { nav.navigate(Routes.trade()) }) {
                Icon(Icons.Filled.Add, contentDescription = "記錄股息", tint = C.Text)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(padding = 14.dp) {
                    Label("$year 累計實收股息（TWD）")
                    NumText(mask(Fmt.num(total)), 26.sp, weight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MarketTag(Market.TW); HGap(6.dp)
                        Text("台股（扣二代健保後）", Modifier.weight(1f), color = C.Text, fontSize = 12.sp)
                        NumText("NT$" + mask(Fmt.num(AppState.dividendTwYtd)), 12.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MarketTag(Market.US); HGap(6.dp)
                        Text("美股 稅前", Modifier.weight(1f), color = C.Text, fontSize = 12.sp)
                        NumText("$" + mask(Fmt.num(gross, 2)), 12.sp)
                    }
                    KeyValue("　　預扣稅 30%", "−$" + mask(Fmt.num(gross * 0.3, 2)), C.Muted)
                    KeyValue("　　實收", "$" + mask(Fmt.num(usNet, 2)) + " ≈ NT$" + mask(Fmt.num(usNetTwd)))
                }
            }
            item {
                Card(padding = 14.dp) {
                    SectionTitle("$year 每月股息", "依交易紀錄")
                    if (months.all { it == 0.0 }) {
                        Label("今年還沒有記錄股息。在「持股 › 記一筆」選「股息」即可記錄。")
                    } else {
                        Row(
                            Modifier.fillMaxWidth().height(110.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            months.forEachIndexed { i, v ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier.fillMaxWidth().height((v / maxV * 86).toFloat().dp)
                                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(C.Primary)
                                    )
                                    Text("${i + 1}", color = C.Muted, fontSize = 10.sp)
                                }
                            }
                        }
                        Label("最高 " + mask("NT$" + Fmt.num(maxV)) + " · 平均每月 " + mask("NT$" + Fmt.num(months.sum() / 12)))
                    }
                }
            }
            item { SectionTitle("持股即將除息", "取自財經行事曆") }
            when (val u = upcoming) {
                is Load.Loading -> item { Label("載入中…") }
                is Load.Err -> item { Label("無法取得：" + u.message) }
                is Load.Ok -> {
                    if (u.value.isEmpty()) item { Label("未來 120 天沒有已公布的除息日") }
                    items(u.value) { e ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(e.dateLabel, Modifier.width(110.dp), color = C.Text2, fontSize = 13.sp)
                            Text(e.title, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (divTrades.isNotEmpty()) {
                item { SectionTitle("股息紀錄", "實收（台幣）") }
                items(divTrades.take(20)) { t ->
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.navigate(Routes.trade(t.symbol, t.id)) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Label(t.date, Modifier.width(90.dp))
                        Text(t.symbol + " " + (AppState.quote(t.symbol)?.name ?: ""), Modifier.weight(1f), color = C.Text, fontSize = 13.sp, maxLines = 1)
                        NumText(mask("NT$" + Fmt.num(AppState.dividendById[t.id] ?: 0.0)), 13.sp)
                    }
                }
            }
            item {
                Card(padding = 14.dp) {
                    Text("稅務提醒", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Label("台股：只有「股利所得」計入綜所稅，且單次股利所得達 NT$20,000 才扣二代健保 2.11%。ETF 配息中的選擇權權利金、收益平準金、證券交易所得不扣；債券與海外 ETF 屬海外所得，適用最低稅負制。")
                    Label("美股：配息預扣 30%，以實收記帳；不扣二代健保。")
                    Label("每檔的稅務類型與應稅比例可在個股頁「股息」分頁調整。")
                    Text("查看年度報表（報稅參考） ›", color = C.Primary, fontSize = 13.sp,
                        modifier = Modifier.clickable { nav.navigate(Routes.REPORT) }.padding(vertical = 6.dp))
                    Text("股息再投入試算（複利） ›", color = C.Primary, fontSize = 13.sp,
                        modifier = Modifier.clickable { nav.navigate(Routes.DRIP) }.padding(vertical = 6.dp))
                }
            }
        }
    }
}

// ============================== 績效分析 ==============================

private fun sectorOf(symbol: String): String {
    val q = AppState.quote(symbol)
    return when {
        q == null -> "其他"
        q.isEtf || symbol.startsWith("00") -> when (TaxRules.kindOf(symbol)) {
            TaxKind.TW_ETF_BOND -> "債券 ETF"
            TaxKind.TW_ETF_OVERSEAS -> "海外 ETF"
            TaxKind.TW_ETF_OPTION -> "收益型 ETF"
            else -> if (q.market == Market.US) "美國 ETF" else "台股 ETF"
        }
        q.market == Market.US -> "美股個股"
        else -> "台股個股"
    }
}

@Composable
fun AnalysisScreen(nav: NavController) {
    var range by remember { mutableIntStateOf(2) }
    var allocBy by remember { mutableIntStateOf(0) }
    var reload by remember { mutableIntStateOf(0) }
    val (pricePart, fxPart) = AppState.usPnlBreakdown()
    val usTotal = pricePart + fxPart
    val mv = AppState.totalMarketValueTwd()
    val cost = AppState.totalCostTwd()
    val xirr = remember(AppState.trades.size) { Analytics.xirr() }
    var bt by remember { mutableStateOf<Load<Analytics.Backtest>>(Load.Loading) }
    LaunchedEffect(reload) {
        bt = Load.Loading
        bt = runCatching { Analytics.backtest(reload > 0) }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }
    val alloc: List<Pair<String, Double>> = if (mv <= 0) emptyList() else when (allocBy) {
        0 -> AppState.holdings.groupBy { sectorOf(it.symbol) }
            .map { (k, v) -> k to v.sumOf { AppState.marketValueTwd(it) } / mv * 100 }
        1 -> listOf(
            "台股" to AppState.totalMarketValueTwd(Market.TW) / mv * 100,
            "美股" to AppState.totalMarketValueTwd(Market.US) / mv * 100,
        )
        else -> AppState.holdings.map { (AppState.quote(it.symbol)?.name ?: it.symbol) to AppState.marketValueTwd(it) / mv * 100 }
    }.sortedByDescending { it.second }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("績效分析", onBack = { nav.popBackStack() }) {
            IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, contentDescription = "重新計算", tint = C.Text) }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(padding = 14.dp) {
                    Row {
                        Stat("未實現報酬", Fmt.pct(if (cost > 0) (mv - cost) / cost * 100 else 0.0), Modifier.weight(1f), mv - cost)
                        Stat("今年已實現", mask(Fmt.signed(AppState.realizedTwdYtd)), Modifier.weight(1f), AppState.realizedTwdYtd)
                        Stat("年化 XIRR", xirr?.let { Fmt.pct(it, 1) } ?: "—", Modifier.weight(1f), xirr ?: 0.0)
                    }
                    if (xirr == null) Label("XIRR 需要完整且有日期的交易紀錄（不含期初持股），且最早一筆距今超過 30 天。")
                }
            }
            item { SectionTitle("持股回測 vs 大盤", "以目前持股回推") }
            item { Segmented(listOf("1月", "3月", "6月", "1年"), range, { range = it }) }
            when (val b = bt) {
                is Load.Loading -> item { Label("下載各持股一年歷史價格中…", Modifier.padding(vertical = 20.dp)) }
                is Load.Err -> item { Text("回測失敗：" + b.message, color = C.Red, fontSize = 13.sp) }
                is Load.Ok -> {
                    val r = b.value
                    val days = listOf(31L, 92L, 183L, 366L)[range]
                    val cutoff = LocalDate.now().minusDays(days)
                    val from = r.dates.indexOfFirst { !it.isBefore(cutoff) }.coerceAtLeast(0)
                    fun rebase(v: List<Float>): List<Float> {
                        if (v.size <= from) return emptyList()
                        val base = 1 + v[from] / 100
                        return v.drop(from).map { ((1 + it / 100) / base - 1) * 100 }
                    }
                    val mine = rebase(r.mine)
                    val tw = rebase(r.tw50)
                    val spy = rebase(r.spy)
                    item {
                        Card(padding = 14.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Legend(C.Primary, "我的 " + Fmt.pct((mine.lastOrNull() ?: 0f).toDouble(), 1))
                                Legend(C.Tw, "0050 " + Fmt.pct((tw.lastOrNull() ?: 0f).toDouble(), 1))
                                if (spy.isNotEmpty()) Legend(C.Us, "SPY " + Fmt.pct((spy.last()).toDouble(), 1))
                            }
                            MultiLine(
                                listOfNotNull(
                                    if (spy.isNotEmpty()) Triple(spy, C.Us, true) else null,
                                    Triple(tw, C.Tw, false),
                                    Triple(mine, C.Primary, false),
                                ),
                                Modifier.fillMaxWidth().height(140.dp),
                            )
                            Row {
                                Label(r.dates.getOrNull(from)?.toString() ?: "", Modifier.weight(1f))
                                Label(r.dates.lastOrNull()?.toString() ?: "")
                            }
                            Label("以目前持股數量套用歷史收盤價計算，美股以目前匯率換算；不含期間買賣與股息。")
                            if (r.missing.isNotEmpty()) Label("缺少歷史價格：" + r.missing.joinToString("、"), color = C.Red)
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MiniStat("最大回撤（1年）", Fmt.num(r.maxDrawdownPct, 1) + "%", Modifier.weight(1f))
                            MiniStat("年化波動", Fmt.num(r.volPct, 1) + "%", Modifier.weight(1f))
                            MiniStat("夏普值", Fmt.num(r.sharpe, 2), Modifier.weight(1f))
                        }
                    }
                }
            }
            concentrationSection()
            statsSection()
            item {
                Card(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("美股損益拆解（TWD）", Modifier.weight(1f), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        NumText(mask(Fmt.signed(usTotal)), 14.sp, Trend.fg(usTotal))
                    }
                    if (pricePart > 0 && fxPart > 0) {
                        StackBar(listOf(pricePart.toFloat() to C.Primary, fxPart.toFloat() to C.Tw))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Legend(C.Primary, "股價 " + mask(Fmt.signed(pricePart)))
                        Legend(C.Tw, "匯兌 " + mask(Fmt.signed(fxPart)))
                    }
                    Label("目前匯率 " + Fmt.num(AppState.usdTwd, 2) + "，與買入時匯率的差異即為匯兌損益")
                }
            }
            item {
                Card(padding = 14.dp) {
                    Text("配置", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Segmented(listOf("類型", "市場", "個股"), allocBy, { allocBy = it }, background = C.Bg)
                    if (alloc.isEmpty()) Label("目前沒有持股")
                    alloc.forEach { (name, pct) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.width(110.dp), color = C.Text, fontSize = 12.sp, maxLines = 1)
                            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Line)) {
                                Box(Modifier.fillMaxWidth((pct / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(C.Primary))
                            }
                            NumText(Fmt.num(pct, 1) + "%", 12.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, sign: Double = 1.0) {
    Column(modifier) {
        Label(label)
        NumText(value, 17.sp, Trend.fg(sign), FontWeight.Bold)
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(C.Card).padding(12.dp)) {
        Label(label)
        NumText(value, 15.sp)
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color)); HGap(5.dp)
        Text(text, color = C.Text2, fontSize = 11.sp, maxLines = 1)
    }
}

/** 附件縮圖：點擊放大、右上角移除 */
@Composable
fun AttachmentThumb(name: String, onRemove: (() -> Unit)? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val thumb = remember(name) { com.claudeetf.app.util.Attachments.load(ctx, name, 240)?.asImageBitmap() }
    var open by remember { mutableStateOf(false) }
    Box(Modifier.size(72.dp)) {
        Box(
            Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(C.Card2).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) androidx.compose.foundation.Image(
                thumb, contentDescription = "附件", modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            ) else Label("遺失")
        }
        if (onRemove != null) {
            Text(
                "✕", color = C.OnPrimary, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).clip(RoundedCornerShape(9.dp))
                    .background(C.Muted).clickable(onClick = onRemove).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
    if (open) {
        val full = remember(name) { com.claudeetf.app.util.Attachments.load(ctx, name, 1600)?.asImageBitmap() }
        androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).clickable { open = false }) {
                if (full != null) androidx.compose.foundation.Image(
                    full, contentDescription = "附件", modifier = Modifier.fillMaxWidth(),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                )
            }
        }
    }
}

// ---------------- 集中度與交易統計 ----------------

private fun androidx.compose.foundation.lazy.LazyListScope.concentrationSection() {
    val c = com.claudeetf.app.data.Insights.concentration() ?: return
    item { SectionTitle("集中度與風險", "等效 " + Fmt.num(c.effectiveN, 1) + " 檔") }
    item {
        Card(padding = 14.dp) {
            Row {
                Column(Modifier.weight(1f)) { Label("最大持股"); NumText(Fmt.num(c.top1, 1) + "%", 16.sp, weight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) { Label("前三大"); NumText(Fmt.num(c.top3, 1) + "%", 16.sp, weight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) { Label("美元資產"); NumText(Fmt.num(c.usd, 1) + "%", 16.sp, weight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) { Label("現金"); NumText(Fmt.num(c.cash, 1) + "%", 16.sp, weight = FontWeight.Bold) }
            }
            c.top.take(5).forEach { (sym, pct) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sym + " " + (AppState.quote(sym)?.name ?: ""), Modifier.width(130.dp), color = C.Text, fontSize = 12.sp, maxLines = 1)
                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Line)) {
                        Box(
                            Modifier.fillMaxWidth((pct / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                                .background(if (pct >= 25) C.Tw else C.Primary)
                        )
                    }
                    NumText(Fmt.num(pct, 1) + "%", 12.sp, modifier = Modifier.width(52.dp).padding(start = 6.dp))
                }
            }
            if (c.warnings.isEmpty()) Label("目前沒有明顯的集中風險。", color = C.Primary)
            c.warnings.forEach { w -> Text("⚠ " + w, color = C.Text2, fontSize = 12.sp, lineHeight = 18.sp) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.statsSection() {
    val s = com.claudeetf.app.data.Insights.tradeStats() ?: return
    item { SectionTitle("交易統計", "已賣出 ${s.closed} 筆") }
    item {
        Card(padding = 14.dp) {
            Row {
                Column(Modifier.weight(1f)) { Label("勝率"); NumText(Fmt.num(s.winRate, 0) + "%", 16.sp, weight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) { Label("獲利因子"); NumText(s.profitFactor?.let { Fmt.num(it, 2) } ?: "—", 16.sp, weight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) { Label("平均持有"); NumText(s.avgHoldDays?.let { Fmt.num(it, 0) + " 天" } ?: "—", 16.sp, weight = FontWeight.Bold) }
            }
            KeyValue("獲利／虧損筆數", "${s.wins} / ${s.losses}")
            KeyValue("平均獲利", mask(Fmt.signed(s.avgWin)), Trend.fg(s.avgWin))
            KeyValue("平均虧損", mask(Fmt.signed(s.avgLoss)), Trend.fg(s.avgLoss))
            s.best?.let { KeyValue("最大單筆獲利", it.first + " " + mask(Fmt.signed(it.second)), Trend.fg(it.second)) }
            s.worst?.let { KeyValue("最大單筆虧損", it.first + " " + mask(Fmt.signed(it.second)), Trend.fg(it.second)) }
            KeyValue("累計已實現", mask(Fmt.signed(s.totalRealized)), Trend.fg(s.totalRealized))
            Label("獲利因子＝總獲利 ÷ 總虧損，大於 1 代表賺多賠少；平均持有天數以先進先出計算，不含期初持股。")
        }
    }
}
