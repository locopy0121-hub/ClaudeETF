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
fun AlertsScreen(nav: NavController) {
    val context = LocalContext.current
    var filter by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf(false) }
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
            if (adding) item { AddAlertCard { adding = false } }
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
                            if (a.type != AlertType.US_OPEN) { MarketTag(a.market); HGap(6.dp) }
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
            item { SectionTitle("最近觸發") }
            if (AppState.alertHistory.isEmpty()) item { Label("尚無觸發紀錄") }
            items(AppState.alertHistory.take(15)) { h ->
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
private fun AddAlertCard(onDone: () -> Unit) {
    var symbol by remember { mutableStateOf("") }
    var typeIdx by remember { mutableIntStateOf(0) }
    var valueText by remember { mutableStateOf("") }
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
                label = { Text("股票代號（例如 2330、AAPL）") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
            )
            OutlinedTextField(
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
                }
            }

            item { Group("行情資料來源") }
            item {
                Card(padding = 14.dp) {
                    Text("台股", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Label("盤中優先使用富果即時行情；盤後、未填金鑰或富果失敗時，自動改用證交所 OpenAPI 收盤資料（上櫃股票用櫃買中心）。")
                    KeyField(
                        label = "富果 Fugle API 金鑰",
                        initial = AppState.fugleKey,
                        onSave = { AppState.updateFugleKey(it) },
                        onTest = { MarketData.testFugle(it) },
                    )
                    Label("目前來源：" + MarketData.twSourcePlan())
                    AppState.sourceStatus["TW"]?.let { Label("最近更新：$it", color = C.Text2) }
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
                    AppState.sourceStatus["US"]?.let { Label("最近更新：$it", color = C.Text2) }
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

            item { Group("帳本") }
            item { LedgerCard() }

            item { Group("備份與匯入") }
            item { DataCard() }

            item { Group("顯示") }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("預覽", Modifier.weight(1f))
                        ChangePill(1.25); HGap(6.dp); ChangePill(-0.84)
                    }
                }
            }
            item { SettingRow("美股時間", "台灣時間・自動夏冬令") }
            item { Group("帳戶與手續費") }
            items(AppState.accounts.toList(), key = { "acc-" + it.id }) { a -> AccountCard(a) }
            item { AddAccountRow() }
            item { Group("提醒") }
            item { SettingRow("提醒中心", "${AppState.alerts.count { it.enabled }} 項開啟 ›") { nav.navigate(Routes.ALERTS) } }
            item { Group("關於") }
            item { SettingRow("版本", version) }
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
        if (uri != null) msg = if (writeText(context, uri, Backup.export(context))) "備份已儲存" else "備份失敗"
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
