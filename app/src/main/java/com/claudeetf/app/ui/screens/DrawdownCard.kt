package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.claudeetf.app.data.Drawdown
import com.claudeetf.app.data.Quote
import com.claudeetf.app.data.StockInfoRepo
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.data.remote.friendly
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.KeyValue
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** 全期間日 K（掛牌至今），同一檔 6 小時內重用 */
private object DrawdownData {
    private val cache = HashMap<String, Pair<Long, YahooExtra.History>>()
    suspend fun load(q: Quote): YahooExtra.History = withContext(Dispatchers.IO) {
        val sym = StockInfoRepo.yahooSymbol(q) + "|d"
        synchronized(cache) { cache[sym]?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }?.let { return@withContext it.second } }
        val h = YahooExtra.history(sym.removeSuffix("|d"), "max", intervalOverride = "1d")
        synchronized(cache) { cache[sym] = System.currentTimeMillis() to h }
        h
    }
}

private fun ymd(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.of("Asia/Taipei")).let { "${it.year}/${it.monthValue}/${it.dayOfMonth}" }

private fun yearsText(ms: Long) = Fmt.num(ms / (365.25 * 86_400_000.0), 1) + " 年"

private fun freqText(years: Double?, total: Double): String = when {
    years == null -> "—"
    years >= total * 0.95 -> Fmt.num(total, 1) + " 年僅 1 次"
    years < 1 -> "約每 " + Fmt.num(years * 12, 0) + " 個月"
    else -> "約每 " + Fmt.num(years, 1) + " 年"
}

/** 事件標籤顏色：依跌幅深淺 */
private fun chipColor(depth: Double): Color = when {
    depth <= -60 -> Color(0xFFD9822B)
    depth <= -45 -> Color(0xFF5B8DEF)
    depth <= -30 -> Color(0xFF2FA37A)
    else -> C.Muted
}

