package com.claudeetf.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.KlineAnalysis
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.Quote
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.KeyValue
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun entryColor(level: Int): Color = when {
    level > 0 -> Trend.up
    level < 0 -> Trend.down
    else -> Trend.flat
}

/** 進場建議標籤；可進場（level > 0）時依設定播放閃爍、跳動與光暈動畫 */
@Composable
fun EntryTag(e: KlineAnalysis.Entry, big: Boolean = false, animLevel: Int = AppState.entryAnim) {
    val c = entryColor(e.level)
    val icon = when (e.level) { 2 -> "✔ "; 1 -> "◐ "; 0 -> "⏸ "; -1 -> "✋ "; else -> "⛔ " }
    val anim = if (e.level > 0) animLevel else 0
    val strong = anim == 2 && e.level >= 2
    val shape = RoundedCornerShape(if (big) 10.dp else 6.dp)
    var mod: Modifier = Modifier
    if (anim > 0) {
        val tr = rememberInfiniteTransition(label = "entry")
        val period = if (strong) 700 else 1100
        // 0→1→0 循環。只在 graphicsLayer／drawBehind（繪製階段）讀取動畫值，
        // 不觸發重組：清單很多列同時播放也只重畫、不重算版面
        val pS = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p")
        val ringS = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(period * 2, easing = LinearEasing), RepeatMode.Restart), label = "ring")
        val bounce = if (strong) 0.10f else if (anim == 2) 0.06f else 0.03f
        val glow = if (anim == 2) 1f else 0.5f
        val lift = if (strong) 2.5f else 1.2f
        val baseA = if (big) 0.65f else 0.16f
        val pulseA = if (big) 0.35f else if (strong) 0.45f else 0.25f
        Text(icon + e.label, color = if (big) Color.White else c, fontSize = if (big) 15.sp else 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier
                .graphicsLayer { val p = pS.value; val sc = 1f + bounce * p; scaleX = sc; scaleY = sc; translationY = -lift * p * density }
                .drawBehind {
                    val p = pS.value
                    val ring = ringS.value
                    val r = (if (big) 10.dp else 6.dp).toPx()
                    // 內層光暈（呼吸）
                    for (i in 1..3) {
                        val g = i * 2.5f * density * (0.6f + 0.4f * p)
                        drawRoundRect(c.copy(alpha = glow * 0.22f * p / i), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g),
                            androidx.compose.ui.geometry.CornerRadius(r + g))
                    }
                    // 外擴光環
                    val e2 = ring * 8f * density
                    drawRoundRect(c.copy(alpha = glow * 0.7f * (1f - ring)), Offset(-e2, -e2), Size(size.width + 2 * e2, size.height + 2 * e2),
                        androidx.compose.ui.geometry.CornerRadius(r + e2), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * density))
                    // 閃爍的底色
                    drawRoundRect(c.copy(alpha = (baseA + pulseA * p).coerceAtMost(1f) * (if (strong && !big) 0.85f else 1f)),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                }
                .padding(horizontal = if (big) 12.dp else 7.dp, vertical = if (big) 6.dp else 2.dp))
        return
    }
    Text(icon + e.label, color = if (big) Color.White else c, fontSize = if (big) 15.sp else 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(shape).background(if (big) c.copy(alpha = 0.9f) else c.copy(alpha = 0.16f))
            .padding(horizontal = if (big) 12.dp else 7.dp, vertical = if (big) 6.dp else 2.dp))
}

/**
 * 清單／卡片用的進場建議標籤：背景載入該股日 K 分析（共用快取，報價變動即重算），
 * 尚未載入或設定關閉時不顯示。holder = true 時在標籤後附上持有者建議。
 */
