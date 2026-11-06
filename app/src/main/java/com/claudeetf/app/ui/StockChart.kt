package com.claudeetf.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import com.claudeetf.app.util.Indicators
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ChartType(val label: String) { LINE("走勢"), CANDLE("K線") }
enum class SubChart(val label: String) { VOLUME("成交量"), KD("KD"), RSI("RSI"), MACD("MACD"), NONE("關閉") }

val MA_COLORS = mapOf(5 to Color(0xFFF2B544), 20 to Color(0xFFB9A6FF), 60 to Color(0xFF4FD1C5))

/** 圖表設定列：圖型、均線、副圖 */
@Composable
fun ChartControls(
    type: ChartType, onType: (ChartType) -> Unit,
    mas: Set<Int>, onToggleMa: (Int) -> Unit,
    sub: SubChart, onSub: (SubChart) -> Unit,
    intraday: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChartType.entries.forEach { t -> Chip(t.label, t == type) { onType(t) } }
            Box(Modifier.width(1.dp).height(30.dp).background(C.Line))
            listOf(5, 20, 60).forEach { n ->
                Chip("MA$n", n in mas, MA_COLORS[n]) { onToggleMa(n) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("副圖", Modifier.align(Alignment.CenterVertically))
            SubChart.entries.forEach { s -> Chip(s.label, s == sub) { onSub(s) } }
        }
        if (intraday && type == ChartType.CANDLE) Label("盤中 K 線為 5／15 分鐘線")
    }
}

