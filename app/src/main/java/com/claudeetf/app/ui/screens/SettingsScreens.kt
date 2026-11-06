package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.os.Build
import com.claudeetf.app.alerts.AlertEngine
import com.claudeetf.app.data.AlertType
import com.claudeetf.app.data.Backup
import com.claudeetf.app.data.TradeCsv
import com.claudeetf.app.data.Account
import com.claudeetf.app.data.Market
import com.claudeetf.app.util.Fmt
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.claudeetf.app.ui.theme.Trend
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.BuildConfigInfo
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.ColorMode
import com.claudeetf.app.data.MarketData
import kotlinx.coroutines.launch
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C

// ============================== 提醒中心 ==============================

@Composable
fun AlertsScreen(nav: NavController, initialSymbol: String = "") {
    val context = LocalContext.current
    var filter by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(initialSymbol.isNotBlank()) }
    var granted by remember { mutableStateOf(AlertEngine.hasPermission(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val list = AppState.alerts.filter {
        when (filter) {
            1 -> it.isPrice
            2 -> !it.isPrice
            else -> true
        }
    }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("提醒中心", onBack = { nav.popBackStack() }) {
            Text(
                if (adding) "取消" else "＋ 新增", color = C.Primary, fontSize = 14.sp,
                modifier = Modifier.clickable { adding = !adding }.padding(12.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!granted && Build.VERSION.SDK_INT >= 33) {
                item {
                    Card(padding = 14.dp) {
                        Text("尚未允許通知", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Label("允許後，條件達成時即使 App 沒開也會推播通知。")
                        PrimaryButton("允許通知") { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    }
                }
            }
            if (adding) item { AddAlertCard(initialSymbol) { adding = false } }
            item { Segmented(listOf("全部", "價格", "事件"), filter, { filter = it }) }
            if (list.isEmpty()) item { Label("沒有提醒，按右上角「＋ 新增」建立") }
            items(list, key = { it.id }) { a ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)
                        .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (a.symbol.isNotBlank()) { MarketTag(a.market); HGap(6.dp) }
                            Text(a.title, color = C.Text, fontSize = 14.sp)
                        }
                        Label(a.subtitle)
                    }
                    Switch(
                        checked = a.enabled,
                        onCheckedChange = { AppState.toggleAlert(a.id) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = C.Primary,
                            checkedThumbColor = C.OnPrimary,
                            uncheckedTrackColor = C.Border,
                            uncheckedThumbColor = C.Muted,
                            uncheckedBorderColor = C.Border,
                        ),
                    )
                    IconButton(onClick = { AppState.deleteAlert(a.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "刪除提醒", tint = C.Muted)
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每日收盤摘要", color = C.Text, fontSize = 14.sp)
                        Label("台股 13:45 後、美股收盤後推播當日持股損益")
                    }
                    Switch(
                        checked = AppState.dailySummary, onCheckedChange = { AppState.updateDailySummary(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.Primary, checkedThumbColor = C.OnPrimary,
                            uncheckedTrackColor = C.Border, uncheckedThumbColor = C.Muted, uncheckedBorderColor = C.Border),
                    )
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每月投資月報", color = C.Text, fontSize = 14.sp)
                        Label("每月 1 日推播上月總資產變化、已實現損益、股息與交易筆數")
                        val last = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Taipei")).minusMonths(1)
                        var preview by remember { mutableStateOf<String?>(null) }
                        Text(if (preview == null) "預覽上月月報" else "收合", color = C.Primary, fontSize = 12.sp,
                            modifier = Modifier.clickable { preview = if (preview == null) AlertEngine.monthlyText(last) else null }.padding(vertical = 4.dp))
                        preview?.let { Text(it, color = C.Text2, fontSize = 12.sp, lineHeight = 18.sp) }
                    }
                    Switch(
                        checked = AppState.monthlyReport, onCheckedChange = { AppState.updateMonthlyReport(it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.Primary, checkedThumbColor = C.OnPrimary,
                            uncheckedTrackColor = C.Border, uncheckedThumbColor = C.Muted, uncheckedBorderColor = C.Border),
                    )
                }
            }
            item { SectionTitle("通知紀錄", "最近 ${AppState.alertHistory.size} 筆") }
            if (AppState.alertHistory.isEmpty()) item { Label("尚無通知紀錄") }
            items(AppState.alertHistory.toList()) { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    NumText(Fmt.time(h.time), 12.sp, C.Muted, modifier = Modifier.width(80.dp))
                    Text(h.text, Modifier.weight(1f), color = C.Text2, fontSize = 13.sp)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        "傳送測試通知", color = C.Primary, fontSize = 13.sp,
                        modifier = Modifier.clickable { AlertEngine.test(context) }.padding(vertical = 8.dp),
                    )
                    Label("App 開著時每次更新行情都會檢查；關閉後由系統約每 15 分鐘在背景檢查一次（實際時間由 Android 省電機制決定）。價格類提醒每天最多通知一次。")
                }
            }
        }
    }
}

