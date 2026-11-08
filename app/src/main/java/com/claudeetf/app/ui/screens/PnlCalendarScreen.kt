package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/** 由每日總資產紀錄推算的每日損益（台幣）；含股息與現金調整 */
private fun dailyPnl(): Map<LocalDate, Double> {
    // 已重建：用「持股股數 × 每日收盤價變動」的結果，不受入金、買賣、刪改交易影響
    if (AppState.pnlHistory.isNotEmpty())
        return AppState.pnlHistory.entries.mapNotNull { e -> runCatching { LocalDate.parse(e.key) }.getOrNull()?.let { it to e.value } }.toMap()
    val entries = AppState.navHistory.entries.mapNotNull { e ->
        runCatching { LocalDate.parse(e.key) }.getOrNull()?.let { it to e.value }
    }.sortedBy { it.first }
    val out = HashMap<LocalDate, Double>()
    for (i in 1 until entries.size) out[entries[i].first] = entries[i].second - entries[i - 1].second
    return out
}

private fun short(v: Double): String {
    val a = abs(v)
    val sign = if (v > 0) "+" else if (v < 0) "−" else ""
    return sign + when {
        a >= 1e8 -> Fmt.num(a / 1e8, 1) + "億"
        a >= 1e4 -> Fmt.num(a / 1e4, 1) + "萬"
        else -> Fmt.num(a)
    }
}

@Composable
fun PnlCalendarScreen(nav: NavController) {
    val today = LocalDate.now(ZoneId.of("Asia/Taipei"))
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    // 第一次打開且有交易紀錄：自動依交易紀錄與歷史股價重建
    LaunchedEffect(Unit) {
        if (AppState.pnlHistory.isEmpty() && AppState.trades.isNotEmpty()) msg = com.claudeetf.app.data.PnlHistory.rebuild()
    }
    val pnl = remember(AppState.navHistory.size, AppState.pnlHistory.size, AppState.pnlHistory.values.sum()) { dailyPnl() }
    val inMonth = pnl.filterKeys { YearMonth.from(it) == month }
    val total = inMonth.values.sum()
    val maxAbs = inMonth.values.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
    val up = inMonth.values.count { it > 0 }
    val down = inMonth.values.count { it < 0 }
    val first = month.atDay(1)
    val lead = first.dayOfWeek.value - 1 // 週一開始
    val days = month.lengthOfMonth()
    val cells: List<LocalDate?> = List(lead) { null } + (1..days).map { month.atDay(it) }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("損益日曆", onBack = { nav.popBackStack() }) {
            Text(if (com.claudeetf.app.data.PnlHistory.running) "計算中…" else "重新計算", color = C.Primary, fontSize = 14.sp,
                modifier = Modifier.clickable(enabled = !com.claudeetf.app.data.PnlHistory.running) {
                    scope.launch { msg = com.claudeetf.app.data.PnlHistory.rebuild() }
                }.padding(horizontal = 6.dp, vertical = 12.dp))
            Text("年度回顧", color = C.Primary, fontSize = 14.sp,
                modifier = Modifier.clickable { nav.navigate(Routes.REVIEW) }.padding(12.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", color = C.Primary, fontSize = 24.sp, modifier = Modifier.clickable { month = month.minusMonths(1) }.padding(12.dp))
                    Text("${month.year} 年 ${month.monthValue} 月", Modifier.weight(1f), color = C.Text, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Text(
                        "›", color = if (month < YearMonth.from(today)) C.Primary else C.Muted, fontSize = 24.sp,
                        modifier = Modifier.clickable { if (month < YearMonth.from(today)) month = month.plusMonths(1) }.padding(12.dp),
                    )
                }
            }
            (com.claudeetf.app.data.PnlHistory.progress.ifBlank { null } ?: msg)?.let { t -> item { Label(t, color = C.Primary) } }
            item {
                Card(padding = 14.dp) {
                    Row {
                        Column(Modifier.weight(1f)) { Label("本月損益"); NumText(mask(Fmt.signed(total)), 18.sp, Trend.fg(total), FontWeight.Bold) }
                        Column(Modifier.weight(1f)) { Label("上漲／下跌天數"); NumText("$up / $down", 18.sp) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth()) {
                    listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                        Text(it, Modifier.weight(1f), color = C.Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            items(cells.chunked(7)) { week ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { d ->
                        val v = d?.let { inMonth[it] }
                        val intensity = v?.let { (abs(it) / maxAbs).toFloat().coerceIn(0.15f, 1f) } ?: 0f
                        Column(
                            Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (v == null || v == 0.0) C.Card
                                    else Trend.fg(v).copy(alpha = 0.12f + 0.45f * intensity)
                                )
                                .padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (d != null) {
                                Text(
                                    d.dayOfMonth.toString(), fontSize = 11.sp,
                                    color = if (d == today) C.Primary else C.Text2,
                                    fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (v != null) Text(if (AppState.hideAmounts) "＊" else short(v), fontSize = 10.sp, color = C.Text, maxLines = 1)
                            }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            item {
                Label(
                    if (AppState.pnlHistory.isNotEmpty())
                        "每日損益＝當日市值 − 前一交易日市值 − 當日買進成本 ＋ 當日賣出所得 ＋ 當日股息。依交易紀錄還原每天的持股股數、配合歷史收盤價計算（已還原拆股），" +
                            "所以入金、刪改交易不會變成損益；美股以目前匯率換算（不含匯兌損益）。修改交易後請按右上「重新計算」。"
                    else "每日損益＝當天與前一次紀錄的總資產差額（含股息、現金調整）。按右上「重新計算」可改依交易紀錄與歷史股價精確計算。",
                    Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
