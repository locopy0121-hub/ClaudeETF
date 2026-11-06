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

/** 個股概覽：投資分析指數（含分析師共識） */
@Composable
fun AnalysisCard(q0: Quote) {
    val q = AppState.quote(q0.symbol) ?: q0
    val result = InvestAnalysis.results[q.symbol]
    var error by remember(q.symbol) { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(q.symbol) {
        while (true) {
            runCatching { InvestAnalysis.analyze(AppState.quote(q.symbol) ?: q) }
                .onSuccess { error = null }
                .onFailure { if (InvestAnalysis.results[q.symbol] == null) error = it.friendly() }
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
                r.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color).padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        ScoreGauge(r.score)
        Text(r.advice, color = C.Text2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp))
        SuggestedInvest(q, r.score)

        // 最強與最弱因子：標籤一行、說明一行，避免長句擠在一起
        val best = r.factors.maxBy { it.score }
        val worst = r.factors.minBy { it.score }
        Spacer(Modifier.height(6.dp))
        FactorLine("優勢", best, Trend.fg(1.0))
        if (worst !== best) FactorLine("留意", worst, Trend.fg(-1.0))

        r.analyst?.let { a ->
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Bg))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("分析師共識", Modifier.weight(1f), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                a.count?.let { Label("$it 位") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    KeyValue("評等", a.rating ?: "—")
                    KeyValue("平均分數", a.mean?.let { "%.1f".format(it) + " / 5" } ?: "—")
                }
                Column(Modifier.weight(1f)) {
                    KeyValue("目標價", a.target?.let { Fmt.price(it) } ?: "—")
                    a.target?.takeIf { q.price > 0 }?.let { t ->
                        val up = (t / q.price - 1) * 100
                        KeyValue("上漲空間", Fmt.pct(up), Trend.fg(up))
                    }
                }
            }
            if (a.high != null && a.low != null) Label("目標價區間 " + Fmt.price(a.low) + "～" + Fmt.price(a.high) + "（1 強力買進～5 強力賣出）")
        }

        r.etf?.let { e ->
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Bg))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ETF 指標", Modifier.weight(1f), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Label("ETF 無分析師評等")
            }
            val tw = q.market == Market.TW
            val cells = listOfNotNull(
                if (e.noDividend) InfoCell("配息", "不配息／尚無紀錄") else null,
                InfoCell("殖利率", e.yieldPct?.let { Fmt.num(it, 2) + "%" }),
                InfoCell("填息", if (e.divCount != null && e.filled != null) "${e.filled} / ${e.divCount} 次" else null),
                InfoCell("平均填息", e.avgFillDays?.let { Fmt.num(it, 0) + " 天" }),
                InfoCell("日均量", e.avgVolume?.let { if (tw) Fmt.num(it) + " 張" else Fmt.num(it / 1e6, 2) + "M" }),
                InfoCell("規模（Yahoo）", e.assets?.let { if (it >= 1e8) Fmt.num(it / 1e8) + " 億" else Fmt.num(it / 1e4) + " 萬" }),
                InfoCell("費用率", e.expensePct?.let { Fmt.num(it, 2) + "%" }),
                InfoCell("前日淨值", e.nav?.let { Fmt.price(it) }),
                InfoCell("昨收折溢價", e.premiumPct?.let { (if (it >= 0) "溢價 " else "折價 ") + Fmt.num(kotlin.math.abs(it), 2) + "%" }),
            ).filter { it.value != null }
            if (cells.isEmpty()) Label("暫無 ETF 指標資料")
            cells.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { KeyValue(it.label, it.value!!, modifier = Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            r.factors.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, color = C.Text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(52.dp))
                    Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Bg)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f.score / 100f).clip(RoundedCornerShape(4.dp)).background(scoreColor(f.score)))
                    }
                    NumText(f.score.toString(), 12.sp, scoreColor(f.score), modifier = Modifier.width(36.dp).padding(start = 8.dp))
                    Label("權重 ${f.weight}", Modifier.width(56.dp))
                }
                Text(f.note, color = C.Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(start = 52.dp, top = 2.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("更新 " + Fmt.time(r.at), Modifier.weight(1f))
            Text(if (AppState.showScoreBadge) "標題顯示指數 ✓" else "標題顯示指數", color = C.Primary, fontSize = 12.sp,
                modifier = Modifier.clickable { AppState.updateShowScoreBadge(!AppState.showScoreBadge) }.padding(4.dp))
        }
        Text(
            (if (r.etf != null) "ETF 依趨勢、動能、52 週位置、法人籌碼、配息與填息、流動性、規模費用、折溢價、大盤環境與波動度計算" else "依趨勢、動能、估值、52 週位置、法人籌碼、分析師、大盤環境與波動度計算") + "的參考分數，缺資料的項目不計入。僅供參考，不構成投資建議。",
            color = C.Muted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private data class InfoCell(val label: String, val value: String?)

/** 參考投入比例與金額：預計投入資金 × 比例，換算可買股數（台股另顯示張數） */
@Composable
private fun SuggestedInvest(q: Quote, score: Int) {
    val ratio = InvestAnalysis.suggestedRatio(score)
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(if (AppState.investBudget > 0) Fmt.num(AppState.investBudget).replace(",", "") else "") }
    val budget = AppState.investBudget
    val fx = AppState.fxFor(q.market)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("參考投入比例", Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
            NumText("$ratio%", 18.sp, scoreColor(score), FontWeight.Bold)
        }
        if (budget > 0 && q.price > 0) {
            val amount = budget * ratio / 100
            val shares = Math.floor(amount / (q.price * fx))
            Label(
                "預計投入 NT$" + mask(Fmt.num(budget)) + " × $ratio% ＝ NT$" + mask(Fmt.num(amount)) +
                    "，約 " + Fmt.shares(shares) + " 股" + (if (q.market == Market.TW && shares >= 1000) "（" + Fmt.num(shares / 1000, 1) + " 張）" else "") +
                    "；其餘 " + (100 - ratio) + "% 分批或保留",
            )
        } else Label("設定預計投入資金後，換算成金額與股數")
        Text(if (editing) "收合" else if (budget > 0) "修改預計投入資金" else "設定預計投入資金", color = C.Primary, fontSize = 12.sp,
            modifier = Modifier.clickable { editing = !editing }.padding(vertical = 4.dp))
        if (editing) Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.OutlinedTextField(text, { text = it.filter { c -> c.isDigit() } }, label = { Text("預計投入（台幣）") },
                singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            Text("儲存", color = C.Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { AppState.updateInvestBudget(text.toDoubleOrNull() ?: 0.0); editing = false }.padding(10.dp))
        }
        Label("偏空 10%・中性偏空 25%・中性 40%・中性偏多 60%・偏多 80%；依分數分批布局的參考，不構成投資建議。")
    }
}