@Composable
private fun AddAlertCard(initialSymbol: String = "", onDone: () -> Unit) {
    var symbol by remember { mutableStateOf(initialSymbol) }
    var typeIdx by remember { mutableIntStateOf(0) }
    var valueText by remember {
        mutableStateOf(AppState.quote(initialSymbol)?.price?.takeIf { it > 0 }?.let { Fmt.price(it).replace(",", "") } ?: "")
    }
    var error by remember { mutableStateOf<String?>(null) }
    val types = AlertType.entries
    val type = types[typeIdx]
    Card(padding = 14.dp) {
        Text("新增提醒", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEachIndexed { i, t ->
                val on = i == typeIdx
                Text(
                    t.label, color = if (on) C.Text else C.Muted, fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(15.dp)).background(if (on) C.Selected else C.Bg)
                        .clickable { typeIdx = i; error = null }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (type != AlertType.US_OPEN) {
            OutlinedTextField(
                value = symbol, onValueChange = { symbol = it; error = null },
                label = { Text(if (type == AlertType.ENTRY_SIGNAL) "股票代號（留空＝全部持股與自選）" else if (type == AlertType.RAPID || type == AlertType.BIG_TRADE || type == AlertType.KEY_LEVEL) "股票代號（留空＝全部持股）" else "股票代號（例如 2330、AAPL）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
            )
            if (type != AlertType.KEY_LEVEL && type != AlertType.ENTRY_SIGNAL) OutlinedTextField(
                value = valueText, onValueChange = { valueText = it; error = null },
                label = { Text(type.label + "（" + type.unit + "）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = fieldColors(),
            )
            val q = AppState.quote(symbol.trim().uppercase())
            if (q != null && q.price > 0 && type.isPrice) Label("目前 " + Fmt.price(q.price) + "（" + Fmt.signedPct(q.changePct) + "）")
        }
        error?.let { Text(it, color = C.Red, fontSize = 12.sp) }
        PrimaryButton("建立提醒") {
            val sym = symbol.trim().uppercase()
            val v = valueText.toDoubleOrNull()
            when {
                type == AlertType.US_OPEN -> { AppState.addAlert("", type, 0.0); onDone() }
                type == AlertType.ENTRY_SIGNAL -> {
                    if (sym.isNotEmpty() && !AppState.isWatched(sym)) AppState.addWatch(sym)
                    AppState.addAlert(sym, type, 0.0); onDone()
                }
                type == AlertType.KEY_LEVEL -> {
                    if (sym.isNotEmpty() && !AppState.isWatched(sym)) AppState.addWatch(sym)
                    AppState.addAlert(sym, type, 0.0); onDone()
                }
                (type == AlertType.RAPID || type == AlertType.BIG_TRADE) && sym.isEmpty() && v != null && v > 0 -> { AppState.addAlert("", type, v); onDone() }
                sym.isEmpty() -> error = "請輸入股票代號"
                v == null || v < 0 -> error = "請輸入數值"
                else -> {
                    if (!AppState.isWatched(sym)) AppState.addWatch(sym)
                    AppState.addAlert(sym, type, v)
                    onDone()
                }
            }
        }
        Label("不在自選清單的代號會自動加入自選，以便更新報價。")
    }
}

// ============================== 我的・設定 ==============================

@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fxText by remember { mutableStateOf(AppState.usdTwd.toString()) }
    val version = remember { BuildConfigInfo.versionName(context) }
    // 各區塊預設收合，點標題展開；同一次開啟 App 內記住展開狀態
    val openGroups = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.runtime.saveable.listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<String>, String>(
            save = { it.toList() }, restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }
    val allGroups = listOf("群聊", "行情資料來源", "新聞來源", "帳本", "備份與匯入", "安全", "顯示", "帳戶與手續費", "提醒", "關於")

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("我的")
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Card(padding = 14.dp) {
                    Text("資料存在本機", color = C.Text, fontSize = 15.sp)
                    Label("持股 ${AppState.holdings.size} 檔 · 自選 ${AppState.quotes.size} 檔 · 交易紀錄 ${AppState.trades.size} 筆")
                    Row {
                        Text("全部展開", color = C.Primary, fontSize = 13.sp, modifier = Modifier.clickable {
                            openGroups.clear(); openGroups.addAll(allGroups)
                        }.padding(end = 16.dp, top = 6.dp, bottom = 2.dp))
                        Text("全部收合", color = C.Primary, fontSize = 13.sp, modifier = Modifier.clickable { openGroups.clear() }.padding(top = 6.dp, bottom = 2.dp))
                    }
                }
            }

            item { FoldHeader("群聊", if (!com.claudeetf.app.chat.ChatPrefs.enabled) "未開啟" else com.claudeetf.app.chat.ChatPrefs.userName + " · 群聊 " + com.claudeetf.app.chat.ChatPrefs.groupId.ifBlank { "未設定" }, "群聊" in openGroups) { if ("群聊" in openGroups) openGroups.remove("群聊") else openGroups.add("群聊") } }
            if ("群聊" in openGroups) {
                item { ChatSettingsCard(nav) }
            }
            item { FoldHeader("行情資料來源", "台股盤中 " + (if (AppState.twUseFugle) "富果" else "證交所") + " · 期貨 " + mapOf("auto" to "自動", "fugle" to "富果", "taifex" to "期交所", "off" to "關閉")[AppState.futSource], "行情資料來源" in openGroups) { if ("行情資料來源" in openGroups) openGroups.remove("行情資料來源") else openGroups.add("行情資料來源") } }
            if ("行情資料來源" in openGroups) {
                item {
                    Card(padding = 14.dp) {
                        Text("台股", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Label("盤中來源")
                        Segmented(listOf("僅用富果（預設）", "證交所為主"), if (AppState.twUseFugle) 0 else 1,
                            { AppState.updateTwIntradaySource(if (it == 0) "fugle" else "twse") }, background = C.Bg)
                        Label(
                            if (AppState.twUseFugle) "盤中一律只用富果：WebSocket 串流與交易所同步，超出訂閱上限的股票改用富果 REST 輪流更新，不混用證交所。未填金鑰時暫用證交所即時。盤後使用證交所／櫃買收盤資料。"
                            else "盤中以證交所即時行情為主（約每 5 秒），不開富果串流；證交所沒有回應的股票，有金鑰時改用富果補上。盤後使用證交所／櫃買收盤資料。"
                        )
                        KeyField(
                            label = "富果 Fugle API 金鑰",
                            initial = AppState.fugleKey,
                            onSave = { AppState.updateFugleKey(it) },
                            onTest = { MarketData.testFugle(it) },
                        )
                        ApplyKeyLink("還沒有金鑰？到富果開發者平台免費申請 ›", "https://developer.fugle.tw/",
                            "登入富果帳號 → 建立 API Key（免費方案即可使用台股即時行情）")
                        Label("目前來源：" + MarketData.twSourcePlan())
                        if (AppState.streamStatus.isNotBlank()) Label("串流：" + AppState.streamStatus, color = C.Text2)
                        if (AppState.fugleKey.isNotBlank()) {
                            // 每 5 秒隨即時更新重繪
                            val tick = AppState.lastLiveAt
                            Label("連線統計：" + com.claudeetf.app.data.remote.FugleStream.stats() + if (tick < 0) "" else "")
                            var showStates by remember { mutableStateOf(false) }
                            Text(if (showStates) "收合各檔狀態" else "查看各檔串流狀態 ›", color = C.Primary, fontSize = 12.sp,
                                modifier = Modifier.clickable { showStates = !showStates }.padding(vertical = 4.dp))
                            if (showStates) {
                                val syms = AppState.quotes.filter { it.market == com.claudeetf.app.data.Market.TW }.map { it.symbol }
                                com.claudeetf.app.data.remote.FugleStream.symbolStates(syms).forEach { (s, st, ok) ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                        Text(s + " " + (AppState.quote(s)?.name ?: ""), Modifier.weight(1f), color = C.Text, fontSize = 12.sp, maxLines = 1)
                                        Text(st, color = if (ok) C.Green else C.Muted, fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                        AppState.sourceStatus["TW"]?.let { Label("最近更新：$it", color = C.Text2) }
                    }
                }
                item {
                    Card(padding = 14.dp) {
                        val fs = com.claudeetf.app.data.remote.FutStream
                        Text("期貨（首頁台指期卡片）", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        val opts = listOf("auto" to "自動", "fugle" to "富果", "taifex" to "期交所", "off" to "關閉")
                        Segmented(opts.map { it.second }, opts.indexOfFirst { it.first == AppState.futSource }.coerceAtLeast(0),
                            { AppState.updateFutSource(opts[it].first) }, background = C.Bg)
                        Label(
                            when (AppState.futSource) {
                                "fugle" -> "只用富果期權行情（WebSocket 串流）。需要富果有開通期貨的方案，基本方案會回覆不支援。"
                                "taifex" -> "使用期交所 OpenAPI 每日期貨行情，免金鑰，收盤後更新（非盤中即時）。"
                                "off" -> "不抓期貨資料，首頁期貨卡片只顯示提示。"
                                else -> "有富果金鑰時先試富果；富果回覆方案不支援期貨或沒有金鑰時，自動改用期交所每日行情（收盤後更新）。"
                            }
                        )
                        Label("目前使用：" + when (fs.effectiveSource()) { "fugle" -> "富果"; "taifex" -> "期交所每日行情"; else -> "關閉" } +
                            if (fs.fugleUnsupported) "（富果方案不支援期貨）" else "", color = C.Text2)
                        if (fs.fugleUnsupported) {
                            Text("重新測試富果期貨", color = C.Primary, fontSize = 13.sp,
                                modifier = Modifier.clickable { fs.fugleUnsupported = false }.padding(vertical = 8.dp))
                        }
                    }
                }
                item {
                    Card(padding = 14.dp) {
                        Text("美股・指數", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Label("使用 Yahoo Finance，免金鑰；含當日走勢、道瓊、S&P 500、那斯達克、費半、VIX、加權指數、10 年債與美元指數。")
                        var yahooTest by remember { mutableStateOf<String?>(null) }
                        Text(
                            "測試 Yahoo 連線", color = C.Primary, fontSize = 13.sp,
                            modifier = Modifier.clickable { scope.launch { yahooTest = MarketData.testYahoo() } }
                                .padding(vertical = 10.dp),
                        )
                        yahooTest?.let { Text(it, color = if (it.startsWith("連線成功")) C.Primary else C.Red, fontSize = 12.sp) }
                        Label("選填：Yahoo 抓不到某檔時，改用 Finnhub 備援。")
                        KeyField(
                            label = "Finnhub API 金鑰（選填）",
                            initial = AppState.finnhubKey,
                            onSave = { AppState.updateFinnhubKey(it) },
                            onTest = { MarketData.testFinnhub(it) },
                        )
                        ApplyKeyLink("還沒有金鑰？到 Finnhub 免費註冊 ›", "https://finnhub.io/register",
                            "註冊後在 Dashboard 複製 API Key（免費方案每分鐘 60 次）")
                        AppState.sourceStatus["US"]?.let { Label("最近更新：$it", color = C.Text2) }
                        Text("美股盤中更新頻率", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                        UsRefreshChooser()
                    }
                }
                item {
                    Card(padding = 14.dp) {
                        Text("美元匯率 USD/TWD", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Label("目前 " + AppState.usdTwd + "（" + AppState.fxSource + "）")
                        AppState.sourceStatus["FX"]?.let { Label(it, color = C.Text2) }
                        OutlinedTextField(
                            value = fxText,
                            onValueChange = {
                                fxText = it
                                it.toDoubleOrNull()?.let { v -> AppState.updateUsdTwd(v) }
                            },
                            label = { Text("手動輸入（下次自動更新時會被台銀匯率取代）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            colors = fieldColors(),
                        )
                    }
                }
                item {
                    PrimaryButton(if (AppState.refreshing) "更新中…" else "立即更新全部行情") {
                        scope.launch {
                            MarketData.refreshAll()
                            fxText = AppState.usdTwd.toString()
                        }
                    }
                }
            }

            item { FoldHeader("新聞來源", com.claudeetf.app.data.remote.RssNews.enabled().joinToString("、") { it.name }.ifBlank { "未選擇（使用 Yahoo股市、Google 新聞）" }, "新聞來源" in openGroups) { if ("新聞來源" in openGroups) openGroups.remove("新聞來源") else openGroups.add("新聞來源") } }
            if ("新聞來源" in openGroups) {
                item { NewsSourcesCard() }
            }
            item { FoldHeader("帳本", "交易紀錄 ${AppState.trades.size} 筆 · 資料健檢", "帳本" in openGroups) { if ("帳本" in openGroups) openGroups.remove("帳本") else openGroups.add("帳本") } }
            if ("帳本" in openGroups) {
                item {
                    val n = remember(AppState.trades.size) { com.claudeetf.app.ui.screens.HealthCheck.run() }
                    SettingRow("資料健檢", if (n.isEmpty()) "正常 ›" else "${n.size} 個項目 ›") { nav.navigate(Routes.HEALTH) }
                }
                item { LedgerCard() }
            }

            item { FoldHeader("備份與匯入", "匯出、匯入與備份", "備份與匯入" in openGroups) { if ("備份與匯入" in openGroups) openGroups.remove("備份與匯入") else openGroups.add("備份與匯入") } }
            if ("備份與匯入" in openGroups) {
                item { DataCard() }
            }

            item { FoldHeader("安全", if (AppState.appLock) "安全鎖已開啟" else "安全鎖未開啟", "安全" in openGroups) { if ("安全" in openGroups) openGroups.remove("安全") else openGroups.add("安全") } }
            if ("安全" in openGroups) {
                item {
                    var msg by remember { mutableStateOf<String?>(null) }
                    val act = context as? com.claudeetf.app.MainActivity
                    Card(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("App 安全鎖", color = C.Text, fontSize = 14.sp)
                                Label("開啟 App 或離開超過 1 分鐘後，需指紋或裝置密碼解鎖；最近使用畫面不顯示內容")
                            }
                            Switch(
                                checked = AppState.appLock,
                                onCheckedChange = { on ->
                                    if (on && act?.deviceSecure() == false) {
                                        msg = "請先在手機「設定 › 安全性」設定螢幕鎖（密碼、圖形或指紋）"
                                    } else {
                                        AppState.updateAppLock(on)
                                        msg = if (on) "已開啟，下次開啟 App 時需要解鎖" else "已關閉"
                                        act?.window?.let { w ->
                                            val f = android.view.WindowManager.LayoutParams.FLAG_SECURE
                                            if (on) w.setFlags(f, f) else w.clearFlags(f)
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = C.Primary, checkedThumbColor = C.OnPrimary,
                                    uncheckedTrackColor = C.Border, uncheckedThumbColor = C.Muted, uncheckedBorderColor = C.Border),
                            )
                        }
                        msg?.let { Label(it, color = C.Primary) }
                    }
                }
            }

            item { FoldHeader("顯示", "主題、字級、配色、首頁卡片", "顯示" in openGroups) { if ("顯示" in openGroups) openGroups.remove("顯示") else openGroups.add("顯示") } }
            if ("顯示" in openGroups) {
                item { PriceBoxSettings() }
                item {
                    Card(padding = 14.dp) {
                        Text("主題", color = C.Text, fontSize = 14.sp)
                        Segmented(listOf("跟隨系統", "深色", "淺色"), AppState.themeMode, { AppState.updateTheme(it) }, background = C.Bg)
                        Text("字級", color = C.Text, fontSize = 14.sp)
                        val scales = listOf(0.9f, 1f, 1.15f, 1.3f)
                        Segmented(
                            listOf("小", "標準", "大", "特大"),
                            scales.indexOfFirst { kotlin.math.abs(it - AppState.fontScale) < 0.01f }.coerceAtLeast(1),
                            { AppState.updateFontScale(scales[it]) },
                            background = C.Bg,
                        )
                    }
                }
                item {
                    Card(padding = 14.dp) {
                        Text("漲跌配色", color = C.Text, fontSize = 14.sp)
                        Segmented(
                            listOf("台式 紅漲綠跌", "美式 綠漲紅跌"),
                            if (AppState.colorMode == ColorMode.TW) 0 else 1,
                            { AppState.updateColorMode(if (it == 0) ColorMode.TW else ColorMode.US) },
                            background = C.Bg,
                        )
                        // 自訂顏色：點色塊叫出調色盤
                        var picking by remember { mutableStateOf<String?>(null) }
                        picking?.let { which ->
                            val cur = when (which) { "up" -> Trend.up; "down" -> Trend.down; else -> Trend.flat }
                            ColorPickerDialog(
                                title = when (which) { "up" -> "上漲顏色"; "down" -> "下跌顏色"; else -> "平盤顏色" },
                                initial = cur,
                                onPick = { c -> AppState.updateCustomColor(which, c.toArgb().toLong() and 0xFFFFFFFFL); picking = null },
                                onReset = { AppState.updateCustomColor(which, 0L); picking = null },
                                onDismiss = { picking = null },
                            )
                        }
                        listOf("up" to "上漲", "down" to "下跌", "flat" to "平盤").forEach { (k, n) ->
                            val c = when (k) { "up" -> Trend.up; "down" -> Trend.down; else -> Trend.flat }
                            val customized = when (k) { "up" -> AppState.customUp; "down" -> AppState.customDown; else -> AppState.customFlat } != 0L
                            Row(Modifier.fillMaxWidth().clickable { picking = k }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(n + "顏色", Modifier.weight(1f), color = C.Text, fontSize = 14.sp)
                                if (customized) Label("自訂", Modifier.padding(end = 8.dp))
                                Box(Modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(8.dp)).background(c)
                                    .border(1.dp, C.Border, RoundedCornerShape(8.dp)))
                                Text("  ›", color = C.Muted, fontSize = 14.sp)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label("預覽", Modifier.weight(1f))
                            Text("123.45", color = Trend.price(1.0), fontSize = 14.sp, fontWeight = FontWeight.Bold); HGap(6.dp)
                            Text("100.00", color = Trend.price(0.0), fontSize = 14.sp, fontWeight = FontWeight.Bold); HGap(6.dp)
                            ChangePill(1.25); HGap(6.dp); ChangePill(-0.84)
                        }
                    }
                }
                item {
                    Card(padding = 14.dp) {
                        var picking by remember { mutableStateOf(false) }
                        if (picking) AddHomeCardPicker { picking = false }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("首頁卡片", Modifier.weight(1f), color = C.Text, fontSize = 14.sp)
                            Text("＋ 新增指數／個股卡片", color = C.Primary, fontSize = 13.sp,
                                modifier = Modifier.clickable { picking = true }.padding(8.dp))
                        }
                        Label("勾選要顯示的卡片，用 ↑ ↓ 調整順序；指數與個股卡片可按 ✕ 移除")
                        AppState.homeOrder.toList().forEach { id ->
                            val name = AppState.homeCardName(id)
                            val shown = id !in AppState.homeHidden
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (shown) "☑" else "☐", color = if (shown) C.Primary else C.Muted, fontSize = 20.sp,
                                    modifier = Modifier.clickable { AppState.toggleHomeCard(id) }.padding(8.dp),
                                )
                                Text(name, Modifier.weight(1f), color = if (shown) C.Text else C.Muted, fontSize = 14.sp)
                                Text("⇈", color = C.Text, fontSize = 16.sp, modifier = Modifier.clickable { AppState.moveHomeCardTop(id) }.padding(8.dp))
                                Text("↑", color = C.Text, fontSize = 18.sp, modifier = Modifier.clickable { AppState.moveHomeCard(id, -1) }.padding(10.dp))
                                Text("↓", color = C.Text, fontSize = 18.sp, modifier = Modifier.clickable { AppState.moveHomeCard(id, 1) }.padding(10.dp))
                                if (AppState.isCustomCard(id)) Text("✕", color = C.Red, fontSize = 16.sp, modifier = Modifier.clickable { AppState.removeHomeCard(id) }.padding(10.dp))
                            }
                        }
                        Text("個股頁", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        OptToggle("標題顯示投資分析指數（0–100）", AppState.showScoreBadge) { AppState.updateShowScoreBadge(it) }
                        OptToggle("自選卡片／清單與持股顯示進場建議標籤", AppState.showCardEntry) { AppState.updateShowCardEntry(it) }
                        OptToggle("行情價下方顯示即時 K 棒訊號（盤中）", AppState.showLiveSignal) { AppState.updateShowLiveSignal(it) }
                        if (AppState.showLiveSignal) OptToggle("收盤後顯示最終進場標籤（關閉則自動隱藏）", AppState.showFinalSignal) { AppState.updateShowFinalSignal(it) }
                        OptToggle("走勢圖標示我的買賣點", AppState.showTradeMarks) { AppState.updateShowTradeMarks(it) }
                        Text("指數卡片顯示內容", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        IndexCardOptions(null)
                    }
                }
                item { SettingRow("美股時間", "台灣時間・自動夏冬令") }
            }
            item { FoldHeader("帳戶與手續費", "${AppState.accounts.size} 個帳戶", "帳戶與手續費" in openGroups) { if ("帳戶與手續費" in openGroups) openGroups.remove("帳戶與手續費") else openGroups.add("帳戶與手續費") } }
            if ("帳戶與手續費" in openGroups) {
                items(AppState.accounts.toList(), key = { "acc-" + it.id }) { a -> AccountCard(a) }
                item { AddAccountRow() }
            }
            item { FoldHeader("提醒", "${AppState.alerts.count { it.enabled }} 項開啟", "提醒" in openGroups) { if ("提醒" in openGroups) openGroups.remove("提醒") else openGroups.add("提醒") } }
            if ("提醒" in openGroups) {
                item { SettingRow("提醒中心", "${AppState.alerts.count { it.enabled }} 項開啟 ›") { nav.navigate(Routes.ALERTS) } }
            }
            item { FoldHeader("關於", version, "關於" in openGroups) { if ("關於" in openGroups) openGroups.remove("關於") else openGroups.add("關於") } }
            if ("關於" in openGroups) {
                item { SettingRow("版本", version) }
                item { UpdateCard() }
                item { SettingRow("錯誤紀錄", if (com.claudeetf.app.util.CrashLog.read(context) != null) "有紀錄 ›" else "無 ›") { nav.navigate(Routes.ERRORS) } }
                item { SettingRow("重新看使用引導", "›") { nav.navigate(Routes.WELCOME) } }
            }
            item { Label("金鑰只存在這支手機，不會上傳到其他地方。", Modifier.padding(4.dp)) }
        }
    }
}

/** API 金鑰輸入：打字即自動儲存，可顯示／隱藏與測試連線 */
@Composable
private fun KeyField(
    label: String,
    initial: String,
    onSave: (String) -> Unit,
    onTest: suspend (String) -> String,
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var show by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onSave(it)
            result = null
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            Text(
                if (show) "隱藏" else "顯示", color = C.Primary, fontSize = 12.sp,
                modifier = Modifier.clickable { show = !show }.padding(12.dp),
            )
        },
        colors = fieldColors(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (testing) "測試中…" else "測試連線",
            color = if (text.isBlank()) C.Muted else C.Primary, fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = text.isNotBlank() && !testing) {
                    testing = true
                    scope.launch {
                        result = onTest(text)
                        testing = false
                    }
                }
                .padding(horizontal = 4.dp, vertical = 10.dp),
        )
        HGap(10.dp)
        Label(if (text.isBlank()) "尚未填寫" else "已自動儲存", Modifier.weight(1f))
    }
    result?.let { Text(it, color = if (it.startsWith("連線成功")) C.Primary else C.Red, fontSize = 12.sp) }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(C.Primary)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = C.OnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Group(text: String) {
    Text(text, color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

/** 調色盤：常用色塊一點即選，或用色相、亮度滑桿微調；不需輸入色碼 */
@Composable
private fun ColorPickerDialog(title: String, initial: androidx.compose.ui.graphics.Color, onPick: (androidx.compose.ui.graphics.Color) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial.toArgb(), it) } }
    var hue by remember { mutableStateOf(hsv[0]) }
    var sat by remember { mutableStateOf(hsv[1]) }
    var value by remember { mutableStateOf(hsv[2]) }
    fun current() = androidx.compose.ui.graphics.Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    val presets = listOf(
        0xFFE53935, 0xFFFF5252, 0xFFEF5350, 0xFFFF7043, 0xFFFF9800, 0xFFFFB74D,
        0xFFFFD54F, 0xFFE6C34A, 0xFFFFEB3B, 0xFFC0CA33, 0xFF8BC34A, 0xFF66BB6A,
        0xFF43A047, 0xFF26A69A, 0xFF00BCD4, 0xFF29B6F6, 0xFF1E88E5, 0xFF5C6BC0,
        0xFF7E57C2, 0xFFAB47BC, 0xFFEC407A, 0xFFBDBDBD, 0xFF90A4AE, 0xFFFFFFFF,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Card,
        title = { Text(title, color = C.Text, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { argb ->
                            val c = androidx.compose.ui.graphics.Color(argb)
                            Box(Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp)).background(c)
                                .border(if (c == current()) 3.dp else 1.dp, if (c == current()) C.Text else C.Border, RoundedCornerShape(8.dp))
                                .clickable {
                                    val f = FloatArray(3); android.graphics.Color.colorToHSV(argb.toInt(), f)
                                    hue = f[0]; sat = f[1]; value = f[2]
                                })
                        }
                    }
                }
                Label("色相")
                // 色相條：拖曳選色
                Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient((0..6).map { androidx.compose.ui.graphics.Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) })))
                androidx.compose.material3.Slider(hue, { hue = it }, valueRange = 0f..360f)
                Label("鮮豔度")
                androidx.compose.material3.Slider(sat, { sat = it }, valueRange = 0f..1f)
                Label("亮度")
                androidx.compose.material3.Slider(value, { value = it }, valueRange = 0.2f..1f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("預覽", Modifier.weight(1f))
                    Text("▲ +1.25%", color = current(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    HGap(10.dp)
                    Box(Modifier.size(width = 56.dp, height = 28.dp).clip(RoundedCornerShape(8.dp)).background(current()))
                }
            }
        },
        confirmButton = { Text("套用", color = C.Primary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onPick(current()) }.padding(12.dp)) },
        dismissButton = {
            Row {
                Text("恢復預設", color = C.Muted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onReset).padding(12.dp))
                Text("取消", color = C.Muted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onDismiss).padding(12.dp))
            }
        },
    )
}

/** 持股現價方格：字型、粗細、字級、即時／收盤文字，附即時預覽 */
@Composable
private fun PriceBoxSettings() {
    Card(padding = 14.dp) {
        Text("持股現價方格", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        OptToggle("顯示現價方格", AppState.priceBoxShow) { AppState.updatePriceBox(show = it) }
        if (!AppState.priceBoxShow) return@Card
        // 預覽：拿第一檔持股，沒有持股就用示意價格
        val sample = AppState.holdings.firstNotNullOfOrNull { h -> AppState.quote(h.symbol)?.takeIf { it.price > 0 } }
            ?: com.claudeetf.app.data.Quote("0050", "元大台灣50", com.claudeetf.app.data.Market.TW, 92.50, 97.15)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("預覽 " + sample.symbol, Modifier.weight(1f), color = C.Muted, fontSize = 12.sp)
            LivePriceBox(sample, sample.market == com.claudeetf.app.data.Market.US)
        }
        OptToggle("顯示「即時／收盤」文字", AppState.priceBoxLabel) { AppState.updatePriceBox(label = it) }
        Text("字型", color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        ChipRow(listOf("mono" to "等寬", "sans" to "一般", "serif" to "襯線", "cond" to "窄體"), AppState.priceBoxFont) { AppState.updatePriceBox(font = it) }
        Text("粗細", color = C.Text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        ChipRow(listOf(300 to "細", 400 to "標準", 500 to "中等", 700 to "粗", 900 to "特粗"), AppState.priceBoxWeight) { AppState.updatePriceBox(weight = it) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("字級", Modifier.weight(1f), color = C.Text, fontSize = 13.sp)
            Text("－", color = C.Primary, fontSize = 18.sp, modifier = Modifier.clickable { AppState.updatePriceBox(size = AppState.priceBoxSize - 1) }.padding(horizontal = 12.dp))
            Text("${AppState.priceBoxSize} sp", color = C.Text, fontSize = 13.sp)
            Text("＋", color = C.Primary, fontSize = 18.sp, modifier = Modifier.clickable { AppState.updatePriceBox(size = AppState.priceBoxSize + 1) }.padding(horizontal = 12.dp))
        }
        androidx.compose.material3.Slider(AppState.priceBoxSize.toFloat(), { AppState.updatePriceBox(size = it.toInt()) }, valueRange = 10f..22f, steps = 11)
        Text("恢復預設", color = C.Muted, fontSize = 12.sp, modifier = Modifier.clickable {
            AppState.updatePriceBox(show = true, label = true, font = "mono", weight = 700, size = 13)
        }.padding(vertical = 4.dp))
    }
}

@Composable
private fun <T> ChipRow(opts: List<Pair<T, String>>, sel: T, onPick: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEach { (v, n) ->
            val on = v == sel
            Text(n, color = if (on) C.OnPrimary else C.Text2, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Primary else C.Bg)
                    .clickable { onPick(v) }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
}

/** 美股更新頻率：即時（Finnhub 逐筆串流）、1／2／3／5／10 秒 */
@Composable
fun UsRefreshChooser() {
    val opts = listOf(0L to "即時", 1_000L to "1 秒", 2_000L to "2 秒", 3_000L to "3 秒", 5_000L to "5 秒", 10_000L to "10 秒")
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        opts.forEach { (v, n) ->
            val on = AppState.usRefreshMs == v
            Text(n, color = if (on) C.OnPrimary else C.Text2, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Primary else C.Bg)
                    .clickable { AppState.updateUsRefreshMs(v) }.padding(horizontal = 12.dp, vertical = 7.dp))
        }
    }
    Label(
        when {
            AppState.usRefreshMs == 0L && AppState.finnhubKey.isBlank() -> "「即時」需要 Finnhub 金鑰（上方填入）以逐筆串流；目前沒有金鑰，改為每 1 秒輪詢 Yahoo。"
            AppState.usRefreshMs == 0L -> "Finnhub WebSocket 逐筆成交即時推送（免費方案最多 50 檔），Yahoo 每 15 秒補昨收與盤前盤後。" +
                AppState.usStreamStatus.takeIf { it.isNotBlank() }?.let { "\n狀態：$it" }.orEmpty()
            AppState.usRefreshMs < 5_000L -> "高頻輪詢 Yahoo；若被限流會自動拉長間隔（最多 10 秒），恢復後回到設定值。耗電與流量較高。"
            else -> "盤中每 " + AppState.usRefreshMs / 1000 + " 秒更新；台股維持每 5 秒（或富果串流）。"
        },
        Modifier.padding(top = 4.dp),
    )
}

/** 金鑰欄位下方的申請連結 */
@Composable
private fun ApplyKeyLink(label: String, url: String, hint: String) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.padding(top = 2.dp, bottom = 4.dp)) {
        Text(label, color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { runCatching { uri.openUri(url) } }.padding(vertical = 6.dp))
        Label(hint)
    }
}

