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
import com.claudeetf.app.data.Trade
import com.claudeetf.app.data.TradeOrigin
import com.claudeetf.app.data.TradeType
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.Fmt

private data class CostBucket(
    var amount: Double = 0.0,
    var fees: Double = 0.0,
    var taxes: Double = 0.0,
    var count: Int = 0,
) {
    val total get() = fees + taxes
    val ratio get() = if (amount > 0) total / amount * 100 else 0.0
}

/** 交易成本分析：手續費、交易稅、占成交金額比例與節省建議 */
@Composable
fun CostScreen(nav: NavController) {
    val trades = AppState.trades.filter { it.origin == TradeOrigin.NORMAL && (it.type == TradeType.BUY || it.type == TradeType.SELL) }
    fun fx(t: Trade) = if (AppState.marketOf(t.symbol) == Market.US) t.fx else 1.0

    val all = CostBucket()
    val byYear = sortedMapOf<String, CostBucket>(compareByDescending { it })
    val byAccount = linkedMapOf<Int, CostBucket>()
    var twCount = 0
    var minHits = 0
    var stockSellTax = 0.0
    for (t in trades) {
        val f = fx(t)
        val amt = t.shares * t.price * f
        listOf(all, byYear.getOrPut(t.date.take(4)) { CostBucket() }, byAccount.getOrPut(t.account) { CostBucket() }).forEach {
            it.amount += amt; it.fees += t.fee * f; it.taxes += t.tax * f; it.count++
        }
        if (AppState.marketOf(t.symbol) == Market.TW) {
            twCount++
            val acc = AppState.account(t.account)
            val minFee = acc?.minFee ?: 20.0
            val disc = acc?.discount ?: 1.0
            if (t.fee <= minFee + 0.5 && amt * 0.001425 * disc < minFee) minHits++
            val etf = AppState.quote(t.symbol)?.isEtf == true || t.symbol.startsWith("00")
            if (t.type == TradeType.SELL && !etf) stockSellTax += t.tax
        }
    }

    val tips = ArrayList<String>()
    if (twCount > 0 && minHits.toDouble() / twCount >= 0.3) {
        val acc = AppState.accountsFor(Market.TW).firstOrNull()
        val breakEven = (acc?.minFee ?: 20.0) / (0.001425 * (acc?.discount ?: 1.0))
        tips += "有 ${Fmt.num(minHits * 100.0 / twCount, 0)}% 的台股交易被收最低手續費。單筆金額低於約 NT$${Fmt.num(breakEven)} 時，手續費率實際偏高，可考慮合併下單或改用定期定額。"
    }
    AppState.accountsFor(Market.TW).filter { it.discount >= 0.999 }.forEach {
        tips += "「${it.name}」尚未設定手續費折扣，多數券商電子下單有折扣，可到「我的 › 帳戶與手續費」設定，記帳時就會自動套用。"
    }
    if (stockSellTax > 0 && all.total > 0 && stockSellTax / all.total > 0.4) {
        tips += "股票的證交稅（0.3%）占你交易成本的 ${Fmt.num(stockSellTax / all.total * 100, 0)}%。ETF 證交稅只有 0.1%，頻繁進出時差距明顯。"
    }
    if (all.count > 0 && all.ratio > 0.5) {
        tips += "整體交易成本占成交金額 ${Fmt.num(all.ratio, 2)}%，偏高；降低交易頻率通常是最直接的節省方式。"
    }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("交易成本分析", onBack = { nav.popBackStack() })
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (trades.isEmpty()) {
                item { Label("還沒有買賣交易紀錄（期初持股不列入）。", Modifier.padding(vertical = 20.dp)) }
                return@LazyColumn
            }
            item {
                Card(padding = 14.dp) {
                    Label("累計交易成本（TWD）")
                    NumText(mask(Fmt.num(all.total)), 26.sp, weight = FontWeight.Bold)
                    KeyValue("手續費", mask("NT$" + Fmt.num(all.fees)))
                    KeyValue("交易稅", mask("NT$" + Fmt.num(all.taxes)))
                    KeyValue("成交金額", mask("NT$" + Fmt.num(all.amount)))
                    KeyValue("成本率", Fmt.num(all.ratio, 3) + "%")
                    KeyValue("買賣筆數", all.count.toString())
                    if (all.amount > 0) {
                        StackBar(listOf((all.fees / all.total).toFloat() to C.Primary, (all.taxes / all.total).toFloat() to C.Tw))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Label("■ 手續費", color = C.Primary); Label("■ 交易稅", color = C.Tw)
                        }
                    }
                }
            }
            if (tips.isNotEmpty()) {
                item { SectionTitle("節省建議") }
                items(tips) { t ->
                    Text(t, color = C.Text2, fontSize = 13.sp, lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).padding(12.dp))
                }
            }
            item { SectionTitle("依年度", "成本 · 成本率") }
            items(byYear.entries.toList()) { (y, b) -> CostRow(y + " 年（${b.count} 筆）", b) }
            item { SectionTitle("依帳戶", "成本 · 成本率") }
            items(byAccount.entries.toList()) { (id, b) ->
                CostRow((if (id == 0) "未指定帳戶" else AppState.account(id)?.name ?: "帳戶 $id") + "（${b.count} 筆）", b)
            }
            item {
                Text("調整帳戶手續費設定 ›", color = C.Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { nav.navigate(Routes.SETTINGS) }.padding(vertical = 8.dp))
            }
            item { Label("美股以每筆交易當時的匯率換算台幣。", Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun CostRow(title: String, b: CostBucket) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
        NumText(mask("NT$" + Fmt.num(b.total)), 13.sp)
        NumText(Fmt.num(b.ratio, 3) + "%", 12.sp, C.Muted, modifier = Modifier.width(70.dp).wrapContentWidth(Alignment.End))
    }
}
