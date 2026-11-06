package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.AppUpdater
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.theme.C
import kotlinx.coroutines.launch

/** 更新說明：去掉 commit 署名行與建置資訊 */
private fun cleanNotes(s: String): String = s.lines()
    .takeWhile { it.trim() != "---" }
    .filterNot { it.startsWith("Co-Authored-By") || it.startsWith("Claude-Session") }
    .joinToString("\n").trim()

private fun sizeText(b: Long) = if (b <= 0) "" else "%.1f MB".format(b / 1048576.0)

/** 開啟 App 時發現新版本：跳出更新提示 */
@Composable
fun UpdatePrompt() {
    if (!AppUpdater.prompt) return
    val ctx = LocalContext.current
    val r = AppUpdater.latest ?: return
    UpdateDialog(r, onLater = { AppUpdater.skip(ctx) })
}

@Composable
private fun UpdateDialog(r: AppUpdater.Release, onLater: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 從「允許安裝未知應用程式」設定頁回來時重新判斷
    var canInstall by remember { mutableStateOf(AppUpdater.canInstall(ctx)) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.currentStateFlow.collect { if (it == Lifecycle.State.RESUMED) canInstall = AppUpdater.canInstall(ctx) }
    }
    val p = AppUpdater.progress
    AlertDialog(
        onDismissRequest = { if (p == null) onLater() },
        containerColor = C.Card,
        title = { Text("有新版本 " + r.tag.removeSuffix("-dev"), color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("目前 " + AppUpdater.currentVersion(ctx) + " → " + r.tag.removePrefix("v") + (sizeText(r.size).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""))
                val notes = cleanNotes(r.notes)
                if (notes.isNotBlank()) Column(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg)
                        .verticalScroll(rememberScrollState()).padding(10.dp),
                ) {
                    Text("更新內容", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(notes, color = C.Text2, fontSize = 13.sp, lineHeight = 19.sp)
                }
                if (p != null) {
                    Label("下載中 " + (p * 100).toInt() + "%")
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Bg)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(4.dp)).background(C.Primary))
                    }
                }
                if (!canInstall) Label("第一次線上更新需要允許本 App「安裝未知應用程式」，按下方按鈕前往設定開啟後返回。")
                else Label("簽章固定，直接覆蓋安裝，持股與設定都會保留。")
                AppUpdater.message?.takeIf { it.startsWith("更新失敗") }?.let { Text(it, color = C.Red, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Text(
                if (p != null) "下載中…" else if (!canInstall) "前往允許安裝" else "下載並安裝",
                color = if (p != null) C.Muted else C.Primary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = p == null) {
                    if (!canInstall) AppUpdater.openInstallPermission(ctx)
                    else scope.launch { AppUpdater.downloadAndInstall(ctx) }
                }.padding(12.dp),
            )
        },
        dismissButton = {
            if (p == null) Text("稍後", color = C.Muted, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onLater).padding(12.dp))
        },
    )
}

/** 我的 › 關於：檢查更新 */
@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    // 打開「關於」時，距上次檢查超過 1 分鐘就自動重新檢查（不跳視窗）
    LaunchedEffect(Unit) { AppUpdater.check(ctx, auto = true, minGapMs = 60_000L, popup = false) }
    val r = AppUpdater.latest
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("線上更新", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Label("目前 " + AppUpdater.currentVersion(ctx) + (r?.let { " · 最新 " + it.tag.removePrefix("v") } ?: ""))
            }
            Text(
                if (AppUpdater.checking) "檢查中…" else if (AppUpdater.hasUpdate(ctx)) "更新 ›" else "檢查更新",
                color = C.Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Bg).clickable(enabled = !AppUpdater.checking) {
                    if (AppUpdater.hasUpdate(ctx)) show = true
                    else scope.launch { AppUpdater.check(ctx); if (AppUpdater.hasUpdate(ctx)) show = true }
                }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Row(Modifier.padding(top = 4.dp)) {
            AppUpdater.message?.let { Text(it, Modifier.weight(1f), color = if (it.contains("失敗")) C.Red else if (it.startsWith("有新版本")) C.Primary else C.Text2, fontSize = 12.sp) }
                ?: Spacer(Modifier.weight(1f))
            if (AppUpdater.lastCheckAt > 0) Label("上次檢查 " + java.time.Instant.ofEpochMilli(AppUpdater.lastCheckAt)
                .atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("開啟 App 時自動檢查", Modifier.weight(1f), color = C.Text2, fontSize = 13.sp)
            Switch(AppState.autoUpdateCheck, { AppState.updateAutoUpdateCheck(it) })
        }
        Label("從 GitHub Releases 下載最新版，交給系統安裝程式覆蓋更新。回到 App 時自動檢查（15 分鐘一次），背景每 6 小時檢查並以通知提醒。")
    }
    if (show && r != null) UpdateDialog(r, onLater = { show = false })
}
