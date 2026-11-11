package com.claudeetf.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.MarketData
import com.claudeetf.app.data.TradeOrigin
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.CrashLog
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.launch

// ============================== 首次使用引導 ==============================

@Composable
fun WelcomeScreen(nav: NavController) {
    var fresh by remember { mutableStateOf<Boolean?>(null) }
    var key by remember { mutableStateOf(AppState.fugleKey) }
    fun finish(route: String) {
        AppState.finishOnboarding()
        nav.popBackStack(Routes.WELCOME, inclusive = true)
        if (route != Routes.HOME) nav.navigate(route)
    }
    LazyColumn(
        Modifier.fillMaxSize().background(C.Bg),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 24.dp)) {
                Text("歡迎使用台美股資產", color = C.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Label("一個 App 管理台股與美股：總資產以台幣看全貌，個股與交易保留原幣細節。三個步驟就能開始。")
            }
        }
        item {
            Card(padding = 16.dp) {
                Text("1　你的資料", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Label("App 預設放了一組示意持股，方便先看看各畫面。")
                Choice("先用示意資料看看", "之後可在「我的 › 帳本」清除", fresh == false) { fresh = false }
                Choice("清除示意資料，從零開始", "持股、交易與損益歸零，自選清單保留", fresh == true) { fresh = true }
            }
        }
        item {
            Card(padding = 16.dp) {
                Text("2　台股即時行情（選填）", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Label("填入富果 Fugle API 金鑰，盤中就能看即時報價與五檔；不填則使用證交所收盤資料。美股與指數用 Yahoo Finance，不需金鑰。")
                OutlinedTextField(
                    key, { key = it; AppState.updateFugleKey(it) }, singleLine = true,
                    label = { Text("富果 API 金鑰（可稍後在「我的」設定）") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
                )
            }
        }
        item {
            Card(padding = 16.dp) {
                Text("3　開始使用", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                PrimaryButton("新增第一筆交易") {
                    if (fresh == true) AppState.clearSampleData()
                    finish(Routes.trade())
                }
                Choice("匯入券商對帳單 CSV", "到「我的 › 備份與匯入」選擇檔案", false) {
                    if (fresh == true) AppState.clearSampleData()
                    finish(Routes.settings("backup"))
                }
                Choice("直接進入 App", if (fresh == true) "清除示意資料後進入" else "保留示意資料", false) {
                    if (fresh == true) AppState.clearSampleData()
                    finish(Routes.HOME)
                }
            }
        }
    }
}

@Composable
private fun Choice(title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (on) C.Selected else C.Bg).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            Label(sub)
        }
        if (on) Text("✓", color = C.Primary, fontSize = 18.sp)
    }
}

// ============================== 全域搜尋 ==============================

private data class Feature(val name: String, val keywords: String, val route: String)

private val features = listOf(
    Feature("新增交易", "記帳 買進 賣出 股息 拆股", Routes.trade()),
    Feature("交易紀錄", "歷史 明細 編輯 刪除", Routes.TRADES),
    Feature("股息與稅務", "配息 二代健保 預扣", Routes.DIVIDEND),
    Feature("資料健檢", "檢查 錯誤 異常 重複 匯率 修正", Routes.HEALTH),
    Feature("年度回顧", "回顧 年度 總結 最佳月份 回撤", Routes.REVIEW),
    Feature("複利試算", "複利 股息再投入 DRIP 存股 試算 退休 目標", Routes.DRIP),
    Feature("美元匯率", "匯率 USD TWD 換匯 台銀", Routes.FX),
    Feature("交易成本分析", "手續費 交易稅 證交稅 折扣 成本", Routes.COST),
    Feature("年度報表", "報稅 綜所稅 海外所得 最低稅負 已實現", Routes.REPORT),
    Feature("績效分析", "報酬 XIRR 回測 0050 SPY 回撤", Routes.ANALYSIS),
    Feature("定期定額", "定額 扣款 DCA", Routes.DCA),
    Feature("目標配置與再平衡", "配置 再平衡 比例", Routes.REBALANCE),
    Feature("個股比較", "比較 對比", Routes.compare()),
    Feature("損益日曆", "日曆 每日損益 熱度", Routes.PNL),
    Feature("財經行事曆", "財報 除息 FOMC 休市", Routes.CALENDAR),
    Feature("提醒中心", "通知 價格提醒 推播", Routes.ALERTS),
    Feature("市場", "熱力圖 類股 排行 指數", Routes.MARKET),
    Feature("主題、配色與 App 圖示", "主題 深色 淺色 字級 漲跌 配色 顏色 圖示 icon", Routes.settings("look")),
    Feature("行情卡片樣式", "自選 行情 卡片 清單 格式 顯示 欄位", Routes.settings("watch")),
    Feature("持股卡片樣式", "持股 卡片 格式 顯示 現價方格", Routes.settings("hold")),
    Feature("首頁卡片與指數跑馬燈", "首頁 卡片 指數 跑馬燈", Routes.settings("pages")),
    Feature("個股頁與進場角色", "個股頁 進場 角色 OS 徽章 先投 預計投入", Routes.settings("stock")),
    Feature("K 棒分析條件", "國安基金 RSI KD 均線 條件", Routes.settings("kcond")),
    Feature("桌面小工具", "小工具 widget 桌面", Routes.settings("widget")),
    Feature("盤中持股監控", "監控 背景 盤中", Routes.settings("monitor")),
    Feature("行情資料來源", "富果 Finnhub 金鑰 證交所 資料來源 更新秒數", Routes.settings("source")),
    Feature("帳戶與手續費", "帳戶 券商 手續費 折扣", Routes.settings("accounts")),
    Feature("備份與匯入", "備份 還原 匯入 匯出 CSV 對帳單", Routes.settings("backup")),
    Feature("安全", "安全鎖 指紋 密碼", Routes.settings("security")),
    Feature("關於與更新", "版本 更新 關於 錯誤紀錄", Routes.settings("about")),
)

