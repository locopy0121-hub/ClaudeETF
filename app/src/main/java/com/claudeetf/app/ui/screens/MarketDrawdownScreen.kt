package com.claudeetf.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.Drawdown
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.data.remote.friendly
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.ErrorLog
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ln
import kotlin.math.pow

/**
 * 台股／美股／全球歷次大跌：長期走勢（對數座標、起點 100 萬）標出每一次大跌與事件，
 * 列出跌幅、高點→低點日期、低點後多久創新高。參考 YP-Finance「台股美股全球歷次大跌」的呈現方式。
 */
private data class DdMarket(val name: String, val symbols: List<String>, val useAdj: Boolean, val note: String)

private val DD_MARKETS = listOf(
    DdMarket("台股", listOf("^TWII"), false, "台灣加權股價指數（^TWII，未含息；含息報酬指數 Yahoo 未提供，長期累積報酬會低於含息版本約每年 3%～4%）"),
    DdMarket("美股", listOf("^SP500TR", "^GSPC"), false, "S&P 500 Total Return（^SP500TR，含息）；取不到時改用 S&P 500 價格指數"),
    DdMarket("全球", listOf("ACWI"), true, "iShares MSCI ACWI ETF（含息還原，美元計價；2008 年起）"),
)

private object MarketDdData {
    private val cache = HashMap<String, Pair<Long, Pair<String, YahooExtra.History>>>()
    suspend fun load(m: DdMarket): Pair<String, YahooExtra.History> = withContext(Dispatchers.IO) {
        val key = m.name + m.symbols.joinToString()
        synchronized(cache) { cache[key]?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }?.let { return@withContext it.second } }
        var last: Throwable? = null
        for (s in m.symbols) {
            val h = runCatching { YahooExtra.history(s, "max", intervalOverride = "1d") }.onFailure { last = it }.getOrNull()
            if (h != null && h.closes.size > 60) {
                synchronized(cache) { cache[key] = System.currentTimeMillis() to (s to h) }
                return@withContext s to h
            }
        }
        throw last ?: IllegalStateException("取不到歷史資料")
    }
}

private val TPE = ZoneId.of("Asia/Taipei")
private fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(TPE).toLocalDate()
private fun ymdDash(ms: Long) = dateOf(ms).toString()

