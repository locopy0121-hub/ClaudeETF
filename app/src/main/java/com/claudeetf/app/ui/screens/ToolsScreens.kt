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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** 目標比例存放鍵：全部用代號本身（相容舊資料），台股／美股分開各自 100% */
private fun targetKey(scope: Int, symbol: String) = when (scope) { 1 -> "tw|$symbol"; 2 -> "us|$symbol"; else -> symbol }

private fun normalizeTenths(weights: List<Double>) = com.claudeetf.app.util.TwRules.normalizeTenths(weights)

@Composable
fun RebalanceScreen(nav: NavController) {
    var scope by rememberSaveable { mutableIntStateOf(0) }
    var cashText by rememberSaveable(scope) { mutableStateOf("0") }
    var buyOnly by rememberSaveable { mutableStateOf(false) }
    val us = scope == 2
    val cur = if (us) "US$" else "NT$"
    val fx = AppState.usdTwd
    val holdings = AppState.holdings.filter {
        when (scope) { 1 -> AppState.marketOf(it.symbol) == Market.TW; 2 -> AppState.marketOf(it.symbol) == Market.US; else -> true }
    }
    // 美股分頁以美元計，其餘以台幣計
    fun value(h: com.claudeetf.app.data.Holding) = if (us) AppState.marketValue(h) else AppState.marketValueTwd(h)
    val rows = holdings.map { it to value(it) }.sortedByDescending { it.second }
    val mv = rows.sumOf { it.second }
    val newCash = (cashText.replace(",", "").toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
    fun tgtOf(s: String) = AppState.targets[targetKey(scope, s)] ?: 0.0
    val targeted = rows.filter { tgtOf(it.first.symbol) > 0 }
    val targetSum = targeted.sumOf { tgtOf(it.first.symbol) }
    // 沒設目標的持股不納入計算（不會被建議全部賣出）
    val base = targeted.sumOf { it.second } + newCash
    // 只買不賣：新資金依缺口比例分配給低配的標的；缺口都補滿後剩餘依目標比例分配
    val plan: Map<String, Double> = run {
        val want = targeted.associate { (h, v) -> h.symbol to (base * tgtOf(h.symbol) / 100 - v) }
        if (!buyOnly) want else {
            val gaps = want.mapValues { maxOf(0.0, it.value) }
            val g = gaps.values.sum()
            if (g <= 0) targeted.associate { (h, _) -> h.symbol to newCash * tgtOf(h.symbol) / targetSum.coerceAtLeast(1e-9) }
            else if (g <= newCash) gaps.mapValues { (s, d) -> d + (newCash - g) * tgtOf(s) / targetSum.coerceAtLeast(1e-9) }
            else gaps.mapValues { newCash * it.value / g }
        }
    }
    // 偏離度：各標的「目前占比 − 目標」絕對值合計的一半（0% = 完全符合）
    val drift = if (base > 0) targeted.sumOf { (h, v) -> abs(v / (base - newCash).coerceAtLeast(1e-9) * 100 - tgtOf(h.symbol)) } / 2 else 0.0
    val buyTotal = plan.values.filter { it > 0 }.sum()
    val sellTotal = -plan.values.filter { it < 0 }.sum()

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("目標配置與再平衡", onBack = { nav.popBackStack() })
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Segmented(listOf("全部", "台股", "美股"), scope, { scope = it })
            }
            item {
                Card(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Label((listOf("全部", "台股", "美股")[scope]) + "持股市值" + if (us) "（美元）" else "（台幣）")
                            NumText(mask(cur + Fmt.num(mv)), 20.sp, C.Text, FontWeight.Bold)
                            if (us) Label("≈ NT$" + mask(Fmt.num(mv * fx)) + " · 匯率 " + Fmt.num(fx, 2))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Label("偏離度")
                            NumText(if (targeted.isEmpty()) "—" else Fmt.num(drift, 1) + "%", 18.sp,
                                when { targeted.isEmpty() -> C.Muted; drift < 2 -> C.Primary; drift < 5 -> C.Tw; else -> C.Red }, FontWeight.Bold)
                            Label(when { targeted.isEmpty() -> "尚未設定"; drift < 2 -> "配置良好"; drift < 5 -> "略有偏離"; else -> "建議調整" })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        cashText, { cashText = it }, label = { Text("本次投入新資金（" + (if (us) "USD" else "TWD") + "，可填 0）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors(),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("只買不賣", color = C.Text, fontSize = 13.sp)
                            Label("只用新資金補足低配標的，不產生賣出與已實現損益")
                        }
                        Switch(buyOnly, { buyOnly = it }, colors = SwitchDefaults.colors(checkedTrackColor = C.Primary, checkedThumbColor = C.OnPrimary, uncheckedTrackColor = C.Border))
                    }
                    val ok = abs(targetSum - 100) < 0.05
                    KeyValue("目標合計（${targeted.size}/${rows.size} 檔）", Fmt.num(targetSum, 1) + "%", if (ok) C.Primary else C.Red)
                    if (!ok && targetSum > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("合計需為 100%，建議才會準確。", Modifier.weight(1f), color = C.Red)
                        Text("一鍵補齊 100%", color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                val n = normalizeTenths(targeted.map { tgtOf(it.first.symbol) })
                                targeted.forEachIndexed { i, (h, _) -> AppState.setTarget(targetKey(scope, h.symbol), n[i]) }
                            }.padding(6.dp))
                    }
                    if (targeted.isNotEmpty() && (buyTotal > 0 || sellTotal > 0)) {
                        KeyValue("合計建議買進", mask(cur + Fmt.num(buyTotal)), Trend.fg(1.0))
                        if (sellTotal > 0) KeyValue("合計建議賣出", mask(cur + Fmt.num(sellTotal)), Trend.fg(-1.0))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                        ToolButton("目標＝目前", Modifier.weight(1f)) {
                            val n = normalizeTenths(rows.map { it.second })
                            rows.forEachIndexed { i, (h, _) -> AppState.setTarget(targetKey(scope, h.symbol), n[i]) }
                        }
                        ToolButton("平均分配", Modifier.weight(1f)) {
                            val n = normalizeTenths(rows.map { 1.0 })
                            rows.forEachIndexed { i, (h, _) -> AppState.setTarget(targetKey(scope, h.symbol), n[i]) }
                        }
                        ToolButton("清除", Modifier.weight(0.7f)) { rows.forEach { (h, _) -> AppState.setTarget(targetKey(scope, h.symbol), null) } }
                    }
                }
            }
            if (rows.isEmpty()) item { Label(if (scope == 0) "目前沒有持股" else "目前沒有" + (if (us) "美股" else "台股") + "持股") }
            items(rows, key = { "rb$scope" + it.first.symbol }) { (h, v) ->
                val q = AppState.quote(h.symbol)
                val curPct = if (mv > 0) v / mv * 100 else 0.0
                val tgt = tgtOf(h.symbol)
                val diff = plan[h.symbol] ?: 0.0
                val px = (q?.price ?: h.avgCost) * (if (us || AppState.marketOf(h.symbol) == Market.TW) 1.0 else fx)
                val sh = if (px > 0) abs(diff) / px else 0.0
                val isUs = AppState.marketOf(h.symbol) == Market.US
                var tgtText by remember(h.symbol, scope) { mutableStateOf(if (tgt > 0) Fmt.num(tgt, 1) else "") }
                LaunchedEffect(tgt) { if ((tgtText.toDoubleOrNull() ?: 0.0) != tgt) tgtText = if (tgt > 0) Fmt.num(tgt, 1) else "" }
                Card(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (scope == 0) { MarketTag(AppState.marketOf(h.symbol)); Spacer(Modifier.width(6.dp)) }
                                Text("${h.symbol} ${q?.name ?: ""}", color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Label("目前 " + Fmt.num(curPct, 1) + "% · " + mask(cur + Fmt.num(v)))
                        }
                        OutlinedTextField(
                            tgtText, { tgtText = it; AppState.setTarget(targetKey(scope, h.symbol), it.toDoubleOrNull()) },
                            label = { Text("目標 %") }, singleLine = true, modifier = Modifier.width(96.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors(),
                        )
                    }
                    // 進度條：實心為目前占比，白色刻度為目標
                    Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Line)) {
                        Box(Modifier.fillMaxWidth((curPct / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                            .background(if (tgt > 0 && curPct > tgt + 0.5) Trend.fg(-1.0) else C.Primary))
                        if (tgt > 0) BoxWithConstraints(Modifier.fillMaxSize()) {
                            Box(Modifier.offset(x = maxWidth * (tgt / 100).toFloat().coerceIn(0f, 1f) - 1.dp).width(2.dp).fillMaxHeight().background(C.Text))
                        }
                    }
                    if (tgt <= 0) Label("未設目標，不納入再平衡計算", Modifier.padding(top = 4.dp))
                    else {
                        val lotTxt = if (!isUs) {
                            val n = Math.floor(sh).toLong()
                            if (n >= 1000) "約 ${Fmt.num(n.toDouble())} 股（${n / 1000} 張 ${n % 1000} 股）" else "約 ${Fmt.num(n.toDouble())} 股（零股）"
                        } else "約 " + Fmt.num(sh, 2) + " 股"
                        val action = when {
                            abs(diff) < maxOf(base * 0.002, 1.0) -> "已接近目標 " + Fmt.num(tgt, 1) + "%"
                            diff > 0 -> "建議買進 " + mask(cur + Fmt.num(diff)) + "，" + lotTxt
                            else -> "建議賣出 " + mask(cur + Fmt.num(-diff)) + "，" + lotTxt
                        }
                        Text(action, color = if (abs(diff) < maxOf(base * 0.002, 1.0)) C.Muted else Trend.fg(diff), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            item {
                Label("台股、美股分頁的目標各自計算（各自合計 100%）；美股分頁以美元計。建議以目前市值計算，未扣手續費與稅，賣出前請留意已實現損益與交易成本。", Modifier.padding(top = 6.dp))
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
        }.fold({ Load.Ok(it) }, { Load.err(it) })
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