@Composable
private fun Chip(text: String, on: Boolean, dot: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(15.dp))
            .background(if (on) C.Selected else C.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (on) dot else C.Gray)); HGap(5.dp) }
        Text(text, color = if (on) C.Text else C.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * 互動圖表：主圖（走勢或 K 線＋均線）與副圖（量／KD／RSI／MACD）。
 * 指標用完整資料計算，只顯示 start 之後的區段；手指按住拖曳可查看各點數值。
 */
@Composable
fun StockChart(
    h: YahooExtra.History,
    start: Int,
    type: ChartType,
    mas: Set<Int>,
    sub: SubChart,
    basePrice: Float?,
    intraday: Boolean,
    /** 參考價位線（例如三關價），畫成虛線 */
    levels: List<KeyLevel> = emptyList(),
    /** 分時圖整個交易時段的 K 棒數；> 0 時 X 軸固定為全時段 */
    slots: Int = 0,
    /** 固定 Y 軸上下限（台股分時用漲停、跌停價） */
    yBounds: Pair<Float, Float>? = null,
    /** 我的買賣點（依成交日期對到 K 棒） */
    marks: List<TradeMark> = emptyList(),
    /** 用來把成交量換成「張」（台股）；null 時直接顯示股數 */
    market: com.claudeetf.app.data.Market? = null,
) {
    val n = h.closes.size
    val baseFrom = start.coerceIn(0, maxOf(0, n - 2))
    val count = n - baseFrom
    // 雙指縮放：zCount = 可見 K 棒數，zStart = 可見起點（浮點，讓縮放平順）
    val zoomable = !intraday && count > 20
    var zCount by remember(h, baseFrom) { mutableStateOf(count.toFloat()) }
    var zStart by remember(h, baseFrom) { mutableStateOf(baseFrom.toFloat()) }
    val zoomed = zoomable && zCount < count - 0.5f
    val from = if (zoomed) zStart.toInt().coerceIn(baseFrom, maxOf(baseFrom, n - 2)) else baseFrom
    val end = if (zoomed) minOf(n, from + kotlin.math.ceil(zCount).toInt()).coerceAtLeast(minOf(n, from + 2)) else n
    val span = if (zoomed) end - from else maxOf(count, slots)
    var sel by remember(h, baseFrom) { mutableStateOf<Int?>(null) }

    val maLines = remember(h, mas) { mas.associateWith { Indicators.sma(h.closes, it) } }
    val kd = remember(h, sub) { if (sub == SubChart.KD) Indicators.kd(h.highs, h.lows, h.closes) else null }
    val rsi = remember(h, sub) { if (sub == SubChart.RSI) Indicators.rsi(h.closes) else null }
    val macd = remember(h, sub) { if (sub == SubChart.MACD) Indicators.macd(h.closes) else null }

    val first = h.closes[baseFrom]
    val last = h.closes.last()
    val ref = basePrice ?: first
    val upColor = Trend.fg(1.0)
    val downColor = Trend.fg(-1.0)
    val lineColor = Trend.fg((last - ref).toDouble())
    val pattern = if (intraday) "M/d HH:mm" else "yyyy/M/d"
    fun date(i: Int) = Instant.ofEpochMilli(h.times[i]).atZone(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern(pattern))

    // 買賣點對應的 K 棒：成交日當天那根；遇非交易日放到之後第一根
    val markAt = remember(h, marks) {
        if (intraday || marks.isEmpty()) emptyMap() else {
            val tpe = ZoneId.of("Asia/Taipei")
            val days = h.times.map { Instant.ofEpochMilli(it).atZone(tpe).toLocalDate() }
            marks.mapNotNull { m ->
                val k = days.indexOfFirst { !it.isBefore(m.date) }
                // 成交日早於資料起點（或對到的 K 棒差太多天，如週 K）就不標
                if (k < 0 || java.time.temporal.ChronoUnit.DAYS.between(m.date, days[k]) > 8) null else k to m
            }.groupBy({ it.first }, { it.second })
        }
    }

    // ---- 讀值列 ----
    val i = sel ?: (end - 1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(date(i) + if (sel == null) "（最新）" else "", Modifier.weight(1f))
            val chg = h.closes[i] - ref
            NumText(
                "區間 " + Fmt.signedPct(if (ref != 0f) (h.closes[i] - ref) / ref * 100.0 else 0.0),
                12.sp, Trend.fg(chg.toDouble()),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (type == ChartType.CANDLE && h.opens.size == n) {
                Mini("開", h.opens[i]); Mini("高", h.highs[i]); Mini("低", h.lows[i])
            }
            Mini("收", h.closes[i])
            if (h.volumes.size == n) {
                fun vt(v: Double) = if (market != null) com.claudeetf.app.ui.screens.volumeText(v, market) else Fmt.num(v)
                // 分時圖未選取時顯示當日總量（最後一根 13:30 常為 0）；選取某根時顯示該根的量
                if (intraday && sel == null) Label("總量 " + vt((from until n).sumOf { h.volumes[it].toDouble() }))
                else Label("量 " + vt(h.volumes[i].toDouble()))
            }
        }
        markAt[i]?.let { ms ->
            Text(
                ms.joinToString("　") { (if (it.buy) "買 " else "賣 ") + Fmt.shares(it.shares) + " 股 @ " + Fmt.price(it.price.toDouble()) },
                color = C.Text2, fontSize = 11.sp, maxLines = 1,
            )
        }
        if (mas.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                mas.sorted().forEach { m ->
                    val v = maLines[m]?.getOrNull(i)
                    Text("MA$m " + (v?.let { Fmt.price(it.toDouble()) } ?: "—"), color = MA_COLORS[m] ?: C.Muted, fontSize = 11.sp)
                }
            }
        }

        // ---- 主圖 ----
        // 單指：按住拖曳看數值、點一下鎖定；雙指：縮放與左右平移（K 線、日線以上）
        val view by rememberUpdatedState(Triple(from, end, span))
        val gesture = Modifier.pointerInput(h, baseFrom) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slop = viewConfiguration.touchSlop
                var multi = false
                var dragging = false
                fun idx(px: Float): Int {
                    val (f, e, sp) = view
                    return indexAt(px, size.width.toFloat(), f, sp).coerceAtMost(e - 1)
                }
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2 && zoomable) {
                        multi = true; sel = null
                        val zoom = ev.calculateZoom()
                        val pan = ev.calculatePan()
                        val c = ev.calculateCentroid(useCurrent = true)
                        val wpx = size.width.toFloat().coerceAtLeast(1f)
                        val total = count.toFloat()
                        val old = zCount
                        val nc = (old / zoom).coerceIn(minOf(15f, total), total)
                        val frac = (c.x / wpx).coerceIn(0f, 1f)
                        val anchor = zStart + frac * old
                        val ns = anchor - frac * nc - pan.x / wpx * nc
                        zCount = nc
                        zStart = ns.coerceIn(baseFrom.toFloat(), maxOf(baseFrom.toFloat(), n - nc))
                        ev.changes.forEach { it.consume() }
                    } else if (!multi) {
                        val p = pressed[0]
                        if (!dragging && (p.position - down.position).getDistance() > slop) dragging = true
                        if (dragging) { sel = idx(p.position.x); p.consume() }
                    }
                }
                if (!multi && !dragging) { val k = idx(down.position.x); sel = if (sel == k) null else k }
                else if (dragging) sel = null
            }
        }

        Canvas(Modifier.fillMaxWidth().height(190.dp).then(gesture)) {
            var hi = -Float.MAX_VALUE
            var lo = Float.MAX_VALUE
            for (k in from until end) {
                val top = if (type == ChartType.CANDLE) h.highs.getOrElse(k) { h.closes[k] } else h.closes[k]
                val bot = if (type == ChartType.CANDLE) h.lows.getOrElse(k) { h.closes[k] } else h.closes[k]
                if (top > hi) hi = top
                if (bot < lo) lo = bot
                maLines.values.forEach { l -> l.getOrNull(k)?.let { v -> if (v > hi) hi = v; if (v < lo) lo = v } }
            }
            if (basePrice != null) { if (basePrice > hi) hi = basePrice; if (basePrice < lo) lo = basePrice }
            levels.forEach { l -> if (l.value > hi) hi = l.value; if (l.value < lo) lo = l.value }
            yBounds?.let { (up, down) -> if (up > hi) hi = up; if (down < lo) lo = down }
            val pad = if (yBounds != null) 0.0001f else (hi - lo) * 0.06f + 0.0001f
            hi += pad; lo -= pad
            val w = size.width
            val ht = size.height
            val step = w / span
            fun x(k: Int) = (k - from) * step + step / 2
            fun y(v: Float) = ht - (v - lo) / (hi - lo) * ht

            // 格線
            for (g in 1..3) drawLine(C.Line, Offset(0f, ht * g / 4), Offset(w, ht * g / 4), 1f)
            basePrice?.let {
                drawLine(C.Gray, Offset(0f, y(it)), Offset(w, y(it)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
            levels.forEach { l ->
                drawLine(l.color, Offset(0f, y(l.value)), Offset(w, y(l.value)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            }

            if (type == ChartType.CANDLE && h.opens.size == n) {
                val bw = (step * 0.7f).coerceAtLeast(1f)
                for (k in from until end) {
                    val o = h.opens[k]; val c = h.closes[k]
                    val col = if (c >= o) upColor else downColor
                    drawLine(col, Offset(x(k), y(h.highs[k])), Offset(x(k), y(h.lows[k])), maxOf(1f, bw / 6))
                    val top = y(maxOf(o, c)); val bot = y(minOf(o, c))
                    drawRect(col, Offset(x(k) - bw / 2, top), Size(bw, maxOf(1f, bot - top)))
                }
            } else {
                val path = Path()
                for (k in from until end) { if (k == from) path.moveTo(x(k), y(h.closes[k])) else path.lineTo(x(k), y(h.closes[k])) }
                val area = Path().apply { addPath(path); lineTo(x(end - 1), ht); lineTo(x(from), ht); close() }
                drawPath(area, lineColor.copy(alpha = 0.12f))
                drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
            }
            maLines.forEach { (m, l) -> drawSeries(l, from, end, ::x, ::y, MA_COLORS[m] ?: C.Muted) }
            // 我的買賣點：買進「B」在成交價下方、賣出「S」在成交價上方
            markAt.forEach { (k, ms) ->
                if (k < from || k >= end) return@forEach
                ms.forEach { m ->
                    val col = if (m.buy) MARK_BUY else MARK_SELL
                    val cy = y(m.price.coerceIn(lo, hi))
                    val r = 7.dp.toPx()
                    val cy2 = if (m.buy) cy + r + 2 else cy - r - 2
                    drawLine(col, Offset(x(k), cy), Offset(x(k), cy2), 1.5f)
                    drawCircle(col, r, Offset(x(k), cy2))
                    val tp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.WHITE; textSize = 9.sp.toPx(); textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = true
                    }
                    drawContext.canvas.nativeCanvas.drawText(if (m.buy) "B" else "S", x(k), cy2 + tp.textSize / 3, tp)
                }
            }
            sel?.let { s -> crosshair(x(s), y(h.closes[s])) }

            // ---- 價格座標與標線數值（讓三關、昨收與走勢的相對位置一目了然） ----
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 10.sp.toPx()
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val nc = drawContext.canvas.nativeCanvas
            fun fmt(v: Float) = Fmt.price(v.toDouble())
            fun text(t: String, xPos: Float, yPos: Float, color: Color, right: Boolean) {
                paint.color = color.toArgb()
                paint.textAlign = if (right) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT
                // 底色避免和曲線重疊看不清楚
                val tw = paint.measureText(t)
                val left = if (right) xPos - tw - 2 else xPos - 2
                paint.color = C.Bg.copy(alpha = 0.75f).toArgb()
                nc.drawRect(left, yPos - paint.textSize, left + tw + 4, yPos + 3, paint)
                paint.color = color.toArgb()
                nc.drawText(t, xPos, yPos, paint)
            }
            val th = paint.textSize
            fun ty(v: Float) = (y(v) - 3).coerceIn(th, ht - 3)
            // 左側：Y 軸上下限與昨收
            text(fmt(hi), 2f, th, C.Muted, false)
            text(fmt(lo), 2f, ht - 3, C.Muted, false)
            basePrice?.let { text("昨收 " + fmt(it), 2f, ty(it), C.Gray, false) }
            // 右側：各關價
            levels.forEach { l -> text(l.label + " " + fmt(l.value), w - 2, ty(l.value), l.color, true) }
            // 最新價標籤（接在曲線最後一點）
            val lp = h.closes[end - 1]
            text(fmt(lp), (x(end - 1) + 6).coerceAtMost(w - 60), (y(lp) + th / 2).coerceIn(th, ht - 3), lineColor, false)
        }

        // ---- 副圖 ----
        if (sub != SubChart.NONE) {
            val subLabel = when (sub) {
                SubChart.VOLUME -> "成交量"
                SubChart.KD -> "K " + (kd?.first?.getOrNull(i)?.let { Fmt.num(it.toDouble(), 1) } ?: "—") +
                    "　D " + (kd?.second?.getOrNull(i)?.let { Fmt.num(it.toDouble(), 1) } ?: "—")
                SubChart.RSI -> "RSI(14) " + (rsi?.getOrNull(i)?.let { Fmt.num(it.toDouble(), 1) } ?: "—")
                SubChart.MACD -> "DIF " + (macd?.dif?.getOrNull(i)?.let { Fmt.num(it.toDouble(), 2) } ?: "—") +
                    "　DEA " + (macd?.dea?.getOrNull(i)?.let { Fmt.num(it.toDouble(), 2) } ?: "—")
                SubChart.NONE -> ""
            }
            Label(subLabel)
            Canvas(Modifier.fillMaxWidth().height(70.dp).then(gesture)) {
                val w = size.width
                val ht = size.height
                val step = w / span
                fun x(k: Int) = (k - from) * step + step / 2
                drawLine(C.Line, Offset(0f, ht), Offset(w, ht), 1f)
                when (sub) {
                    SubChart.VOLUME -> {
                        if (h.volumes.size == n) {
                            val mx = (from until end).maxOfOrNull { h.volumes[it] }?.takeIf { it > 0 } ?: 1f
                            val bw = (step * 0.7f).coerceAtLeast(1f)
                            for (k in from until end) {
                                val up = if (k > 0) h.closes[k] >= h.closes[k - 1] else true
                                val bh = h.volumes[k] / mx * ht
                                drawRect((if (up) upColor else downColor).copy(alpha = 0.7f), Offset(x(k) - bw / 2, ht - bh), Size(bw, bh))
                            }
                        }
                    }
                    SubChart.KD, SubChart.RSI -> {
                        fun y(v: Float) = ht - v / 100f * ht
                        listOf(20f, 50f, 80f).forEach { lv ->
                            drawLine(C.Line, Offset(0f, y(lv)), Offset(w, y(lv)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                        }
                        if (sub == SubChart.KD && kd != null) {
                            drawSeries(kd.first, from, end, ::x, ::y, Color(0xFFF2B544))
                            drawSeries(kd.second, from, end, ::x, ::y, Color(0xFF6EA8FF))
                        }
                        if (sub == SubChart.RSI && rsi != null) drawSeries(rsi, from, end, ::x, ::y, Color(0xFFB9A6FF))
                    }
                    SubChart.MACD -> if (macd != null) {
                        var mx = 0.0001f
                        for (k in from until end) mx = maxOf(mx, kotlin.math.abs(macd.dif[k]), kotlin.math.abs(macd.dea[k]), kotlin.math.abs(macd.hist[k]))
                        fun y(v: Float) = ht / 2 - v / mx * (ht / 2) * 0.95f
                        drawLine(C.Gray, Offset(0f, ht / 2), Offset(w, ht / 2), 1f)
                        val bw = (step * 0.6f).coerceAtLeast(1f)
                        for (k in from until end) {
                            val v = macd.hist[k]
                            val col = if (v >= 0) upColor else downColor
                            val top = minOf(y(v), ht / 2); val bot = maxOf(y(v), ht / 2)
                            drawRect(col.copy(alpha = 0.7f), Offset(x(k) - bw / 2, top), Size(bw, maxOf(1f, bot - top)))
                        }
                        drawSeries(macd.dif, from, end, ::x, ::y, Color(0xFFF2B544))
                        drawSeries(macd.dea, from, end, ::x, ::y, Color(0xFF6EA8FF))
                    }
                    SubChart.NONE -> {}
                }
                sel?.let { s -> drawLine(C.Muted, Offset(x(s), 0f), Offset(x(s), ht), 1f) }
            }
        }
        Row {
            Label(date(from), Modifier.weight(1f))
            if (zoomed) Text("重設縮放", color = C.Primary, fontSize = 12.sp,
                modifier = Modifier.clickable { zCount = count.toFloat(); zStart = baseFrom.toFloat() }.padding(horizontal = 6.dp))
            else Label(if (zoomable) "雙指縮放・拖曳看數值" else "按住拖曳查看數值")
            Label(date(end - 1), Modifier.weight(1f).wrapContentWidth(Alignment.End))
        }
    }
}

@Composable
private fun Mini(label: String, v: Float) {
    Text("$label " + Fmt.price(v.toDouble()), color = C.Text2, fontSize = 11.sp)
}

/** 圖上的參考價位 */
data class KeyLevel(val label: String, val value: Float, val color: Color)

/** 走勢圖上的我的買賣點 */
data class TradeMark(val date: java.time.LocalDate, val price: Float, val buy: Boolean, val shares: Double)

val MARK_BUY = Color(0xFF3B82F6)
val MARK_SELL = Color(0xFFF59E0B)

private fun indexAt(px: Float, width: Float, from: Int, count: Int): Int {
    if (count <= 0 || width <= 0f) return from
    val k = (px / (width / count)).toInt().coerceIn(0, count - 1)
    return from + k
}

private fun DrawScope.drawSeries(
    v: List<Float?>, from: Int, n: Int, x: (Int) -> Float, y: (Float) -> Float, color: Color,
) {
    val path = Path()
    var started = false
    for (k in from until minOf(n, v.size)) {
        val value = v[k] ?: continue
        if (!started) { path.moveTo(x(k), y(value)); started = true } else path.lineTo(x(k), y(value))
    }
    if (started) drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))
}

private fun DrawScope.crosshair(cx: Float, cy: Float) {
    drawLine(C.Muted, Offset(cx, 0f), Offset(cx, size.height), 1f)
    drawLine(C.Muted, Offset(0f, cy), Offset(size.width, cy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    drawCircle(C.Text, 4.dp.toPx(), Offset(cx, cy))
}
