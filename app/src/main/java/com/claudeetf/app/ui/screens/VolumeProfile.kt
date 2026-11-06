package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.Quote
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.HGap
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt

/** 分價量表的一個價位區間 */
data class PriceBin(val low: Double, val high: Double, val volume: Double) {
    val mid: Double get() = (low + high) / 2
}

object VolumeProfileCalc {
    /**
     * 將每根 K 棒的成交量依「典型價」(高+低+收)/3 歸入價位區間。
     * 沒有高低價時用收盤價；回傳由高價到低價排序。
     */
    fun bins(h: YahooExtra.History, start: Int, count: Int = 12): List<PriceBin> {
        if (h.volumes.isEmpty()) return emptyList()
        val idx = (start until h.closes.size).filter { i ->
            i < h.volumes.size && h.volumes[i] > 0 && !h.closes[i].isNaN()
        }
        if (idx.size < 3) return emptyList()
        fun typical(i: Int): Double {
            val c = h.closes[i].toDouble()
            val hi = h.highs.getOrNull(i)?.toDouble()?.takeIf { !it.isNaN() && it > 0 } ?: c
            val lo = h.lows.getOrNull(i)?.toDouble()?.takeIf { !it.isNaN() && it > 0 } ?: c
            return (hi + lo + c) / 3
        }
        val prices = idx.map { typical(it) }
        val min = prices.min()
        val max = prices.max()
        if (max <= min) return listOf(PriceBin(min, max, idx.sumOf { h.volumes[it].toDouble() }))
        val step = (max - min) / count
        val vol = DoubleArray(count)
        idx.forEachIndexed { k, i ->
            val b = ((prices[k] - min) / step).toInt().coerceIn(0, count - 1)
            vol[b] += h.volumes[i].toDouble()
        }
        return (count - 1 downTo 0).map { b -> PriceBin(min + step * b, min + step * (b + 1), vol[b]) }
    }
}

@Composable
fun VolumeProfileCard(q: Quote, h: YahooExtra.History, start: Int, rangeLabel: String) {
    val bins = VolumeProfileCalc.bins(h, start)
    Card(padding = 12.dp) {
        Text("分價量表（$rangeLabel）", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (bins.isEmpty()) {
            Label("此區間沒有成交量資料")
            return@Card
        }
        val maxVol = bins.maxOf { it.volume }.takeIf { it > 0 } ?: 1.0
        val total = bins.sumOf { it.volume }
        val poc = bins.maxBy { it.volume }
        val price = q.price
        bins.forEach { b ->
            val here = price >= b.low && price <= b.high + 1e-9
            val isPoc = b === poc
            Row(Modifier.fillMaxWidth().height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                NumText(
                    Fmt.price(b.mid), 11.sp,
                    if (here) C.Primary else C.Text2,
                    modifier = Modifier.width(64.dp),
                )
                Box(Modifier.weight(1f).height(12.dp)) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth((b.volume / maxVol).toFloat().coerceIn(0.01f, 1f))
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when {
                                    isPoc -> C.Primary
                                    b.mid >= q.prevClose -> Trend.fg(1.0).copy(alpha = 0.55f)
                                    else -> Trend.fg(-1.0).copy(alpha = 0.55f)
                                }
                            ),
                    )
                }
                HGap(6.dp)
                NumText(
                    Fmt.num(b.volume / total * 100, 1) + "%", 11.sp,
                    if (isPoc) C.Primary else C.Muted,
                    modifier = Modifier.width(44.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Label("最大量價位 " + Fmt.price(poc.low) + "–" + Fmt.price(poc.high), color = C.Primary)
            Label("總量 " + volumeText(total, q.market))
        }
        Label(
            if (price > poc.high) "現價在最大量區之上，該區可視為支撐參考"
            else if (price < poc.low) "現價在最大量區之下，該區可視為壓力參考"
            else "現價位於最大量區內",
        )
    }
}

/** 成交量文字：台股以「張」、美股以股數縮寫 */
fun volumeText(shares: Double, market: Market): String = when {
    market == Market.TW -> Fmt.num(shares / 1000) + " 張"
    shares >= 1e9 -> Fmt.num(shares / 1e9, 2) + "B"
    shares >= 1e6 -> Fmt.num(shares / 1e6, 2) + "M"
    shares >= 1e3 -> Fmt.num(shares / 1e3, 1) + "K"
    else -> Fmt.num(shares)
}
