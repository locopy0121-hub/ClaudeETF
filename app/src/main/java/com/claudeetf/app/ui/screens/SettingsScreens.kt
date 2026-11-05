package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.BuildConfigInfo
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.ColorMode
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C

// ============================== 提醒中心 ==============================

@Composable
fun AlertsScreen(nav: NavController) {
    var filter by remember { mutableIntStateOf(0) }
    val list = AppState.alerts.filter {
        when (filter) {
            1 -> it.isPrice
            2 -> !it.isPrice
            else -> true
        }
    }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("提醒中心", onBack = { nav.popBackStack() })
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Segmented(listOf("全部", "價格", "事件"), filter, { filter = it }) }
            items(list, key = { it.id }) { a ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MarketTag(a.market); HGap(6.dp)
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
                }
            }
            item {
                Label(
                    "推播通知將在第二階段接上背景更新後啟用；目前可先設定規則。",
                    Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

// ============================== 我的・設定 ==============================

@Composable
fun SettingsScreen(nav: NavController) {
    val context = LocalContext.current
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
                    Label("持股 ${AppState.holdings.size} 檔 · 交易紀錄 ${AppState.trades.size} 筆")
                }
            }
            item { Group("顯示") }
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
            item {
                Card(padding = 14.dp) {
                    Text("美元匯率 USD/TWD", color = C.Text, fontSize = 14.sp)
                    OutlinedTextField(
                        value = fxText,
                        onValueChange = {
                            fxText = it
                            it.toDoubleOrNull()?.let { v -> AppState.updateUsdTwd(v) }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = fieldColors(),
                    )
                    Label("第二階段會自動抓台灣銀行即期牌告；目前可手動輸入")
                }
            }
            item { SettingRow("美股時間", "台灣時間・自動夏冬令") }
            item { Group("帳戶與手續費") }
            item { SettingRow("台股手續費", "0.1425%（最低 20 元）") }
            item { SettingRow("美股手續費", "0.1%（可在交易時修改）") }
            item { Group("提醒") }
            item { SettingRow("提醒中心", "${AppState.alerts.count { it.enabled }} 項開啟 ›") { nav.navigate(Routes.ALERTS) } }
            item { Group("關於") }
            item { SettingRow("版本", version) }
            item { SettingRow("資料來源", "示意資料（第二階段接即時行情）") }
        }
    }
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
