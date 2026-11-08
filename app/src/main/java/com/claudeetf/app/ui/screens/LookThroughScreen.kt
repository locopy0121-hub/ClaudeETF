package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.EtfComposition
import com.claudeetf.app.data.EtfTags
import com.claudeetf.app.data.LookThrough
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.KeyValue
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.Routes
import com.claudeetf.app.ui.SectionTitle
import com.claudeetf.app.ui.TopBar
import com.claudeetf.app.ui.mask
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.Fmt

/** 金額：元以下無條件捨去；比例：捨去至小數第 2 位 */
private fun yuan(v: Double) = "NT$" + Fmt.num(Math.floor(v))
private fun pctT(v: Double) = Fmt.numTrunc(v, 2) + "%"

/**
 * 穿透持股：持有的 ETF 依成分權重拆回個股，與直接持股合併，
 * 看實際曝險最大的個股、集中度與 ETF 之間的重疊。
 */
@Composable
fun LookThroughScreen(nav: NavController) {
    var result by remember { mutableStateOf<LookThrough.Result?>(null) }
    var progress by remember { mutableStateOf("") }
    var missing by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(AppState.holdings.size) {
        val miss = ArrayList<String>()
        val pos = AppState.holdings.mapNotNull { h ->
            val q = AppState.quote(h.symbol) ?: return@mapNotNull null
            val v = AppState.marketValueTwd(h)
            if (EtfTags.isEtf(q)) {
                progress = "載入 ${h.symbol} 成分…"
                val c = runCatching { EtfComposition.cached(q) }.getOrNull()
                if (c == null || c.rows.isEmpty()) { miss += h.symbol; LookThrough.Position(h.symbol, q.name, v, listOf()) }
                else LookThrough.Position(h.symbol, q.name, v, c.rows.map { Triple(it.symbol, it.name, it.weight) })
            } else LookThrough.Position(h.symbol, q.name, v, null)
        }
        missing = miss
        result = LookThrough.compute(pos)
        progress = ""
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("穿透持股", onBack = { nav.popBackStack() })
        val r = result
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (r == null) { item { Label(progress.ifBlank { "計算中…" }) }; return@LazyColumn }
            if (r.total <= 0) { item { Label("目前沒有持股") }; return@LazyColumn }
            val stocks = r.exposures
            val top5 = stocks.take(5).sumOf { it.total } / r.total * 100
            val top10 = stocks.take(10).sumOf { it.total } / r.total * 100
            item {
                Card(padding = 14.dp) {
                    Text("實際持有的個股", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Label("把 ETF 依成分權重拆回個股後，你的資金實際分散在 ${stocks.size} 檔個股")
                    KeyValue("組合總市值", mask(yuan(r.total)))
                    KeyValue("前 5 大個股合計", pctT(top5), if (top5 >= 50) C.Red else C.Text)
                    KeyValue("前 10 大個股合計", pctT(top10))
                    if (r.other.isNotEmpty()) KeyValue("未揭露／現金、期貨等", mask(yuan(r.other.values.sum())) + "（" + pctT(r.other.values.sum() / r.total * 100) + "）", C.Muted)
                    if (missing.isNotEmpty()) Label("⚠ ${missing.joinToString("、")} 取不到成分資料，整筆計入「未揭露」", color = C.Red)
                    if (top5 >= 50) Label("前 5 大占一半以上，集中度偏高；不同 ETF 可能重複持有相同權值股。", color = C.Red)
                }
            }
            if (r.overlap.isNotEmpty()) {
                item { SectionTitle("ETF 重疊度", "兩檔 ETF 共同持股的權重重疊") }
                items(r.overlap.take(10), key = { "o" + it.first + it.second }) { (a, b, ov) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$a × $b", Modifier.weight(1f), color = C.Text, fontSize = 14.sp)
                        val c = when { ov >= 50 -> C.Red; ov >= 25 -> Color(0xFFE6C34A); else -> C.Primary }
                        NumText(pctT(ov), 14.sp, c, FontWeight.Bold)
                        Text(when { ov >= 50 -> "  高度重疊"; ov >= 25 -> "  部分重疊"; else -> "  分散" }, color = c, fontSize = 12.sp)
                    }
                }
            }
            item { SectionTitle("個股曝險排行", "直接持有＋經由 ETF") }
            items(if (showAll) stocks else stocks.take(30), key = { "e" + it.symbol }) { e ->
                val pct = e.total / r.total * 100
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card)
                    .clickable(enabled = AppState.quote(e.symbol) != null) { nav.navigate(Routes.stock(e.symbol)) }.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, color = C.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            NumText(e.symbol, 11.sp, C.Muted)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            NumText(mask(yuan(e.total)), 14.sp, C.Text, FontWeight.Bold)
                            NumText(pctT(pct), 12.sp, C.Primary)
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Line)) {
                        Box(Modifier.fillMaxWidth((pct / (stocks.first().total / r.total * 100)).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(C.Primary))
                    }
                    // 來源：直接持有／各 ETF
                    val parts = buildList {
                        if (e.direct > 0) add("直接 " + mask(yuan(e.direct)))
                        e.via.entries.sortedByDescending { it.value }.forEach { (etf, v) -> add("$etf " + mask(yuan(v))) }
                    }
                    Label(parts.joinToString(" · "), Modifier.padding(top = 4.dp))
                }
            }
            if (!showAll && stocks.size > 30) item {
                Text("顯示全部 ${stocks.size} 檔", color = C.Primary, fontSize = 13.sp, modifier = Modifier.clickable { showAll = true }.padding(8.dp))
            }
            item {
                Card(padding = 12.dp) {
                    Text("計算方式", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Label("經由 ETF 的曝險 ＝ ETF 市值 × 成分權重 ÷ 100\n個股總曝險 ＝ 直接持有市值 ＋ Σ 各 ETF 曝險\n占比 ＝ 個股總曝險 ÷ 組合總市值 × 100\n重疊度 ＝ Σ min(兩檔 ETF 對同一個股的權重)\n金額元以下捨去、比例捨去至小數第 2 位；美股以目前匯率換算台幣。成分權重以最近公布的持股為準。")
                }
            }
        }
    }
}
