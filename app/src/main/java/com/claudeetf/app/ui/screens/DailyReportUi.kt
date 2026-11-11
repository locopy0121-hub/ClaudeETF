package com.claudeetf.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.DailyReport
import com.claudeetf.app.data.MarketData
import com.claudeetf.app.data.TwPicks
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.launch

private fun moodColor(m: Int): Color = when { m > 0 -> Trend.fg(1.0); m < 0 -> Trend.fg(-1.0); else -> Color(0xFFF2B544) }
private fun dateShort(d: String) = d.substring(5).replace("-", "/")

/** 首頁：每日盤後報表卡片 */
@Composable
fun DailyReportHomeCard(nav: NavController) {
    val scope = rememberCoroutineScope()
    val r = DailyReport.latest
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card).clickable { nav.navigate(Routes.DAILY_REPORT) }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("每日盤後報表", Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (r != null) Text(r.moodLabel, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(moodColor(r.mood)).padding(horizontal = 6.dp, vertical = 2.dp))
            HGap(6.dp); Text("›", color = C.Muted, fontSize = 18.sp)
        }
        when {
            DailyReport.generating -> Label("產生中：" + DailyReport.stage + (if (TwPicks.running && TwPicks.total > 0) " ${TwPicks.done}/${TwPicks.total}" else ""))
            r == null -> {
                Label("平日 14:45 收盤資料公布後自動產生；也可以現在產生。")
                Text("立即產生", color = C.Primary, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Bg)
                    .clickable { scope.launch { DailyReport.generate() } }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            else -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Label(dateShort(r.date) + " 加權 "); NumText("%,.2f".format(r.twii), 16.sp, Trend.fg(r.twiiChg), FontWeight.Bold)
                    HGap(6.dp); NumText("%+.2f".format(r.twiiPct) + "%", 13.sp, Trend.fg(r.twiiChg))
                }
                BreadthBar(r, 8.dp)
                Label("上漲 ${r.up} · 下跌 ${r.down} · 漲停 ${r.limitUp} · 跌停 ${r.limitDown} · 成交 " + "%,.0f".format(r.tseValue + r.otcValue) + " 億")
                if (r.bulls.isNotEmpty()) Text("看漲：" + r.bulls.take(5).joinToString("、") { it.name }, color = Trend.fg(1.0), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.bears.isNotEmpty()) Text("看跌：" + r.bears.take(5).joinToString("、") { it.name }, color = Trend.fg(-1.0), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BreadthBar(r: DailyReport.Report, h: androidx.compose.ui.unit.Dp) {
    val tot = (r.up + r.down + r.flat).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(4.dp))) {
        if (r.up > 0) Box(Modifier.weight(r.up.toFloat() / tot).fillMaxHeight().background(Trend.fg(1.0)))
        if (r.flat > 0) Box(Modifier.weight(r.flat.toFloat() / tot).fillMaxHeight().background(C.Muted))
        if (r.down > 0) Box(Modifier.weight(r.down.toFloat() / tot).fillMaxHeight().background(Trend.fg(-1.0)))
    }
}

