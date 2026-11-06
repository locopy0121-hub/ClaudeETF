package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.EtfTags
import com.claudeetf.app.data.Market
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.mask
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.Fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 持股 ETF 配息總覽：依配息方式分組，估算每年／每月現金流，
 * 並依去年各月實際除息月份推估未來 12 個月每月可領金額（台幣，未扣稅與二代健保）。
 */
@Composable
fun EtfDividendCard(onOpen: (String) -> Unit) {
    val etfs = AppState.holdings.filter { h -> AppState.quote(h.symbol)?.let { EtfTags.isEtf(it) } == true && h.shares > 0 }
    if (etfs.isEmpty()) return
    LaunchedEffect(etfs.map { it.symbol }) {
        etfs.forEach { h -> AppState.quote(h.symbol)?.let { EtfTags.ensureFreq(it) } }
    }
    var open by remember { mutableStateOf(false) }

    data class Row0(val symbol: String, val name: String, val freq: String, val perPay: Double, val annual: Double, val yieldPct: Double?)
    val now = LocalDate.now()
    val months = DoubleArray(12)   // 0 = 下個月 … 11 = 12 個月後
    val rows = etfs.mapNotNull { h ->
        val q = AppState.quote(h.symbol) ?: return@mapNotNull null
        val freq = EtfTags.divFreq[h.symbol] ?: "載入中"
        val list = EtfTags.divs[h.symbol].orEmpty()
        val fx = AppState.fxFor(q.market) * if (q.market == Market.US) 0.7 else 1.0   // 美股預扣 30%
        val last = list.firstOrNull()?.amount ?: 0.0
        val perPay = last * h.shares * fx
        val k = EtfTags.perYear(freq)
        // 去年同期的除息月份 → 推到未來 12 個月
        val zone = ZoneId.of(if (q.market == Market.TW) "Asia/Taipei" else "America/New_York")
        val yearAgo = now.minusMonths(12)
        val past = list.map { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() to it.amount }.filter { it.first.isAfter(yearAgo) }
        past.forEach { (d, amt) ->
            val target = d.plusYears(1)
            val idx = ((target.year - now.year) * 12 + target.monthValue - now.monthValue) - 1
            if (idx in 0..11) months[idx] += amt * h.shares * fx
        }
        val annual = if (k > 0) perPay * k else past.sumOf { it.second } * h.shares * fx
        Row0(h.symbol, q.name, freq, perPay, annual, EtfTags.annualYield(h.symbol, q.price))
    }
    val annual = rows.sumOf { it.annual }
    val freqOrder = listOf("月配", "季配", "半年配", "年配", "已配息 1 次", "尚未配息", "不配息", "載入中")

    Card(padding = 14.dp) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ETF 配息總覽", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Label("${rows.size} 檔 ETF · 依最近一次配息估算")
            }
            Column(horizontalAlignment = Alignment.End) {
                Label("年領約")
                NumText("NT$" + mask(Fmt.num(annual)), 16.sp, Color(0xFFE6C34A), FontWeight.Bold)
                Label("月均 NT$" + mask(Fmt.num(annual / 12)))
            }
            Text(if (open) "  ▲" else "  ▼", color = C.Muted, fontSize = 12.sp)
        }
        // 未來 12 個月現金流
        val mx = months.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        Row(Modifier.fillMaxWidth().height(70.dp).padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            months.forEach { v ->
                Box(Modifier.weight(1f).fillMaxHeight((v / mx).toFloat().coerceIn(0.03f, 1f)).clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (v > 0) Color(0xFFE6C34A).copy(alpha = 0.85f) else C.Line))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (1..12).forEach { k ->
                Text(now.plusMonths(k.toLong()).monthValue.toString(), Modifier.weight(1f), color = C.Muted, fontSize = 9.sp, textAlign = TextAlign.Center)
            }
        }
        Label("未來 12 個月預估（依去年同月除息）", Modifier.padding(top = 2.dp))

        if (open) {
            rows.groupBy { it.freq }.toList().sortedBy { (f, _) -> freqOrder.indexOf(f).let { if (it < 0) 99 else it } }.forEach { (f, list) ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f, color = Color(0xFFE6C34A), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Label("${list.size} 檔 · 年 NT$" + mask(Fmt.num(list.sumOf { it.annual })))
                }
                list.sortedByDescending { it.annual }.forEach { r ->
                    Row(Modifier.fillMaxWidth().clickable { onOpen(r.symbol) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.symbol + " " + r.name, color = C.Text, fontSize = 13.sp, maxLines = 1)
                            Label("每次約 NT$" + mask(Fmt.num(r.perPay)) + (r.yieldPct?.let { " · 年化 " + Fmt.num(it, 1) + "%" } ?: ""))
                        }
                        NumText("NT$" + mask(Fmt.num(r.annual)), 13.sp, C.Text2)
                    }
                }
            }
            Label("估算未扣二代健保與所得稅；美股已扣 30% 預扣稅。實際以投信公告為準。", Modifier.padding(top = 8.dp))
        }
    }
}