private fun monthsText(from: Long, to: Long): String {
    val m = ChronoUnit.MONTHS.between(dateOf(from), dateOf(to)).coerceAtLeast(0)
    return if (m >= 24) Fmt.num(m / 12.0, 1) + " 年" else "$m 個月"
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MarketDrawdownScreen(nav: NavController) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 條件：起點（0 最早／掛牌、1 2003 年、2 近 10 年、3 近 5 年、4 近 3 年、5 近 1 年、6 自訂日期）、起始金額（萬）
    var startMode by rememberSaveable { mutableIntStateOf(1) }
    var customDate by rememberSaveable { mutableStateOf("2020/01/01") }
    var amountText by rememberSaveable { mutableStateOf("100") }
    // 個股：代號、名稱、Yahoo 代號
    var pick by rememberSaveable { mutableStateOf(com.claudeetf.app.data.AppState.holdings.firstOrNull()?.symbol ?: "0050") }
    var pickName by rememberSaveable { mutableStateOf("") }
    var pickYahoo by rememberSaveable { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val stockTab = tab == DD_MARKETS.size
    fun yahooOf(sym: String, otc: Boolean?): String =
        if (sym.first().isDigit()) sym + if (otc ?: com.claudeetf.app.data.StockDirectory.isOtc(sym)) ".TWO" else ".TW" else sym
    val m = if (stockTab) {
        val y = pickYahoo.ifBlank { yahooOf(pick, null) }
        val alt = if (y.endsWith(".TW")) listOf(y, y.removeSuffix(".TW") + ".TWO") else if (y.endsWith(".TWO")) listOf(y, y.removeSuffix(".TWO") + ".TW") else listOf(y)
        DdMarket(pick, alt, true, (pickName.ifBlank { com.claudeetf.app.data.AppState.quote(pick)?.name ?: pick }) + "（$pick，還原股價含息；資料自掛牌或 Yahoo 最早可得日起）")
    } else DD_MARKETS[tab]
    var data by remember(tab, pick) { mutableStateOf<Load<Pair<String, YahooExtra.History>>>(Load.Loading) }
    LaunchedEffect(tab, pick, ErrorLog.retryTick) {
        data = Load.Loading
        data = runCatching { MarketDdData.load(m) }.fold({ Load.Ok(it) }, { Load.err(it) })
    }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("台股美股全球歷次大跌", onBack = { nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Segmented(DD_MARKETS.map { it.name } + "個股", tab, onSelect = { tab = it }) }
            if (stockTab) {
                item {
                    Card(padding = 12.dp) {
                        Text("選擇個股", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        androidx.compose.material3.OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text("輸入代號或名稱，例如 2330、台積電、NVDA", fontSize = 13.sp) })
                        StockSuggestions(query, maxHeight = 260.dp, onPick = { it ->
                            pick = it.symbol; pickName = it.name.takeIf { n -> !n.startsWith("（") } ?: ""
                            pickYahoo = if (it.market == com.claudeetf.app.data.Market.US) it.symbol else yahooOf(it.symbol, it.board == "櫃")
                            query = ""
                        })
                        val quick = (com.claudeetf.app.data.AppState.holdings.map { it.symbol } + com.claudeetf.app.data.AppState.quotes.map { it.symbol }).distinct().take(20)
                        if (query.isBlank() && quick.isNotEmpty()) {
                            Label("持股與自選")
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                quick.forEach { sym ->
                                    val on = sym == pick
                                    val q = com.claudeetf.app.data.AppState.quote(sym)
                                    Text((q?.name ?: sym).take(6), color = if (on) C.Text else C.Text2, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) C.Selected else C.Bg).clickable {
                                            pick = sym; pickName = q?.name ?: ""
                                            pickYahoo = q?.let { StockInfoRepoBridge.yahoo(it) } ?: yahooOf(sym, null)
                                        }.padding(horizontal = 10.dp, vertical = 5.dp))
                                }
                            }
                        }
                    }
                }
            }
            item {
                Card(padding = 12.dp) {
                    Text("條件設定", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("起始金額", Modifier.width(64.dp))
                        androidx.compose.material3.OutlinedTextField(amountText, { v -> amountText = v.filter { it.isDigit() || it == '.' }.take(8) },
                            Modifier.width(140.dp), singleLine = true, suffix = { Text("萬") },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                        HGap(8.dp)
                        listOf("10", "100", "1000").forEach { v ->
                            Text(v + " 萬", color = if (amountText == v) C.Text else C.Muted, fontSize = 12.sp,
                                modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(10.dp)).background(if (amountText == v) C.Selected else C.Bg)
                                    .clickable { amountText = v }.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    Label("起點", Modifier.padding(top = 6.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(if (stockTab) "掛牌起" else "最早", "2003 年", "近 10 年", "近 5 年", "近 3 年", "近 1 年", "自訂日期").forEachIndexed { i, n ->
                            val on = i == startMode
                            Text(n, color = if (on) C.Text else C.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) C.Selected else C.Bg)
                                    .clickable { startMode = i }.padding(horizontal = 10.dp, vertical = 5.dp))
                        }
                    }
                    if (startMode == 6) androidx.compose.material3.OutlinedTextField(customDate, { v -> customDate = v.filter { it.isDigit() || it == '/' || it == '-' }.take(10) },
                        Modifier.fillMaxWidth().padding(top = 6.dp), singleLine = true, label = { Text("起始日（例如 2020/01/02）") })
                    Label("結果計算到最新收盤；資料最早日晚於起點時，自最早可得日起算。")
                }
            }
            when (val d = data) {
                is Load.Loading -> item { Label("載入長期歷史資料中…", Modifier.padding(vertical = 20.dp)) }
                is Load.Err -> item { ErrorBox("歷史資料載入失敗：" + d.message) }
                is Load.Ok -> {
                    val (sym, h) = d.value
                    val raw = if (m.useAdj && h.adjCloses.size == h.closes.size) h.adjCloses else h.closes
                    val today = LocalDate.now(TPE)
                    val startDate: LocalDate? = when (startMode) {
                        1 -> LocalDate.of(2003, 1, 1); 2 -> today.minusYears(10); 3 -> today.minusYears(5); 4 -> today.minusYears(3); 5 -> today.minusYears(1)
                        6 -> runCatching { LocalDate.parse(customDate.replace('/', '-').split('-').let { p -> "%04d-%02d-%02d".format(p[0].toInt(), p[1].toInt(), p[2].toInt()) }) }.getOrNull()
                        else -> null
                    }
                    val amount = amountText.toDoubleOrNull()?.takeIf { it > 0 } ?: 100.0
                    val startMs = startDate?.atStartOfDay(TPE)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
                    val idx = h.times.indices.filter { h.times[it] >= startMs && raw[it] > 0 }
                    if (idx.size < 20) { item { Label("起點之後的資料不足（至少需要 20 個交易日）") }; return@LazyColumn }
                    val t = ArrayList(idx.map { h.times[it] }); val c = ArrayList(idx.map { raw[it].toDouble() })
                    // Yahoo 台股日 K 常晚一天：用 App 內最新收盤補上最後一根
                    run {
                        val lastDay = dateOf(t.last())
                        val exp = com.claudeetf.app.data.MarketData.expectedCloseDay()
                        val px = when {
                            sym == "^TWII" -> com.claudeetf.app.data.AppState.twIndices.firstOrNull { it.symbol == "^TWII" }?.value
                            stockTab && (sym.endsWith(".TW") || sym.endsWith(".TWO")) -> com.claudeetf.app.data.AppState.quote(pick)?.price
                            else -> null
                        }
                        if (px != null && px > 0 && lastDay < exp && (sym == "^TWII" || !m.useAdj || t.size > 0)) {
                            // 還原股價：以最後一根的還原比例換算
                            val ratio = if (m.useAdj) c.last() / (h.closes.getOrNull(idx.last())?.toDouble()?.takeIf { it > 0 } ?: c.last()) else 1.0
                            t += exp.atTime(13, 30).atZone(TPE).toInstant().toEpochMilli(); c += px * ratio
                        }
                    }
                    val waves = Drawdown.waves(t, c)
                    val shown = waves.filter { -it.depth >= 20 || (Drawdown.eventOf(it) != null && -it.depth >= 10) }
                    val total = (c.last() / c.first() - 1) * 100
                    var hi = 0; for (i in c.indices) if (c[i] > c[hi]) hi = i
                    val fromHigh = (c.last() / c[hi] - 1) * 100
                    item {
                        Card(padding = 14.dp) {
                            if (stockTab) Text(m.note.substringBefore("（"), color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Label("累積報酬（" + (if (sym == "^TWII") "未含息" else "含息") + "）")
                            Text((if (total >= 0) "+" else "") + Fmt.num(total, 0) + "%", color = Trend.fg(total), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Label(ymdDash(t.first()) + " 投入 " + Fmt.num(amount, if (amount < 10) 1 else 0) + " 萬，" + ymdDash(t.last()) + " 約 " + Fmt.num(amount * c.last() / c.first(), if (amount * c.last() / c.first() < 10) 2 else 0) + " 萬")
                            val yrs = (t.last() - t.first()) / (365.25 * 86_400_000.0)
                            val cagr = if (yrs >= 1) ((c.last() / c.first()).pow(1 / yrs) - 1) * 100 else null
                            val worst = waves.minByOrNull { it.depth }
                            Row(Modifier.padding(top = 8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Label("年化報酬")
                                    Text(cagr?.let { (if (it >= 0) "+" else "") + Fmt.num(it, 1) + "%" } ?: "未滿 1 年", color = cagr?.let { Trend.fg(it) } ?: C.Muted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f)) {
                                    Label("期間最大跌幅")
                                    Text(worst?.let { Fmt.num(it.depth, 1) + "%" } ?: "—", color = Trend.fg(-1.0), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    worst?.let { Label(ymdDash(it.peakT) + " → " + ymdDash(it.troughT)) }
                                }
                                Column(Modifier.weight(1f)) {
                                    Label("期間")
                                    Text(Fmt.num(yrs, 1) + " 年", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Label("${t.size} 個交易日")
                                }
                            }
                            worst?.let { Label("最大跌幅時 " + Fmt.num(amount, 0) + " 萬最低剩約 " + Fmt.num(amount * it.trough / c.first(), 1) + " 萬", Modifier.padding(top = 4.dp)) }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Card(Modifier.weight(1f), padding = 12.dp) {
                                Label("目前距前高")
                                Text(Fmt.num(fromHigh, 1) + "%", color = if (fromHigh < -0.05) Trend.fg(-1.0) else C.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Label("前高 " + ymdDash(t[hi]))
                            }
                            Card(Modifier.weight(1f), padding = 12.dp) {
                                Label("資料")
                                Text(sym, color = C.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Label("資料至 " + ymdDash(t.last()))
                            }
                        }
                    }
                    item {
                        Card(padding = 12.dp) {
                            Text((if (stockTab) m.note.substringBefore("（") else m.name) + "：${ymdDash(t.first())} 投入 ${Fmt.num(amount, if (amount < 10) 1 else 0)} 萬的變化", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).background(Color(0xFFE5A93B))); HGap(4.dp); Label("走勢（對數座標）"); HGap(10.dp)
                                Box(Modifier.size(10.dp).background(Color(0x66E5484D))); HGap(4.dp); Label("跌幅 ≥ 20%"); HGap(10.dp)
                                Box(Modifier.size(10.dp).background(Color(0x55888888))); HGap(4.dp); Label("未達 20% 的重大事件")
                            }
                            DdChart(t, c, shown, amount, Modifier.fillMaxWidth().height(240.dp).padding(top = 8.dp))
                        }
                    }
                    item {
                        Text("歷次大跌與事件", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        Label("跌幅 20% 以上 ${shown.count { -it.depth >= 20 }} 次；未達 20% 的只列有重大事件的")
                    }
                    items(shown) { w ->
                        val big = -w.depth >= 20
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Drawdown.eventOf(w) ?: "未標註事件", Modifier.weight(1f), color = if (big) C.Text else C.Text2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text(Fmt.num(w.depth, 1) + "%", color = Trend.fg(-1.0), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            Label(ymdDash(w.peakT) + " → " + ymdDash(w.troughT) + "（下跌 " + monthsText(w.peakT, w.troughT) + "）")
                            Text(w.recoverT?.let { monthsText(w.troughT, it) + "後創新高（" + ymdDash(it) + "）" } ?: "尚未回到新高",
                                color = if (w.recoverT == null) Trend.fg(-1.0) else C.Text2, fontSize = 12.sp, fontWeight = if (w.recoverT == null) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    item {
                        Card(padding = 12.dp) {
                            Text("資料來源與說明", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Label("・" + m.note)
                            Label("・「N 個月後創新高」從低點起算到收盤再度回到前高為止。")
                            Label("・波段＝前高跌到低點、回到前高為一波；長空頭中自低點翻倍後又跌 20% 以上另計一波。")
                            Label("・圖表為對數座標，同樣的漲跌幅在圖上高度相同；金額以起始金額換算，未扣除稅費與匯差。")
                            Label("・事件名稱參考 YP-Finance「台股美股全球歷次大跌」。歷史表現不代表未來，僅供參考。")
                        }
                    }
                }
            }
        }
    }
}

/** 對數座標走勢＋大跌區間底色＋低點跌幅標籤 */
@Composable
private fun DdChart(t: List<Long>, c: List<Double>, waves: List<Drawdown.Wave>, amount: Double, modifier: Modifier) {
    val line = Color(0xFFE5A93B)
    val red = Color(0x55E5484D)
    val gray = Color(0x44888888)
    val grid = C.Border
    val label = C.Muted
    val down = Trend.fg(-1.0)
    Canvas(modifier) {
        val base = c.first()
        val vals = c.map { amount * it / base }
        val lo = ln(vals.min() * 0.9); val hi = ln(vals.max() * 1.1)
        val left = 46f; val w = size.width - left; val h = size.height - 18f
        val t0 = t.first(); val t1 = t.last(); val span = (t1 - t0).coerceAtLeast(1).toFloat()
        fun x(ms: Long) = left + (ms - t0) / span * w
        fun y(v: Double) = (h * (1 - (ln(v) - lo) / (hi - lo))).toFloat()
        val paint = android.graphics.Paint().apply { color = label.toArgb(); textSize = 22f; isAntiAlias = true }
        // 格線：100、200、500、1000…萬
        val steps = generateSequence(0.01) { it * 10 }.takeWhile { it < 1e7 }.flatMap { sequenceOf(it, it * 2, it * 5) }
        steps.filter { ln(it) in lo..hi }.forEach { v ->
            val yy = y(v)
            drawLine(grid, Offset(left, yy), Offset(size.width, yy), 1f)
            drawContext.canvas.nativeCanvas.drawText(Fmt.num(v, if (v < 10) 1 else 0) + " 萬", 0f, yy + 7f, paint)
        }
        // 年份
        val y0 = dateOf(t0).year; val y1 = dateOf(t1).year
        val yStep = if (y1 - y0 > 20) 10 else 5
        for (yr in ((y0 / yStep) + 1) * yStep..y1 step yStep) {
            val ms = LocalDate.of(yr, 1, 1).atStartOfDay(TPE).toInstant().toEpochMilli()
            drawContext.canvas.nativeCanvas.drawText(yr.toString(), x(ms) - 24f, size.height, paint)
        }
        // 大跌區間底色
        waves.forEach { wv ->
            val x0 = x(wv.peakT); val x1 = x(wv.recoverT ?: t1)
            drawRect(if (-wv.depth >= 20) red else gray, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(2f), h))
        }
        // 走勢
        val path = Path()
        val stride = (c.size / 900).coerceAtLeast(1)
        var first = true
        for (i in c.indices step stride) {
            val px = x(t[i]); val py = y(vals[i])
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
        }
        path.lineTo(x(t.last()), y(vals.last()))
        drawPath(path, line, style = Stroke(width = 3f))
        // 低點跌幅標籤
        val lp = android.graphics.Paint().apply { color = down.toArgb(); textSize = 22f; isAntiAlias = true; isFakeBoldText = true }
        waves.forEach { wv ->
            val v = amount * wv.trough / base
            drawContext.canvas.nativeCanvas.drawText(Fmt.num(wv.depth, 0) + "%", (x(wv.troughT) - 20f).coerceIn(left, size.width - 56f), (y(v) + 26f).coerceAtMost(h - 2f), lp)
        }
    }
}

/** 由報價取得 Yahoo 代號（上市 .TW、上櫃 .TWO、美股原代號） */
private object StockInfoRepoBridge {
    fun yahoo(q: com.claudeetf.app.data.Quote): String = com.claudeetf.app.data.StockInfoRepo.yahooSymbol(q)
}
