package com.claudeetf.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.InvestAnalysis
import com.claudeetf.app.data.KlineAnalysis
import com.claudeetf.app.data.Quote
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.Fmt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 進場動畫徽章的樣式：前 5 種為動畫圖示，之後為動畫角色（MascotData） */
val ENTRY_ICON_STYLES = listOf("脈衝光環", "雷達掃描", "能量柱", "躍升箭頭", "霓虹進度環")
val ENTRY_FLOAT_STYLES: List<String> get() = ENTRY_ICON_STYLES + MascotData.names
fun isMascotStyle(style: Int) = style >= ENTRY_ICON_STYLES.size

/**
 * 個股頁右上角的進場動畫徽章：上方為動畫圖示，下方為綜合結論與「先投 N%」，
 * 有設定每檔預計投入金額時換算成金額。懸浮置頂時捲動也一直顯示。點一下開啟 K 線分析。
 */
@Composable
fun EntryFloatBadge(q: Quote, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    LaunchedEffect(q.symbol) { if (InvestAnalysis.cached(q.symbol) == null) runCatching { InvestAnalysis.analyze(q) } }
    LaunchedEffect(q.symbol) { runCatching { KlineAnalysis.load(q, false) } }
    val k = KlineAnalysis.latestDaily[q.symbol] ?: return
    val r = InvestAnalysis.results[q.symbol]
    val ratio = r?.let { it.verdict?.ratio ?: InvestAnalysis.suggestedRatio(it.score) }
    val (word, now) = if (ratio != null) KlineAnalysis.combined(ratio, k.entry) else (k.entry.label to null)
    val budget = AppState.investBudget
    EntryFloatView(AppState.entryFloatStyle, k.entry.level, word, now, ratio, if (budget > 0 && now != null) budget * now / 100 else null, modifier, onOpen)
}

/**
 * 角色的「內心 OS」台詞：依角色個性與進場等級（2 … -2）；{n} 代入先投比例。
 * 動畫圖示（非角色）用通用台詞。
 */
private val OS_LINES: Map<String, List<String>> = mapOf(
    "" to listOf("訊號轉強，可以進場囉！先投 {n}%", "可以分批上車，先投 {n}%", "再等等…先小投 {n}% 就好", "先別急，等止跌再說", "先避一避，暫不進場"),
    "存存" to listOf("好時機！趕快囤 {n}% 的橡實～", "一點一點存，先存 {n}%", "橡實先藏好，只放 {n}%", "冬天還沒過，先別出洞", "糧倉守好，這次不囤"),
    "牛牛" to listOf("哞～多頭來了，衝 {n}%！", "蹄子熱好了，先跑 {n}%", "哞…先觀望，小跑 {n}%", "等等，熊還在附近", "哞嗚，先回牛棚躲雨"),
    "博士" to listOf("依我分析：可以進場 {n}%", "數據不錯，建議分批 {n}%", "嗯…再觀察，先試 {n}%", "形態未穩，等止跌確認", "結論：暫不宜進場"),
    "K寶" to listOf("嗶嗶！進場訊號 ON，{n}%", "嗶！分批模式，先投 {n}%", "掃描中…先投 {n}% 待命", "警示：等待止跌訊號", "系統建議：暫停進場"),
    "穩穩" to listOf("穩穩的，可以進場 {n}%", "穩住節奏，分批 {n}%", "穩一下，先放 {n}% 就好", "先拿盾牌，等止跌", "穩住！這次先不進"),
    "衝衝" to listOf("3、2、1，點火！上 {n}%", "引擎暖好了，先衝 {n}%", "發射台待命，先 {n}%", "天氣不佳，延後發射", "任務取消，暫不起飛"),
    "圓圓" to listOf("叮噹～把我投進去 {n}%", "叮！先投 {n}% 試試水溫", "我先轉一圈，投 {n}% 就好", "先躺著，等止跌再翻身", "我要躺平，暫不進場"),
    "滿滿" to listOf("噗通～餵我 {n}%！", "肚子還有空位，先 {n}%", "吃一點點就好，{n}%", "先別餵，等止跌", "吃太飽了，暫不進場"),
    "複複" to listOf("陽光正好，種下 {n}%", "慢慢澆水，先投 {n}%", "先曬太陽，投 {n}% 就好", "下雨天，等雨停再說", "風太大，先不種"),
    "八爪" to listOf("八隻手一起上，{n}%！", "分散布局，先投 {n}%", "先觀望，每邊放一點 {n}%", "觸手收起來，等止跌", "全部收手，暫不進場"),
    "晴晴" to listOf("今天大晴天！進場 {n}%", "晴時多雲，分批 {n}%", "多雲，帶傘小投 {n}%", "小雨，等雨停再出門", "大雷雨，待在家別出門"),
)