@Composable
fun EntryTagFor(q: Quote, holder: Boolean = false, modifier: Modifier = Modifier) {
    // 自選行情用自己的開關；持股卡片由 HoldStyle 控制（呼叫端決定是否顯示）
    if (!holder && !AppState.showCardEntry) return
    val r = adviceFor(q) ?: return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        EntryTag(r.entry, animLevel = if (holder) AppState.holdStyle.entryAnim else AppState.entryAnim)
        if (holder) Text(" " + r.entry.holderTip, color = C.Muted, fontSize = 10.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/**
 * 綜合進場建議（K 線、投資分析各因子、光譜、分析師、籌碼、波動、K 棒條件、三關價、營收、融資）：
 * 背景載入並共用快取；價格 1.5 秒內連續變動時只算最後一次。
 */
@Composable
fun adviceFor(q: Quote, full: Boolean = false): com.claudeetf.app.data.EntryAdvisor.Advice? {
    if (q.price <= 0) return com.claudeetf.app.data.EntryAdvisor.results[q.symbol]
    LaunchedEffect(q.symbol, q.price, full) {
        if (com.claudeetf.app.data.EntryAdvisor.results[q.symbol] != null) kotlinx.coroutines.delay(1_500)
        runCatching { com.claudeetf.app.data.EntryAdvisor.load(q, full) }
    }
    return com.claudeetf.app.data.EntryAdvisor.results[q.symbol]
}

/** 取得（並在背景更新）此檔的日 K 分析結果 */
@Composable
fun entryResultFor(q: Quote): KlineAnalysis.Result? {
    if (q.price <= 0) return null
    // 盤中逐筆跳動時去抖動：價格 1.5 秒內再變就取消上一次，避免每一筆成交都對整份 K 線重跑形態分析
    LaunchedEffect(q.symbol, q.price) {
        if (KlineAnalysis.latestDaily[q.symbol] != null) kotlinx.coroutines.delay(1_500)
        runCatching { KlineAnalysis.load(q, false) }
    }
    return KlineAnalysis.latestDaily[q.symbol]
}

private fun dirColor(d: KlineAnalysis.Dir): Color = when {
    d.bullish -> Trend.up
    d.bearish -> Trend.down
    else -> Trend.flat
}

/** K 線形態分析：方向與強度、量價位置、近期形態（含此股歷史勝率）、操作計畫 */
@Composable
fun KlineAnalysisCard(q0: Quote) {
    val q = AppState.quote(q0.symbol) ?: q0
    var weekly by remember { mutableStateOf(false) }
    var res by remember(q.symbol, weekly) { mutableStateOf<KlineAnalysis.Result?>(null) }
    var state by remember(q.symbol, weekly) { mutableStateOf("分析中…") }
    LaunchedEffect(q.symbol, weekly) {
        val r = runCatching { KlineAnalysis.load(q, weekly) }
        res = r.getOrNull()
        state = if (r.isFailure) "無法取得 K 線資料" else "K 線資料不足（至少 40 根）"
    }
    val zone = ZoneId.of(if (q.market == Market.TW) "Asia/Taipei" else "America/New_York")
    fun date(t: Long) = Instant.ofEpochMilli(t).atZone(zone).format(DateTimeFormatter.ofPattern(if (weekly) "yyyy/M/d 當週" else "yyyy/M/d"))

    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("K 線形態分析", Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            listOf(false to "日 K", true to "週 K").forEach { (w, n) ->
                val on = w == weekly
                Text(n, color = if (on) C.Text else C.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(12.dp)).background(if (on) C.Selected else C.Bg)
                        .clickable { weekly = w }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        val r = res
        if (r == null) { Label(state, Modifier.padding(top = 8.dp)); return@Card }
        val dc = dirColor(r.dir)

        // ---- 結論與訊號強度 ----
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(r.timeframe + "綜合訊號")
                Text(r.dir.label, color = dc, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Label("多空強度（50 = 中性）")
                NumText(r.strength.toString(), 22.sp, dc, FontWeight.Bold)
            }
        }
        // ---- 是否進場 ----
        val held = AppState.holdings.any { it.symbol == q.symbol && it.shares > 0 }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(entryColor(r.entry.level).copy(alpha = 0.10f)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Label("K 線觀點（綜合建議見「分析」分頁）")
                Text(r.entry.reason, color = C.Text2, fontSize = 12.sp, lineHeight = 17.sp)
                if (held) Text("已持有：" + r.entry.holderTip, color = C.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
            }
            EntryTag(r.entry, big = true)
        }
        // 強度條：左空右多
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Bg)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(r.strength / 100f).clip(RoundedCornerShape(4.dp)).background(dc.copy(alpha = 0.85f)))
        }
        Row(Modifier.fillMaxWidth()) {
            Label("偏空", Modifier.weight(1f)); Label("中性", Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally))
            Label("偏多", Modifier.weight(1f).wrapContentWidth(Alignment.End))
        }

        // ---- 量價與位置 ----
        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip2(r.ctx.trendLabel, when (r.ctx.trend) { 1 -> Trend.up; -1 -> Trend.down; else -> Trend.flat })
            Chip2(r.ctx.vol.label, when (r.ctx.vol) {
                KlineAnalysis.Vol.CONFIRMED -> C.Primary
                KlineAnalysis.Vol.DIVERGENCE_TOP -> Trend.down
                KlineAnalysis.Vol.DIVERGENCE_BOTTOM -> Trend.up
                else -> C.Text2
            })
            Chip2(r.ctx.loc.label, when (r.ctx.loc) {
                KlineAnalysis.Loc.SUPPORT, KlineAnalysis.Loc.ABOVE -> Trend.up
                KlineAnalysis.Loc.RESISTANCE, KlineAnalysis.Loc.BELOW -> Trend.down
                else -> C.Text2
            })
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Column(Modifier.weight(1f)) { Label("支撐"); NumText(Fmt.price(r.ctx.support), 13.sp, Trend.up) }
            Column(Modifier.weight(1f)) { Label("壓力"); NumText(Fmt.price(r.ctx.resistance), 13.sp, Trend.down) }
            Column(Modifier.weight(1f)) { Label("MA20"); NumText(Fmt.price(r.ctx.ma20), 13.sp) }
            Column(Modifier.weight(1f)) { Label("ATR(14)"); NumText(Fmt.price(r.ctx.atr), 13.sp) }
        }

        // ---- 近 40 根 K 線與形態標記 ----
        PatternChart(r, Modifier.fillMaxWidth().height(170.dp).padding(top = 10.dp))
        Row(Modifier.fillMaxWidth()) {
            Label("▲ 偏多　▼ 偏空（可信度 ≥ 72%）", Modifier.weight(1f))
            Label("近 40 根")
        }

        // ---- 最新形態 ----
        Text("最新形態（近 3 根）", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        if (r.latest.isEmpty()) Label("近 3 根沒有明顯的 K 線形態，以趨勢與量價為主。")
        r.latest.distinctBy { it.pat to it.idx }.forEach { h -> HitRow(h, r, date(r.candles[h.idx].t)) }

        // ---- 理由 ----
        Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(10.dp)) {
            Text("判斷依據", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            r.reasons.forEach { Text("・$it", color = C.Text2, fontSize = 12.sp, lineHeight = 17.sp) }
        }

        // ---- K 棒分析條件（設置 › K 棒分析條件）----
        if (!weekly) KCondBox(q, r)

        // ---- 進場訊號提醒 ----
        val hasAlert = AppState.alerts.any { it.type == com.claudeetf.app.data.AlertType.ENTRY_SIGNAL && it.enabled && (it.symbol == q.symbol || it.symbol.isBlank()) }
        Text(
            if (hasAlert) "✓ 已設進場訊號變化提醒" else "＋ 進場建議改變時通知我",
            color = if (hasAlert) C.Muted else C.Primary, fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp).clickable(enabled = !hasAlert) {
                AppState.addAlert(q.symbol, com.claudeetf.app.data.AlertType.ENTRY_SIGNAL, 0.0)
            }.padding(vertical = 4.dp),
        )

        // ---- 操作計畫 ----
        r.plan?.let { p -> PlanBox(p, dc) } ?: Label("訊號中性：建議觀望，等待帶量突破壓力或跌破支撐再決定方向。", Modifier.padding(top = 8.dp))

        // ---- 近期全部形態 ----
        var more by remember { mutableStateOf(false) }
        val older = r.recent.filter { it.idx < r.candles.size - 3 }.sortedByDescending { it.idx }
        if (older.isNotEmpty()) {
            Text(if (more) "收合近期形態 ▲" else "近 40 根其他形態（${older.size}）▼", color = C.Primary, fontSize = 12.sp,
                modifier = Modifier.clickable { more = !more }.padding(vertical = 8.dp))
            if (more) older.forEach { h -> HitRow(h, r, date(r.candles[h.idx].t)) }
        }
        Label("形態辨識依據 K 棒實體、影線、前趨勢、量能與前 20 根高低點；勝率為此股過去 2 年（週 K 為 5 年）同形態出現後 ${r.forward} 根的結果。僅供參考，不構成投資建議。",
            Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Chip2(text: String, color: Color) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun HitRow(h: KlineAnalysis.Hit, r: KlineAnalysis.Result, dateText: String) {
    val c = dirColor(h.pat.dir)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(h.pat.cn, color = c, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip2(h.pat.cat.label, C.Text2)
            Spacer(Modifier.width(6.dp))
            Chip2(h.pat.dir.label, c)
        }
        Row(Modifier.padding(top = 2.dp)) {
            Label(dateText, Modifier.weight(1f))
            Label("可信度 " + (h.conf * 100).toInt() + "%")
        }
        Text(h.note, color = C.Text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
        r.stats[h.pat]?.let { s ->
            if (s.count >= 2) Text(
                "此股歷史出現 ${s.count} 次，${r.forward} 根後符合方向 " + Fmt.num(s.winRate * 100, 0) + "%，平均 " +
                    (if (s.avgRet >= 0) "+" else "") + Fmt.num(s.avgRet, 2) + "%",
                color = if (s.winRate >= 0.55) Trend.up else if (s.winRate < 0.45) Trend.down else C.Muted, fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun PlanBox(p: KlineAnalysis.Plan, color: Color) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.08f)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.title, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Label("盈虧比 ")
            NumText("1 : " + Fmt.num(p.rr, 2), 14.sp, if (p.rr >= 2) Trend.up else if (p.rr >= 1.2) Trend.flat else Trend.down, FontWeight.Bold)
        }
        Text(p.trigger, color = C.Text2, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
        KeyValue(if (p.title.startsWith("偏多")) "布局區間" else "減碼區間", Fmt.price(p.entryMin) + " ～ " + Fmt.price(p.entryMax))
        KeyValue("停損／防守", Fmt.price(p.stop), Trend.down)
        KeyValue("目標一", Fmt.price(p.tp1), Trend.up)
        KeyValue("目標二", Fmt.price(p.tp2), Trend.up)
        Label(p.note)
    }
}

@Composable
private fun PatternChart(r: KlineAnalysis.Result, modifier: Modifier) {
    val up = Trend.up; val down = Trend.down; val flat = Trend.flat
    val muted = C.Muted; val text = C.Text2
    Canvas(modifier) {
        val n = r.candles.size
        val from = maxOf(0, n - 40)
        val cs = r.candles.subList(from, n)
        val labelW = 48.dp.toPx()
        val w = size.width - labelW
        val h = size.height
        val pad = 14.dp.toPx()
        var hi = cs.maxOf { it.h }; var lo = cs.minOf { it.l }
        hi = maxOf(hi, r.ctx.resistance); lo = minOf(lo, r.ctx.support)
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val step = w / cs.size
        fun x(k: Int) = (k - from) * step + step / 2
        fun y(v: Double) = (pad + (hi - v) / span * (h - pad * 2)).toFloat()
        val bw = (step * 0.62f).coerceAtLeast(1.5f)
        // 支撐壓力
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        drawLine(down.copy(alpha = 0.7f), Offset(0f, y(r.ctx.resistance)), Offset(w, y(r.ctx.resistance)), 1.5f, pathEffect = dash)
        drawLine(up.copy(alpha = 0.7f), Offset(0f, y(r.ctx.support)), Offset(w, y(r.ctx.support)), 1.5f, pathEffect = dash)
        // K 棒
        for (k in from until n) {
            val c = r.candles[k]
            val col = if (c.c >= c.o) up else down
            drawLine(col, Offset(x(k), y(c.h)), Offset(x(k), y(c.l)), maxOf(1f, bw / 6))
            val top = y(maxOf(c.o, c.c)); val bot = y(minOf(c.o, c.c))
            drawRect(col, Offset(x(k) - bw / 2, top), Size(bw, maxOf(1f, bot - top)))
        }
        // 形態標記（同一根多個形態只畫一個方向符號）
        r.recent.filter { it.conf >= 0.72 && it.pat.dir != KlineAnalysis.Dir.NEUTRAL }.groupBy { it.idx }.forEach { (k, hs) ->
            if (k < from) return@forEach
            val s = hs.sumOf { it.pat.dir.weight }
            val c = r.candles[k]
            val tri = Path()
            val sz = 5.dp.toPx()
            if (s > 0) {
                val yy = y(c.l) + 4.dp.toPx()
                tri.moveTo(x(k), yy); tri.lineTo(x(k) - sz, yy + sz * 1.6f); tri.lineTo(x(k) + sz, yy + sz * 1.6f); tri.close()
                drawPath(tri, up)
            } else if (s < 0) {
                val yy = y(c.h) - 4.dp.toPx()
                tri.moveTo(x(k), yy); tri.lineTo(x(k) - sz, yy - sz * 1.6f); tri.lineTo(x(k) + sz, yy - sz * 1.6f); tri.close()
                drawPath(tri, down)
            } else drawCircle(flat, sz * 0.7f, Offset(x(k), y(c.h) - 8.dp.toPx()))
        }
        // 右側標籤
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10.sp.toPx(); typeface = android.graphics.Typeface.MONOSPACE
        }
        val nc = drawContext.canvas.nativeCanvas
        fun label(t: String, v: Double, col: Color) { paint.color = col.toArgb(); nc.drawText(t, w + 4, (y(v) + paint.textSize / 3).coerceIn(paint.textSize, h - 2), paint) }
        label("壓 " + Fmt.price(r.ctx.resistance), r.ctx.resistance, down)
        label("撐 " + Fmt.price(r.ctx.support), r.ctx.support, up)
        val last = r.candles.last().c
        if (kotlin.math.abs(y(last) - y(r.ctx.resistance)) > paint.textSize && kotlin.math.abs(y(last) - y(r.ctx.support)) > paint.textSize)
            label(Fmt.price(last), last, text)
        paint.color = muted.toArgb()
    }
}


/** 使用者自訂條件：國安基金進場（大盤）與個股技術條件的達成狀態 */
@Composable
private fun KCondBox(q: Quote, r: KlineAnalysis.Result) {
    val K = com.claudeetf.app.data.KlineConditions
    val on = K.enabled()
    val wantIdx = q.market == Market.TW && on.any { it.id == "nsf" }
    var idx by remember(q.symbol) { mutableStateOf<com.claudeetf.app.data.KlineConditions.Eval?>(null) }
    val conf = AppState.kconds
    LaunchedEffect(q.symbol, wantIdx, conf, K.nsf) { if (wantIdx) { runCatching { K.refreshNews(false) }; idx = K.evalIndex(K.indexCandles()) } }
    val stock = remember(r, conf) { K.evalStock(r.candles, on) }
    val list = listOfNotNull(if (wantIdx) idx else null) + stock
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("條件訊號", Modifier.weight(1f), color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            val met = list.count { it.met }
            if (list.isNotEmpty()) Text(if (met > 0) "達成 $met／${list.size}" else "未達成", color = if (met > 0) C.Primary else C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (on.isEmpty()) Label("尚未開啟任何條件，到「設置 › K 棒分析條件」設定（例如國安基金進場、RSI 超賣）。")
        else if (wantIdx && idx == null && stock.isEmpty()) Label("計算中…")
        list.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (e.met) "●" else "○", color = if (e.met) C.Primary else C.Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                Column(Modifier.weight(1f)) {
                    Text((if (e.def.scope == "大盤") "［大盤］" else "") + e.def.name, color = if (e.met) C.Text else C.Text2, fontSize = 13.sp,
                        fontWeight = if (e.met) FontWeight.Bold else FontWeight.Normal)
                    Text(e.value, color = C.Muted, fontSize = 11.sp)
                }
                Text(if (e.met) "達成" else "未達成", color = if (e.met) C.OnPrimary else C.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (e.met) C.Primary else C.Card).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
    }
}
