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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.DividendCalendar
import com.claudeetf.app.data.InvestAnalysis
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.Quote
import com.claudeetf.app.data.remote.friendly
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.KeyValue
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.mask
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import java.time.format.DateTimeFormatter

/** 分數顏色：高分用上漲色、低分用下跌色，中間灰 */
private fun scoreColor(score: Int): Color = when {
    score >= 58 -> Trend.fg(1.0)
    score >= 45 -> C.Primary
    else -> Trend.fg(-1.0)
}

/** 個股概覽：投資分析指數 */
@Composable
fun AnalysisCard(q0: Quote) {
    val q = AppState.quote(q0.symbol) ?: q0
    var result by remember(q.symbol) { mutableStateOf(InvestAnalysis.cached(q.symbol)) }
    var error by remember(q.symbol) { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(q.symbol) {
        while (true) {
            runCatching { InvestAnalysis.analyze(AppState.quote(q.symbol) ?: q) }
                .onSuccess { result = it; error = null }
                .onFailure { if (result == null) error = it.friendly() }
            kotlinx.coroutines.delay(10 * 60_000L + 1_000L)
        }
    }
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("投資分析指數", Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(if (expanded) "收合" else "看因子 ›", color = C.Primary, fontSize = 12.sp,
                modifier = Modifier.clickable { expanded = !expanded }.padding(4.dp))
        }
        val r = result
        if (r == null) {
            Label(error?.let { "無法分析：$it" } ?: "依市場數據計算中…")
            return@Card
        }
        val color = scoreColor(r.score)
        Row(verticalAlignment = Alignment.Bottom) {
            NumText(r.score.toString(), 34.sp, color, FontWeight.Bold)
            Text(" / 100", color = C.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            Text(
                r.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color).padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        ScoreGauge(r.score)
        Text(r.advice, color = C.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        // 依各因子分數列出最強與最弱
        val best = r.factors.maxBy { it.score }
        val worst = r.factors.minBy { it.score }
        Label("優勢：${best.name}（${best.note}）", color = Trend.fg(1.0))
        if (worst !== best) Label("留意：${worst.name}（${worst.note}）", color = Trend.fg(-1.0))
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            r.factors.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, color = C.Text, fontSize = 13.sp, modifier = Modifier.width(40.dp))
                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Bg)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f.score / 100f).clip(RoundedCornerShape(4.dp)).background(scoreColor(f.score)))
                    }
                    NumText(f.score.toString(), 12.sp, scoreColor(f.score), modifier = Modifier.width(34.dp).padding(start = 8.dp))
                    Label("權重 ${f.weight}", Modifier.width(52.dp))
                }
                Label(f.note, Modifier.padding(start = 40.dp))
            }
        }
        Label(
            "依趨勢、動能、估值、52 週位置、法人籌碼、大盤環境與波動度計算的參考分數，" +
                "缺資料的項目不計入。僅供參考，不構成投資建議，請自行判斷並控制風險。" +
                " 更新 " + Fmt.time(r.at),
            Modifier.padding(top = 8.dp),
        )
    }
}

/** 0–100 分色帶與指針 */
@Composable
private fun ScoreGauge(score: Int) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
            Row(Modifier.fillMaxWidth().height(8.dp).align(Alignment.Center).clip(RoundedCornerShape(4.dp))) {
                listOf(
                    Trend.fg(-1.0), Trend.fg(-1.0).copy(alpha = 0.55f), C.Primary.copy(alpha = 0.6f),
                    Trend.fg(1.0).copy(alpha = 0.55f), Trend.fg(1.0),
                ).zip(listOf(33f, 12f, 13f, 12f, 30f)).forEach { (c, w) ->
                    Box(Modifier.weight(w).fillMaxHeight().background(c))
                }
            }
            val x = maxWidth * (score.coerceIn(0, 100) / 100f)
            Box(Modifier.offset(x = x - 2.dp).width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(C.Text))
        }
        Row(Modifier.fillMaxWidth()) {
            Label("偏空", Modifier.weight(1f))
            Label("中性", Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally))
            Label("偏多", Modifier.weight(1f).wrapContentWidth(Alignment.End))
        }
    }
}

private val MD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d (E)", java.util.Locale.TAIWAN)

