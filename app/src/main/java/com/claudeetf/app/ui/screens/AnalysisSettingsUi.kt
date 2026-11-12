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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AnalysisConfig
import com.claudeetf.app.data.AnalysisConfig.Cfg
import com.claudeetf.app.data.TwPicks
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.theme.C

/** 設置 › 分析與提醒 › 分析參數（進階）：推薦排行範圍、篩選門檻、顯示檔數、綜合分數權重、進場建議權重 */
@Composable
fun AnalysisSettings() {
    val c = AnalysisConfig.cfg
    val d = Cfg()
    fun up(f: (Cfg) -> Cfg) = AnalysisConfig.update(f)
    fun r1(x: Double) = Math.round(x * 10) / 10.0
    fun r2(x: Double) = Math.round(x * 100) / 100.0

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card {
            Text("分析參數（進階）", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Label("調整推薦排行、每日盤後報表與綜合進場建議的計算方式。改動分析範圍或篩選條件後，到市場頁按「重新分析」即可套用；權重與門檻下次計算時生效。")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(if (AnalysisConfig.isDefault) "目前為預設值" else "已自訂", Modifier.weight(1f), color = if (AnalysisConfig.isDefault) C.Muted else C.Primary)
                Text("全部恢復預設", color = if (AnalysisConfig.isDefault) C.Muted else C.Primary, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.Selected).clickable(enabled = !AnalysisConfig.isDefault) { AnalysisConfig.reset() }
                        .padding(horizontal = 10.dp, vertical = 5.dp))
                Text("立即重新分析", color = C.Primary, fontSize = 13.sp,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(C.Selected)
                        .clickable(enabled = !TwPicks.running) { TwPicks.start(force = true); TwPicks.startEtf(force = true) }
                        .padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }

        Card {
            Group("分析範圍（成交值前 N 檔）")
            NumRow("上市個股", "${c.tse} 檔", c.tse != d.tse, { up { it.copy(tse = (it.tse - 10).coerceAtLeast(30)) } }, { up { it.copy(tse = (it.tse + 10).coerceAtMost(300)) } })
            NumRow("上櫃個股", "${c.otc} 檔", c.otc != d.otc, { up { it.copy(otc = (it.otc - 10).coerceAtLeast(30)) } }, { up { it.copy(otc = (it.otc + 10).coerceAtMost(300)) } })
            NumRow("ETF", "${c.etf} 檔", c.etf != d.etf, { up { it.copy(etf = (it.etf - 10).coerceAtLeast(20)) } }, { up { it.copy(etf = (it.etf + 10).coerceAtMost(200)) } })
            Label("檔數越多越完整，但分析時間與流量越大（每檔需讀取一年日 K）。")
        }

        Card {
            Group("篩選條件")
            NumRow("個股最低股價", "${c.minPrice.toInt()} 元", c.minPrice != d.minPrice, { up { it.copy(minPrice = (it.minPrice - 5).coerceAtLeast(0.0)) } }, { up { it.copy(minPrice = (it.minPrice + 5).coerceAtMost(1000.0)) } })
            NumRow("個股最低成交量", "${c.minLots} 張", c.minLots != d.minLots, { up { it.copy(minLots = (it.minLots - 250).coerceAtLeast(0)) } }, { up { it.copy(minLots = (it.minLots + 250).coerceAtMost(100_000)) } })
            NumRow("ETF 最低成交量", "${c.etfMinLots} 張", c.etfMinLots != d.etfMinLots, { up { it.copy(etfMinLots = (it.etfMinLots - 50).coerceAtLeast(0)) } }, { up { it.copy(etfMinLots = (it.etfMinLots + 50).coerceAtMost(100_000)) } })
            NumRow("個股列入門檻", "分數 ±${r1(c.threshold)}", c.threshold != d.threshold, { up { it.copy(threshold = r1((it.threshold - 0.1).coerceAtLeast(0.0))) } }, { up { it.copy(threshold = r1((it.threshold + 0.1).coerceAtMost(5.0))) } })
            NumRow("ETF 列入門檻", "分數 ±${r1(c.etfThreshold)}", c.etfThreshold != d.etfThreshold, { up { it.copy(etfThreshold = r1((it.etfThreshold - 0.1).coerceAtLeast(0.0))) } }, { up { it.copy(etfThreshold = r1((it.etfThreshold + 0.1).coerceAtMost(5.0))) } })
            Label("門檻越高，名單越少但訊號越明確。")
        }

        Card {
            Group("顯示檔數")
            NumRow("市場頁・個股每邊", "${c.top} 檔", c.top != d.top, { up { it.copy(top = (it.top - 1).coerceAtLeast(3)) } }, { up { it.copy(top = (it.top + 1).coerceAtMost(30)) } })
            NumRow("市場頁・ETF 每邊", "${c.etfTop} 檔", c.etfTop != d.etfTop, { up { it.copy(etfTop = (it.etfTop - 1).coerceAtLeast(3)) } }, { up { it.copy(etfTop = (it.etfTop + 1).coerceAtMost(30)) } })
            NumRow("盤後報表・個股每邊", "${c.reportTop} 檔", c.reportTop != d.reportTop, { up { it.copy(reportTop = (it.reportTop - 5).coerceAtLeast(5)) } }, { up { it.copy(reportTop = (it.reportTop + 5).coerceAtMost(50)) } })
            NumRow("盤後報表・ETF 每邊", "${c.reportEtfTop} 檔", c.reportEtfTop != d.reportEtfTop, { up { it.copy(reportEtfTop = (it.reportEtfTop - 5).coerceAtLeast(5)) } }, { up { it.copy(reportEtfTop = (it.reportEtfTop + 5).coerceAtMost(40)) } })
        }

        Card {
            Group("推薦排行・綜合分數權重")
            Label("各項分數乘上倍數後加總（1.0＝預設，0＝不採計）。")
            fun w(title: String, v: Double, set: (Cfg, Double) -> Cfg) =
                Triple(title, v, set)
            listOf(
                w("日 K 分析（形態、量價、支撐壓力）", c.wKline) { x, v -> x.copy(wKline = v) },
                w("與 60 日均線距離", c.wMa60) { x, v -> x.copy(wMa60 = v) },
                w("近 20 日動能", c.wMom) { x, v -> x.copy(wMom = v) },
                w("進場建議等級", c.wEntry) { x, v -> x.copy(wEntry = v) },
                w("一年股價光譜", c.wZone) { x, v -> x.copy(wZone = v) },
            ).forEach { (t, v, set) -> WeightRow(t, v) { nv -> up { set(it, nv) } } }
        }

        Card {
            Group("綜合進場建議・各面向權重")
            Label("個股頁「綜合進場建議」彙整各項指標；可加重或降低某一面向的影響。")
            listOf(
                Triple("技術面（K 線、策略、條件）", c.gTech) { x: Cfg, v: Double -> x.copy(gTech = v) },
                Triple("價位（光譜、三關價）", c.gValue) { x: Cfg, v: Double -> x.copy(gValue = v) },
                Triple("基本面（營收、股息）", c.gFund) { x: Cfg, v: Double -> x.copy(gFund = v) },
                Triple("籌碼（法人、融資）", c.gChip) { x: Cfg, v: Double -> x.copy(gChip = v) },
                Triple("大盤（相對強弱、類股、VIX）", c.gMarket) { x: Cfg, v: Double -> x.copy(gMarket = v) },
                Triple("風險（波動）", c.gRisk) { x: Cfg, v: Double -> x.copy(gRisk = v) },
                Triple("ETF 專屬指標", c.gEtf) { x: Cfg, v: Double -> x.copy(gEtf = v) },
            ).forEach { (t, v, set) -> WeightRow(t, v) { nv -> up { set(it, nv) } } }
            NumRow("單一指標權重上限", "${Math.round(c.cap * 100)}%", c.cap != d.cap,
                { up { it.copy(cap = r2((it.cap - 0.02).coerceAtLeast(0.08))) } }, { up { it.copy(cap = r2((it.cap + 0.02).coerceAtMost(1.0))) } })
            NumRow("「建議進場」門檻", "綜合 ≥ ${r2(c.strongTh)}", c.strongTh != d.strongTh,
                { up { it.copy(strongTh = r2((it.strongTh - 0.05).coerceAtLeast(maxOf(0.3, it.entryTh + 0.05)))) } }, { up { it.copy(strongTh = r2((it.strongTh + 0.05).coerceAtMost(2.0))) } })
            NumRow("「可分批」門檻", "綜合 ≥ ${r2(c.entryTh)}", c.entryTh != d.entryTh,
                { up { it.copy(entryTh = r2((it.entryTh - 0.05).coerceAtLeast(0.05))) } }, { up { it.copy(entryTh = r2((it.entryTh + 0.05).coerceAtMost(it.strongTh - 0.05))) } })
            Label("上限越低，越不會被單一指標主導；門檻越高，「建議進場」越少越保守（偏空門檻對稱套用）。")
        }
    }
}

@Composable
private fun Group(t: String) { Text(t, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold) }

@Composable
private fun NumRow(title: String, value: String, changed: Boolean, minus: () -> Unit, plus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = C.Text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        RepeatBtn("－", true, minus)
        Text(value, color = if (changed) C.Primary else C.Text2, fontSize = 13.sp, fontWeight = if (changed) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.widthIn(min = 76.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        RepeatBtn("＋", true, plus)
    }
}

@Composable
private fun WeightRow(title: String, v: Double, set: (Double) -> Unit) {
    fun r1(x: Double) = Math.round(x * 10) / 10.0
    NumRow(title, if (v == 0.0) "不採計" else "×" + r1(v), v != 1.0, { set(r1((v - 0.1).coerceAtLeast(0.0))) }, { set(r1((v + 0.1).coerceAtMost(3.0))) })
}