/** 新聞來源多選、各來源載入狀態、自訂 RSS */
@Composable
private fun NewsSourcesCard() {
    val rss = com.claudeetf.app.data.remote.RssNews
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var testMsg by remember { mutableStateOf<String?>(null) }
    Card(padding = 14.dp) {
        Label("勾選的來源會合併顯示在個股「新聞」分頁與首頁財經新聞卡片，重複的新聞自動去除，依時間排序。點新聞在 App 內閱讀全文。")
        rss.builtIn.forEach { src ->
            val on = src.id in AppState.newsSources
            Row(Modifier.fillMaxWidth().clickable { AppState.toggleNewsSource(src.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (on) "☑" else "☐", color = if (on) C.Primary else C.Muted, fontSize = 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(src.name, color = C.Text, fontSize = 14.sp)
                    val st = rss.status[src.id]
                    val sub = listOfNotNull(src.note.ifBlank { null }, st).joinToString(" · ")
                    if (sub.isNotBlank()) Label(sub, color = if (st?.contains("失敗") == true || st?.contains("沒有內容") == true) C.Red else C.Muted)
                }
            }
        }
        if (AppState.customFeeds.isNotEmpty()) Text("自訂 RSS", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        AppState.customFeeds.toList().forEach { (n, u) ->
            val id = "c:$n"
            val on = id in AppState.newsSources
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (on) "☑" else "☐", color = if (on) C.Primary else C.Muted, fontSize = 20.sp,
                    modifier = Modifier.clickable { AppState.toggleNewsSource(id) })
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(n, color = C.Text, fontSize = 14.sp, maxLines = 1)
                    Label(rss.status[id] ?: u, color = if (rss.status[id]?.contains("失敗") == true) C.Red else C.Muted)
                }
                Text("刪除", color = C.Red, fontSize = 12.sp, modifier = Modifier.clickable { AppState.removeCustomFeed(n) }.padding(8.dp))
            }
        }
        Text("新增自訂 RSS", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(name, { name = it }, label = { Text("名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        OutlinedTextField(url, { url = it; testMsg = null }, label = { Text("RSS 網址（https://…）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("測試", color = C.Primary, fontSize = 13.sp, modifier = Modifier.clickable(enabled = url.startsWith("http")) {
                testMsg = "測試中…"
                scope.launch { testMsg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { rss.test(name.ifBlank { "自訂" }, url.trim()) } }
            }.padding(vertical = 10.dp).padding(end = 20.dp))
            Text("加入", color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(enabled = url.startsWith("http")) {
                AppState.addCustomFeed(name, url); name = ""; url = ""; testMsg = "已加入並勾選"
            }.padding(vertical = 10.dp))
        }
        testMsg?.let { Label(it, color = if (it.startsWith("成功") || it.startsWith("已加入")) C.Primary else C.Text2) }
        Label("各來源的狀態在開啟新聞後更新；顯示「失敗」或「沒有內容」代表該媒體 RSS 網址已變更，可取消勾選或改用自訂 RSS。")
    }
}

