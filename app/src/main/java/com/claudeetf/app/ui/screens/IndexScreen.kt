package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.MarketData
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.data.remote.friendly
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class IdxRange(val label: String, val fetch: String, val days: Long?, val intraday: Boolean = false)

private val idxRanges = listOf(
    IdxRange("1日", "1d", null, true), IdxRange("5日", "5d", null, true), IdxRange("1月", "6mo", 31),
    IdxRange("3月", "1y", 92), IdxRange("6月", "1y", 183), IdxRange("1年", "2y", 366), IdxRange("5年", "5y", null),
)

/** 指數走勢頁：分時（盤中每 30 秒更新）、K 線、均線與指標 */
@Composable
fun IndexScreen(nav: NavController, symbol: String) {
    val info = (AppState.twIndices + AppState.usIndices + AppState.macro).firstOrNull { it.symbol == symbol }
    val name = info?.name ?: symbol
    val tw = symbol == "^TWII" || symbol == "^TWOII"
    var range by remember { mutableIntStateOf(0) }
    var type by remember { mutableStateOf(ChartType.LINE) }
    var mas by remember { mutableStateOf(setOf<Int>()) }
    var sub by remember { mutableStateOf(SubChart.VOLUME) }
    var hist by remember { mutableStateOf<Load<YahooExtra.History>>(Load.Loading) }
    LaunchedEffect(symbol, range) {
        hist = Load.Loading
        suspend fun load() = runCatching { withContext(Dispatchers.IO) { YahooExtra.history(symbol, idxRanges[range].fetch) } }
        hist = load().fold({ Load.Ok(it) }, { Load.Err(it.friendly()) })
        while (range == 0) {
            delay(30_000)
            val open = if (tw) MarketData.isTwIntraday() else MarketData.usLive()
            if (open) load().onSuccess { hist = Load.Ok(it) }
        }
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar(name, onBack = { nav.popBackStack() })
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Label(symbol)
                    Row(verticalAlignment = Alignment.Bottom) {
                        NumText(info?.let { Fmt.num(it.value, 2) } ?: "—", 32.sp, weight = FontWeight.Bold)
                        HGap(10.dp)
                        info?.let { NumText(Fmt.signed(it.change, 2) + " 點（" + Fmt.pct(it.changePct) + "）", 14.sp, Trend.fg(it.changePct), modifier = Modifier.padding(bottom = 5.dp)) }
                    }
                    info?.takeIf { it.updatedAt > 0 }?.let { Label("更新 " + Fmt.time(it.updatedAt) + if (tw && MarketData.isTwIntraday()) " · 證交所即時" else "") }
                }
            }
            item { Segmented(idxRanges.map { it.label }, range, { range = it }) }
            item { ChartControls(type, { type = it }, mas, { m -> mas = if (m in mas) mas - m else mas + m }, sub, { sub = it }, idxRanges[range].intraday) }
            item {
                when (val h = hist) {
                    is Load.Loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Label("載入走勢中…") }
                    is Load.Err -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { Label("走勢載入失敗：" + h.message) }
                    is Load.Ok -> {
                        val v = h.value
                        if (v.closes.size < 2) Label("此區間沒有走勢資料")
                        else {
                            val opt = idxRanges[range]
                            val start = opt.days?.let { d ->
                                val cutoff = System.currentTimeMillis() - d * 86_400_000
                                v.times.indexOfFirst { it >= cutoff }.takeIf { it >= 0 }
                            } ?: 0
                            StockChart(v, start, type, mas, sub, if (range == 0) v.prevClose?.toFloat() else null, opt.intraday)
                        }
                    }
                }
            }
            // 歷年報酬格圖（加權指數、櫃買、美股指數都適用）
            item { YearlyReturnsBlock(symbol, tw, info?.value, isIndex = true) }
            item { Label("走勢資料來源 Yahoo Finance；台股指數報價盤中以證交所即時更新。", Modifier.padding(top = 6.dp)) }
        }
    }
}
