package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.gross
import com.claudeetf.app.data.tradeGross
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.QuoteSource
import com.claudeetf.app.data.TradeOrigin
import com.claudeetf.app.data.TradeType
import com.claudeetf.app.ui.KeyValue
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.NumText
import com.claudeetf.app.ui.Routes
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.Fmt

/**
 * 資產核對：逐檔列出「股數 × 現價 × 匯率 = 台幣市值」與現金組成，
 * 標出可能輸入錯誤的交易（美股匯率異常、成交價與現價差距過大、股數異常），點一下直接修改。
 */
@Composable
fun AssetAuditDialog(nav: NavController, onClose: () -> Unit) {
    val total = AppState.totalAssetsTwd()
    val mv = AppState.totalMarketValueTwd()
    val rows = AppState.holdings.map { h ->
        val q = AppState.quote(h.symbol)
        val m = AppState.marketOf(h.symbol)
        val fx = AppState.fxFor(m)
        val price = q?.price ?: h.avgCost
        val v = AppState.marketValueTwd(h)
        val warn = buildList {
            if (q == null) add("沒有報價，用成本價計算")
            else if (q.source == QuoteSource.SAMPLE) add("示意報價，尚未更新")
            if (h.avgCost > 0 && (price / h.avgCost > 5 || price / h.avgCost < 0.2)) add("現價與成本差距超過 5 倍，請確認股數或成交價")
            if (m == Market.US && (h.avgFx < 20 || h.avgFx > 45)) add("平均匯率 ${Fmt.num(h.avgFx, 2)} 不合理")
        }
        Triple(h, Triple(price, fx, v), warn)
    }.sortedByDescending { it.second.third }

    // 現金組成
    val ts = AppState.trades.filter { it.origin != TradeOrigin.LEGACY }
    fun twd(t: com.claudeetf.app.data.Trade) = if (AppState.marketOf(t.symbol) == Market.US) t.fx else 1.0
    val buys = ts.filter { it.type == TradeType.BUY && it.origin != TradeOrigin.OPENING }.sumOf { (it.gross + it.fee) * twd(it) }
    val sells = ts.filter { it.type == TradeType.SELL }.sumOf { (it.gross - it.fee - it.tax) * twd(it) }
    val divs = ts.filter { it.type == TradeType.DIVIDEND }.sumOf { t ->
        val g = t.gross
        if (AppState.marketOf(t.symbol) == Market.US) g * 0.7 * t.fx else g - t.fee
    }
    val flows = AppState.cashFlowNet
    val base = AppState.cashTwd - sells - divs + buys - flows

    // 可疑交易
    val suspicious = ts.filter { it.type == TradeType.BUY || it.type == TradeType.SELL }.mapNotNull { t ->
        val us = AppState.marketOf(t.symbol) == Market.US
        val cur = AppState.quote(t.symbol)?.price
        val why = when {
            us && (t.fx < 20 || t.fx > 45) -> "匯率 ${Fmt.num(t.fx, 2)}（美股應約 28–35）"
            cur != null && cur > 0 && (t.price / cur > 10 || t.price / cur < 0.1) -> "成交價 ${Fmt.price(t.price)} 與現價 ${Fmt.price(cur)} 差距超過 10 倍"
            t.shares >= 1_000_000 -> "股數 ${Fmt.shares(t.shares)} 異常（台股 1 張 = 1,000 股）"
            (t.gross * (if (us) t.fx else 1.0)) > 50_000_000 -> "單筆金額超過 5,000 萬"
            else -> null
        }
        why?.let { t to it }
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = C.Card,
        title = { Text("資產核對", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyValue("總資產", "NT$" + Fmt.num(total))
                KeyValue("持股市值", "NT$" + Fmt.num(mv))
                KeyValue("現金", "NT$" + Fmt.num(AppState.cashTwd), if (AppState.cashTwd < 0) C.Red else C.Text)

                if (suspicious.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Red.copy(alpha = 0.12f)).padding(10.dp)) {
                        Text("⚠ 可能輸入錯誤的交易（點一下修改）", color = C.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        suspicious.forEach { (t, why) ->
                            Column(Modifier.fillMaxWidth().clickable { onClose(); nav.navigate(Routes.trade(t.symbol, t.id)) }.padding(vertical = 5.dp)) {
                                Text(t.date + " " + t.type.label + " " + t.symbol + " " + Fmt.shares(t.shares) + " 股 @ " + Fmt.price(t.price),
                                    color = C.Text, fontSize = 12.sp)
                                Text(why + " ›", color = C.Red, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Text("持股明細（股數 × 現價 × 匯率）", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                rows.forEach { (h, v, warn) ->
                    val (price, fx, twd) = v
                    val us = AppState.marketOf(h.symbol) == Market.US
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(C.Bg)
                        .clickable { onClose(); nav.navigate(Routes.stock(h.symbol)) }.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(h.symbol + " " + (AppState.quote(h.symbol)?.name ?: ""), Modifier.weight(1f), color = C.Text, fontSize = 13.sp, maxLines = 1)
                            NumText("NT$" + Fmt.num(twd), 13.sp, C.Text, FontWeight.Bold)
                        }
                        Label(Fmt.shares(h.shares) + " 股 × " + (if (us) "$" else "") + Fmt.price(price) + (if (us) " × " + Fmt.num(fx, 2) else "") +
                            " · 占 " + Fmt.num(if (mv > 0) twd / mv * 100 else 0.0, 1) + "%")
                        warn.forEach { Text("⚠ $it", color = C.Red, fontSize = 11.sp) }
                    }
                }

                Text("現金組成", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                KeyValue("初始現金（帳本設定）", "NT$" + Fmt.num(base))
                KeyValue("－ 買進", "NT$" + Fmt.num(buys))
                KeyValue("＋ 賣出", "NT$" + Fmt.num(sells))
                KeyValue("＋ 股息（淨額）", "NT$" + Fmt.num(divs))
                if (AppState.cashFlows.isNotEmpty()) KeyValue("± 現金收支（淨額）", (if (flows >= 0) "+" else "−") + "NT$" + Fmt.num(kotlin.math.abs(flows)))
                KeyValue("＝ 現金", "NT$" + Fmt.num(AppState.cashTwd), if (AppState.cashTwd < 0) C.Red else C.Text)
                if (AppState.cashTwd < 0) Label("現金為負：買進金額大於「初始現金＋賣出＋股息」。若沒有記錄入金，可到 我的 › 帳本 調整初始現金；若有上方⚠交易，先修正那筆。")
            }
        },
        confirmButton = { Text("關閉", color = C.Primary, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onClose).padding(12.dp)) },
    )
}