/** 可收合的區塊標題：收合時顯示摘要 */
@Composable
private fun FoldHeader(title: String, summary: String?, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (open) C.Bg else C.Card)
            .clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (open) C.Primary else C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (!open && !summary.isNullOrBlank()) Label(summary)
        }
        Text(if (open) "▲" else "▼", color = C.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(C.Card)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f), color = C.Text, fontSize = 14.sp)
        Label(value)
    }
}

@Composable
private fun LedgerCard() {
    var cashText by remember { mutableStateOf(Fmt.num(AppState.cashTwd, 0).replace(",", "")) }
    var confirm by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    Card(padding = 14.dp) {
        Text("現金餘額（TWD）", color = C.Text, fontSize = 14.sp)
        OutlinedTextField(
            value = cashText,
            onValueChange = { cashText = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = fieldColors(),
            trailingIcon = {
                Text(
                    "套用", color = C.Primary, fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        cashText.replace(",", "").toDoubleOrNull()?.let { AppState.setCash(it); msg = "現金已更新" }
                    }.padding(12.dp),
                )
            },
        )
        Label("買賣與股息會自動增減現金；這裡用來對齊券商帳戶的實際餘額。")
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
        Text("清除示意資料", color = C.Text, fontSize = 14.sp)
        Label("刪除示意持股、交易、股息與已實現損益，從你自己的部位開始記錄。自選清單、金鑰與提醒會保留。")
        Text(
            if (confirm) "確定清除？此動作無法復原" else "清除示意資料",
            color = C.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                if (!confirm) confirm = true
                else {
                    AppState.clearSampleData()
                    cashText = "0"
                    confirm = false
                    msg = "已清除，請到「持股 › 記一筆」新增你的交易"
                }
            }.padding(vertical = 10.dp),
        )
        msg?.let { Label(it, color = C.Primary) }
    }
}