fun osLine(style: Int, level: Int, now: Int?): String {
    val key = if (isMascotStyle(style)) MascotData.names.getOrElse(style - ENTRY_ICON_STYLES.size) { "" } else ""
    val lines = OS_LINES[key] ?: OS_LINES[""]!!
    val i = when { level >= 2 -> 0; level == 1 -> 1; level == 0 -> 2; level == -1 -> 3; else -> 4 }
    return lines[i].replace("{n}", (now ?: 0).toString())
}

/** 依背景亮度選字色，淺色背景用深字 */
private fun onColor(c: Color) = if (c.luminance() > 0.45f) Color(0xFF1B1F2A) else Color.White

/** 角落對應的對齊方式 */
fun entryFloatAlign(corner: Int): Alignment = when (corner) { 1 -> Alignment.TopStart; 2 -> Alignment.BottomEnd; 3 -> Alignment.BottomStart; else -> Alignment.TopEnd }

/**
 * 依設定放置徽章：角落＋位移＋大小；長按後可直接拖曳，放開即存成新位置。
 * scrollY：未懸浮置頂時隨頁面捲走的位移（px）。
 */
@Composable
fun BoxScope.PlacedEntryFloat(scrollY: Float = 0f, preview: Float = 1f, content: @Composable (Modifier) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    var drag by remember { mutableStateOf(Offset.Zero) }
    val corner = AppState.entryFloatCorner
    val left = corner == 1 || corner == 3
    val top = corner == 0 || corner == 1
    val sc = AppState.entryFloatScale / 100f * preview
    val m = Modifier.align(entryFloatAlign(corner))
        .padding(start = if (left) 10.dp else 0.dp, end = if (left) 0.dp else 10.dp, top = if (top) 6.dp else 0.dp, bottom = if (top) 0.dp else 10.dp)
        .offset { IntOffset(((AppState.entryFloatX * preview).dp.toPx() + drag.x).toInt(), ((AppState.entryFloatY * preview).dp.toPx() + drag.y - scrollY).toInt()) }
        .graphicsLayer {
            scaleX = sc; scaleY = sc
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (left) 0f else 1f, if (top) 0f else 1f)
        }
        .pointerInput(preview) {
            detectDragGesturesAfterLongPress(
                onDrag = { ch, d -> ch.consume(); drag += d },
                onDragEnd = {
                    with(density) {
                        AppState.updateEntryFloatPos(x = AppState.entryFloatX + (drag.x / preview).toDp().value.toInt(), y = AppState.entryFloatY + (drag.y / preview).toDp().value.toInt())
                    }
                    drag = Offset.Zero
                },
                onDragCancel = { drag = Offset.Zero },
            )
        }
    content(m)
}

/** OS 泡泡實際方向：自動時朝畫面內側（右邊角落→左側；左邊角落→右側） */
private fun bubbleSide(): Int = when (AppState.entryFloatBubble) {
    1 -> 1; 2 -> 2; 3 -> 3
    else -> if (AppState.entryFloatCorner == 1 || AppState.entryFloatCorner == 3) 2 else 1
}

