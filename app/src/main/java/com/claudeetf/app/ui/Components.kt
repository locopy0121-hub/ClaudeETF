package com.claudeetf.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.Market
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Mono
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt

@Composable
fun Card(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(C.Card)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = C.Muted) {
    Text(text, modifier = modifier, color = color, fontSize = 12.sp)
}

@Composable
fun NumText(
    text: String,
    size: TextUnit = 14.sp,
    color: Color = C.Text,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) {
    Text(text, modifier = modifier, fontFamily = Mono, fontSize = size, color = color, fontWeight = LocalItemWeight.current ?: weight, maxLines = 1)
}

/** 單一欄位樣式指定的字重（null = 依元件預設） */
val LocalItemWeight = androidx.compose.runtime.compositionLocalOf<FontWeight?> { null }

@Composable
fun MarketTag(market: Market) {
    val (bg, fg) = if (market == Market.TW) C.TwBg to C.Tw else C.UsBg to C.Us
    Text(
        market.label,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold,
    )
}

@Composable
fun ChangePill(pct: Double, modifier: Modifier = Modifier) {
    Text(
        Fmt.signedPct(pct),
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(Trend.pillBg(pct))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = Trend.pillFg(pct), fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold,
    )
}

@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    background: Color = C.Card,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) C.Selected else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (on) C.Text else C.Muted, fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                )
            }
        }
    }
}

/** 頁面標題列：主分頁用 title，次層頁加返回鍵 */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = if (onBack == null) 16.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = C.Text)
            }
        }
        Text(
            title, modifier = Modifier.weight(1f), color = C.Text,
            fontSize = if (onBack == null) 20.sp else 17.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (trailing != null) Label(trailing)
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = C.Text, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        // 標題不換行：空間不足時以 … 收尾，數值優先完整顯示
        Text(key, Modifier.weight(1f).padding(end = 8.dp), color = C.Muted, fontSize = 13.sp, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        NumText(value, 13.sp, valueColor)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(color))
}

/** 比例條：(比例, 顏色) */
@Composable
fun StackBar(parts: List<Pair<Float, Color>>, height: Dp = 10.dp) {
    Row(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.filter { it.first > 0f }.forEach { (w, c) ->
            Box(Modifier.weight(w).height(height).background(c))
        }
    }
}

@Composable
fun Sparkline(points: List<Float>, color: Color, modifier: Modifier = Modifier, fill: Boolean = false) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val max = points.max()
        val min = points.min()
        val range = if (max - min > 0f) max - min else 1f
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v - min) / range * size.height * 0.9f - size.height * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (fill) {
            val area = Path()
            area.addPath(path)
            area.lineTo(size.width, size.height)
            area.lineTo(0f, size.height)
            area.close()
            drawPath(area, color.copy(alpha = 0.12f))
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}

/** 多條折線（共用座標範圍），dashed 用於基準線 */
@Composable
fun MultiLine(series: List<Triple<List<Float>, Color, Boolean>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val all = series.flatMap { it.first }
        if (all.isEmpty()) return@Canvas
        val max = all.max()
        val min = all.min()
        val range = if (max - min > 0f) max - min else 1f
        drawLine(C.Selected, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        series.forEach { (pts, color, dashed) ->
            if (pts.size < 2) return@forEach
            val stepX = size.width / (pts.size - 1)
            val path = Path()
            pts.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height - (v - min) / range * size.height * 0.92f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path, color,
                style = Stroke(
                    width = (if (dashed) 1.8f else 2.4f) * density,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null,
                ),
            )
        }
    }
}

@Composable
fun Gap(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HGap(w: Dp) = Spacer(Modifier.width(w))