private fun readText(context: android.content.Context, uri: android.net.Uri): String? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val utf = bytes.toString(Charsets.UTF_8)
    // 台灣券商對帳單常用 Big5，UTF-8 解碼出現亂碼時改用 Big5
    if (utf.contains('\uFFFD')) String(bytes, java.nio.charset.Charset.forName("Big5")) else utf
}.getOrNull()

private fun writeText(context: android.content.Context, uri: android.net.Uri, text: String): Boolean = runCatching {
    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
}.getOrDefault(false)

@Composable
private fun DataCard() {
    val context = LocalContext.current
    var msg by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<TradeCsv.ParseResult?>(null) }
    var restoreText by remember { mutableStateOf<String?>(null) }

    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) msg = if (writeText(context, uri, Backup.export(context))) { AppState.markBackup(); "備份已儲存" } else "備份失敗"
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val t = readText(context, uri)
            if (t == null) msg = "無法讀取檔案" else restoreText = t
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) msg = if (writeText(context, uri, TradeCsv.export(AppState.trades.toList()))) "交易 CSV 已匯出" else "匯出失敗"
    }
    val openCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val t = readText(context, uri)
            if (t == null) msg = "無法讀取檔案" else pending = TradeCsv.parse(t, AppState.usdTwd)
        }
    }

    Card(padding = 14.dp) {
        Text("完整備份", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Label(
            if (AppState.lastBackupAt == 0L) "尚未備份過，建議定期匯出（超過 7 天會提醒）"
            else "上次備份：" + Fmt.time(AppState.lastBackupAt) + "（超過 7 天會提醒）",
            color = if (AppState.lastBackupAt == 0L || System.currentTimeMillis() - AppState.lastBackupAt > 7L * 86_400_000) C.Tw else C.Muted,
        )
        Label("交易、帳本、自選、提醒、稅務設定與淨值紀錄存成一個檔案。存檔時選 Google 雲端硬碟即可雲端備份；API 金鑰不會匯出。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("匯出備份", Modifier.weight(1f)) { exportBackup.launch(Backup.fileName("json")) }
            SmallButton("從備份還原", Modifier.weight(1f)) { openBackup.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }
        restoreText?.let { text ->
            Text("還原會取代手機上目前的所有資料，確定嗎？", color = C.Red, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("確定還原", Modifier.weight(1f)) {
                    val err = Backup.import(context, text)
                    msg = err ?: "已還原備份"
                    restoreText = null
                }
                SmallButton("取消", Modifier.weight(1f)) { restoreText = null }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
        Text("交易 CSV", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Label("可匯入券商對帳單：第一列為標題，需有「日期、代號、股數、價格」，可選「類別（買進／賣出／股息）、手續費、交易稅、匯率、備註」。日期支援 2025/11/05、2025-11-05、114/11/05。先匯出一次就能當範本。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("匯入 CSV", Modifier.weight(1f)) { openCsv.launch(arrayOf("text/*", "application/vnd.ms-excel", "*/*")) }
            SmallButton("匯出 CSV", Modifier.weight(1f)) { exportCsv.launch(Backup.fileName("csv")) }
        }
        pending?.let { r ->
            Text("讀到 ${r.trades.size} 筆交易" + if (r.errors.isNotEmpty()) "，${r.errors.size} 列無法辨識" else "", color = C.Text, fontSize = 13.sp)
            r.errors.take(5).forEach { Label(it, color = C.Red) }
            r.trades.take(3).forEach { t -> Label(t.date + " " + t.type.label + " " + t.symbol + " " + Fmt.shares(t.shares) + " 股 × " + Fmt.price(t.price)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.trades.isNotEmpty()) {
                    SmallButton("匯入 ${r.trades.size} 筆", Modifier.weight(1f)) {
                        val err = AppState.importTrades(r.trades)
                        msg = err?.let { "匯入失敗：$it" } ?: "已匯入 ${r.trades.size} 筆交易"
                        pending = null
                    }
                }
                SmallButton("取消", Modifier.weight(1f)) { pending = null }
            }
        }
        msg?.let { Label(it, color = C.Primary) }
    }
}

@Composable
private fun SmallButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(10.dp)).background(C.Selected).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = C.Text, fontSize = 13.sp) }
}

