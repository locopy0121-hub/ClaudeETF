package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.DcaPlan
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.StockInfoRepo
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.data.remote.friendly
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

@Composable
private fun ToolButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (primary) C.Primary else C.Selected).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (primary) C.OnPrimary else C.Text, fontSize = 13.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal) }
}

// ============================== 定期定額 ==============================

@Composable
fun DcaScreen(nav: NavController) {
    var adding by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val due = AppState.dueDca()
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("定期定額", onBack = { nav.popBackStack() }) {
            Text(
                if (adding) "取消" else "＋ 新增", color = C.Primary, fontSize = 14.sp,
                modifier = Modifier.clickable { adding = !adding }.padding(12.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (adding) item { DcaEditor(null) { adding = false } }
            if (due.isNotEmpty()) {
                item { SectionTitle("本月待記錄", "${due.size} 筆") }
                items(due, key = { "due-" + it.id }) { p ->
                    val q = AppState.quote(p.symbol)
                    Card(padding = 14.dp) {
                        Text("${p.symbol} ${q?.name ?: ""}", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Label(
                            "每月 ${p.day} 日 · " + (if (q?.market == Market.US) "$" else "NT$") + Fmt.num(p.amount) +
                                (q?.takeIf { it.price > 0 }?.let { " · 現價 " + Fmt.price(it.price) } ?: ""),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToolButton("依現價記錄", Modifier.weight(1f), primary = true) {
                                msg = AppState.recordDca(p) ?: "已記錄 ${p.symbol} 定期定額買進"
                            }
                            ToolButton("本月略過", Modifier.weight(1f)) { AppState.skipDca(p) }
                        }
                    }
                }
            }
            msg?.let { m -> item { Label(m, color = C.Primary) } }
            item { SectionTitle("我的計畫") }
            if (AppState.dcaPlans.isEmpty()) item { Label("還沒有定期定額計畫，按右上角「＋ 新增」建立。") }
            items(AppState.dcaPlans.toList(), key = { it.id }) { p -> DcaRow(p) }
            item {
                Label(
                    "扣款日到了會推播通知，並出現在「本月待記錄」。依現價記錄時，台股以整股計算、美股可到小數 4 位，" +
                        "手續費依所選帳戶設定；實際成交價與股數請以券商對帳單為準，可在交易紀錄中修改。",
                    Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DcaRow(p: DcaPlan) {
    var editing by remember { mutableStateOf(false) }
    val q = AppState.quote(p.symbol)
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { editing = !editing }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MarketTag(q?.market ?: if (p.symbol.firstOrNull()?.isDigit() == true) Market.TW else Market.US); HGap(6.dp)
                    Text("${p.symbol} ${q?.name ?: ""}", color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Label(
                    "每月 ${p.day} 日 · " + (if (q?.market == Market.US) "$" else "NT$") + Fmt.num(p.amount) +
                        (AppState.account(p.account)?.let { " · " + it.name } ?: "") +
                        (if (p.lastDone.isNotEmpty()) " · 最近 ${p.lastDone}" else ""),
                )
            }
            Switch(
                checked = p.enabled, onCheckedChange = { AppState.saveDca(p.copy(enabled = it)) },
                colors = SwitchDefaults.colors(checkedTrackColor = C.Primary, checkedThumbColor = C.OnPrimary, uncheckedTrackColor = C.Border),
            )
        }
        if (editing) DcaEditor(p) { editing = false }
    }
}

@Composable
private fun DcaEditor(plan: DcaPlan?, onDone: () -> Unit) {
    var symbol by remember { mutableStateOf(plan?.symbol ?: "") }
    var amount by remember { mutableStateOf(plan?.amount?.let { Fmt.num(it).replace(",", "") } ?: "") }
    var day by remember { mutableStateOf(plan?.day?.toString() ?: "6") }
    var accountId by remember { mutableIntStateOf(plan?.account ?: 0) }
    var error by remember { mutableStateOf<String?>(null) }
    val sym = symbol.trim().uppercase()
    val market = if (sym.firstOrNull()?.isDigit() == true) Market.TW else Market.US
    val accs = AppState.accountsFor(market)
    val eff = if (accs.any { it.id == accountId }) accountId else accs.firstOrNull()?.id ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plan == null) Text("新增定期定額", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(symbol, { symbol = it }, label = { Text("代號（例：0050、VOO）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                amount, { amount = it }, label = { Text(if (market == Market.US) "每期金額（美元）" else "每期金額（元）") },
                singleLine = true, modifier = Modifier.weight(1.4f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors(),
            )
            OutlinedTextField(
                day, { day = it }, label = { Text("每月幾日") }, singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = fieldColors(),
            )
        }
        if (accs.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                accs.forEach { a ->
                    val on = a.id == eff
                    Text(
                        a.name, color = if (on) C.Text else C.Muted, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Selected else C.Bg)
                            .clickable { accountId = a.id }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        error?.let { Text(it, color = C.Red, fontSize = 12.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolButton("儲存", Modifier.weight(1f), primary = true) {
                val a = amount.replace(",", "").toDoubleOrNull()
                val d = day.toIntOrNull()
                when {
                    sym.isEmpty() -> error = "請輸入代號"
                    a == null || a <= 0 -> error = "請輸入金額"
                    d == null || d !in 1..31 -> error = "日期請輸入 1–31"
                    else -> {
                        AppState.saveDca((plan ?: DcaPlan(0, sym, a, d)).copy(symbol = sym, amount = a, day = d, account = eff))
                        onDone()
                    }
                }
            }
            if (plan != null) ToolButton("刪除", Modifier.weight(1f)) { AppState.deleteDca(plan.id); onDone() }
        }
    }
}

// ============================== 再平衡 ==============================

@Composable
fun RebalanceScreen(nav: NavController) {
    var cashText by remember { mutableStateOf("0") }
    val newCash = cashText.replace(",", "").toDoubleOrNull() ?: 0.0
    val holdings = AppState.holdings.toList()
    val mv = holdings.sumOf { AppState.marketValueTwd(it) }
    val total = mv + newCash
    val targetSum = holdings.sumOf { AppState.targets[it.symbol] ?: 0.0 }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("目標配置與再平衡", onBack = { nav.popBackStack() })
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(padding = 14.dp) {
                    KeyValue("持股市值", mask("NT$" + Fmt.num(mv)))
                    OutlinedTextField(
                        cashText, { cashText = it }, label = { Text("本次要投入的新資金（TWD，可填 0）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors(),
                    )
                    KeyValue("目標合計", Fmt.num(targetSum, 1) + "%", if (abs(targetSum - 100) < 0.05) C.Primary else C.Red)
                    if (abs(targetSum - 100) >= 0.05) Label("目標比例合計需為 100%，建議才會準確。", color = C.Red)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolButton("目標＝目前比例", Modifier.weight(1f)) {
                            if (mv > 0) holdings.forEach { AppState.setTarget(it.symbol, Math.round(AppState.marketValueTwd(it) / mv * 1000) / 10.0) }
                        }
                        ToolButton("平均分配", Modifier.weight(1f)) {
                            if (holdings.isNotEmpty()) holdings.forEach { AppState.setTarget(it.symbol, Math.round(1000.0 / holdings.size) / 10.0) }
                        }
                    }
                }
            }
            if (holdings.isEmpty()) item { Label("目前沒有持股") }
            items(holdings, key = { it.symbol }) { h ->
                val q = AppState.quote(h.symbol)
                val cur = AppState.marketValueTwd(h)
                val curPct = if (total > 0) cur / total * 100 else 0.0
                val tgt = AppState.targets[h.symbol] ?: 0.0
                val diff = total * tgt / 100 - cur
                val fx = AppState.fxFor(AppState.marketOf(h.symbol))
                val price = (q?.price ?: h.avgCost) * fx
                val shares = if (price > 0) diff / price else 0.0
                val isUs = AppState.marketOf(h.symbol) == Market.US
                var tgtText by remember(h.symbol) { mutableStateOf(if (tgt > 0) Fmt.num(tgt, 1) else "") }
                Card(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${h.symbol} ${q?.name ?: ""}", color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Label("目前 " + Fmt.num(curPct, 1) + "% · " + mask("NT$" + Fmt.num(cur)))
                        }
                        OutlinedTextField(
                            tgtText, { tgtText = it; AppState.setTarget(h.symbol, it.toDoubleOrNull()) },
                            label = { Text("目標 %") }, singleLine = true, modifier = Modifier.width(96.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors(),
                        )
                    }
                    if (tgt > 0) {
                        val action = when {
                            abs(diff) < total * 0.005 -> "已接近目標"
                            diff > 0 -> "建議買進 " + mask("NT$" + Fmt.num(diff)) + "（約 " + (if (isUs) Fmt.num(shares, 2) else Fmt.num(Math.floor(shares))) + " 股）"
                            else -> "建議賣出 " + mask("NT$" + Fmt.num(-diff)) + "（約 " + (if (isUs) Fmt.num(-shares, 2) else Fmt.num(Math.floor(-shares))) + " 股）"
                        }
                        Text(action, color = if (diff > 0) Trend.fg(1.0) else if (diff < 0) Trend.fg(-1.0) else C.Muted, fontSize = 13.sp)
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Line)) {
                            Box(Modifier.fillMaxWidth((curPct / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(C.Primary))
                        }
                    }
                }
            }
            item {
                Label("建議以目前市值與新資金計算，未扣手續費與稅；賣出前請留意已實現損益與交易成本。", Modifier.padding(top = 6.dp))
            }
        }
    }
}

// ============================== 個股比較 ==============================

private val compareColors = listOf(Color(0xFF6EA8FF), Color(0xFFF2B544), Color(0xFFB9A6FF), Color(0xFF4FD1C5))

@Composable
fun CompareScreen(nav: NavController, initial: String) {
    var picked by remember { mutableStateOf(initial.split(",").filter { it.isNotBlank() }.take(4)) }
    var range by remember { mutableIntStateOf(2) }
    val ranges = listOf("1mo" to "1月", "6mo" to "6月", "1y" to "1年", "5y" to "5年")
    var data by remember { mutableStateOf<Load<Map<String, YahooExtra.History>>>(Load.Ok(emptyMap())) }
    LaunchedEffect(picked, range) {
        if (picked.isEmpty()) { data = Load.Ok(emptyMap()); return@LaunchedEffect }
        data = Load.Loading
        data = runCatching {
            picked.mapNotNull { s -> AppState.quote(s)?.let { q -> s to StockInfoRepo.history(q, ranges[range].first) } }.toMap()
        }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("個股比較", onBack = { nav.popBackStack() })
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Label("從自選清單選最多 4 檔（點一下加入或移除）") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppState.quotes.forEach { q ->
                        val idx = picked.indexOf(q.symbol)
                        val on = idx >= 0
                        Row(
                            Modifier.clip(RoundedCornerShape(15.dp)).background(if (on) C.Selected else C.Card)
                                .clickable {
                                    picked = if (on) picked - q.symbol else if (picked.size < 4) picked + q.symbol else picked
                                }.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (on) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(compareColors[idx])); HGap(5.dp) }
                            Text(q.symbol, color = if (on) C.Text else C.Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            item { Segmented(ranges.map { it.second }, range, { range = it }) }
            when (val d = data) {
                is Load.Loading -> item { Label("載入走勢中…", Modifier.padding(vertical = 20.dp)) }
                is Load.Err -> item { Text("載入失敗：" + d.message, color = C.Red, fontSize = 13.sp) }
                is Load.Ok -> {
                    val series = picked.mapIndexedNotNull { i, s ->
                        val h = d.value[s] ?: return@mapIndexedNotNull null
                        if (h.closes.size < 2) null else Triple(s, h, compareColors[i])
                    }
                    if (series.isEmpty()) {
                        item { Label("選擇要比較的股票", Modifier.padding(vertical = 20.dp)) }
                    } else {
                        item {
                            Card(padding = 14.dp) {
                                MultiLine(
                                    series.map { (_, h, c) -> Triple(h.closes.map { (it / h.closes.first() - 1) * 100 }, c, false) },
                                    Modifier.fillMaxWidth().height(180.dp),
                                )
                                Label("以區間第一天為 0% 的累積報酬")
                            }
                        }
                        item {
                            Row(Modifier.fillMaxWidth()) {
                                Label("代號", Modifier.weight(1.2f))
                                listOf("區間報酬", "年化波動", "最大回撤").forEach { Label(it, Modifier.weight(1f).wrapContentWidth(Alignment.End)) }
                            }
                        }
                        items(series) { (s, h, c) ->
                            val ret = (h.closes.last() / h.closes.first() - 1) * 100.0
                            val rets = (1 until h.closes.size).map { h.closes[it] / h.closes[it - 1] - 1.0 }
                            val mean = rets.average()
                            val perYear = when (range) { 3 -> 52.0; else -> 252.0 }
                            val vol = sqrt(rets.sumOf { (it - mean).pow(2) } / maxOf(1, rets.size - 1)) * sqrt(perYear) * 100
                            var peak = h.closes.first()
                            var mdd = 0.0
                            for (v in h.closes) { if (v > peak) peak = v; mdd = minOf(mdd, (v / peak - 1).toDouble()) }
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(1.2f), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c)); HGap(6.dp)
                                    Text(s, color = C.Text, fontSize = 13.sp)
                                }
                                NumText(Fmt.pct(ret, 1), 13.sp, Trend.fg(ret), modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End))
                                NumText(Fmt.num(vol, 1) + "%", 13.sp, modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End))
                                NumText(Fmt.num(mdd * 100, 1) + "%", 13.sp, modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End))
                            }
                        }
                        item { Label("價格為原幣計價，不含股息；資料來源 Yahoo Finance。", Modifier.padding(top = 6.dp)) }
                    }
                }
            }
        }
    }
}