@Composable
private fun FactorLine(tag: String, f: InvestAnalysis.Factor, color: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        Text(tag, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name + " " + f.score + " 分", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(f.note, color = C.Text2, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

/** 個股頁標題列的投資分析指數徽章 */
@Composable
fun ScoreBadge(q: Quote) {
    if (!AppState.showScoreBadge) return
    val r = InvestAnalysis.results[q.symbol]
    LaunchedEffect(q.symbol) { if (InvestAnalysis.cached(q.symbol) == null) runCatching { InvestAnalysis.analyze(q) } }
    val color = r?.let { scoreColor(it.score) } ?: C.Muted
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("指數 ", color = C.Text2, fontSize = 11.sp, maxLines = 1)
        NumText(r?.let { "${it.score}%" } ?: "…", 14.sp, color, FontWeight.Bold)
        r?.let {
            Text(" " + it.label, color = color, fontSize = 11.sp, maxLines = 1)
            Text(" · 投入 " + InvestAnalysis.suggestedRatio(it.score) + "%", color = C.Text2, fontSize = 11.sp, maxLines = 1)
        }
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

/** 每年報酬（價格報酬，不含股息）：去年最後收盤 → 今年最後收盤；最早一年從上市首筆、今年為至今 */
data class YearReturn(val year: Int, val pct: Double, val partial: Boolean)

object YearlyReturns {
    data class Data(val total: List<YearReturn>, val price: List<YearReturn>, val firstDate: java.time.LocalDate?, val fixedSplits: Int)
    private val cache = HashMap<String, Pair<Long, Data>>()

    /**
     * 修正 Yahoo 歷史資料中「拆分（分割）只調整了部分期間」的斷層：
     * 台股單日漲跌限制 10%，週線不可能一週跌到剩 45% 以下或漲到 2.2 倍以上，
     * 遇到這種跳動視為未調整的拆分，把之前的價格乘上同一比例接起來。
     */
    private fun fixSplits(v: MutableList<Double>, tw: Boolean): Int {
        var fixed = 0
        for (i in v.size - 1 downTo 1) {
            val a = v[i - 1]; val b = v[i]
            if (a <= 0 || b <= 0) continue
            val r = b / a
            val jump = if (tw) (r < 0.45 || r > 2.2) else (r < 0.35 || r > 3.0)
            if (jump) {
                for (k in 0 until i) v[k] = v[k] * r
                fixed++
            }
        }
        return fixed
    }

    private fun yearly(times: List<Long>, values: List<Double>, tz: java.time.ZoneId, last: Double?): List<YearReturn> {
        val lastClose = sortedMapOf<Int, Double>()
        var firstClose = 0.0
        for (i in times.indices) {
            val c = values[i]
            if (c.isNaN() || c <= 0) continue
            val y = java.time.Instant.ofEpochMilli(times[i]).atZone(tz).year
            if (lastClose.isEmpty()) firstClose = c
            lastClose[y] = c
        }
        val thisYear = java.time.LocalDate.now(tz).year
        if (last != null && last > 0 && lastClose.containsKey(thisYear)) lastClose[thisYear] = last
        val out = ArrayList<YearReturn>()
        var prev: Double? = null
        for ((y, c) in lastClose) {
            val base = prev ?: firstClose
            if (base > 0) out += YearReturn(y, (c / base - 1) * 100, partial = prev == null || y == thisYear)
            prev = c
        }
        return out
    }

    fun compute(h: com.claudeetf.app.data.remote.YahooExtra.History, tz: java.time.ZoneId, currentPrice: Double?, tw: Boolean): Data {
        val ok = h.closes.indices.filter { !h.closes[it].isNaN() && h.closes[it] > 0 }
        val times = ok.map { h.times[it] }
        val price = ok.map { h.closes[it].toDouble() }.toMutableList()
        val splits = fixSplits(price, tw)
        val adj = if (h.adjCloses.size == h.closes.size) ok.map { h.adjCloses[it].toDouble() }.toMutableList() else null
        adj?.let { fixSplits(it, tw) }
        // 今年最後一格用目前行情：含息序列依「目前價／最後收盤」等比例延伸
        val lastP = price.lastOrNull()
        val ratio = if (currentPrice != null && currentPrice > 0 && lastP != null && lastP > 0) currentPrice / h.closes[ok.last()] else null
        val priceYears = yearly(times, price, tz, ratio?.let { lastP!! * it })
        val totalYears = adj?.takeIf { it.none { v -> v.isNaN() } }?.let { a -> yearly(times, a, tz, ratio?.let { a.last() * it }) } ?: priceYears
        return Data(totalYears, priceYears, times.firstOrNull()?.let { java.time.Instant.ofEpochMilli(it).atZone(tz).toLocalDate() }, splits)
    }

    suspend fun load(q: Quote): Data = load(com.claudeetf.app.data.StockInfoRepo.yahooSymbol(q), q.market == Market.TW, q.price)

    /** 任意 Yahoo 代號（個股、ETF、指數如 ^TWII） */
    suspend fun load(yahooSymbol: String, tw: Boolean, current: Double?): Data = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        cache[yahooSymbol]?.let { (t, v) -> if (System.currentTimeMillis() - t < 30 * 60_000L) return@withContext v }
        val h = com.claudeetf.app.data.remote.YahooExtra.history(yahooSymbol, "max")
        val tz = java.time.ZoneId.of(if (tw) "Asia/Taipei" else "America/New_York")
        compute(h, tz, current, tw).also { cache[yahooSymbol] = System.currentTimeMillis() to it }
    }
}

/** 個股概覽：歷年報酬格圖＋平均年報酬、年化報酬（CAGR）、上漲年數 */
@Composable
fun YearlyReturnsCard(q0: Quote) {
    val q = AppState.quote(q0.symbol) ?: q0
    YearlyReturnsBlock(com.claudeetf.app.data.StockInfoRepo.yahooSymbol(q), q.market == Market.TW, q.price, isIndex = false)
}

/** 歷年報酬（個股、ETF、指數共用）；isIndex = true 時不顯示含息切換（指數本身不含息） */
@Composable
fun YearlyReturnsBlock(yahooSymbol: String, tw: Boolean, current: Double?, isIndex: Boolean) {
    var all by remember(yahooSymbol) { mutableStateOf<YearlyReturns.Data?>(null) }
    var withDiv by remember { mutableStateOf(true) }
    var err by remember(yahooSymbol) { mutableStateOf<String?>(null) }
    // 年數：近 5／10／20 年或全部
    var span by remember { mutableIntStateOf(10) }
    LaunchedEffect(yahooSymbol) {
        runCatching { YearlyReturns.load(yahooSymbol, tw, current) }.onSuccess { all = it }.onFailure { err = it.friendly() }
    }
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("歷年報酬", Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (!isIndex) listOf(true to "含息", false to "不含息").forEach { (v, n) ->
                val on = withDiv == v
                Text(n, color = if (on) C.Text else C.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(12.dp)).background(if (on) C.Selected else C.Bg)
                        .clickable { withDiv = v }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Label("年數")
            listOf(5 to "近 5 年", 10 to "近 10 年", 20 to "近 20 年", 0 to "全部").forEach { (n, label) ->
                val on = span == n
                Text(label, color = if (on) C.Text else C.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) C.Selected else C.Bg)
                        .clickable { span = n }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        val src = all?.let { if (withDiv && !isIndex) it.total else it.price }
        if (src == null) { Label(err?.let { "無法載入：$it" } ?: "載入歷年走勢中…"); return@Card }
        if (src.isEmpty()) { Label("沒有足夠的歷史資料"); return@Card }
        // 依選擇的年數只取最近 N 個完整年度（今年至今另外附在最後）
        val thisYear = java.time.LocalDate.now().year
        val d = if (span == 0) src else {
            val fullYears = src.filter { !it.partial }.takeLast(span).map { it.year }.toSet()
            src.filter { it.year in fullYears || it.year == thisYear }
        }
        val full = d.filter { !it.partial }
        // 平均年報酬＝完整年度的算術平均；年化報酬＝完整年度的幾何平均（CAGR）
        val mean = full.takeIf { it.isNotEmpty() }?.map { it.pct }?.average()
        val cagr = full.takeIf { it.isNotEmpty() }?.let { ys ->
            val g = ys.fold(1.0) { acc, y -> acc * (1 + y.pct / 100) }
            (Math.pow(g, 1.0 / ys.size) - 1) * 100
        }
        val up = full.count { it.pct > 0 }
        val best = full.maxByOrNull { it.pct }
        val worst = full.minByOrNull { it.pct }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Label("平均年報酬")
                NumText(mean?.let { Fmt.pct(it) } ?: "—", 20.sp, Trend.fg(mean ?: 0.0), FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Label("年化報酬（CAGR）")
                NumText(cagr?.let { Fmt.pct(it) } ?: "—", 20.sp, Trend.fg(cagr ?: 0.0), FontWeight.Bold)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            KeyValue("上漲年數", if (full.isEmpty()) "—" else "$up / ${full.size} 年", modifier = Modifier.weight(1f))
            KeyValue("統計期間", if (full.isEmpty()) "—" else "${full.first().year}–${full.last().year}", modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            KeyValue("最佳年", best?.let { "${it.year} " + Fmt.pct(it.pct) } ?: "—", Trend.fg(1.0), Modifier.weight(1f))
            KeyValue("最差年", worst?.let { "${it.year} " + Fmt.pct(it.pct) } ?: "—", Trend.fg(-1.0), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        // 格圖：每列 5 年，依年份由舊到新
        d.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { y ->
                    val c = Trend.fg(y.pct)
                    val strength = (kotlin.math.abs(y.pct) / 40.0).coerceIn(0.25, 1.0).toFloat()
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp))
                                .background(c.copy(alpha = 0.25f + 0.6f * strength)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                (if (y.pct > 0) "+" else "") + Fmt.num(y.pct, 1) + "%", color = Color.White,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            )
                        }
                        Text(y.year.toString() + if (y.partial) "*" else "", color = C.Text2, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Label(
            "* 不完整年度（資料首年或今年至今），不計入平均。" +
                (if (isIndex) "指數為價格報酬（不含股息）。" else if (withDiv) "含息＝還原權值報酬（股息再投入）。" else "不含息＝只看股價漲跌。") +
                "資料來源 Yahoo Finance，資料起始 " + (all?.firstDate?.toString() ?: "—") + "（可能晚於實際上市日）" +
                (if ((all?.fixedSplits ?: 0) > 0) "；已修正 ${all?.fixedSplits} 處分割造成的資料斷層。" else "。"),
            Modifier.padding(top = 6.dp),
        )
    }
}
