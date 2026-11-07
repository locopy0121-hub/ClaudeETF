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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.StockInfoRepo
import com.claudeetf.app.data.TaxRules
import com.claudeetf.app.data.remote.BotFxApi
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.data.remote.friendly
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.launch

// ============================== 複利試算（股息再投入） ==============================

private data class DripYear(
    val year: Int, val reinvest: Double, val cashOut: Double, val divThisYear: Double, val invested: Double,
    val divTotal: Double, val real: Double,
)

private data class DripIn(
    val initial: Double, val monthly: Double, val yieldPct: Double, val growthPct: Double, val years: Int,
    val taxRate: Double, val stepUpPct: Double, val payPerYear: Int, val inflationPct: Double,
)

/**
 * 逐月複利：股價每月依年成長率成長；股息依配息頻率（月／季／半年／年）發放，
 * 再投入情境把稅後股息買回，領現情境累積成現金；每月投入每年依加碼率調升。
 * real = 再投入資產折算成今天的購買力（扣通膨）。
 */
private fun simulate(i: DripIn): List<DripYear> {
    val g = Math.pow(1 + i.growthPct / 100, 1.0 / 12) - 1
    val per = i.payPerYear.coerceIn(1, 12)
    val every = 12 / per
    val dPay = i.yieldPct / 100 / per
    var a = i.initial; var b = i.initial; var cash = 0.0; var invested = i.initial; var divTotal = 0.0
    var m = i.monthly
    val out = ArrayList<DripYear>()
    out += DripYear(0, a, b, 0.0, invested, 0.0, a)
    for (y in 1..i.years) {
        var divYear = 0.0
        for (mo in 1..12) {
            a = a * (1 + g) + m
            b = b * (1 + g) + m
            invested += m
            if (mo % every == 0) {
                val divA = a * dPay * (1 - i.taxRate)
                a += divA; divYear += divA; divTotal += divA
                cash += b * dPay * (1 - i.taxRate)
            }
        }
        m *= 1 + i.stepUpPct / 100
        out += DripYear(y, a, b + cash, divYear, invested, divTotal, a / Math.pow(1 + i.inflationPct / 100, y.toDouble()))
    }
    return out
}

/** 目標反推：在給定年數內達到目標金額，每月需投入多少（二分搜尋） */
private fun requiredMonthly(target: Double, i: DripIn): Double? {
    if (simulate(i.copy(monthly = 0.0)).last().reinvest >= target) return 0.0
    var lo = 0.0; var hi = maxOf(target / maxOf(1, i.years * 12), 1.0)
    while (simulate(i.copy(monthly = hi)).last().reinvest < target) { hi *= 2; if (hi > 1e12) return null }
    repeat(50) { val mid = (lo + hi) / 2; if (simulate(i.copy(monthly = mid)).last().reinvest >= target) hi = mid else lo = mid }
    return hi
}

/** 目標反推：以目前每月投入，第幾年達到目標（50 年內） */
private fun yearsToTarget(target: Double, i: DripIn): Int? =
    simulate(i.copy(years = 50)).firstOrNull { it.reinvest >= target }?.year

