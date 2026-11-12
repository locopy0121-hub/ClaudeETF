package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.EtfMarket
import com.claudeetf.app.data.TwPicks
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt

/** 市場 › ETF：台股 ETF 概況、類型漲跌、排行、綜合分析看漲／看跌 */
fun LazyListScope.etfMarketSection(
    snap: Load<EtfMarket.Snapshot>, moverTab: Int, onMoverTab: (Int) -> Unit, side: Int, onSide: (Int) -> Unit, open: (String) -> Unit,
) {
    when (snap) {
        is Load.Loading -> item { Label("載入台股 ETF 行情中…", Modifier.padding(vertical = 16.dp)) }
        is Load.Err -> item { Text("ETF 行情載入失敗：" + snap.message, color = C.Red, fontSize = 13.sp) }
        is Load.Ok -> {
            val s = snap.value
            item {
                Card(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("台股 ETF 概況", Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Label(s.date.replace("-", "/"))
                    }
                    val tot = (s.up + s.down + s.flat).coerceAtLeast(1)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(12.dp).clip(RoundedCornerShape(4.dp))) {
                        if (s.up > 0) Box(Modifier.weight(s.up.toFloat() / tot).fillMaxHeight().background(Trend.fg(1.0)))
                        if (s.flat > 0) Box(Modifier.weight(s.flat.toFloat() / tot).fillMaxHeight().background(C.Muted))
                        if (s.down > 0) Box(Modifier.weight(s.down.toFloat() / tot).fillMaxHeight().background(Trend.fg(-1.0)))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("上漲 ${s.up}", Modifier.weight(1f), color = Trend.fg(1.0), fontSize = 12.sp)
                        Text("平盤 ${s.flat}", Modifier.weight(1f), color = C.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                        Text("下跌 ${s.down}", Modifier.weight(1f), color = Trend.fg(-1.0), fontSize = 12.sp, textAlign = TextAlign.End)
                    }
                    Label("成交值 " + "%,.0f".format(s.valueYi) + " 億，占上市櫃 " + "%.1f".format(if (s.marketYi > 0) s.valueYi / s.marketYi * 100 else 0.0) + "% · 有成交 ${tot} 檔")
                }
            }
            // 類型
            item {
                Card(padding = 14.dp) {
                    Text("類型漲跌", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Label("依代號字尾與名稱分類，平均漲跌幅；長條為成交值占比")
                    val mx = s.cats.maxOfOrNull { kotlin.math.abs(it.avgPct) }?.takeIf { it > 0 } ?: 1.0
                    s.cats.forEach { c ->
                        val col = Trend.fg(c.avgPct)
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.name, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
                                Text("${c.count} 檔 · ▲${c.up} ▼${c.down}", color = C.Text2, fontSize = 11.sp)
                                Text("  " + "%+.2f".format(c.avgPct) + "%", color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 3.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Bg)) {
                                val half = maxWidth / 2
                                val len = half * (kotlin.math.abs(c.avgPct) / mx).toFloat().coerceIn(0f, 1f)
                                Box(Modifier.offset(x = if (c.avgPct >= 0) half else half - len).width(len).fillMaxHeight().background(col))
                            }
                            Label("成交 " + "%,.1f".format(c.valueYi) + " 億（" + "%.0f".format(if (s.valueYi > 0) c.valueYi / s.valueYi * 100 else 0.0) + "%）")
                        }
                    }
                }
            }
            // 排行
            item { SectionTitle("ETF 排行", "漲跌幅限成交 ≥ 200 張") }
            item { Segmented(listOf("漲幅", "跌幅", "成交值"), moverTab, onMoverTab) }
            val list = when (moverTab) { 0 -> s.gainers; 1 -> s.losers; else -> s.actives }
            list.forEachIndexed { i, r ->
                item(key = "etfm$moverTab${r.symbol}") {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).clickable { open(r.symbol) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", Modifier.width(24.dp), color = C.Muted, fontSize = 12.sp)
                        Column(Modifier.weight(1f)) {
                            Text(r.name, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Label(r.symbol + " · " + (if (r.otc) "櫃" else "市") + " · " + EtfMarket.category(r.symbol, r.name) + " · " +
                                "%,.1f".format(r.valueYi) + " 億 · " + Fmt.num(r.lots) + " 張")
                        }
                        val lim = if (kotlin.math.abs(r.pct) >= 9) limitDir(r.price, r.price / (1 + r.pct / 100), r.symbol, true) else 0
                        if (lim != 0) { LimitTag(lim, 10.sp); HGap(4.dp) }
                        Column(horizontalAlignment = Alignment.End) { NumText(Fmt.price(r.price), 14.sp); ChangePill(r.pct) }
                    }
                }
            }
        }
    }
    // 綜合分析排行
    item { SectionTitle("ETF 分析推薦", "成交值前 ${TwPicks.UNIVERSE_ETF} 檔 · ETF 引擎＋光譜") }
    item { Segmented(listOf("看漲・值得布局", "看跌・暫避"), side, onSide) }
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when {
                    TwPicks.etfRunning -> Label("分析中 " + TwPicks.etfDone + "／" + TwPicks.etfTotal + " 檔…")
                    TwPicks.etfAtState > 0 -> Label("分析於 " + Fmt.time(TwPicks.etfAtState))
                    else -> Label("尚未分析")
                }
            }
            Text(if (TwPicks.etfRunning) "分析中" else "重新分析", color = if (TwPicks.etfRunning) C.Muted else C.Primary, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !TwPicks.etfRunning) { TwPicks.startEtf(force = true) }
                    .padding(horizontal = 10.dp, vertical = 6.dp))
        }
        if (TwPicks.etfRunning && TwPicks.etfTotal > 0) Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Selected)) {
            Box(Modifier.fillMaxWidth(TwPicks.etfDone.toFloat() / TwPicks.etfTotal).fillMaxHeight().background(C.Primary))
        }
    }
    val picks = if (side == 0) TwPicks.etfBulls else TwPicks.etfBears
    if (picks.isEmpty() && !TwPicks.etfRunning && TwPicks.etfAtState > 0) item { Label(if (side == 0) "目前沒有明顯看漲的 ETF。" else "目前沒有明顯看跌的 ETF。") }
    picks.forEachIndexed { i, p ->
        item(key = "etfp$side${p.symbol}") { PickCard(i + 1, p, side == 0, showBoard = false) { open(p.symbol) } }
    }
    item { Label("資料：證交所、櫃買中心行情（自選中的 ETF 用即時價）；分析排除槓桿與反向型。僅供參考，非投資建議。") }
}