/** 下次除權息：除息日、最後買進日、發放日、每股股利、持有預估實收 */
@Composable
fun NextDividendCard(q: Quote, compact: Boolean = false) {
    val p = AppState.divPlans[q.symbol]
    LaunchedEffect(Unit) { runCatching { DividendCalendar.refresh() } }
    val ccy = if (q.market == Market.US) "$" else "NT$"
    val dec = if (q.market == Market.US) 2 else 0
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("下次配息", Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (p != null) {
                val d = p.daysToEx()
                Text(
                    if (d <= 0) "今天${p.type}" else "$d 天後${p.type}", color = C.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Selected).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (p == null) {
            Label(
                if (DividendCalendar.loading || AppState.divPlansUpdatedAt == 0L) "查詢除權息公告中…"
                else "目前沒有已公布的除權息日（每天開 App 自動更新）"
            )
            return@Card
        }
        val lbDays = p.daysToLastBuy()
        KeyValue(p.type + "日", p.exDate.format(MD))
        KeyValue(
            "最後買進日", p.lastBuy.format(MD) + when {
                lbDays == 0L -> "（今天）"
                lbDays < 0 -> "（已過）"
                else -> "（還有 $lbDays 天）"
            },
            if (lbDays == 0L) C.Primary else if (lbDays < 0) C.Muted else C.Text,
        )
        KeyValue("發放日", p.payDate?.format(MD) ?: "尚未公布")
        p.cash?.let { KeyValue("每股現金股利", ccy + Fmt.num(it, 4).trimEnd('0').trimEnd('.') + if (p.announced) "" else "（依上次估）") }
        p.stock?.let { KeyValue("每股配股", Fmt.num(it, 4).trimEnd('0').trimEnd('.') + " 股") }
        p.cash?.takeIf { q.price > 0 }?.let {
            KeyValue("本次殖利率（現價）", Fmt.num(it / q.price * 100, 2) + "%")
        }
        DividendCalendar.estimate(p)?.let { e ->
            val h = AppState.holdings.firstOrNull { it.symbol == q.symbol }
            if (!compact) Spacer(Modifier.height(4.dp))
            KeyValue("持有 " + Fmt.shares(h?.shares ?: 0.0) + " 股預估", ccy + mask(Fmt.num(e.gross, dec)))
            if (e.nhi > 0) KeyValue("二代健保 2.11%", "−" + ccy + mask(Fmt.num(e.nhi, dec)), C.Muted)
            if (e.withholding > 0) KeyValue("美國預扣稅 30%", "−" + ccy + mask(Fmt.num(e.withholding, dec)), C.Muted)
            KeyValue("預估實收", ccy + mask(Fmt.num(e.net, dec)), C.Primary)
            if (e.stockShares > 0) KeyValue("預估配股", Fmt.shares(e.stockShares) + " 股")
            if (lbDays < 0 && p.daysToEx() > 0) Label("已過最後買進日，現在買進不參加本次配息", color = C.Muted)
        }
        if (!compact) Label("資料來源：" + p.source + "；最後買進日為除息日前一個營業日，遇國定假日以券商公告為準", Modifier.padding(top = 4.dp))
    }
}

/** 持股即將配息清單（股息頁、首頁卡片） */
@Composable
fun UpcomingHoldingDividends(nav: androidx.navigation.NavController, limit: Int = 20) {
    LaunchedEffect(Unit) { runCatching { DividendCalendar.refresh() } }
    val md = DateTimeFormatter.ofPattern("M/d")
    val rows = AppState.holdings.mapNotNull { h -> AppState.divPlans[h.symbol]?.let { h to it } }.sortedBy { it.second.exDate }.take(limit)
    if (rows.isEmpty()) {
        Label(if (DividendCalendar.loading || AppState.divPlansUpdatedAt == 0L) "查詢除權息公告中…" else "持股近期沒有已公布的除權息")
        return
    }
    var totalTwd = 0.0
    rows.forEach { (h, p) ->
        val q = AppState.quote(h.symbol)
        val us = q?.market == Market.US
        val e = DividendCalendar.estimate(p)
        if (e != null) totalTwd += e.net * if (us) AppState.usdTwd else 1.0
        Column(Modifier.fillMaxWidth().clickable { nav.navigate(com.claudeetf.app.ui.Routes.stock(h.symbol)) }.padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.symbol + " " + (q?.name ?: ""), Modifier.weight(1f), color = C.Text, fontSize = 13.sp, maxLines = 1)
                NumText(
                    e?.takeIf { it.net > 0 }?.let { (if (us) "$" else "") + mask(Fmt.num(it.net, if (us) 2 else 0)) } ?: "—",
                    13.sp, C.Primary,
                )
            }
            Label(
                listOfNotNull(
                    p.type + " " + p.exDate.format(md),
                    "最後買進 " + p.lastBuy.format(md),
                    p.payDate?.let { "發放 " + it.format(md) },
                    p.cash?.let { "每股 " + Fmt.num(it, 4).trimEnd('0').trimEnd('.') + if (p.announced) "" else "（估）" },
                ).joinToString(" · ")
            )
        }
    }
    if (totalTwd > 0) Label("合計預估實收約 NT$" + mask(Fmt.num(totalTwd)) + "（已扣二代健保／美股預扣稅）", color = C.Text2)
}
