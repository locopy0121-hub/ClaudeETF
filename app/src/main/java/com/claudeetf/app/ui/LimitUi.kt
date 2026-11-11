package com.claudeetf.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.Quote
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.TwSession

/** 台股漲停 1、跌停 -1、其他 0（依前一日收盤與升降單位計算 ±10%） */
fun limitDir(price: Double, prevClose: Double, symbol: String, etf: Boolean = false): Int =
    when (TwSession.limitState(price, prevClose, etf || symbol.startsWith("00"))) { "漲停" -> 1; "跌停" -> -1; else -> 0 }

fun limitOf(q: Quote?): Int = if (q == null || q.market != Market.TW || q.price <= 0) 0 else limitDir(q.price, q.prevClose, q.symbol, q.isEtf)

/** 漲停／跌停時整塊反白的底色（依漲跌配色） */
fun limitBg(dir: Int): Color = Trend.fg(dir.toDouble())

/** 漲停／跌停標籤（實心、白字）；設定關閉或非漲跌停時不顯示 */
@Composable
fun LimitTag(dir: Int, size: TextUnit = 11.sp, modifier: Modifier = Modifier) {
    if (dir == 0 || !AppState.limitTag) return
    Text(
        if (dir > 0) "漲停" else "跌停", color = Color.White, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(limitBg(dir)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 價格數字：漲停／跌停時改成實心色塊白字（設定可關） */
@Composable
fun LimitPrice(text: String, size: TextUnit, color: Color, dir: Int, weight: FontWeight = FontWeight.Bold, modifier: Modifier = Modifier) {
    if (dir != 0 && AppState.limitFill) {
        NumText(text, size, Color.White, weight, modifier = modifier.clip(RoundedCornerShape(6.dp)).background(limitBg(dir)).padding(horizontal = 6.dp))
    } else NumText(text, size, color, weight, modifier = modifier)
}
