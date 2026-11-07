package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.Analytics
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.gross
import com.claudeetf.app.data.tradeGross
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.TradeOrigin
import com.claudeetf.app.data.TradeType
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import java.time.LocalDate
import kotlin.math.abs

/** 年度回顧：依每日淨值紀錄與交易紀錄彙整一年的表現 */
@Composable
fun ReviewScreen(nav: NavController) {
    val thisYear = LocalDate.now().year
    var year by remember { mutableIntStateOf(thisYear) }
    val y = year.toString()

    val navs = AppState.navHistory.entries.filter { it.key.startsWith(y) }.sortedBy { it.key }.map { it.key to it.value }
    val start = navs.firstOrNull()?.second
    val end = navs.lastOrNull()?.second

    // 淨投入：買進成本 − 賣出所得（不含股息、期初持股）
    val ts = AppState.trades.filter { it.origin == TradeOrigin.NORMAL && it.date.startsWith(y) }
    var netIn = 0.0
    for (t in ts) {
        val fx = if (AppState.marketOf(t.symbol) == Market.US) t.fx else 1.0
        when (t.type) {
            TradeType.BUY -> netIn += (t.gross + t.fee) * fx
            TradeType.SELL -> netIn -= (t.gross - t.fee - t.tax) * fx
            else -> {}
        }
    }
    // 現金也算在總資產內，買賣只是資產轉換；以外部入金估算時改用現金調整前後差，這裡保守只顯示資產變化
    val change = if (start != null && end != null) end - start else null
    val pct = if (start != null && start > 0 && change != null) change / start * 100 else null

    // 月度：每月最後一筆淨值對前一月
    val monthEnd = navs.groupBy { it.first.substring(0, 7) }.mapValues { it.value.last().second }.toSortedMap()
    val monthly = ArrayList<Pair<String, Double>>()
    var prev = start
    monthEnd.forEach { (m, v) -> prev?.let { monthly += m to (v - it) }; prev = v }
    val best = monthly.maxByOrNull { it.second }
    val worst = monthly.minByOrNull { it.second }

    var peak = 0.0
    var mdd = 0.0
    navs.forEach { (_, v) -> if (v > peak) peak = v; if (peak > 0) mdd = minOf(mdd, v / peak - 1) }

    val divThis = Analytics.monthlyDividends(year).sum()
    val divLast = Analytics.monthlyDividends(year - 1).sum()
    val report = ReportCalc.build(year)
    val realized = report.realizedTw + report.realizedUs

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("年度回顧", onBack = { nav.popBackStack() })
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", color = C.Primary, fontSize = 24.sp, modifier = Modifier.clickable { year-- }.padding(12.dp))
                    Text("$year 年", Modifier.weight(1f), color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("›", color = if (year < thisYear) C.Primary else C.Muted, fontSize = 24.sp,
                        modifier = Modifier.clickable { if (year < thisYear) year++ }.padding(12.dp))
                }
            }
            item {
                Card(padding = 16.dp) {
                    Label("總資產變化" + if (navs.isNotEmpty()) "（${navs.first().first} → ${navs.last().first}）" else "")
                    if (change == null) {
                        Label("這一年沒有每日淨值紀錄（App 每天開啟時自動記錄）。")
                    } else {
                        NumText(mask(Fmt.signed(change)), 26.sp, Trend.fg(change), FontWeight.Bold)
                        pct?.let { NumText(Fmt.pct(it, 2), 14.sp, Trend.fg(change)) }
                        KeyValue("年初", mask("NT$" + Fmt.num(start ?: 0.0)))
                        KeyValue("年末／目前", mask("NT$" + Fmt.num(end ?: 0.0)))
                        KeyValue("期間最大回撤", Fmt.num(mdd * 100, 1) + "%")
                        Label("資產變化包含股價漲跌、股息與你調整的現金；買賣只是資產轉換，不影響總額。")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewTile("已實現損益", mask(Fmt.signed(realized)), Trend.fg(realized), Modifier.weight(1f))
                    ReviewTile("股息實收", mask(Fmt.num(divThis)), C.Text, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val growth = if (divLast > 0) (divThis / divLast - 1) * 100 else null
                    ReviewTile("股息年增", growth?.let { Fmt.pct(it, 1) } ?: "—", Trend.fg(growth ?: 0.0), Modifier.weight(1f))
                    ReviewTile("淨買進金額", mask(Fmt.signed(netIn)), C.Text, Modifier.weight(1f))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewTile("買進／賣出", "${report.buyCount} / ${report.sellCount} 筆", C.Text, Modifier.weight(1f))
                    ReviewTile("交易成本", mask(Fmt.num(report.fees + report.taxes)), C.Text, Modifier.weight(1f))
                }
            }
            if (monthly.isNotEmpty()) {
                item { SectionTitle("每月資產變化") }
                item {
                    val maxAbs = monthly.maxOf { abs(it.second) }.takeIf { it > 0 } ?: 1.0
                    Card(padding = 14.dp) {
                        monthly.forEach { (m, v) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.substring(5) + " 月", Modifier.width(48.dp), color = C.Text, fontSize = 12.sp)
                                Box(Modifier.weight(1f).height(10.dp)) {
                                    Box(
                                        Modifier.fillMaxWidth((abs(v) / maxAbs).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight()
                                            .clip(RoundedCornerShape(5.dp)).background(Trend.fg(v)),
                                    )
                                }
                                NumText(mask(Fmt.signed(v)), 12.sp, Trend.fg(v), modifier = Modifier.width(96.dp).wrapContentWidth(Alignment.End))
                            }
                        }
                        best?.let { Label("最佳月份 " + it.first.substring(5) + " 月（" + mask(Fmt.signed(it.second)) + "）") }
                        worst?.let { Label("最弱月份 " + it.first.substring(5) + " 月（" + mask(Fmt.signed(it.second)) + "）") }
                    }
                }
            }
            if (report.realizedBySymbol.isNotEmpty()) {
                item { SectionTitle("已實現貢獻前五") }
                item {
                    Card(padding = 14.dp) {
                        report.realizedBySymbol.take(5).forEach { (s, v) ->
                            KeyValue(s + " " + (AppState.quote(s)?.name ?: ""), mask(Fmt.signed(v)), Trend.fg(v))
                        }
                    }
                }
            }
            item {
                Text("年度報表（報稅參考） ›", color = C.Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { nav.navigate(Routes.REPORT) }.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun ReviewTile(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(C.Card).padding(12.dp)) {
        Label(label)
        NumText(value, 15.sp, color, FontWeight.Bold)
    }
}
