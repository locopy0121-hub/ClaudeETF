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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.QuoteSource
import com.claudeetf.app.data.TradeOrigin
import com.claudeetf.app.data.TradeType
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.Fmt
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 一個問題：嚴重度（2 錯誤／1 注意）、說明、可點擊開啟的交易 */
data class Issue(val level: Int, val title: String, val detail: String, val tradeId: Long? = null, val symbol: String? = null)

object HealthCheck {
    fun run(): List<Issue> {
        val out = ArrayList<Issue>()
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        val ts = AppState.trades.filter { it.origin != TradeOrigin.LEGACY }
        for (t in ts) {
            val name = t.symbol + " " + (AppState.quote(t.symbol)?.name ?: "")
            val head = (if (t.origin == TradeOrigin.OPENING) "期初" else t.date) + " " + t.type.label + " " + name
            val us = AppState.marketOf(t.symbol) == Market.US
            if (t.type != TradeType.SPLIT && t.shares <= 0) out += Issue(2, "股數為 0", head, t.id)
            if (t.price <= 0) out += Issue(2, "價格為 0", head, t.id)
            if (us && t.type != TradeType.SPLIT && (t.fx < 20 || t.fx > 45)) {
                out += Issue(2, "匯率異常", head + " · 匯率 " + Fmt.num(t.fx, 3) + "（合理範圍約 28–35）", t.id)
            }
            if (t.origin == TradeOrigin.NORMAL && t.date > today) out += Issue(1, "未來日期", head, t.id)
            if (t.type == TradeType.DIVIDEND) {
                val px = AppState.quote(t.symbol)?.price ?: 0.0
                if (px > 0 && t.price > px * 0.3) {
                    out += Issue(1, "每股股息偏高", head + " · 每股 " + Fmt.num(t.price, 4) + "，可能把總額填成每股金額", t.id)
                }
            }
            if (t.type == TradeType.BUY || t.type == TradeType.SELL) {
                val amt = t.shares * t.price
                if (!us && amt > 0 && t.fee > amt * 0.01) out += Issue(1, "手續費偏高", head + " · 手續費 " + Fmt.num(t.fee) + " 元", t.id)
                if (!us && t.type == TradeType.BUY && t.shares >= 1 && t.shares % 1000 != 0.0 && t.shares > 1000) {
                    out += Issue(1, "股數非整張", head + " · " + Fmt.shares(t.shares) + " 股，若為零股請忽略", t.id)
                }
            }
        }
        // 重複交易
        ts.filter { it.origin == TradeOrigin.NORMAL }
            .groupBy { listOf(it.date, it.symbol, it.type, it.shares, it.price) }
            .filter { it.value.size > 1 }
            .forEach { (_, list) ->
                val t = list.first()
                out += Issue(1, "疑似重複交易", t.date + " " + t.type.label + " " + t.symbol + " 共 ${list.size} 筆相同", t.id)
            }
        if (AppState.cashTwd < 0) {
            out += Issue(1, "現金為負", "目前現金 " + Fmt.num(AppState.cashTwd) + " 元，可能漏記入金或期初現金；可到「我的 › 帳本」調整")
        }
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        AppState.holdings.forEach { h ->
            val q = AppState.quote(h.symbol)
            when {
                q == null || q.price <= 0 -> out += Issue(2, "沒有報價", h.symbol + " 無法取得價格，市值以成本計算", symbol = h.symbol)
                q.source == QuoteSource.SAMPLE -> out += Issue(1, "使用示意價格", h.symbol + " 尚未從資料來源更新", symbol = h.symbol)
                q.updatedAt in 1 until weekAgo -> out += Issue(1, "報價過舊", h.symbol + " 最後更新 " + Fmt.time(q.updatedAt), symbol = h.symbol)
                else -> {}
            }
        }
        return out.sortedByDescending { it.level }
    }
}

@Composable
fun HealthScreen(nav: NavController) {
    var tick by remember { mutableIntStateOf(0) }
    val issues = remember(tick, AppState.trades.size, AppState.holdings.size) { HealthCheck.run() }
    val errors = issues.count { it.level == 2 }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("資料健檢", onBack = { nav.popBackStack() }) {
            Text("重新檢查", color = C.Primary, fontSize = 14.sp, modifier = Modifier.clickable { tick++ }.padding(12.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(padding = 14.dp) {
                    Text(
                        if (issues.isEmpty()) "資料看起來都正常" else "發現 ${issues.size} 個項目（$errors 個需要修正）",
                        color = if (errors > 0) C.Red else C.Primary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    )
                    Label("檢查交易紀錄、匯率、股息、重複交易、現金與報價。點項目可直接修改。")
                }
            }
            items(issues) { i ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)
                        .clickable(enabled = i.tradeId != null || i.symbol != null) {
                            when {
                                i.tradeId != null -> AppState.trade(i.tradeId)?.let { nav.navigate(Routes.trade(it.symbol, it.id)) }
                                i.symbol != null -> nav.navigate(Routes.stock(i.symbol))
                            }
                        }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (i.level == 2) "●" else "○", color = if (i.level == 2) C.Red else C.Tw, fontSize = 14.sp)
                    HGap(10.dp)
                    Column(Modifier.weight(1f)) {
                        Text(i.title, color = C.Text, fontSize = 14.sp)
                        Label(i.detail)
                    }
                    if (i.tradeId != null || i.symbol != null) Label("›")
                }
            }
        }
    }
}
