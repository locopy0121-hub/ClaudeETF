package com.claudeetf.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.IndexQuote
import com.claudeetf.app.data.MarketData
import com.claudeetf.app.ui.HGap
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.Routes
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt

/** 指數跑馬燈可選的指數（代號 → 顯示名稱） */
val TICKER_CHOICES = linkedMapOf(
    "^TWII" to "加權指數", "^TWOII" to "櫃買指數",
    "^DJI" to "道瓊", "^GSPC" to "S&P 500", "^IXIC" to "那斯達克", "^SOX" to "費城半導體",
)

/**
 * 持股、自選行情頁頂部的指數跑馬燈（一行）：
 * TW 即時／盤後 加權指數 39,701.64 ▼ -1,873.06 (-4.50%)　US 即時／盤後 道瓊 …
 * 內容超過一行時自動左右捲動；點指數開啟指數頁。可在「我的 › 首頁與個股頁」開關與選擇指數。
 */
/** 時段排程：現在是否在設定的顯示時段內（「HH:mm-HH:mm」，結束早於開始代表跨午夜） */
fun tickerInSchedule(now: java.time.LocalDateTime = java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Taipei"))): Boolean {
    if (!AppState.tickerSched) return true
    if (AppState.tickerWeekdays && now.dayOfWeek.value >= 6) {
        // 週六清晨仍屬週五晚上的美股時段：跨午夜的時段只看開始那天
        val y = now.minusDays(1)
        if (now.dayOfWeek.value == 7 || y.dayOfWeek.value >= 6) return false
        return AppState.tickerWindows.any { w -> crossesMidnight(w) && inWindow(w, now.toLocalTime(), afterMidnightOnly = true) }
    }
    return AppState.tickerWindows.any { inWindow(it, now.toLocalTime()) }
}

private fun parseHm(s: String) = runCatching { java.time.LocalTime.parse(s.trim().padStart(5, '0')) }.getOrNull()
private fun crossesMidnight(w: String): Boolean {
    val (a, b) = w.split("-").let { (it.getOrNull(0)?.let(::parseHm)) to (it.getOrNull(1)?.let(::parseHm)) }
    return a != null && b != null && b < a
}
private fun inWindow(w: String, t: java.time.LocalTime, afterMidnightOnly: Boolean = false): Boolean {
    val p = w.split("-"); val a = p.getOrNull(0)?.let(::parseHm) ?: return false; val b = p.getOrNull(1)?.let(::parseHm) ?: return false
    return if (b >= a) !afterMidnightOnly && t >= a && t < b
    else (if (afterMidnightOnly) t < b else t >= a || t < b)
}

/**
 * inList：放在可捲動清單裡（隨頁面捲動）時為 true；固定置頂時為 false。
 * 依「我的 › 首頁與個股頁 › 指數跑馬燈」的設定決定是否顯示與位置。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IndexTicker(nav: NavController, inList: Boolean = false) {
    if (!AppState.tickerShow || AppState.tickerPinned == inList) return
    // 每 30 秒重新判斷是否在顯示時段內
    val inSched by androidx.compose.runtime.produceState(tickerInSchedule(), AppState.tickerSched, AppState.tickerWindows, AppState.tickerWeekdays) {
        while (true) { value = tickerInSchedule(); kotlinx.coroutines.delay(30_000) }
    }
    if (!inSched) return
    val all = AppState.twIndices + AppState.usIndices
    val list = AppState.tickerSymbols.mapNotNull { s -> all.firstOrNull { it.symbol == s }?.takeIf { it.value > 0 } }
    if (list.isEmpty()) return
    val tw = list.filter { it.symbol == "^TWII" || it.symbol == "^TWOII" }
    val us = list - tw.toSet()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (inList) 0.dp else 16.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp)).background(C.Card)
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .then(
                if (AppState.tickerAuto) Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 2000, initialDelayMillis = 1500, velocity = 32.dp)
                else Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tw.isNotEmpty()) Group("TW", C.Tw, MarketData.isTwIntraday(), tw, nav)
        if (tw.isNotEmpty() && us.isNotEmpty()) HGap(18.dp)
        if (us.isNotEmpty()) Group("US", C.Us, MarketData.usLive(), us, nav)
    }
}

@Composable
private fun Group(tag: String, color: androidx.compose.ui.graphics.Color, live: Boolean, items: List<IndexQuote>, nav: NavController) {
    Text(tag, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 5.dp, vertical = 1.dp))
    HGap(5.dp)
    // 盤中「即時」，其餘時段「盤後」
    Text(if (live) "即時" else "盤後", color = if (live) C.Red else C.Muted, fontSize = 11.sp)
    items.forEachIndexed { i, q ->
        HGap(if (i == 0) 6.dp else 14.dp)
        Row(Modifier.clickable { nav.navigate(Routes.index(q.symbol)) }, verticalAlignment = Alignment.CenterVertically) {
            Text(TICKER_CHOICES[q.symbol] ?: q.name, color = C.Text, fontSize = 13.sp, maxLines = 1)
            HGap(5.dp)
            NumText(Fmt.num(q.value, 2), 13.sp, C.Text, FontWeight.Bold)
            HGap(5.dp)
            val c = Trend.fg(q.changePct)
            NumText(Fmt.signed(q.change, 2), 12.sp, c)
            HGap(4.dp)
            NumText("(" + Fmt.pct(q.changePct) + ")", 12.sp, c)
        }
    }
}
