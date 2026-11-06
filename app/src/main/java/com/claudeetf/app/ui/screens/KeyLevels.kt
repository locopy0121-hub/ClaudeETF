package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.Quote
import com.claudeetf.app.data.StockInfoRepo
import com.claudeetf.app.ui.KeyLevel
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.Fmt

object KeyLevelColors {
    val upper = Color(0xFFE5484D)
    val mid = Color(0xFFF5B83D)
    val lower = Color(0xFF3DBE6E)
}

object KeyLevelsUi {
    fun lines(k: StockInfoRepo.KeyPrices): List<KeyLevel> = listOf(
        KeyLevel("上關", k.upper.toFloat(), KeyLevelColors.upper),
        KeyLevel("中關", k.mid.toFloat(), KeyLevelColors.mid),
        KeyLevel("下關", k.lower.toFloat(), KeyLevelColors.lower),
    )

    /** 現價相對三關的位置 */
    fun position(price: Double, k: StockInfoRepo.KeyPrices): Pair<String, Color> = when {
        price <= 0 -> "—" to C.Muted
        price >= k.upper -> "站上上關" to KeyLevelColors.upper
        price >= k.mid -> "中關～上關" to KeyLevelColors.mid
        price > k.lower -> "下關～中關" to KeyLevelColors.mid
        else -> "跌破下關" to KeyLevelColors.lower
    }

    /** 缺少的代號在背景補抓（每檔每 30 分鐘最多一次） */
    suspend fun load(quotes: List<Quote>) {
        for (q in quotes) {
            runCatching { StockInfoRepo.keyPrices(q) }.getOrNull()?.let { AppState.keyPrices[q.symbol] = it }
        }
    }
}

/** 個股走勢上方的三關價列：上關、中關、下關與開關 */
@Composable
fun KeyLevelsBar(q: Quote, k: StockInfoRepo.KeyPrices?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (k != null && AppState.showKeyLevels) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Lv("上關", k.upper, KeyLevelColors.upper)
                    Lv("中關", k.mid, KeyLevelColors.mid)
                    Lv("下關", k.lower, KeyLevelColors.lower)
                }
            } else {
                Label(if (k == null) "三關價計算中…" else "三關價已隱藏", Modifier.weight(1f))
            }
            Text(
                "三關價", color = if (AppState.showKeyLevels) C.Text else C.Muted, fontSize = 12.sp,
                fontWeight = if (AppState.showKeyLevels) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (AppState.showKeyLevels) C.Selected else C.Card)
                    .clickable { AppState.updateShowKeyLevels(!AppState.showKeyLevels) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (k != null && AppState.showKeyLevels) {
            val (pos, color) = KeyLevelsUi.position(q.price, k)
            Label("依 ${k.basisDate.replace("-", "/")} 高 ${Fmt.price(k.prevHigh)}、低 ${Fmt.price(k.prevLow)} 計算 · 現價：$pos", color = color)
        }
    }
}

@Composable
private fun Lv(label: String, v: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label + " ", color = color, fontSize = 12.sp)
        NumText(Fmt.price(v), 12.sp)
    }
}
