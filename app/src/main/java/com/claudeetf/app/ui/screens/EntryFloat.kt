package com.claudeetf.app.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** 進場動畫徽章的 5 種樣式 */
val ENTRY_FLOAT_STYLES = listOf("脈衝光環", "雷達掃描", "能量柱", "躍升箭頭", "霓虹進度環")

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

/** 徽章本體（設定頁預覽也用這個） */
@Composable
fun EntryFloatView(
    style: Int, level: Int, word: String, now: Int?, ratio: Int?, amount: Double?,
    modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, iconSize: Dp = 46.dp,
) {
    val c = entryColor(level)
    Column(
        modifier.shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(C.Card.copy(alpha = 0.94f)).border(1.dp, c.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EntryAnimIcon(style, level, now ?: 0, Modifier.size(iconSize))
        Text(word, color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (now != null) Text("先投 $now%", color = C.Text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        when {
            amount != null -> Text("≈ NT$" + Fmt.num(amount), color = C.Text2, fontSize = 10.sp, maxLines = 1)
            ratio != null -> Text("長線 $ratio%", color = C.Muted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

/** 動畫圖示：依樣式繪製，level > 0 時動畫較強烈 */
@Composable
fun EntryAnimIcon(style: Int, level: Int, pct: Int, modifier: Modifier) {
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