@Composable
private fun EventChip(w: Drawdown.Wave) {
    val c = chipColor(w.depth)
    Text(Drawdown.eventName(w) + " " + Fmt.num(w.depth, 1) + "%", color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

/** 波段最深跌幅統計：前波高點 → 其後低點，回到前高為一波 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DrawdownCard(q0: Quote) {
    val q = AppState.quote(q0.symbol) ?: q0
    var withDiv by remember { mutableStateOf(false) }
    var hist by remember(q.symbol) { mutableStateOf<YahooExtra.History?>(null) }
    var err by remember(q.symbol) { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(q.symbol) {
        runCatching { DrawdownData.load(q) }.onSuccess { hist = it }.onFailure { err = it.friendly() }
    }
    val stats = remember(hist, withDiv) {
        hist?.let { h ->
            val closes = if (withDiv && h.adjCloses.size == h.closes.size) h.adjCloses else h.closes
            Drawdown.stats(h.times, closes.map { it.toDouble() })
        }
    }

    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("波段最深跌幅統計", Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            listOf(false to "未含息", true to "含息").forEach { (v, n) ->
                val on = v == withDiv
                Text(n, color = if (on) C.Text else C.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(12.dp)).background(if (on) C.Selected else C.Bg)
                        .clickable { withDiv = v }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        val s = stats
        when {
            err != null && hist == null -> { Label("無法取得歷史資料：$err"); return@Card }
            hist == null -> { Label("載入掛牌以來日 K…"); return@Card }
            s == null -> { Label("歷史資料不足"); return@Card }
        }
        s!!
        Label("收盤價計算「前波高點 → 其後低點」｜${ymd(s.firstT)} 至 ${ymd(s.lastT)}，約 ${Fmt.num(s.years, 1)} 年 · ${hist!!.times.size} 個交易日")
        // 平均每年少於 200 根代表不是日 K（週／月線會把波段算淺、次數變少）
        if (hist!!.times.size < s.years * 200) Label("⚠ 取得的不是完整日 K（約每年 ${Fmt.num(hist!!.times.size / s.years.coerceAtLeast(0.1), 0)} 根），跌幅與次數可能偏少，請稍後下拉重新整理", color = C.Red)

        // 目前狀態
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label("目前距歷史高點")
                NumText(if (s.fromHigh > -0.005) "創新高中" else Fmt.num(s.fromHigh, 2) + "%", 18.sp,
                    if (s.fromHigh > -0.005) C.Primary else Trend.fg(-1.0), FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Label("歷史高點 " + ymd(s.highT))
                NumText(Fmt.price(s.high), 14.sp)
            }
        }

        // 門檻表
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Label("波段跌幅", Modifier.width(64.dp)); Label("次數", Modifier.width(44.dp)); Label("平均頻率", Modifier.width(88.dp)); Label("對應事件")
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(C.Border))
        s.buckets.filter { showAll || it.waves.isNotEmpty() || it.threshold <= 30 }.forEach { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Text("≥ ${b.threshold}%", Modifier.width(64.dp), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Row(Modifier.width(44.dp), verticalAlignment = Alignment.Bottom) {
                    NumText(b.waves.size.toString(), 16.sp, if (b.waves.isEmpty()) C.Muted else C.Primary, FontWeight.Bold)
                    Text(" 波", color = C.Muted, fontSize = 10.sp)
                }
                Text(freqText(b.everyYears, s.years), Modifier.width(88.dp), color = C.Text, fontSize = 12.sp, maxLines = 2)
                // ≥ 30% 列出各波；較淺的門檻只列最深的 3 波，並標示進行中的
                val show = if (b.threshold >= 30) b.waves else b.waves.take(3)
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (b.waves.isEmpty()) Label("—")
                    show.forEach { EventChip(it) }
                    if (show.size < b.waves.size) Label("＋${b.waves.size - show.size} 波", Modifier.padding(top = 3.dp))
                }
            }
        }
        if (s.buckets.any { it.waves.isEmpty() && it.threshold > 30 }) Text(if (showAll) "收合" else "顯示全部門檻", color = C.Primary, fontSize = 12.sp,
            modifier = Modifier.clickable { showAll = !showAll }.padding(vertical = 4.dp))
        s.waves.firstOrNull { it.ongoing && it.depth <= -10 }?.let { w ->
            Label("含進行中：${ymd(w.peakT)} 起尚未回到前高的 ${Fmt.num(w.depth, 1)}%", color = Trend.fg(-1.0))
        }

        // 最深的 3 波
        val top = s.waves.sortedBy { it.depth }.take(3)
        if (top.isNotEmpty()) {
            Text("最深的 ${top.size} 波", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            top.forEach { w ->
                val c = chipColor(w.depth).takeIf { it != C.Muted } ?: Trend.fg(-1.0)
                Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        EventChip(w)
                        Spacer(Modifier.weight(1f))
                        NumText(Fmt.num(w.depth, 2) + "%", 20.sp, c, FontWeight.Bold)
                    }
                    Label(ymd(w.peakT) + " → " + ymd(w.troughT) + "（下跌 " + yearsText(w.troughT - w.peakT) + "）", Modifier.padding(top = 4.dp))
                    Label(w.recoverT?.let { "回到前高：" + ymd(it) + "（自高點共 " + yearsText(it - w.peakT) + "）" } ?: "尚未回到前高",
                        color = if (w.recoverT == null) Trend.fg(-1.0) else C.Muted)
                }
            }
        }
        // 跌深進場回測
        val dips = remember(stats) { hist?.let { h ->
            val closes = if (withDiv && h.adjCloses.size == h.closes.size) h.adjCloses else h.closes
            Drawdown.dipBacktest(h.times, closes.map { it.toDouble() })
        } ?: emptyList() }
        if (dips.isNotEmpty()) {
            Text("跌深進場回測", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            Label("每一波自前高跌到門檻的第一天以收盤價買進，之後的表現（" + (if (withDiv) "含息" else "未含息") + "）：")
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Label("跌幅", Modifier.width(52.dp)); Label("次數", Modifier.width(36.dp)); Label("1 年後", Modifier.weight(1f)); Label("3 年後", Modifier.weight(1f)); Label("回前高", Modifier.width(64.dp))
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(C.Border))
            dips.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Text("≥ ${d.threshold}%", Modifier.width(52.dp), color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    NumText(d.count.toString(), 14.sp, C.Primary, FontWeight.Bold, modifier = Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        if (d.avg1y == null) Label("樣本不足") else {
                            NumText("%+.1f".format(d.avg1y) + "%", 13.sp, Trend.fg(d.avg1y), FontWeight.Bold)
                            Label("勝率 " + Fmt.num(d.win1y ?: 0.0, 0) + "%（${d.n1y}）")
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        if (d.avg3y == null) Label("樣本不足") else {
                            NumText("%+.1f".format(d.avg3y) + "%", 13.sp, Trend.fg(d.avg3y), FontWeight.Bold)
                            Label("勝率 " + Fmt.num(d.win3y ?: 0.0, 0) + "%（${d.n3y}）")
                        }
                    }
                    Column(Modifier.width(64.dp)) {
                        Text(d.avgRecoverYears?.let { if (it < 1) Fmt.num(it * 12, 0) + " 個月" else Fmt.num(it, 1) + " 年" } ?: "—", color = C.Text, fontSize = 12.sp)
                        Label("${d.recovered}/${d.count} 已回")
                    }
                }
                d.avgFurther?.let { Label("　進場後平均再跌 " + Fmt.num(it, 1) + "%", Modifier.padding(start = 52.dp)) }
            }
            s.waves.firstOrNull { it.ongoing }?.let { w ->
                Drawdown.deeperShare(s.waves, w.depth)?.let { pct ->
                    Text("目前自前高 " + Fmt.num(w.depth, 1) + "%：歷史上 " + Fmt.num(pct, 0) + "% 的波段跌得比現在更深",
                        color = Trend.fg(-1.0), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Label("勝率＝持有期滿仍為正報酬的比例，括號為樣本數；最近的事件尚未滿 1／3 年不計入。歷史回測不代表未來表現。", Modifier.padding(top = 4.dp))
        }
        Label("波段＝自前波高點跌至低點、回到前高為一波；長空頭中若自低點翻倍後又下跌 20% 以上，另計為新的一波（如網路泡沫與 2008 金融海嘯分開）。資料來源 Yahoo Finance（已還原分割）；歷史統計不代表未來表現。",
            Modifier.padding(top = 10.dp))
    }
}