@Composable
fun DripScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var initial by rememberSaveable { mutableStateOf("1000000") }
    var monthly by rememberSaveable { mutableStateOf("10000") }
    var yieldText by rememberSaveable { mutableStateOf("4") }
    var growth by rememberSaveable { mutableStateOf("3") }
    var yearsText by rememberSaveable { mutableStateOf("20") }
    var stepUp by rememberSaveable { mutableStateOf("0") }
    var inflation by rememberSaveable { mutableStateOf("2") }
    var targetText by rememberSaveable { mutableStateOf("10000000") }
    var freq by rememberSaveable { mutableIntStateOf(1) } // 0 月 1 季 2 半年 3 年
    var withTax by rememberSaveable { mutableStateOf(false) }
    var taxRate by rememberSaveable { mutableStateOf(0.0) }
    var picked by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    fun d(s: String) = s.replace(",", "").toDoubleOrNull() ?: 0.0

    val years = (yearsText.toIntOrNull() ?: 20).coerceIn(1, 50)
    val input = DripIn(
        d(initial).coerceAtLeast(0.0), d(monthly).coerceAtLeast(0.0), d(yieldText).coerceIn(0.0, 50.0), d(growth).coerceIn(-50.0, 50.0),
        years, if (withTax) taxRate else 0.0, d(stepUp).coerceIn(0.0, 50.0), listOf(12, 4, 2, 1)[freq], d(inflation).coerceIn(0.0, 20.0),
    )
    val rows = remember(input) { simulate(input) }
    val last = rows.last()
    val target = d(targetText)

    /** 帶入一組持股：市值合計、以市值加權的近 12 個月殖利率；美股預設扣 30% 預扣稅 */
    fun loadGroup(label: String, list: List<com.claudeetf.app.data.Holding>) {
        if (list.isEmpty()) return
        picked = label
        val total = list.sumOf { AppState.marketValueTwd(it) }
        initial = Math.round(total).toString()
        val usShare = list.filter { AppState.marketOf(it.symbol) == Market.US }.sumOf { AppState.marketValueTwd(it) } / total.coerceAtLeast(1.0)
        taxRate = TaxRules.US_WITHHOLD * usShare
        withTax = usShare > 0.01
        msg = "載入 $label 配息紀錄中…"
        scope.launch {
            var w = 0.0; var y = 0.0; var miss = 0
            list.forEach { h ->
                val q = AppState.quote(h.symbol) ?: return@forEach
                val v = AppState.marketValueTwd(h)
                val yp = runCatching { StockInfoRepo.dividends(q).yieldPct }.getOrNull()
                if (yp != null) { y += yp * v; w += v } else miss++
            }
            msg = if (w > 0) {
                val avg = y / total
                yieldText = Fmt.num(avg, 2)
                "已帶入 $label：市值 NT$" + Fmt.num(total) + "，加權殖利率 " + Fmt.num(avg, 2) + "%" + if (miss > 0) "（$miss 檔無配息，以 0 計）" else ""
            } else "$label 近一年沒有配息紀錄"
        }
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("複利試算", onBack = { nav.popBackStack() })
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Segmented(listOf("存股複利", "目標反推"), mode, { mode = it }) }
            if (AppState.holdings.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label("從持股帶入（市值與近 12 個月殖利率）")
                        val tw = AppState.holdings.filter { AppState.marketOf(it.symbol) == Market.TW }
                        val usL = AppState.holdings.filter { AppState.marketOf(it.symbol) == Market.US }
                        val groups = listOf("全部持股" to AppState.holdings.toList(), "台股持股" to tw, "美股持股" to usL).filter { it.second.isNotEmpty() }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            groups.forEach { (n, l) ->
                                val on = picked == n
                                Text(n, color = if (on) C.OnPrimary else C.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Primary else C.Card)
                                        .clickable { loadGroup(n, l) }.padding(horizontal = 12.dp, vertical = 7.dp))
                            }
                            AppState.holdings.forEach { h ->
                                val on = picked == h.symbol
                                Text(h.symbol, color = if (on) C.Text else C.Muted, fontSize = 12.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Selected else C.Card)
                                        .clickable { loadGroup(h.symbol, listOf(h)) }.padding(horizontal = 12.dp, vertical = 7.dp))
                            }
                        }
                        msg?.let { Label(it, color = C.Primary) }
                    }
                }
            }
            item {
                Card(padding = 14.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("期初金額（元）", initial, Modifier.weight(1f)) { initial = it }
                        NumField("每月投入（元）", monthly, Modifier.weight(1f)) { monthly = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("年殖利率 %", yieldText, Modifier.weight(1f)) { yieldText = it }
                        NumField("股價年成長 %", growth, Modifier.weight(1f)) { growth = it }
                        NumField("年數", yearsText, Modifier.weight(0.8f)) { yearsText = it }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumField("每年加碼 %", stepUp, Modifier.weight(1f)) { stepUp = it }
                        NumField("通膨 %", inflation, Modifier.weight(1f)) { inflation = it }
                        if (mode == 1) NumField("目標金額", targetText, Modifier.weight(1.3f)) { targetText = it }
                    }
                    Label("配息頻率", Modifier.padding(top = 6.dp))
                    Segmented(listOf("月配", "季配", "半年", "年配"), freq, { freq = it }, Modifier.fillMaxWidth().padding(top = 4.dp), background = C.Bg)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("扣除股息預扣稅", color = C.Text, fontSize = 13.sp)
                            Label(if (taxRate > 0) "以 ${Fmt.num(taxRate * 100, 1)}% 計（美股部位）" else "台股多數不預扣，二代健保另計")
                        }
                        Switch(
                            checked = withTax, onCheckedChange = { withTax = it; if (it && taxRate == 0.0) taxRate = TaxRules.US_WITHHOLD },
                            colors = SwitchDefaults.colors(checkedTrackColor = C.Primary, checkedThumbColor = C.OnPrimary, uncheckedTrackColor = C.Border),
                        )
                    }
                }
            }
            if (mode == 1) item {
                Card(padding = 14.dp) {
                    Label("目標 NT$" + Fmt.num(target))
                    if (target <= 0) Label("請輸入目標金額", color = C.Red) else {
                        val need = remember(input, target) { requiredMonthly(target, input) }
                        val yrs = remember(input, target) { yearsToTarget(target, input) }
                        KeyValue("$years 年內達標，每月需投入", need?.let { "NT$" + Fmt.num(Math.ceil(it)) } ?: "無法達成", C.Primary)
                        need?.let { KeyValue("與目前每月投入相比", Fmt.signed(it - input.monthly, 0), Trend.fg(input.monthly - it)) }
                        KeyValue("以目前每月 NT$" + Fmt.num(input.monthly) + "，達標需要", yrs?.let { "$it 年" } ?: "50 年內無法達成", if (yrs != null && yrs <= years) C.Primary else C.Red)
                        val real = target / Math.pow(1 + input.inflationPct / 100, years.toDouble())
                        KeyValue("目標折合今日購買力", "NT$" + Fmt.num(real))
                    }
                }
            }
            item {
                Card(padding = 14.dp) {
                    Label("$years 年後")
                    Row {
                        Column(Modifier.weight(1f)) {
                            Label("股息再投入")
                            NumText(Fmt.num(last.reinvest), 20.sp, C.Primary, FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f)) {
                            Label("股息領現（含累積現金）")
                            NumText(Fmt.num(last.cashOut), 20.sp, C.Tw, FontWeight.Bold)
                        }
                    }
                    KeyValue("累計投入本金", Fmt.num(last.invested))
                    KeyValue("複利報酬（資產 − 本金）", Fmt.signed(last.reinvest - last.invested, 0) + "（" + Fmt.num(if (last.invested > 0) (last.reinvest / last.invested - 1) * 100 else 0.0, 1) + "%）", Trend.fg(last.reinvest - last.invested))
                    KeyValue("再投入比領現多", Fmt.signed(last.reinvest - last.cashOut, 0), Trend.fg(last.reinvest - last.cashOut))
                    KeyValue("累計領到股息（稅後）", Fmt.num(last.divTotal))
                    KeyValue("第 $years 年股息", Fmt.num(last.divThisYear) + "（月均 " + Fmt.num(last.divThisYear / 12) + "）")
                    KeyValue("折合今日購買力（扣通膨）", Fmt.num(last.real))
                    // 年化總報酬（股價成長＋股息）與資產翻倍年數
                    val r = ((1 + input.growthPct / 100) * (1 + input.yieldPct / 100 * (1 - input.taxRate)) - 1)
                    if (r > 0) KeyValue("資產翻倍約需（不計投入）", Fmt.num(Math.log(2.0) / Math.log(1 + r), 1) + " 年（年化 " + Fmt.num(r * 100, 2) + "%）")
                    // 被動收入超過每月投入：股息自己就能養投入
                    rows.firstOrNull { it.year > 0 && input.monthly > 0 && it.divThisYear / 12 >= input.monthly }?.let {
                        KeyValue("月均股息超過每月投入", "第 ${it.year} 年", C.Primary)
                    }
                    MultiLine(
                        listOf(
                            Triple(rows.map { it.invested.toFloat() }, C.Gray, true),
                            Triple(rows.map { it.cashOut.toFloat() }, C.Tw, false),
                            Triple(rows.map { it.reinvest.toFloat() }, C.Primary, false),
                        ),
                        Modifier.fillMaxWidth().height(150.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Label("■ 再投入", color = C.Primary); Label("■ 領現", color = C.Tw); Label("--- 本金", color = C.Gray)
                    }
                }
            }
            item { SectionTitle("逐年明細", "本金 · 再投入資產 · 年股息") }
            items(rows.filter { it.year > 0 && (it.year % maxOf(1, years / 10) == 0 || it.year == years) }, key = { "y" + it.year }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("第 ${r.year} 年", Modifier.width(64.dp), color = C.Text, fontSize = 13.sp)
                    NumText(Fmt.num(r.invested), 13.sp, C.Muted, modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End))
                    NumText(Fmt.num(r.reinvest), 13.sp, modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End))
                    NumText(Fmt.num(r.divThisYear), 13.sp, C.Tw, modifier = Modifier.weight(0.9f).wrapContentWidth(Alignment.End))
                }
            }
            item { Label("試算假設殖利率與成長率固定、不含手續費；實際結果會不同，僅供規劃參考。", Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
private fun NumField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, label = { Text(label, fontSize = 11.sp, maxLines = 1) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors(),
    )
}

// ============================== 匯率 ==============================

@Composable
fun FxScreen(nav: NavController) {
    var range by remember { mutableIntStateOf(2) }
    val ranges = listOf("5d" to "5日", "1mo" to "1月", "6mo" to "6月", "1y" to "1年", "5y" to "5年")
    var hist by remember { mutableStateOf<Load<YahooExtra.History>>(Load.Loading) }
    var bank by remember { mutableStateOf<Load<BotFxApi.Fx>>(Load.Loading) }
    var usd by remember { mutableStateOf("1000") }
    var twd by remember { mutableStateOf("") }
    LaunchedEffect(range) {
        hist = Load.Loading
        hist = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YahooExtra.history("TWD=X", ranges[range].first) }
        }.fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }
    LaunchedEffect(Unit) {
        bank = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BotFxApi.usd() } }
            .fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
    }
    val us = AppState.holdingsOf(Market.US)
    val usCost = us.sumOf { it.shares * it.avgCost }
    val avgFx = if (usCost > 0) us.sumOf { it.shares * it.avgCost * it.avgFx } / usCost else null

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("美元匯率", onBack = { nav.popBackStack() })
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(padding = 14.dp) {
                    Label("USD/TWD（App 計價用）")
                    NumText(Fmt.num(AppState.usdTwd, 3), 28.sp, weight = FontWeight.Bold)
                    Label("來源：" + AppState.fxSource + if (AppState.fxUpdatedAt > 0) " · " + Fmt.time(AppState.fxUpdatedAt) else "")
                    when (val b = bank) {
                        is Load.Ok -> {
                            KeyValue("台灣銀行 即期買入", Fmt.num(b.value.spotBuy, 3))
                            KeyValue("台灣銀行 即期賣出", Fmt.num(b.value.spotSell, 3))
                            Label("換美元看「賣出」、美元換回台幣看「買入」")
                        }
                        is Load.Err -> Label("台銀牌告：" + b.message)
                        is Load.Loading -> Label("載入台銀牌告…")
                    }
                }
            }
            item { Segmented(ranges.map { it.second }, range, { range = it }) }
            item {
                when (val h = hist) {
                    is Load.Loading -> Label("載入走勢中…", Modifier.padding(vertical = 20.dp))
                    is Load.Err -> Text("走勢載入失敗：" + h.message, color = C.Red, fontSize = 13.sp)
                    is Load.Ok -> if (h.value.closes.size >= 2) {
                        Card(padding = 14.dp) {
                            val c = h.value.closes
                            val chg = (c.last() / c.first() - 1) * 100.0
                            Row {
                                Label("區間 ", Modifier.weight(1f))
                                Text("美元" + (if (chg >= 0) "升值 " else "貶值 ") + Fmt.num(kotlin.math.abs(chg), 2) + "%",
                                    color = C.Text, fontSize = 12.sp)
                            }
                            Sparkline(c, C.Primary, Modifier.fillMaxWidth().height(140.dp), fill = true)
                            Row {
                                Label("低 " + Fmt.num(c.min().toDouble(), 3), Modifier.weight(1f))
                                Label("高 " + Fmt.num(c.max().toDouble(), 3))
                            }
                            avgFx?.let { a ->
                                Label("你的美股平均買入匯率 " + Fmt.num(a, 3) + "，目前" +
                                    (if (AppState.usdTwd >= a) "高於" else "低於") + "買入時（" + Fmt.signed((AppState.usdTwd - a), 3) + "）")
                            }
                        }
                    } else Label("此區間沒有資料")
                }
            }
            item {
                Card(padding = 14.dp) {
                    Text("換算", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        NumField("美元", usd, Modifier.weight(1f)) {
                            usd = it; twd = it.toDoubleOrNull()?.let { v -> Fmt.num(v * AppState.usdTwd, 0).replace(",", "") } ?: ""
                        }
                        Text("⇄", color = C.Muted, fontSize = 18.sp)
                        NumField("台幣", twd.ifEmpty { usd.toDoubleOrNull()?.let { Fmt.num(it * AppState.usdTwd, 0).replace(",", "") } ?: "" }, Modifier.weight(1f)) {
                            twd = it; usd = it.toDoubleOrNull()?.let { v -> Fmt.num(v / AppState.usdTwd, 2).replace(",", "") } ?: ""
                        }
                    }
                    Label("以目前計價匯率 ${Fmt.num(AppState.usdTwd, 3)} 換算，不含銀行價差與手續費")
                }
            }
            item { Label("走勢資料來源 Yahoo Finance（TWD=X）；牌告來源台灣銀行。", Modifier.padding(top = 4.dp)) }
        }
    }
}
