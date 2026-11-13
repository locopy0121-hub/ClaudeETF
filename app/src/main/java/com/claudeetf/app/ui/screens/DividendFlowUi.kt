package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.DividendFlow
import com.claudeetf.app.data.EtfTags
import com.claudeetf.app.data.Market
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.KeyValue
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.Routes
import com.claudeetf.app.ui.mask
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.Fmt
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val GOLD = Color(0xFFE6C34A)
private val SKY = Color(0xFF6EC1FF)
private val md = DateTimeFormatter.ofPattern("M/d")
private val mmdd = DateTimeFormatter.ofPattern("MM/dd")

private fun money(e: DividendFlow.Event, v: Double) = if (e.usd) "US$" + Fmt.num(v, 2) else "NT$" + Fmt.num(v)
private fun unit(v: Double) = Fmt.num(v, 4).trimEnd('0').trimEnd('.')
private fun lots(shares: Double) = if (shares >= 1000 && shares % 1000 == 0.0) Fmt.num(shares / 1000) + " 張" else Fmt.shares(shares) + " 股"

/** 股息頁上方：下一筆預估入帳 */
@Composable
fun DivNextCard(nav: NavController) {
    LaunchedEffect(Unit) { DividendFlow.refresh() }
    val e = DividendFlow.next()
    var detail by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(SKY.copy(alpha = 0.16f), C.Card)))
            .border(1.dp, SKY.copy(alpha = 0.35f), RoundedCornerShape(16.dp)).padding(16.dp),
    ) {
        Text("MY CASH FLOW", color = C.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text("下一筆預估入帳", color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Label("依持有日期自動排除不符合除息資格的期別")
        when {
            e == null && DividendFlow.loading -> Label("計算中…", Modifier.padding(top = 10.dp))
            e == null -> Label(if (AppState.holdings.isEmpty()) "還沒有持股" else "近期沒有符合資格的配息", Modifier.padding(top = 10.dp))
            else -> {
                Row(Modifier.padding(top = 12.dp).clickable { nav.navigate(Routes.stock(e.symbol)) }, verticalAlignment = Alignment.CenterVertically) {
                    NumText(e.symbol, 17.sp, C.Text, FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("發放 " + e.payDate.format(md) + if (e.payEstimated) "（估）" else "", color = SKY, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    val d = e.daysToPay()
                    Label(if (d == 0L) "今天" else "$d 天後")
                }
                Text(e.name, color = C.Text2, fontSize = 13.sp)
                Label("符合資格期別 · 除息 " + e.exDate.format(md) + (e.holdSince?.let { " · " + it + " 起持有" } ?: ""))
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(C.Border))
                Text("本次預估入帳", color = GOLD, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                NumText(mask(money(e, e.net)), 32.sp, SKY, FontWeight.Bold)
                Label(lots(e.eligibleShares) + " × " + (if (e.usd) "US$" else "NT$") + unit(e.perUnit) + " 每單位" + if (e.amountConfirmed) "" else "（以上次配息估計）")
                if (e.eligibleShares < e.currentShares) Text("僅列入本期最後買進日（${e.lastBuy.format(md)}）前已持有的部位", color = SKY, fontSize = 12.sp)
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val on = AppState.divPayAlert && e.symbol !in AppState.divPayMuted
                    Text((if (on) "🔔 入帳提醒已開啟 · 11:00" else "🔕 入帳提醒已關閉"), color = if (on) SKY else C.Muted, fontSize = 12.sp,
                        modifier = Modifier.clickable { if (!AppState.divPayAlert) AppState.updateDivPayAlert(true) else AppState.toggleDivPayMuted(e.symbol) }.padding(vertical = 4.dp))
                }
                Text(if (detail) "收合計算明細 ▲" else "查看計算明細 ›", color = SKY, fontSize = 12.sp, modifier = Modifier.clickable { detail = !detail }.padding(vertical = 4.dp))
                if (detail) Column {
                    KeyValue("最後買進日", e.lastBuy.toString())
                    KeyValue("除息日", e.exDate.toString())
                    KeyValue("發放日", e.payDate.toString() + if (e.payEstimated) "（依過往節奏推估）" else "")
                    KeyValue("符合資格股數", Fmt.shares(e.eligibleShares) + " 股" + if (e.eligibleShares != e.currentShares) "（目前 ${Fmt.shares(e.currentShares)}）" else "")
                    KeyValue("每單位配息", unit(e.perUnit) + if (e.amountConfirmed) "" else "（估）")
                    KeyValue("配息總額", mask(money(e, e.gross)))
                    if (e.nhi > 0) KeyValue("二代健保 2.11%", "−" + mask(money(e, e.nhi)))
                    if (e.withholding > 0) KeyValue("美股預扣 30%", "−" + mask(money(e, e.withholding)))
                    KeyValue("預估入帳", mask(money(e, e.net)), SKY)
                    if (e.recorded) Label("✓ 已記錄到交易紀錄")
                }
            }
        }
    }
}

/** 本月（可切換月份）入帳明細 */
@Composable
fun DivMonthCard(nav: NavController) {
    var ym by remember { mutableStateOf(YearMonth.now()) }
    val list = DividendFlow.ofMonth(ym)
    val today = LocalDate.now()
    val total = list.sumOf { it.netTwd }
    val paid = list.filter { !it.payDate.isAfter(today) }.sumOf { it.netTwd }
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("THIS MONTH", color = C.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("${ym.monthValue} 月預估入帳", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Text("‹", color = C.Primary, fontSize = 22.sp, modifier = Modifier.clickable { ym = ym.minusMonths(1) }.padding(horizontal = 10.dp))
            Text("${list.size} 筆", color = SKY, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, SKY.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
            Text("›", color = C.Primary, fontSize = 22.sp, modifier = Modifier.clickable { ym = ym.plusMonths(1) }.padding(horizontal = 10.dp))
        }
        Text(if (ym == YearMonth.now()) "本月預估入帳" else "${ym.year}/${ym.monthValue} 預估入帳", color = GOLD, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        NumText(mask("NT$" + Fmt.num(total)), 28.sp, SKY, FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("已到發放日", mask("NT$" + Fmt.num(paid)), Modifier.weight(1f))
            Stat("入帳筆數", "${list.size} 筆", Modifier.weight(1f))
            Stat("待發放", mask("NT$" + Fmt.num(total - paid)), Modifier.weight(1f))
        }
        if (list.isEmpty()) Label(if (DividendFlow.loading) "計算中…" else "這個月沒有預估入帳", Modifier.padding(top = 8.dp))
        list.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg)
                .clickable { nav.navigate(Routes.stock(e.symbol)) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (e.paid) C.Muted else SKY))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    NumText(e.symbol + " " + e.name, 13.sp, C.Text, FontWeight.Bold)
                    Label("發放 " + e.payDate.format(md) + (if (e.payEstimated) "（估）" else "") + " · " + lots(e.eligibleShares) + (if (e.paid) " · 已到發放日" else ""))
                }
                NumText(mask(money(e, e.net)), 14.sp, SKY, FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Stat(title: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(10.dp)) {
        Label(title)
        NumText(value, 14.sp, C.Text, FontWeight.Bold)
    }
}

/** 持股配息清單：類型、除息／發放日、每單位、持有張數、預估、入帳提醒 */
@Composable
fun DivHoldingList(nav: NavController) {
    val byStock = DividendFlow.events.groupBy { it.symbol }.mapValues { (_, l) ->
        // 每檔顯示「最接近的下一次」，都已發放就顯示最近一次
        l.filter { !it.paid }.minByOrNull { it.payDate } ?: l.maxByOrNull { it.payDate }!!
    }.values.sortedBy { it.payDate }
    Card(padding = 14.dp) {
        Text("MY HOLDINGS", color = C.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("持股配息", Modifier.weight(1f), color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Label("${byStock.size} 檔")
        }
        if (byStock.isEmpty()) Label(if (DividendFlow.loading) "計算中…" else "持股近期沒有配息資料", Modifier.padding(top = 6.dp))
        byStock.forEach { e ->
            val q = AppState.quote(e.symbol)
            val type = q?.let { EtfTags.typeTags(it).firstOrNull { t -> t.kind == EtfTags.Kind.TYPE }?.text?.let { "$it ETF" } } ?: "股票"
            val muted = e.symbol in AppState.divPayMuted || !AppState.divPayAlert
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.Border, RoundedCornerShape(12.dp))) {
                Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.stock(e.symbol)) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumText(e.symbol, 13.sp, SKY, FontWeight.Bold,
                        Modifier.clip(RoundedCornerShape(10.dp)).background(SKY.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 8.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(type, color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(e.name, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Row {
                            Text("除 ", color = GOLD, fontSize = 12.sp); NumText(e.exDate.format(mmdd), 12.sp, C.Text2)
                            Text("  發 ", color = GOLD, fontSize = 12.sp); NumText(e.payDate.format(mmdd) + if (e.payEstimated) "估" else "", 12.sp, C.Text2)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        NumText((if (e.usd) "US$ " else "NT$ ") + unit(e.perUnit), 14.sp, C.Text, FontWeight.Bold)
                        Label("每單位" + if (e.amountConfirmed) "" else "（估）")
                    }
                    Text(if (muted) "🔕" else "🔔", fontSize = 16.sp, modifier = Modifier.clickable { AppState.toggleDivPayMuted(e.symbol) }.padding(start = 10.dp, top = 6.dp, bottom = 6.dp))
                }
                Row(Modifier.fillMaxWidth().background(C.Bg).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NumText(lots(e.eligibleShares), 13.sp, SKY, FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Label("預估 " + mask(money(e, e.net)) + if (e.paid) " · 已發放" else "")
                }
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(if (DividendFlow.updatedAt > 0) "更新 " + Fmt.time(DividendFlow.updatedAt) + "，每天開 App 自動更新" else "", Modifier.weight(1f))
            OptToggleSmall("入帳提醒", AppState.divPayAlert) { AppState.updateDivPayAlert(it) }
        }
    }
}

@Composable
private fun OptToggleSmall(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(label)
        androidx.compose.material3.Switch(on, set, Modifier.padding(start = 4.dp))
    }
}

/** 指定 ETF 配息試算：單次目標配息 → 需要張數、參考本金，與目前持股比較 */
@Composable
fun DivSimulatorCard() {
    // 搜尋任選的個股／ETF（不在持股與自選中也可以試算）
    var extra by remember { mutableStateOf<com.claudeetf.app.data.Quote?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val candidates = (listOfNotNull(extra) + AppState.holdings.mapNotNull { AppState.quote(it.symbol) } +
        AppState.quotes.filter { EtfTags.isEtf(it) }).distinctBy { it.symbol }.filter { it.price > 0 }
    if (candidates.isEmpty() && extra == null) return
    var sym by remember { mutableStateOf(candidates.firstOrNull()?.symbol ?: "") }
    val q = candidates.firstOrNull { it.symbol == sym } ?: candidates.first()
    var targetText by remember { mutableStateOf("10000") }
    var custom by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    var whole by remember { mutableStateOf(true) }
    LaunchedEffect(q.symbol) { EtfTags.ensureFreq(q); com.claudeetf.app.data.DivPortfolio.ensure(q.symbol, q.name) }
    val last = EtfTags.divs[q.symbol]?.firstOrNull() ?: com.claudeetf.app.data.DivPortfolio.infos[q.symbol]?.takeIf { it.last > 0 }
        ?.let { com.claudeetf.app.data.remote.YahooExtra.Dividend(it.lastTime, it.last) }
    val perUnit = if (custom) customText.toDoubleOrNull() ?: 0.0 else last?.amount ?: 0.0
    val target = targetText.toDoubleOrNull() ?: 0.0
    val sim = DividendFlow.simulate(q, target, perUnit, whole)

    Card(padding = 14.dp) {
        Text("ETF INCOME SIMULATOR", color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text("配息試算", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            candidates.forEach { c ->
                val on = c.symbol == q.symbol
                Text(c.symbol, color = if (on) C.OnPrimary else C.Text2, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Primary else C.Bg).clickable { sym = c.symbol }.padding(horizontal = 11.dp, vertical = 6.dp))
            }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜尋任一個股或 ETF（代號、名稱）", fontSize = 13.sp) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp), colors = fieldColors())
        StockSuggestions(query, maxHeight = 220.dp, onPick = { it ->
            query = ""; searching = true
            scope.launch {
                val inf = com.claudeetf.app.data.DivPortfolio.ensure(it.symbol, it.name)
                if (inf != null) {
                    extra = com.claudeetf.app.data.Quote(inf.symbol, inf.name, inf.market, inf.price, inf.price, isEtf = inf.isEtf)
                    sym = inf.symbol
                }
                searching = false
            }
        })
        if (searching) Label("讀取價格與配息紀錄中…")
        Text(q.symbol + " " + q.name, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(targetText, { targetText = it.filter { c -> c.isDigit() } }, label = { Text("單次目標配息（稅前，台幣）") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp), colors = fieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), suffix = { Text("元") })
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("1000", "3000", "10000", "30000").forEach { v ->
                val on = targetText == v
                Text(Fmt.num(v.toDouble()) + " 元", color = if (on) SKY else C.Text2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, if (on) SKY else C.Border, RoundedCornerShape(14.dp))
                        .clickable { targetText = v }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        Text("每單位配息依據", color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice("最近正式配息", !custom) { custom = false }
            Choice("自行假設", custom) { custom = true }
        }
        if (!custom) Label(last?.let {
            "每股 " + (if (q.market == Market.US) "US$" else "NT$") + unit(it.amount) + " ｜ 除息日 " +
                java.time.Instant.ofEpochMilli(it.time).atZone(java.time.ZoneId.of("Asia/Taipei")).toLocalDate() + "。採最近一筆正式配息，不代表下一期金額。"
        } ?: "查無配息紀錄，請改用「自行假設」")
        else OutlinedTextField(customText, { customText = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("每單位配息") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), colors = fieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        if (q.market == Market.TW) {
            Text("買入單位", color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Choice("整張買入", whole) { whole = true }
                Choice("包含零股", !whole) { whole = false }
            }
            Label(if (whole) "1 張＝1,000 股；依目標向上取整。" else "以股為單位，依目標向上取整。")
        }
        sim?.let { s ->
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(SKY.copy(alpha = 0.10f))
                .border(1.dp, SKY.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text((if (custom) "依假設配息" else "依最近正式配息") + "試算・從零持有需準備", color = SKY, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                NumText(if (q.market == Market.TW && whole) Fmt.num(s.lots) + " 張" else Fmt.shares(s.shares) + " 股", 30.sp, SKY, FontWeight.Bold)
                Label("共 " + Fmt.shares(s.shares) + " 股")
                KeyValue("單次模擬配息（稅前）", "NT$" + Fmt.num(s.gross))
                KeyValue("參考本金（不含交易費用）", "NT$" + Fmt.num(s.capital))
                Label("依目前價格 " + Fmt.price(q.price) + (if (q.market == Market.US) "（美元 × 匯率 " + AppState.usdTwd + "）" else "") + "；非即時成交價。")
                Text("與目前持股比較", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Text("已有 " + lots(s.have) + "・尚需 " + (if (s.need <= 0) "0" else lots(s.need)), color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (s.need > 0) Label("補足約需 NT$" + Fmt.num(s.need * q.price * (if (q.market == Market.US) AppState.usdTwd else 1.0)))
            }
        }
    }
}

@Composable
private fun Choice(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, color = if (on) SKY else C.Text2, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, if (on) SKY else C.Border, RoundedCornerShape(16.dp))
            .background(if (on) SKY.copy(alpha = 0.12f) else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp))
}