@Composable
fun SearchScreen(nav: NavController) {
    var q by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val text = q.trim()
    val quotes = if (text.isEmpty()) emptyList() else AppState.quotes.filter { it.symbol.contains(text, true) || it.name.contains(text, true) }
    val trades = if (text.isEmpty()) emptyList() else AppState.trades.filter { t ->
        t.origin != TradeOrigin.LEGACY && (t.symbol.contains(text, true) || t.note.contains(text, true) ||
            (AppState.quote(t.symbol)?.name?.contains(text, true) == true))
    }.take(20)
    val noteHits = if (text.isEmpty()) emptyList() else AppState.notes.entries.filter { it.value.first.contains(text, true) }.map { it.key }
    val feats = if (text.isEmpty()) features else features.filter { it.name.contains(text, true) || it.keywords.contains(text, true) }
    val canAdd = text.isNotEmpty() && text.all { it.isLetterOrDigit() || it == '.' } && !AppState.isWatched(text.uppercase())

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("搜尋", onBack = { nav.popBackStack() })
        OutlinedTextField(
            q, { q = it }, singleLine = true,
            placeholder = { Text("代號、名稱、交易備註或功能") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            colors = fieldColors(),
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (canAdd) {
                item {
                    Text(
                        "＋ 加入自選並開啟：" + text.uppercase(), color = C.Primary, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card)
                            .clickable {
                                scope.launch {
                                    MarketData.addAndFetch(text.uppercase())
                                    nav.navigate(Routes.stock(text.uppercase()))
                                }
                            }.padding(14.dp),
                    )
                }
            }
            if (quotes.isNotEmpty()) {
                item { SectionTitle("自選", "${quotes.size}") }
                items(quotes, key = { "q" + it.symbol }) { qq -> QuoteRow(qq) { nav.navigate(Routes.stock(qq.symbol)) } }
            }
            if (noteHits.isNotEmpty()) {
                item { SectionTitle("筆記", "${noteHits.size}") }
                items(noteHits, key = { "n$it" }) { sym ->
                    val n = AppState.notes[sym]?.first ?: ""
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card)
                            .clickable { nav.navigate(Routes.stock(sym)) }.padding(12.dp),
                    ) {
                        Text(sym + " " + (AppState.quote(sym)?.name ?: ""), color = C.Text, fontSize = 14.sp)
                        Label(n.replace("\n", " ").take(80))
                    }
                }
            }
            if (trades.isNotEmpty()) {
                item { SectionTitle("交易紀錄", "${trades.size}") }
                items(trades, key = { "t" + it.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card)
                            .clickable { nav.navigate(Routes.trade(t.symbol, t.id)) }.padding(12.dp),
                    ) {
                        Label(if (t.origin == TradeOrigin.OPENING) "期初" else t.date, Modifier.width(90.dp))
                        Text(t.type.label + " " + t.symbol + " " + (AppState.quote(t.symbol)?.name ?: ""), Modifier.weight(1f),
                            color = C.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        NumText(Fmt.shares(t.shares) + " 股", 12.sp, C.Muted)
                    }
                }
            }
            if (feats.isNotEmpty()) {
                item { SectionTitle("功能") }
                items(feats, key = { "f" + it.name }) { f ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card)
                            .clickable { nav.navigate(f.route) }.padding(14.dp),
                    ) {
                        Text(f.name, Modifier.weight(1f), color = C.Text, fontSize = 14.sp)
                        Label("›")
                    }
                }
            }
            if (text.isNotEmpty() && quotes.isEmpty() && trades.isEmpty() && noteHits.isEmpty() && feats.isEmpty() && !canAdd) {
                item { Label("找不到「$text」") }
            }
        }
    }
}

// ============================== 錯誤紀錄 ==============================

@Composable
fun ErrorLogScreen(nav: NavController) {
    val context = LocalContext.current
    var log by remember { mutableStateOf(CrashLog.read(context)) }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("錯誤紀錄", onBack = { nav.popBackStack() })
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val l = log
            if (l == null) {
                item { Label("沒有閃退紀錄。") }
            } else {
                item { Label("App 上次異常結束時的紀錄。可以分享給開發者協助修正。") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            PrimaryButton("分享") {
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, l)
                                runCatching { context.startActivity(Intent.createChooser(send, "分享錯誤紀錄")) }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            PrimaryButton("清除") { CrashLog.clear(context); log = null }
                        }
                    }
                }
                item {
                    Text(
                        l, color = C.Text2, fontSize = 11.sp, fontFamily = com.claudeetf.app.ui.theme.Mono,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card).padding(12.dp),
                    )
                }
            }
        }
    }
}
