package com.claudeetf.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.TaxKind
import com.claudeetf.app.data.TaxRules
import com.claudeetf.app.data.TradeOrigin
import com.claudeetf.app.data.TradeType
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import java.time.LocalDate

/** 年度報表（報稅參考）：所有金額為台幣 */
data class YearReport(
    val year: Int,
    val realizedTw: Double,
    val realizedUs: Double,
    val realizedBySymbol: List<Pair<String, Double>>,
    val divTwGross: Double,
    val divTwTaxable: Double,
    val nhi: Double,
    val creditable: Double,
    val divTwOverseas: Double,
    val divUsGross: Double,
    val divUsWithheld: Double,
    val overseasIncome: Double,
    val fees: Double,
    val taxes: Double,
    val buyCount: Int,
    val sellCount: Int,
    val divCount: Int,
)

object ReportCalc {
    const val OVERSEAS_THRESHOLD = 1_000_000.0
    const val CREDIT_RATE = 0.085
    const val CREDIT_CAP = 80_000.0

    fun build(year: Int): YearReport {
        val y = year.toString()
        val ts = AppState.trades.filter { it.origin == TradeOrigin.NORMAL && it.date.startsWith(y) }
        var rTw = 0.0; var rUs = 0.0
        val bySym = HashMap<String, Double>()
        var dGross = 0.0; var dTaxable = 0.0; var nhi = 0.0; var dOverseas = 0.0
        var usGross = 0.0; var usWh = 0.0
        var fees = 0.0; var taxes = 0.0
        for (t in ts) {
            val us = AppState.marketOf(t.symbol) == Market.US
            val fx = if (us) t.fx else 1.0
            when (t.type) {
                TradeType.SELL -> {
                    val r = AppState.realizedById[t.id] ?: 0.0
                    if (us) rUs += r else rTw += r
                    bySym[t.symbol] = (bySym[t.symbol] ?: 0.0) + r
                    fees += t.fee * fx; taxes += t.tax * fx
                }
                TradeType.BUY -> fees += t.fee * fx
                TradeType.DIVIDEND -> {
                    val gross = t.shares * t.price * fx
                    if (us) {
                        usGross += gross; usWh += gross * TaxRules.US_WITHHOLD
                    } else {
                        val kind = TaxRules.kindOf(t.symbol)
                        if (kind == TaxKind.TW_ETF_BOND || kind == TaxKind.TW_ETF_OVERSEAS) dOverseas += gross
                        else { dGross += gross; dTaxable += gross * TaxRules.taxableRatio(t.symbol) }
                        nhi += t.fee
                    }
                }
                TradeType.SPLIT -> {}
            }
        }
        return YearReport(
            year, rTw, rUs, bySym.entries.sortedByDescending { kotlin.math.abs(it.value) }.map { it.key to it.value },
            dGross, dTaxable, nhi, minOf(CREDIT_CAP, dTaxable * CREDIT_RATE), dOverseas, usGross, usWh,
            rUs + usGross + dOverseas, fees, taxes,
            ts.count { it.type == TradeType.BUY }, ts.count { it.type == TradeType.SELL }, ts.count { it.type == TradeType.DIVIDEND },
        )
    }

    fun csv(r: YearReport): String {
        val sb = StringBuilder("﻿項目,金額（TWD）\n")
        fun row(k: String, v: Double) = sb.append(k).append(",").append(Math.round(v)).append("\n")
        row("${r.year} 台股已實現損益", r.realizedTw)
        row("${r.year} 美股已實現損益", r.realizedUs)
        row("台股股利總額", r.divTwGross)
        row("股利所得（應稅部分，估）", r.divTwTaxable)
        row("可抵減稅額（8.5%，上限 8 萬，估）", r.creditable)
        row("二代健保補充保費", r.nhi)
        row("國內 ETF 海外所得配息", r.divTwOverseas)
        row("美股股息（稅前）", r.divUsGross)
        row("美股預扣稅 30%", r.divUsWithheld)
        row("海外所得合計（估）", r.overseasIncome)
        row("手續費", r.fees)
        row("交易稅", r.taxes)
        sb.append("\n個股已實現損益\n代號,金額（TWD）\n")
        r.realizedBySymbol.forEach { (s, v) -> sb.append(s).append(",").append(Math.round(v)).append("\n") }
        sb.append("\n本表依 App 交易紀錄估算，僅供報稅參考，請以券商與國稅局資料為準。\n")
        return sb.toString()
    }
}

