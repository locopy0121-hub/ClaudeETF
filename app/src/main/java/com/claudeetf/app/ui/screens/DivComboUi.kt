package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import com.claudeetf.app.data.DivPortfolio
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.launch

private val COMBO = Color(0xFF4FA3E0)

/**
 * 配息組合試算：任選個股／ETF 與權重，套用各自近一年的正式配息，
 * 算出年領、月領分布、殖利率；可套用 App 規則推薦或 AI 推薦的組合。
 */
@Composable
fun DivComboCard(nav: NavController) {
    LaunchedEffect(Unit) { DivPortfolio.loadCombo() }
    val scope = rememberCoroutineScope()
    var capText by remember { mutableStateOf(AppState.cacheGet("divComboCap") ?: "1000000") }
    var whole by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var targetMonthText by remember { mutableStateOf("10000") }
    var picks by remember { mutableStateOf<List<DivPortfolio.Pick>>(emptyList()) }
    var pickMsg by remember { mutableStateOf<String?>(null) }
    var pref by remember { mutableIntStateOf(0) }
    val combo = DivPortfolio.combo
    // 確保組合內每一檔都有配息資料
    LaunchedEffect(combo.map { it.symbol }) { combo.forEach { runCatching { DivPortfolio.ensure(it.symbol) } } }
    val capital = capText.toDoubleOrNull() ?: 0.0
    val res = DivPortfolio.simulate(capital, combo.toList(), whole)

    Card(padding = 14.dp) {
        Text("DIVIDEND PORTFOLIO", color = COMBO, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text("配息組合試算", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Label("任選個股、ETF 組成配置，套用各自最近一年的正式配息，看每月現金流。")

        OutlinedTextField(capText, { v -> capText = v.filter { it.isDigit() }.take(11); AppState.cachePut("divComboCap", capText) },
            label = { Text("總本金（台幣）") }, singleLine = true, suffix = { Text("元") },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("300000" to "30 萬", "1000000" to "100 萬", "3000000" to "300 萬", "10000000" to "1000 萬").forEach { (v, n) ->
                ComboChip(n, capText == v) { capText = v; AppState.cachePut("divComboCap", v) }
            }
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("台股買入", Modifier.padding(end = 6.dp))
            ComboChip("可買零股", !whole) { whole = false }; HGap(6.dp)
            ComboChip("只買整張", whole) { whole = true }
        }

        // 組合內容
        Text("組合內容（權重合計 ${Fmt.num(combo.sumOf { it.weight }, 0)}%）", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        if (combo.isEmpty()) Label("尚未加入標的：用下方搜尋加入，或套用推薦組合。")
        combo.toList().forEachIndexed { idx, it ->
            val inf = DivPortfolio.infos[it.symbol]
            val row = res.rows.firstOrNull { r -> r.info.symbol == it.symbol }
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it.symbol, color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { nav.navigate(Routes.stock(it.symbol)) })
                    HGap(6.dp)
                    Text(inf?.name ?: DivPortfolio.error(it.symbol)?.let { e -> "讀取失敗：$e" } ?: "讀取中…", color = C.Text, fontSize = 13.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    RepeatBtn("－", true) { DivPortfolio.combo[idx] = it.copy(weight = (it.weight - 5).coerceAtLeast(0.0)); DivPortfolio.saveCombo() }
                    Text(Fmt.num(it.weight, 0) + "%", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.widthIn(min = 44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    RepeatBtn("＋", true) { DivPortfolio.combo[idx] = it.copy(weight = (it.weight + 5).coerceAtMost(100.0)); DivPortfolio.saveCombo() }
                    Text("✕", color = C.Muted, fontSize = 14.sp, modifier = Modifier.clickable { DivPortfolio.combo.removeAt(idx); DivPortfolio.saveCombo() }.padding(start = 8.dp, end = 2.dp))
                }
                inf?.let { i ->
                    Label("${i.freq} · 近一年每股 " + Fmt.price(i.annual) + "（殖利率 " + Fmt.num(i.yieldPct, 2) + "%）· 配息月 " +
                        i.byMonth.keys.sorted().joinToString("、") { m -> "$m" } + (row?.let { r -> " · " + Fmt.shares(r.shares) + " 股，年領 NT$" + Fmt.num(r.annualTwd) } ?: ""))
                }
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (combo.isNotEmpty()) {
                ComboChip("權重平均分配", false) { DivPortfolio.setCombo(combo.map { it.copy(weight = 1.0) }) }
                ComboChip("調整為合計 100%", false) { DivPortfolio.setCombo(combo.toList()) }
                ComboChip("清空", false) { DivPortfolio.setCombo(emptyList()) }
            }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("加入個股或 ETF（代號、名稱）", fontSize = 13.sp) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        StockSuggestions(query, maxHeight = 220.dp, onPick = { it ->
            query = ""
            if (combo.none { c -> c.symbol == it.symbol }) {
                val w = if (combo.isEmpty()) 100.0 else (100.0 / (combo.size + 1))
                DivPortfolio.combo += DivPortfolio.Item(it.symbol, w)
                DivPortfolio.saveCombo()
                scope.launch { DivPortfolio.ensure(it.symbol, it.name) }
            }
        })

        // 結果
        if (res.rows.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).background(COMBO.copy(alpha = 0.10f))
                .border(1.dp, COMBO.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("依最近一年正式配息試算（稅前）", color = COMBO, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.padding(top = 4.dp)) {
                    Column(Modifier.weight(1f)) { Label("年領合計"); NumText("NT$" + Fmt.num(res.annual), 22.sp, COMBO, FontWeight.Bold) }
                    Column(Modifier.weight(1f)) { Label("平均月領"); NumText("NT$" + Fmt.num(res.monthAvg), 22.sp, C.Text, FontWeight.Bold) }
                }
                Row(Modifier.padding(top = 4.dp)) {
                    Column(Modifier.weight(1f)) { Label("組合殖利率"); Text(Fmt.num(res.yieldPct, 2) + "%", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f)) { Label("實際投入"); Text("NT$" + Fmt.num(res.invested), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
                // 12 個月分布長條
                Text("每月入帳分布", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                val mx = res.monthly.maxOrNull()?.takeIf { it > 0 } ?: 1.0
                Row(Modifier.fillMaxWidth().height(96.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                    res.monthly.forEachIndexed { i, v ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                            if (v > 0) Text(if (v >= 10000) Fmt.num(v / 10000, 1) + "萬" else Fmt.num(v / 1000, 1) + "k", color = C.Muted, fontSize = 8.sp, maxLines = 1)
                            Box(Modifier.fillMaxWidth().height((60 * (v / mx)).coerceAtLeast(if (v > 0) 3.0 else 1.0).dp)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)).background(if (v > 0) COMBO else C.Border))
                            Text("${i + 1}", color = C.Muted, fontSize = 9.sp)
                        }
                    }
                }
                val empty = res.emptyMonths
                Label(if (empty.isEmpty()) "12 個月都有配息入帳 ✓" else "沒有入帳的月份：" + empty.joinToString("、") { "$it 月" } + "（可加入這些月份配息的標的）")
                val target = targetMonthText.toDoubleOrNull() ?: 0.0
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("想要月領", Modifier.padding(end = 6.dp))
                    OutlinedTextField(targetMonthText, { v -> targetMonthText = v.filter { it.isDigit() }.take(8) }, singleLine = true, suffix = { Text("元") },
                        modifier = Modifier.width(150.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (target > 0 && res.yieldPct > 0) Label("依此組合殖利率，月領 NT$" + Fmt.num(target) + " 約需本金 NT$" + Fmt.num(target * 12 / (res.yieldPct / 100)) +
                    "（目前 " + Fmt.num(res.monthAvg / target * 100, 0) + "%）")
            }
        }

        // 推薦
        Text("推薦組合", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("AI 偏好", Modifier.padding(end = 6.dp))
            listOf("穩定月月配", "高殖利率", "低波動股債", "兼顧成長").forEachIndexed { i, n -> ComboChip(n, pref == i) { pref = i }; HGap(4.dp) }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComboChip("App 推薦", false) {
                pickMsg = "分析常見配息 ETF 中…"
                scope.launch {
                    val pool = runCatching { DivPortfolio.loadPool() }.getOrDefault(emptyList())
                    picks = DivPortfolio.appPicks(pool)
                    pickMsg = if (picks.isEmpty()) "候選資料不足，請稍後再試" else null
                }
            }
            if (AppState.aiOn) ComboChip("AI 推薦", false) {
                pickMsg = "AI 分析中（使用候選 ETF 的實際殖利率與配息月份）…"
                scope.launch {
                    val pool = runCatching { DivPortfolio.loadPool() }.getOrDefault(emptyList())
                    val r = runCatching {
                        DivPortfolio.aiPick(pool, capital, targetMonthText.toDoubleOrNull() ?: 0.0,
                            listOf("每個月都有配息、穩定", "殖利率高", "股債搭配、波動低", "兼顧長期成長與配息")[pref])
                    }
                    r.getOrNull()?.let { p -> picks = listOf(p) + picks.filter { !it.title.startsWith("AI") } }
                    pickMsg = r.exceptionOrNull()?.let { "AI 推薦失敗：" + (it.message ?: "") }
                }
            }
        }
        pickMsg?.let { Label(it, Modifier.padding(top = 4.dp)) }
        picks.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("套用", color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.Selected).clickable { DivPortfolio.setCombo(p.items) }.padding(horizontal = 12.dp, vertical = 5.dp))
                }
                Label(p.items.joinToString("、") { i -> i.symbol + " " + Fmt.num(i.weight, 0) + "%" })
                val sim = DivPortfolio.simulate(capital, p.items, whole)
                if (sim.rows.isNotEmpty()) Label("殖利率 " + Fmt.num(sim.yieldPct, 2) + "% · 平均月領 NT$" + Fmt.num(sim.monthAvg) +
                    (if (sim.emptyMonths.isEmpty()) " · 月月有息" else " · 缺 " + sim.emptyMonths.size + " 個月"))
                if (p.desc.isNotBlank()) Text(p.desc, color = C.Text2, fontSize = 12.sp, lineHeight = 17.sp)
                if (p.source != "App") Label("來源：" + p.source)
            }
        }
        Label("配息採各標的近 12 個月正式配息（Yahoo Finance 除息紀錄），不代表未來；未計二代健保、所得稅與交易費用。推薦僅依配息資料計算，不構成投資建議。", Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ComboChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, color = if (on) COMBO else C.Text2, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, if (on) COMBO else C.Border, RoundedCornerShape(14.dp))
            .background(if (on) COMBO.copy(alpha = 0.12f) else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp))
}
