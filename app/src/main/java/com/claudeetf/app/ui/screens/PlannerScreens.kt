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

// ============================== 股息再投入試算 ==============================

private data class DripYear(val year: Int, val reinvest: Double, val cashOut: Double, val divThisYear: Double, val invested: Double)

/** 每月複利：股價每月成長、股息按年殖利率逐月發放（簡化），可選擇再投入 */
private fun simulate(initial: Double, monthly: Double, yieldPct: Double, growthPct: Double, years: Int, taxRate: Double): List<DripYear> {
    val g = Math.pow(1 + growthPct / 100, 1.0 / 12) - 1
    val dm = yieldPct / 100 / 12
    var a = initial      // 再投入情境
    var b = initial      // 不再投入（股息領現）
    var cash = 0.0
    var invested = initial
    val out = ArrayList<DripYear>()
    out += DripYear(0, a, b, 0.0, invested)
    for (y in 1..years) {
        var divYear = 0.0
        repeat(12) {
            val divA = a * dm * (1 - taxRate)
            val divB = b * dm * (1 - taxRate)
            a = a * (1 + g) + divA + monthly
            b = b * (1 + g) + monthly
            cash += divB
            divYear += divA
            invested += monthly
        }
        out += DripYear(y, a, b + cash, divYear, invested)
    }
    return out
}

@Composable
fun DripScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var initial by remember { mutableStateOf("1000000") }
    var monthly by remember { mutableStateOf("10000") }
    var yieldText by remember { mutableStateOf("4") }
    var growth by remember { mutableStateOf("3") }
    var yearsText by remember { mutableStateOf("20") }
    var withTax by remember { mutableStateOf(false) }
    var taxRate by remember { mutableStateOf(0.0) }
    var picked by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    val years = (yearsText.toIntOrNull() ?: 20).coerceIn(1, 50)
    val rows = simulate(
        initial.replace(",", "").toDoubleOrNull() ?: 0.0,
        monthly.replace(",", "").toDoubleOrNull() ?: 0.0,
        yieldText.toDoubleOrNull() ?: 0.0,
        growth.toDoubleOrNull() ?: 0.0,
        years,
        if (withTax) taxRate else 0.0,
    )
    val last = rows.last()

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("股息再投入試算", onBack = { nav.popBackStack() })
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (AppState.holdings.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Label("從持股帶入（市值與近 12 個月殖利率）")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AppState.holdings.forEach { h ->
                                val on = picked == h.symbol
                                Text(
                                    h.symbol, color = if (on) C.Text else C.Muted, fontSize = 12.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Selected else C.Card)
                                        .clickable {
                                            picked = h.symbol
                                            initial = Fmt.num(AppState.marketValueTwd(h)).replace(",", "")
                                            val us = AppState.marketOf(h.symbol) == Market.US
                                            taxRate = if (us) TaxRules.US_WITHHOLD else 0.0
                                            withTax = us
                                            msg = "載入 ${h.symbol} 配息紀錄中…"
                                            scope.launch {
                                                val q = AppState.quote(h.symbol)
                                                msg = if (q == null) "找不到報價" else runCatching { StockInfoRepo.dividends(q) }.fold(
                                                    { d -> d.yieldPct?.let { yieldText = Fmt.num(it, 2); "已帶入 ${h.symbol}：殖利率 ${Fmt.num(it, 2)}%" } ?: "${h.symbol} 近一年沒有配息" },
                                                    { "殖利率載入失敗：" + it.friendly() },
                                                )
                                            }
                                        }.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("扣除股息預扣稅", color = C.Text, fontSize = 13.sp)
                            Label(if (taxRate > 0) "以 ${Fmt.num(taxRate * 100, 0)}% 計（美股）" else "台股多數不預扣，二代健保另計")
                        }
                        Switch(
                            checked = withTax, onCheckedChange = { withTax = it; if (it && taxRate == 0.0) taxRate = TaxRules.US_WITHHOLD },
                            colors = SwitchDefaults.colors(checkedTrackColor = C.Primary, checkedThumbColor = C.OnPrimary, uncheckedTrackColor = C.Border),
                        )
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
                    KeyValue("再投入多出", Fmt.signed(last.reinvest - last.cashOut), Trend.fg(last.reinvest - last.cashOut))
                    KeyValue("第 $years 年股息（再投入情境）", Fmt.num(last.divThisYear) + "（月均 " + Fmt.num(last.divThisYear / 12) + "）")
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
            item { SectionTitle("逐年明細", "再投入 · 領現") }
            items(rows.filter { it.year > 0 && (it.year % maxOf(1, years / 10) == 0 || it.year == years) }) { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("第 ${r.year} 年", Modifier.width(70.dp), color = C.Text, fontSize = 13.sp)
                    NumText(Fmt.num(r.reinvest), 13.sp, modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End))
                    NumText(Fmt.num(r.cashOut), 13.sp, C.Muted, modifier = Modifier.weight(1f).wrapContentWidth(Alignment.End))
                }
            }
            item { Label("試算假設殖利率與成長率固定、股息每月平均發放、不含手續費；實際結果會不同，僅供規劃參考。", Modifier.padding(top = 6.dp)) }
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
