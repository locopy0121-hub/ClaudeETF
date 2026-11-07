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
    UpdatedDialog()
    if (!AppUpdater.prompt) return
    val ctx = LocalContext.current
    val r = AppUpdater.latest ?: return
    UpdateDialog(r, onLater = { AppUpdater.skip(ctx) })
}

/** 線上更新完成後第一次開啟：顯示「已更新至 vX」與這版更新內容 */
@Composable
private fun UpdatedDialog() {
    val v = AppUpdater.justUpdated ?: return
    val ctx = LocalContext.current
    // 說明要等版本清單載入；還沒有就抓一次
    LaunchedEffect(v) { if (AppUpdater.notesFor(v) == null) AppUpdater.check(ctx, auto = true, minGapMs = 0L, popup = false) }
    val notes = AppUpdater.notesFor(v)?.let { cleanNotes(it) }
    AlertDialog(
        onDismissRequest = { AppUpdater.justUpdated = null },
        containerColor = C.Card,
        title = { Text("✓ 已更新至 v$v", color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label("線上更新成功，持股與設定都已保留。")
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg)
                        .verticalScroll(rememberScrollState()).padding(10.dp),
                ) {
                    Text("這版更新", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(notes ?: "載入更新內容中…", color = C.Text2, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        },
        confirmButton = { Text("好", color = C.Primary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { AppUpdater.justUpdated = null }.padding(12.dp)) },
    )
}

/** 我的 › 關於：版本紀錄（最近 15 版） */
@Composable
fun ReleaseHistoryCard() {
    var open by remember { mutableStateOf(false) }
    val list = AppUpdater.releases.filter { it.tag.startsWith("v1.") || it.tag.startsWith("v2.") }.ifEmpty { AppUpdater.releases }
    Card(padding = 14.dp) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text("版本紀錄", Modifier.weight(1f), color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Label(if (list.isEmpty()) "檢查更新後顯示" else "${list.size} 版 " + if (open) "▲" else "▼")
        }
        if (open) list.forEach { r ->
            val first = cleanNotes(r.notes).lines().firstOrNull { it.isNotBlank() } ?: r.name
            var more by remember(r.tag) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).clickable { more = !more }.padding(10.dp)) {
                Row {
                    Text(r.tag.removePrefix("v"), Modifier.weight(1f), color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Label(r.publishedAt.take(10).replace("-", "/"))
                }
                Text(if (more) cleanNotes(r.notes) else first, color = C.Text2, fontSize = 12.sp, lineHeight = 17.sp,
                    maxLines = if (more) Int.MAX_VALUE else 2)
            }
        }
    }
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
    val isReady = AppUpdater.ready == r.tag
    AlertDialog(
        onDismissRequest = { if (p == null) onLater() },
        containerColor = C.Card,
        title = { Text((if (isReady) "✓ 新版本 " else "有新版本 ") + r.tag.removeSuffix("-dev") + (if (isReady) " 已下載" else ""), color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
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
                if (isReady) Text("已在背景下載並驗證完成，按「立即安裝」即可更新。", color = C.Primary, fontSize = 13.sp)
                else if (AppUpdater.prefetching && p == null) Label("背景下載中…")
                if (p != null) {
                    Label(if (AppUpdater.prefetching) "等待背景下載完成…" else "下載中 " + (p * 100).toInt() + "%")
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
                if (p != null) "下載中…" else if (!canInstall) "前往允許安裝" else if (isReady) "立即安裝" else "下載並安裝",
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
                if (AppUpdater.checking) "檢查中…" else if (AppUpdater.hasUpdate(ctx)) (if (AppUpdater.ready != null) "安裝 ›" else "更新 ›") else "檢查更新",
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
        // 預先下載：偵測到新版就先下載好，等使用者點安裝
        var mode by remember { mutableStateOf(AppUpdater.prefetchMode(ctx)) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("新版本預先下載", Modifier.weight(1f), color = C.Text2, fontSize = 13.sp)
            listOf(0 to "僅 Wi-Fi", 1 to "任何網路", 2 to "關閉").forEach { (v, n) ->
                val on = mode == v
                Text(n, color = if (on) C.Text else C.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(12.dp)).background(if (on) C.Selected else C.Bg)
                        .clickable { mode = v; AppUpdater.setPrefetchMode(ctx, v) }.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        if (AppUpdater.ready != null && AppUpdater.hasUpdate(ctx)) Text("✓ " + AppUpdater.ready!!.removePrefix("v") + " 已下載完成，點「更新 ›」立即安裝", color = C.Primary, fontSize = 12.sp)
        else if (AppUpdater.prefetching) Label("背景下載新版中…")
        Label("從 GitHub Releases 下載最新版並驗證，交給系統安裝程式覆蓋更新。回到 App 時自動檢查（15 分鐘一次），背景每 6 小時檢查；偵測到新版會先在背景下載好，再提示你點擊安裝。")
    }
    if (show && r != null) UpdateDialog(r, onLater = { show = false })
}