/** 徽章本體（設定頁預覽也用這個）：角色＋內心 OS 對話框＋先投比例 */
@Composable
fun EntryFloatView(
    style: Int, level: Int, word: String, now: Int?, ratio: Int?, amount: Double?,
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, iconSize: Dp = if (isMascotStyle(style)) 66.dp else 48.dp,
) {
    val c = entryColor(level)
    val tag = if (level > 0) Color(0xFFE5484D) else if (level < 0) Color(0xFF16A34A) else Color(0xFF64748B)
    var osOpen by remember { mutableStateOf(true) }
    val side = bubbleSide()
    val showOs = AppState.entryFloatOs && osOpen

    @Composable fun Bubble() {
        Column(
            Modifier.widthIn(max = 168.dp).clickable(remember { MutableInteractionSource() }, null) { osOpen = false }
                .shadow(6.dp, RoundedCornerShape(16.dp)).background(Color.White, RoundedCornerShape(16.dp))
                .border(2.dp, tag, RoundedCornerShape(16.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("OS", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.background(tag, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                Text(" " + word, color = tag, fontSize = 13.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
            Text(osLine(style, level, now), color = Color(0xFF1B1F2A), fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 3.dp))
            val sub = when {
                amount != null -> "≈ NT$" + Fmt.num(amount)
                ratio != null -> "長線建議 $ratio%"
                else -> null
            }
            if (sub != null) Text(sub, color = Color(0xFF5B6475), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
    }
    @Composable fun Dot(s: Int) = Box(Modifier.size(s.dp).background(Color.White, CircleShape).border(1.5.dp, tag, CircleShape))
    @Composable fun Character() {
        Column(
            Modifier.then(if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, null) { if (AppState.entryFloatOs && !osOpen) osOpen = true else onClick() } else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EntryAnimIcon(style, level, now ?: 0, Modifier.size(iconSize))
            Text(if (now != null) "先投 $now%" else word, color = onColor(c), fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1,
                modifier = Modifier.shadow(3.dp, RoundedCornerShape(8.dp)).background(c, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
    if (!showOs) { Box(modifier) { Character() }; return }
    when (side) {
        3 -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Character()
            Dot(6); Spacer(Modifier.height(2.dp)); Dot(9)
            Spacer(Modifier.height(2.dp)); Bubble()
        }
        2 -> Row(modifier, verticalAlignment = Alignment.Top) {
            Character()
            Column(Modifier.padding(top = 18.dp, end = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.End) { Dot(6); Dot(9) }
            Box(Modifier.padding(top = 6.dp)) { Bubble() }
        }
        else -> Row(modifier, verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp)) { Bubble() }
            Column(Modifier.padding(top = 18.dp, start = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { Dot(9); Box(Modifier.padding(start = 6.dp)) { Dot(6) } }
            Character()
        }
    }
}

/** 動畫圖示：依樣式繪製，level > 0 時動畫較強烈 */
@Composable
fun EntryAnimIcon(style: Int, level: Int, pct: Int, modifier: Modifier) {
    if (isMascotStyle(style)) { MascotView(style - ENTRY_ICON_STYLES.size, level, modifier); return }
    val c = entryColor(level)
    val strong = level > 0
    val tr = rememberInfiniteTransition(label = "ef")
    val p by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(if (strong) 1100 else 2200, easing = LinearEasing)), label = "p")
    Canvas(modifier) {
        when (style) {
            1 -> radar(c, p, level)
            2 -> energy(c, p, level)
            3 -> arrow(c, p, level)
            4 -> ring(c, p, pct, level)
            else -> pulse(c, p, level)
        }
    }
}

private fun glyph(level: Int) = when (level) { 2 -> 0; 1 -> 1; 0 -> 2; -1 -> 3; else -> 4 }

/** 中央符號：勾、半圓、暫停、手掌（橫線）、禁止 */
private fun DrawScope.symbol(level: Int, col: Color, r: Float) {
    val cx = size.width / 2; val cy = size.height / 2
    val w = r * 0.16f
    when (glyph(level)) {
        0 -> { val path = Path().apply { moveTo(cx - r * .45f, cy); lineTo(cx - r * .1f, cy + r * .35f); lineTo(cx + r * .5f, cy - r * .35f) }
            drawPath(path, col, style = Stroke(w, cap = StrokeCap.Round)) }
        1 -> { drawCircle(col, r * .45f, Offset(cx, cy), style = Stroke(w)); drawArc(col, 90f, 180f, true, Offset(cx - r * .45f, cy - r * .45f), Size(r * .9f, r * .9f)) }
        2 -> { drawLine(col, Offset(cx - r * .18f, cy - r * .35f), Offset(cx - r * .18f, cy + r * .35f), w, StrokeCap.Round)
            drawLine(col, Offset(cx + r * .18f, cy - r * .35f), Offset(cx + r * .18f, cy + r * .35f), w, StrokeCap.Round) }
        3 -> drawLine(col, Offset(cx - r * .4f, cy), Offset(cx + r * .4f, cy), w, StrokeCap.Round)
        else -> { drawCircle(col, r * .42f, Offset(cx, cy), style = Stroke(w))
            drawLine(col, Offset(cx - r * .3f, cy - r * .3f), Offset(cx + r * .3f, cy + r * .3f), w, StrokeCap.Round) }
    }
}

/** 0 脈衝光環：實心圓＋向外擴散的光環 */
private fun DrawScope.pulse(c: Color, p: Float, level: Int) {
    val r = size.minDimension / 2
    val center = Offset(size.width / 2, size.height / 2)
    for (i in 0..1) {
        val q = (p + i * 0.5f) % 1f
        drawCircle(c.copy(alpha = (1 - q) * if (level > 0) 0.6f else 0.3f), r * (0.55f + 0.45f * q), center, style = Stroke(r * 0.08f))
    }
    val breathe = 0.5f + 0.06f * sin(p * 2 * PI.toFloat())
    drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0.7f)), center, r * breathe), r * breathe, center)
    symbol(level, Color.White, r * breathe)
}

/** 1 雷達掃描：外圈＋旋轉掃描扇形＋閃爍光點 */
private fun DrawScope.radar(c: Color, p: Float, level: Int) {
    val r = size.minDimension / 2 * 0.92f
    val center = Offset(size.width / 2, size.height / 2)
    drawCircle(c.copy(alpha = 0.12f), r, center)
    drawCircle(c.copy(alpha = 0.6f), r, center, style = Stroke(r * 0.06f))
    drawCircle(c.copy(alpha = 0.3f), r * 0.6f, center, style = Stroke(r * 0.04f))
    rotate(p * 360f, center) {
        drawArc(Brush.sweepGradient(listOf(Color.Transparent, c.copy(alpha = 0.75f)), center), -60f, 60f, true,
            Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
    }
    val blips = if (level > 0) 3 else 1
    for (i in 0 until blips) {
        val a = (i * 2.1f + 0.7f)
        val d = r * (0.35f + 0.18f * i)
        val vis = ((p * 360f - Math.toDegrees(a.toDouble()).toFloat() + 360f) % 360f) / 360f
        drawCircle(c.copy(alpha = (1 - vis).coerceIn(0.2f, 1f)), r * 0.09f, Offset(center.x + d * cos(a), center.y + d * sin(a)))
    }
    symbol(level, c, r * 0.55f)
}

/** 2 能量柱：5 根柱子依進場等級點亮，點亮的柱子波浪跳動 */
private fun DrawScope.energy(c: Color, p: Float, level: Int) {
    val lit = (level + 3).coerceIn(1, 5)
    val n = 5
    val gap = size.width * 0.05f
    val bw = (size.width - gap * (n + 1)) / n
    for (i in 0 until n) {
        val base = 0.3f + 0.7f * (i + 1) / n
        val on = i < lit
        val wave = if (on) 0.12f * sin((p * 2 * PI - i * 0.9).toFloat()) else 0f
        val h = size.height * (base * 0.86f + wave).coerceIn(0.1f, 1f)
        val x = gap + i * (bw + gap)
        drawRoundRect(if (on) Brush.verticalGradient(listOf(c, c.copy(alpha = 0.55f)), size.height - h, size.height) else Brush.verticalGradient(listOf(Color.Gray.copy(alpha = 0.25f), Color.Gray.copy(alpha = 0.25f))),
            Offset(x, size.height - h), Size(bw, h), CornerRadius(bw / 3))
    }
}

/** 3 躍升箭頭：可進場時向上跳動並拖曳殘影；觀望或偏空時箭頭橫向或向下 */
private fun DrawScope.arrow(c: Color, p: Float, level: Int) {
    val cx = size.width / 2; val h = size.height
    val ang = when { level > 0 -> 0f; level == 0 -> 90f; else -> 180f }
    val bounce = if (level > 0) (sin(p * 2 * PI).toFloat() * 0.12f * h) else (sin(p * 2 * PI).toFloat() * 0.04f * h)
    rotate(ang, Offset(cx, h / 2)) {
        for (t in 3 downTo 0) {
            val y = h / 2 - bounce + t * h * 0.09f
            val a = if (t == 0) 1f else 0.35f / t
            val path = Path().apply {
                moveTo(cx, y - h * 0.32f); lineTo(cx + h * 0.28f, y + h * 0.02f); lineTo(cx + h * 0.1f, y + h * 0.02f)
                lineTo(cx + h * 0.1f, y + h * 0.3f); lineTo(cx - h * 0.1f, y + h * 0.3f); lineTo(cx - h * 0.1f, y + h * 0.02f)
                lineTo(cx - h * 0.28f, y + h * 0.02f); close()
            }
            drawPath(path, c.copy(alpha = a))
        }
    }
}

/** 4 霓虹進度環：環形顯示「先投」比例，發光的頭部繞行 */
private fun DrawScope.ring(c: Color, p: Float, pct: Int, level: Int) {
    val sw = size.minDimension * 0.12f
    val r = size.minDimension / 2 - sw
    val center = Offset(size.width / 2, size.height / 2)
    drawCircle(c.copy(alpha = 0.15f), r, center, style = Stroke(sw))
    val sweep = 360f * (pct.coerceIn(0, 100) / 100f).coerceAtLeast(0.04f)
    drawArc(c, -90f, sweep, false, Offset(center.x - r, center.y - r), Size(r * 2, r * 2), style = Stroke(sw, cap = StrokeCap.Round))
    val a = Math.toRadians((-90f + 360f * p).toDouble())
    val head = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
    drawCircle(c.copy(alpha = if (level > 0) 0.35f else 0.18f), sw * 1.3f, head)
    drawCircle(Color.White.copy(alpha = 0.9f), sw * 0.45f, head)
    symbol(level, c, r * 0.8f)
}