/** 報表頁 */
@Composable
fun DailyReportScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var sel by remember { mutableStateOf(DailyReport.dates.firstOrNull()) }
    val r = remember(sel, DailyReport.latest) { sel?.let { DailyReport.load(it) } ?: DailyReport.latest }
    var pickSide by remember { mutableIntStateOf(0) }
    var moverTab by remember { mutableIntStateOf(0) }
    var showAll by remember { mutableStateOf(false) }
    var autoOn by remember { mutableStateOf(DailyReport.autoOn) }
    var notifyOn by remember { mutableStateOf(DailyReport.notifyOn) }
    var exporting by remember { mutableStateOf<String?>(null) }
    fun open(sym: String) = scope.launch {
        if (!AppState.isWatched(sym)) MarketData.addAndFetch(sym)
        nav.navigate(Routes.stock(sym))
    }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("每日盤後報表", onBack = { nav.popBackStack() }) {
            if (r != null) Box {
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Share, contentDescription = "輸出與分享", tint = C.Text) }
                fun export(pdf: Boolean, save: Boolean) {
                    menu = false
                    scope.launch {
                        exporting = if (pdf) "產生 PDF…" else "產生圖片…"
                        val f = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.claudeetf.app.util.ReportExport.file(ctx, r, pdf) } }
                            .onFailure { exporting = "輸出失敗：" + (it.message ?: "") }.getOrNull()
                        if (f != null) {
                            if (save) {
                                val path = runCatching { com.claudeetf.app.util.ReportExport.saveToDownloads(ctx, f) }.getOrNull()
                                exporting = path?.let { "已儲存到 $it" } ?: "此 Android 版本無法直接存到下載，請改用分享"
                            } else { exporting = null; com.claudeetf.app.util.ReportExport.share(ctx, f) }
                        }
                    }
                }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = C.Card) {
                    listOf(
                        "分享圖片（PNG 長圖）" to { export(false, false) },
                        "分享 PDF（A4 分頁）" to { export(true, false) },
                        "儲存圖片到手機" to { export(false, true) },
                        "儲存 PDF 到手機" to { export(true, true) },
                        "分享文字摘要" to {
                            menu = false
                            shareText(ctx, "台股盤後報表 " + r.date.replace("-", "/") + "\n" + r.summary.joinToString("\n") +
                                "\n\n看漲 25：" + r.bulls.joinToString("、") { it.symbol + " " + it.name } +
                                "\n看跌 25：" + r.bears.joinToString("、") { it.symbol + " " + it.name } + "\n（僅供參考，非投資建議）", "分享盤後報表")
                        },
                    ).forEach { (t, f) ->
                        androidx.compose.material3.DropdownMenuItem(text = { Text(t, color = C.Text, fontSize = 14.sp) }, onClick = f)
                    }
                }
            }
            IconButton(onClick = { scope.launch { DailyReport.generate(force = true); sel = DailyReport.dates.firstOrNull() } }, enabled = !DailyReport.generating) {
                Icon(Icons.Filled.Refresh, contentDescription = "重新產生", tint = if (DailyReport.generating) C.Muted else C.Text)
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---- 日期（歷史報表）
            if (DailyReport.dates.isNotEmpty()) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DailyReport.dates.forEach { d ->
                        val on = d == (sel ?: r?.date)
                        Text(dateShort(d), color = if (on) C.OnPrimary else C.Text2, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                            .background(if (on) C.Primary else C.Card).clickable { sel = d }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
            }
            if (DailyReport.generating) item { Card(padding = 12.dp) {
                Label("產生中：" + DailyReport.stage + (if (TwPicks.running && TwPicks.total > 0) " ${TwPicks.done}/${TwPicks.total}" else ""))
                if (TwPicks.running && TwPicks.total > 0) Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Selected)) {
                    Box(Modifier.fillMaxWidth(TwPicks.done.toFloat() / TwPicks.total).fillMaxHeight().background(C.Primary))
                }
            } }
            DailyReport.error?.let { e -> item { Text(e, color = C.Red, fontSize = 13.sp) } }
            exporting?.let { m -> item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m, Modifier.weight(1f), color = C.Text2, fontSize = 13.sp)
                    Text("✕", color = C.Muted, fontSize = 14.sp, modifier = Modifier.clickable { exporting = null }.padding(4.dp))
                }
            } }
            if (r == null) {
                item {
                    Card(padding = 14.dp) {
                        Text("還沒有報表", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Label("平日 14:45 收盤資料公布後自動產生並儲存；按右上角 ↻ 可立即產生（約 1～2 分鐘）。")
                    }
                }
            } else {
                // ---- 總結
                item {
                    Card(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("市場總結 " + r.date.replace("-", "/"), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Label("產生於 " + Fmt.time(r.at) + " · 分析 ${r.analyzed} 檔")
                            }
                            Text(r.moodLabel, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(moodColor(r.mood)).padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                        r.summary.forEach { Text("• $it", color = C.Text2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp)) }
                    }
                }
                // ---- 指數走勢
                item {
                    Card(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("加權指數", Modifier.weight(1f), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            NumText("%,.2f".format(r.twii), 18.sp, Trend.fg(r.twiiChg), FontWeight.Bold); HGap(6.dp)
                            NumText("%+,.2f".format(r.twiiChg) + "（" + "%+.2f".format(r.twiiPct) + "%）", 12.sp, Trend.fg(r.twiiChg))
                        }
                        r.otcIdx?.let { Label("櫃買指數 " + "%,.2f".format(it) + "（" + "%+.2f".format(r.otcPct ?: 0.0) + "%）") }
                        if (r.twiiSeries.size >= 2) LineChart(r.twiiSeries, Trend.fg(r.twiiSeries.last() - r.twiiSeries.first()), Modifier.fillMaxWidth().height(140.dp).padding(top = 8.dp))
                        Label("近 ${r.twiiSeries.size} 個交易日收盤（虛線：月線）")
                    }
                }
                // ---- 漲跌家數與分布
                item {
                    Card(padding = 14.dp) {
                        Text("漲跌家數", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        BreadthBar(r, 14.dp)
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text("上漲 ${r.up}", Modifier.weight(1f), color = Trend.fg(1.0), fontSize = 12.sp)
                            Text("平盤 ${r.flat}", Modifier.weight(1f), color = C.Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Text("下跌 ${r.down}", Modifier.weight(1f), color = Trend.fg(-1.0), fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LimitTag(1); HGap(4.dp); Text("${r.limitUp} 家", color = C.Text, fontSize = 13.sp); HGap(14.dp)
                            LimitTag(-1); HGap(4.dp); Text("${r.limitDown} 家", color = C.Text, fontSize = 13.sp); HGap(14.dp)
                            Label("成交 上市 " + "%,.0f".format(r.tseValue) + " 億 · 上櫃 " + "%,.0f".format(r.otcValue) + " 億")
                        }
                        Text("漲跌幅分布（個股與 ETF，%）", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                        Histogram(r.hist, Modifier.fillMaxWidth().height(120.dp).padding(top = 6.dp))
                        Row(Modifier.fillMaxWidth()) { DailyReport.HIST_LABELS.forEach { Text(it, Modifier.weight(1f), color = C.Muted, fontSize = 9.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1) } }
                    }
                }
                // ---- 類股
                if (r.sectors.isNotEmpty()) item {
                    Card(padding = 14.dp) {
                        Text("類股漲跌", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        val list = if (showAll || r.sectors.size <= 16) r.sectors else r.sectors.take(8) + r.sectors.takeLast(8)
                        val maxAbs = r.sectors.maxOf { kotlin.math.abs(it.second) }.takeIf { it > 0 } ?: 1.0
                        list.forEach { (n, p) -> CenterBar(n, p, maxAbs, "%+.2f".format(p) + "%") }
                        if (r.sectors.size > 16) Text(if (showAll) "只看前後 8 名" else "顯示全部 ${r.sectors.size} 類", color = C.Primary, fontSize = 12.sp,
                            modifier = Modifier.clickable { showAll = !showAll }.padding(vertical = 6.dp))
                    }
                }
                // ---- 排行
                item {
                    Card(padding = 14.dp) {
                        Text("個股排行（成交 ≥ 500 張）", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Segmented(listOf("漲幅", "跌幅", "成交值"), moverTab, { moverTab = it })
                        (when (moverTab) { 0 -> r.gainers; 1 -> r.losers; else -> r.actives }).forEachIndexed { i, m ->
                            Row(Modifier.fillMaxWidth().clickable { open(m.symbol) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", Modifier.width(22.dp), color = C.Muted, fontSize = 12.sp)
                                Column(Modifier.weight(1f)) {
                                    Text(m.name, color = C.Text, fontSize = 14.sp, maxLines = 1)
                                    Label(m.symbol + " · " + (if (m.otc) "櫃" else "市") + " · " + "%,.1f".format(m.valueYi) + " 億")
                                }
                                val lim = if (kotlin.math.abs(m.pct) >= 9) limitDir(m.price, m.price / (1 + m.pct / 100), m.symbol) else 0
                                if (lim != 0) { LimitTag(lim, 10.sp); HGap(4.dp) }
                                Column(horizontalAlignment = Alignment.End) { NumText(Fmt.price(m.price), 13.sp); ChangePill(m.pct) }
                            }
                        }
                    }
                }
                // ---- 看漲／看跌 25
                item {
                    Card(padding = 14.dp) {
                        Text("綜合分析排行（各 ${DailyReport.TOP} 檔）", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Label("依日 K 形態與量價、60 日均線、20 日動能、進場等級與一年股價光譜綜合評分；上市取成交值前 ${TwPicks.UNIVERSE_TSE} 檔、上櫃前 ${TwPicks.UNIVERSE_OTC} 檔。")
                        Segmented(listOf("看漲・趨勢向上 ${r.bulls.size}", "看跌・避開 ${r.bears.size}"), pickSide, { pickSide = it })
                        val list = if (pickSide == 0) r.bulls else r.bears
                        if (list.isEmpty()) Label("沒有符合條件的個股。", Modifier.padding(top = 8.dp))
                        else {
                            ScoreBars(list, pickSide == 0, Modifier.fillMaxWidth().padding(top = 8.dp))
                            // 分布摘要
                            val otcN = list.count { it.otc }
                            Label("上市 ${list.size - otcN} 檔、上櫃 $otcN 檔 · 平均近 20 日 " + "%+.1f".format(list.map { it.mom20 }.average()) + "%")
                            list.forEachIndexed { i, p -> ReportPickRow(i + 1, p, pickSide == 0) { open(p.symbol) } }
                        }
                    }
                }
            }
            // ---- 設定
            item {
                Card(padding = 14.dp) {
                    Text("報表設定", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    OptToggle("盤後自動產生（平日 14:45 後）", autoOn) { autoOn = it; DailyReport.autoOn = it }
                    OptToggle("產生後通知", notifyOn) { notifyOn = it; DailyReport.notifyOn = it }
                    Label("報表每天存一份，保留最近 120 個交易日；App 在背景時由監控排程產生（需開啟通知與背景執行）。資料來源：證交所、櫃買中心收盤行情與類股指數、Yahoo 指數走勢。僅供參考，非投資建議。")
                }
            }
        }
    }
}

@Composable
private fun ReportPickRow(rank: Int, p: TwPicks.Pick, bull: Boolean, onClick: () -> Unit) {
    val tone = Trend.fg(if (bull) 1.0 else -1.0)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(rank.toString(), color = if (rank <= 3) Color.White else tone, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(if (rank <= 3) tone else tone.copy(alpha = 0.15f)).wrapContentSize(Alignment.Center))
        HGap(8.dp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                HGap(4.dp); NumText(p.symbol, 11.sp, C.Muted); HGap(4.dp); Label(if (p.otc) "櫃" else "市")
                if (p.zone >= 0) { HGap(4.dp); Text(com.claudeetf.app.data.PriceBand.zoneNames[p.zone], color = zoneColor(p.zone), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
            }
            // 完整解析：分析理由、建議、操作參考、策略訊號（不截斷）
            Text(com.claudeetf.app.util.ReportExport.pickDetail(p), color = C.Text2, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
        HGap(6.dp)
        Column(horizontalAlignment = Alignment.End) {
            NumText(Fmt.price(p.price), 13.sp)
            ChangePill(p.pct)
            Text("%+.1f".format(p.score), color = tone, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 指數折線（含月線虛線） */
@Composable
private fun LineChart(v: List<Double>, color: Color, modifier: Modifier) {
    val grid = C.Border
    Canvas(modifier) {
        val mn = v.min(); val mx = v.max(); val rg = (mx - mn).takeIf { it > 0 } ?: 1.0
        fun x(i: Int) = i * size.width / (v.size - 1)
        fun y(d: Double) = (size.height * (1 - (d - mn) / rg)).toFloat()
        for (k in 0..3) { val yy = size.height * k / 3; drawLine(grid, Offset(0f, yy), Offset(size.width, yy), 1f) }
        val p = Path(); v.forEachIndexed { i, d -> if (i == 0) p.moveTo(x(i), y(d)) else p.lineTo(x(i), y(d)) }
        val fill = Path().apply { addPath(p); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, color.copy(alpha = 0.12f))
        drawPath(p, color, style = Stroke(width = 2.dp.toPx()))
        if (v.size >= 20) {
            val ma = Path()
            for (i in 19 until v.size) { val m = v.subList(i - 19, i + 1).average(); if (i == 19) ma.moveTo(x(i), y(m)) else ma.lineTo(x(i), y(m)) }
            drawPath(ma, Color(0xFFF2B544), style = Stroke(width = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        }
        drawCircle(color, 3.dp.toPx(), Offset(x(v.size - 1), y(v.last())))
    }
}

/** 漲跌幅分布長條 */
@Composable
private fun Histogram(h: List<Int>, modifier: Modifier) {
    val up = Trend.fg(1.0); val dn = Trend.fg(-1.0); val flat = C.Muted; val txt = C.Text2
    Canvas(modifier) {
        val mx = (h.maxOrNull() ?: 1).coerceAtLeast(1)
        val w = size.width / h.size
        h.forEachIndexed { i, n ->
            val bh = (size.height - 14.dp.toPx()) * n / mx
            val c = when { i < 5 -> dn; i == 5 -> flat; else -> up }.copy(alpha = if (i == 0 || i == 10) 1f else 0.75f)
            drawRect(c, Offset(i * w + w * 0.12f, size.height - bh), Size(w * 0.76f, bh))
            drawContext.canvas.nativeCanvas.drawText(n.toString(), i * w + w / 2, size.height - bh - 3.dp.toPx(),
                android.graphics.Paint().apply { color = txt.toArgb()
                    textSize = 9.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true })
        }
    }
}

/** 置中長條（類股） */
@Composable
private fun CenterBar(name: String, v: Double, maxAbs: Double, text: String) {
    val c = Trend.fg(v)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.width(84.dp), color = C.Text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BoxWithConstraints(Modifier.weight(1f).height(10.dp)) {
            val half = maxWidth / 2
            val len = half * (kotlin.math.abs(v) / maxAbs).toFloat().coerceIn(0f, 1f)
            Box(Modifier.offset(x = if (v >= 0) half else half - len).width(len).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c))
            Box(Modifier.offset(x = half).width(1.dp).fillMaxHeight().background(C.Border))
        }
        Text(text, Modifier.width(58.dp), color = c, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** 看漲／看跌分數長條圖 */
@Composable
private fun ScoreBars(list: List<TwPicks.Pick>, bull: Boolean, modifier: Modifier) {
    val c = Trend.fg(if (bull) 1.0 else -1.0)
    val mx = list.maxOf { kotlin.math.abs(it.score) }.takeIf { it > 0 } ?: 1.0
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        list.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.width(72.dp), color = C.Text2, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f).height(8.dp)) {
                    Box(Modifier.fillMaxWidth((kotlin.math.abs(p.score) / mx).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(c.copy(alpha = 0.85f)))
                }
                Text("%+.1f".format(p.score), Modifier.width(36.dp), color = c, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}