@Composable
fun ReportScreen(nav: NavController) {
    val context = LocalContext.current
    val thisYear = LocalDate.now().year
    var year by remember { mutableIntStateOf(thisYear) }
    val r = remember(year, AppState.trades.size, AppState.realizedById.size) { ReportCalc.build(year) }
    var msg by remember { mutableStateOf<String?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) msg = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(ReportCalc.csv(r).toByteArray(Charsets.UTF_8)) }
            "報表已匯出"
        }.getOrElse { "匯出失敗" }
    }
    fun m(v: Double) = mask("NT$" + Fmt.num(v))

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("年度報表", onBack = { nav.popBackStack() }) {
            Text("匯出", color = C.Primary, fontSize = 14.sp, modifier = Modifier.clickable { export.launch("ClaudeETF-${year}年度報表.csv") }.padding(12.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", color = C.Primary, fontSize = 24.sp, modifier = Modifier.clickable { year-- }.padding(12.dp))
                    Text("$year 年", Modifier.weight(1f), color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("›", color = if (year < thisYear) C.Primary else C.Muted, fontSize = 24.sp,
                        modifier = Modifier.clickable { if (year < thisYear) year++ }.padding(12.dp))
                }
            }
            msg?.let { t -> item { Label(t, color = C.Primary) } }
            item {
                Text("年度回顧（資產變化、最佳月份、股息成長） ›", color = C.Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { nav.navigate(Routes.REVIEW) }.padding(vertical = 4.dp))
            }
            item {
                Text("交易成本分析（手續費、交易稅、節省建議） ›", color = C.Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { nav.navigate(Routes.COST) }.padding(vertical = 4.dp))
            }
            item {
                Card(padding = 14.dp) {
                    Text("已實現損益", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    KeyValue("台股（證所稅停徵）", m(r.realizedTw), Trend.fg(r.realizedTw))
                    KeyValue("美股（海外財產交易所得）", m(r.realizedUs), Trend.fg(r.realizedUs))
                    KeyValue("買進／賣出／股息筆數", "${r.buyCount} / ${r.sellCount} / ${r.divCount}")
                    KeyValue("手續費", m(r.fees))
                    KeyValue("交易稅", m(r.taxes))
                }
            }
            item {
                Card(padding = 14.dp) {
                    Text("台股股利（綜合所得稅）", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    KeyValue("股利總額", m(r.divTwGross))
                    KeyValue("股利所得（應稅部分，估）", m(r.divTwTaxable))
                    KeyValue("合併計稅可抵減稅額（估）", m(r.creditable))
                    KeyValue("已扣二代健保", m(r.nhi))
                    Label("合併計稅：股利 × 8.5% 可抵減，每戶上限 8 萬元；分開計稅：股利所得按 28% 單獨計算。應稅比例依各檔目前設定估算。")
                }
            }
            item {
                Card(padding = 14.dp) {
                    Text("海外所得（最低稅負制）", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    KeyValue("美股已實現損益", m(r.realizedUs), Trend.fg(r.realizedUs))
                    KeyValue("美股股息（稅前）", m(r.divUsGross))
                    KeyValue("美股已預扣 30%", m(r.divUsWithheld))
                    KeyValue("國內債券／海外 ETF 配息", m(r.divTwOverseas))
                    KeyValue("海外所得合計（估）", m(r.overseasIncome), if (r.overseasIncome >= ReportCalc.OVERSEAS_THRESHOLD) C.Red else C.Text)
                    Label(
                        if (r.overseasIncome >= ReportCalc.OVERSEAS_THRESHOLD) "海外所得全年合計已達 100 萬元，須計入基本所得額申報。"
                        else "海外所得全年合計未達 100 萬元者免計入基本所得額。",
                    )
                }
            }
            if (r.realizedBySymbol.isNotEmpty()) {
                item { SectionTitle("個股已實現損益") }
                items(r.realizedBySymbol) { (s, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(s + " " + (AppState.quote(s)?.name ?: ""), Modifier.weight(1f), color = C.Text, fontSize = 13.sp, maxLines = 1)
                        NumText(mask(Fmt.signed(v)), 13.sp, Trend.fg(v))
                    }
                }
            }
            item {
                Label("本表依 App 內的交易紀錄估算（不含期初持股與舊版紀錄），僅供報稅參考；請以券商年度對帳單與國稅局資料為準。", Modifier.padding(top = 6.dp))
            }
        }
    }
}
