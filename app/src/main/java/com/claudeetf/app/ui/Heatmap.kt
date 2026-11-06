package com.claudeetf.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** changePct 決定顏色；text 有值時取代方塊上的漲跌幅文字 */
data class HeatItem(val key: String, val label: String, val weight: Double, val changePct: Double, val text: String? = null)

private data class Rect(val x: Float, val y: Float, val w: Float, val h: Float)

/** Squarified treemap：回傳與 items 同順序（依權重由大到小）的方塊 */
private fun squarify(weights: List<Double>, w: Float, h: Float): List<Rect> {
    val total = weights.sum()
    if (total <= 0 || weights.isEmpty()) return emptyList()
    val area = weights.map { it / total * w * h }
    val out = ArrayList<Rect>()
    var x = 0f; var y = 0f; var rw = w; var rh = h
    var i = 0
    fun worst(row: List<Double>, side: Float): Double {
        val s = row.sum()
        val mx = row.max(); val mn = row.min()
        val side2 = side.toDouble() * side
        return max(side2 * mx / (s * s), s * s / (side2 * mn))
    }
    while (i < area.size) {
        val side = min(rw, rh)
        val row = arrayListOf(area[i])
        var j = i + 1
        while (j < area.size && worst(row + area[j], side) <= worst(row, side)) { row += area[j]; j++ }
        val s = row.sum().toFloat()
        if (rw >= rh) {
            val colW = if (rh > 0) s / rh else 0f
            var cy = y
            row.forEach { a -> val hh = if (colW > 0) a.toFloat() / colW else 0f; out += Rect(x, cy, colW, hh); cy += hh }
            x += colW; rw -= colW
        } else {
            val rowH = if (rw > 0) s / rw else 0f
            var cx = x
            row.forEach { a -> val ww = if (rowH > 0) a.toFloat() / rowH else 0f; out += Rect(cx, y, ww, rowH); cx += ww }
            y += rowH; rh -= rowH
        }
        i = j
    }
    return out
}

/** 依權重（市值）排版、顏色代表漲跌幅（±3% 最深） */
@Composable
fun Heatmap(items: List<HeatItem>, height: Dp = 260.dp, colorScale: Double = 3.0, showValue: Boolean = true, onClick: (String) -> Unit) {
    val sorted = items.filter { it.weight > 0 }.sortedByDescending { it.weight }
    if (sorted.isEmpty()) {
        Label("沒有可顯示的資料", Modifier.padding(vertical = 16.dp))
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp))) {
        val wPx = maxWidth.value
        val hPx = maxHeight.value
        val rects = squarify(sorted.map { it.weight }, wPx, hPx)
        sorted.zip(rects).forEach { (it, r) ->
            val t = (abs(it.changePct) / colorScale).coerceIn(0.0, 1.0).toFloat()
            val bg = if (abs(it.changePct) < 0.01) C.Card2 else lerp(C.Card2, Trend.fg(it.changePct), 0.25f + 0.6f * t)
            val fg = if (abs(it.changePct) < 0.01 || t < 0.35f) C.Text else Color.White
            Box(
                Modifier.offset(r.x.dp, r.y.dp).size(r.w.dp, r.h.dp).padding(1.dp)
                    .clip(RoundedCornerShape(6.dp)).background(bg).clickable { onClick(it.key) },
            ) {
                if (r.w > 44 && r.h > 30) {
                    Column(Modifier.padding(5.dp)) {
                        Text(
                            it.label, color = fg, fontSize = if (r.w > 110 && r.h > 60) 13.sp else 11.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (showValue && r.h > 44) Text(it.text ?: Fmt.signedPct(it.changePct), color = fg, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}