/** 帳戶設定卡：名稱、折扣／費率、最低收費；可刪除 */
@Composable
private fun AccountCard(a: Account) {
    var expanded by remember { mutableStateOf(false) }
    var name by remember(a.id) { mutableStateOf(a.name) }
    var rateText by remember(a.id) {
        mutableStateOf(if (a.market == Market.TW) Fmt.num(a.discount * 10, 2).trimEnd('0').trimEnd('.') else Fmt.num(a.usRate * 100, 3).trimEnd('0').trimEnd('.'))
    }
    var minText by remember(a.id) { mutableStateOf(Fmt.num(if (a.market == Market.TW) a.minFee else a.usMinFee, 2).trimEnd('0').trimEnd('.')) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tw = a.market == Market.TW
    Card(padding = 14.dp) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            MarketTag(a.market); HGap(8.dp)
            Column(Modifier.weight(1f)) {
                Text(a.name, color = C.Text, fontSize = 14.sp)
                Label(
                    if (tw) "0.1425% × " + Fmt.num(a.discount * 10, 2).trimEnd('0').trimEnd('.') + " 折，最低 " + Fmt.num(a.minFee) + " 元"
                    else "手續費 " + Fmt.num(a.usRate * 100, 3).trimEnd('0').trimEnd('.') + "%，最低 $" + Fmt.num(a.usMinFee, 2)
                )
            }
            Label(if (expanded) "收合" else "編輯")
        }
        if (expanded) {
            OutlinedTextField(name, { name = it }, label = { Text("帳戶名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    rateText, { rateText = it },
                    label = { Text(if (tw) "折扣（例：2.8 折填 2.8）" else "費率 %（例：0.1）") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors(),
                )
                OutlinedTextField(
                    minText, { minText = it },
                    label = { Text(if (tw) "最低（元）" else "最低（美元）") },
                    singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("儲存", Modifier.weight(1f)) {
                    val r = rateText.toDoubleOrNull()
                    val m = minText.toDoubleOrNull() ?: 0.0
                    if (r != null && r > 0) {
                        AppState.saveAccount(
                            if (tw) a.copy(name = name.ifBlank { a.name }, discount = (r / 10).coerceIn(0.01, 1.0), minFee = m)
                            else a.copy(name = name.ifBlank { a.name }, usRate = r / 100, usMinFee = m)
                        )
                        expanded = false
                    }
                }
                SmallButton(if (confirmDelete) "確定刪除" else "刪除", Modifier.weight(1f)) {
                    if (confirmDelete) AppState.deleteAccount(a.id) else confirmDelete = true
                }
            }
            Label("交易時選這個帳戶，手續費會依此設定自動計算。刪除帳戶不會刪除交易。")
        }
    }
}

@Composable
private fun AddAccountRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallButton("＋ 台股帳戶", Modifier.weight(1f)) {
            AppState.saveAccount(Account(0, "台股帳戶 ${AppState.accountsFor(Market.TW).size + 1}", Market.TW))
        }
        SmallButton("＋ 美股帳戶", Modifier.weight(1f)) {
            AppState.saveAccount(Account(0, "美股帳戶 ${AppState.accountsFor(Market.US).size + 1}", Market.US))
        }
    }
}
