package com.claudeetf.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.SettingsProfile
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.theme.C
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 設定檔管理：選擇模組 → 存成設定檔（App 內）或匯出檔案；
 * 讀取檔案或清單中的設定檔 → 勾選要套用的模組 → 套用。
 */
@Composable
fun ProfileCard() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val saved = remember(tick) { SettingsProfile.saved() }
    var name by remember { mutableStateOf("") }
    var pick by remember { mutableStateOf(SettingsProfile.MODULES.map { it.id }.toSet()) }
    var msg by remember { mutableStateOf<String?>(null) }
    var applying by remember { mutableStateOf<SettingsProfile.Profile?>(null) }
    var toExport by remember { mutableStateOf<SettingsProfile.Profile?>(null) }

    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val p = toExport
        if (uri != null && p != null) {
            msg = if (runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(p.toJson().toString(2).toByteArray()) } != null
                }.getOrDefault(false)) "已匯出「${p.name}」" else "匯出失敗"
        }
        toExport = null
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val t = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (t == null) msg = "無法讀取檔案" else {
                val (p, err) = SettingsProfile.parse(t)
                if (p == null) msg = err else applying = p
            }
        }
    }

    Card(padding = 14.dp) {
        Text("設定檔", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Label("把各板塊的樣式與設定存成設定檔，之後可一鍵套用、匯出到其他手機，或讀取檔案套用。設定檔不含交易、持股、金額與 API 金鑰。")

        Text("要包含的模組", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        ModulePicker(SettingsProfile.MODULES.map { it.id }, pick) { pick = it }

        OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth().padding(top = 6.dp),
            singleLine = true, label = { Text("設定檔名稱") }, placeholder = { Text("例：大字深色、上班精簡") })
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PBtn("存到 App", Modifier.weight(1f), enabled = pick.isNotEmpty()) {
                val p = SettingsProfile.capture(context, name.trim(), pick)
                val exists = saved.any { it.name == p.name }
                SettingsProfile.saveLocal(p); tick++
                msg = if (exists) "已更新「${p.name}」" else "已儲存「${p.name}」"
            }
            PBtn("匯出檔案", Modifier.weight(1f), enabled = pick.isNotEmpty()) {
                val p = SettingsProfile.capture(context, name.trim(), pick)
                toExport = p; exportFile.launch(SettingsProfile.fileName(p.name))
            }
            PBtn("讀取檔案", Modifier.weight(1f)) { openFile.launch(arrayOf("application/json", "text/plain", "*/*")) }
        }
        msg?.let { Label(it, Modifier.padding(top = 6.dp), color = C.Primary) }

        if (saved.isNotEmpty()) {
            Text("已儲存的設定檔（${saved.size}）", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            val fmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN) }
            saved.forEach { p ->
                Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(C.Bg).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Label(fmt.format(Date(p.savedAt)))
                    }
                    Label(p.modules.mapNotNull { id -> SettingsProfile.MODULES.firstOrNull { it.id == id }?.title }.joinToString("、"))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PBtn("套用…", Modifier.weight(1f)) { applying = p }
                        PBtn("匯出", Modifier.weight(1f)) { toExport = p; exportFile.launch(SettingsProfile.fileName(p.name)) }
                        PBtn("刪除", Modifier.weight(1f)) { SettingsProfile.delete(p.name, p.savedAt); tick++; msg = "已刪除「${p.name}」" }
                    }
                }
            }
        }
    }

    applying?.let { p ->
        var sel by remember(p) { mutableStateOf(p.modules.toSet()) }
        AlertDialog(
            onDismissRequest = { applying = null },
            containerColor = C.Card,
            title = { Text("套用「${p.name}」", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Label("勾選要套用的模組。未勾選的模組維持目前設定；套用前會自動保存一份「套用前自動保存」，可隨時還原。")
                    ModulePicker(p.modules, sel) { sel = it }
                }
            },
            confirmButton = {
                TextButton(enabled = sel.isNotEmpty(), onClick = {
                    val n = SettingsProfile.apply(context, p, sel)
                    applying = null; tick++
                    msg = if (n > 0) "已套用 $n 個模組" else "沒有可套用的模組"
                }) { Text("套用", color = if (sel.isNotEmpty()) C.Primary else C.Muted) }
            },
            dismissButton = {
                Row {
                    if (saved.none { it.name == p.name && it.savedAt == p.savedAt }) {
                        TextButton(onClick = { SettingsProfile.saveLocal(p); applying = null; tick++; msg = "已存入「${p.name}」" }) { Text("只存入清單", color = C.Primary) }
                    }
                    TextButton(onClick = { applying = null }) { Text("取消", color = C.Muted) }
                }
            },
        )
    }
}

@Composable
private fun ModulePicker(ids: List<String>, sel: Set<String>, onChange: (Set<String>) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(if (sel.size == ids.size) "全部取消" else "全選", color = C.Primary, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange(if (sel.size == ids.size) emptySet() else ids.toSet()) }
                    .padding(horizontal = 8.dp, vertical = 4.dp))
        }
        ids.forEach { id ->
            val m = SettingsProfile.MODULES.firstOrNull { it.id == id } ?: return@forEach
            val on = id in sel
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp))
                .border(1.dp, if (on) C.Primary else C.Muted.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                .clickable { onChange(if (on) sel - id else sel + id) }.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (on) "☑" else "☐", color = if (on) C.Primary else C.Muted, fontSize = 16.sp)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(m.title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Label(m.desc)
                }
            }
        }
    }
}

@Composable
private fun PBtn(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).background(C.Selected)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) C.Primary else C.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
