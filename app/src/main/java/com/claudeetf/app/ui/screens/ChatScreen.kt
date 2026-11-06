package com.claudeetf.app.ui.screens

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import com.claudeetf.app.chat.ChatEngine
import com.claudeetf.app.chat.ChatPrefs
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.TopBar
import com.claudeetf.app.ui.theme.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val EMOJI = listOf(
    "😀", "😂", "🤣", "😊", "😍", "😎", "🤔", "😅", "😭", "😡", "😱", "🥳",
    "👍", "👎", "👏", "🙏", "💪", "🤝", "👌", "✌️", "🔥", "💯", "🎉", "❤️",
    "📈", "📉", "💰", "💵", "🚀", "🐂", "🐻", "⚠️", "✅", "❌", "⏰", "📊",
    "☕", "🍜", "🍺", "🌙", "☀️", "⭐",
)

/** 群聊需要的執行期權限（依 Android 版本） */
fun chatPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
}.toTypedArray()

private fun hhmm(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("M/d HH:mm"))

private fun sizeText(b: Long) = when {
    b >= 1 shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
fun ChatScreen(nav: NavController) {
    val ctx = LocalContext.current
    var permMsg by remember { mutableStateOf<String?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res.values.all { it }) ChatEngine.start(ctx)
        else permMsg = "需要「附近裝置」、藍牙與定位權限才能和附近成員 P2P 連線"
    }
    DisposableEffect(Unit) {
        ChatEngine.viewing = true; ChatEngine.unread = 0
        onDispose { ChatEngine.viewing = false }
    }
    LaunchedEffect(ChatPrefs.enabled, ChatPrefs.groupId) {
        if (ChatPrefs.enabled && ChatPrefs.groupId.isNotBlank() && !ChatEngine.running) permLauncher.launch(chatPermissions())
    }
    var text by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf(false) }
    var attach by remember { mutableStateOf(false) }
    var showPeers by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<String?>(null) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { runCatching { ChatEngine.sendUri(ctx, it, image = true) } }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { runCatching { ChatEngine.sendUri(ctx, it, image = false) } }
    }
    val list = rememberLazyListState()
    LaunchedEffect(ChatEngine.messages.size) { if (ChatEngine.messages.isNotEmpty()) list.animateScrollToItem(ChatEngine.messages.size - 1) }

    viewer?.let { path -> ImageViewer(path) { viewer = null } }

    Column(Modifier.fillMaxSize().background(C.Bg).imePadding()) {
        TopBar("群聊 · " + ChatPrefs.groupId.ifBlank { "未設定" }, onBack = { nav.popBackStack() }) {
            Text(
                "👥 ${ChatEngine.peers.size + if (ChatEngine.running) 1 else 0}", color = C.Text, fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(C.Card).clickable { showPeers = !showPeers }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (!ChatPrefs.enabled || ChatPrefs.groupId.isBlank()) {
            Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text("群聊尚未開啟", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Label("到「我的 › 群聊」開啟功能，設定名稱與群聊 ID。同一個群聊 ID 的手機在附近（藍牙／Wi-Fi 範圍內）會自動互連，不經過任何伺服器。")
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (ChatEngine.peers.isNotEmpty()) C.Green else C.Muted))
            Spacer(Modifier.width(6.dp))
            Label(permMsg ?: ChatEngine.status, Modifier.weight(1f))
            if (!ChatEngine.running) Text("連線", color = C.Primary, fontSize = 12.sp, modifier = Modifier.clickable { permLauncher.launch(chatPermissions()) }.padding(4.dp))
        }
        if (showPeers) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(C.Card).padding(12.dp)) {
                Text("成員", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Label("我：" + ChatPrefs.userName + "（" + ChatPrefs.userId + "）")
                if (ChatEngine.peers.isEmpty()) Label("附近還沒有同群組的成員")
                ChatEngine.peers.values.forEach { p -> Label("● " + p.name + "（" + p.userId + "）", color = C.Green) }
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ChatEngine.messages.isEmpty()) item { Label("還沒有訊息。訊息與檔案只存在每支手機上。", Modifier.padding(16.dp)) }
            items(ChatEngine.messages, key = { it.id }) { m -> MessageBubble(m, onImage = { viewer = it }) }
        }
        if (emoji) {
            LazyVerticalGrid(GridCells.Fixed(8), Modifier.fillMaxWidth().height(180.dp).background(C.Card).padding(6.dp)) {
                items(EMOJI) { e ->
                    Text(e, fontSize = 24.sp, modifier = Modifier.clickable { text += e }.padding(6.dp))
                }
            }
        }
        if (attach) {
            Row(Modifier.fillMaxWidth().background(C.Card).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AttachButton("🖼️", "圖片", Modifier.weight(1f)) { attach = false; pickImage.launch("image/*") }
                AttachButton("📄", "檔案", Modifier.weight(1f)) { attach = false; pickFile.launch("*/*") }
            }
        }
        Row(
            Modifier.fillMaxWidth().background(C.Card).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("😊", fontSize = 22.sp, modifier = Modifier.clickable { emoji = !emoji; attach = false }.padding(8.dp))
            Text("＋", color = C.Primary, fontSize = 24.sp, modifier = Modifier.clickable { attach = !attach; emoji = false }.padding(8.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("輸入訊息") }, maxLines = 4, colors = fieldColors(),
            )
            Text(
                "傳送", color = if (text.isNotBlank()) C.Primary else C.Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable(enabled = text.isNotBlank()) { ChatEngine.sendText(text); text = ""; emoji = false }.padding(10.dp),
            )
        }
    }
}

@Composable
private fun AttachButton(icon: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.Bg).clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, fontSize = 26.sp)
        Text(label, color = C.Text, fontSize = 13.sp)
    }
}

@Composable
private fun MessageBubble(m: ChatEngine.Message, onImage: (String) -> Unit) {
    if (m.type == "system") {
        Label(m.text + " · " + hhmm(m.time), Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
        return
    }
    val mine = m.mine
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        if (!mine) Text(m.fromName, color = C.Text2, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        Column(
            Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp))
                .background(if (mine) C.Primary.copy(alpha = 0.9f) else C.Card).padding(10.dp),
        ) {
            val fg = if (mine) C.OnPrimary else C.Text
            when (m.type) {
                "image" -> {
                    val bmp = rememberThumb(m.path)
                    when {
                        bmp != null -> Image(bmp, null, Modifier.widthIn(max = 240.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(8.dp))
                            .clickable { m.path?.let(onImage) }, contentScale = ContentScale.Fit)
                        m.progress < 0 -> Text("圖片接收失敗：" + m.fileName, color = fg, fontSize = 13.sp)
                        else -> Text("接收圖片中… ${m.progress}%", color = fg, fontSize = 13.sp)
                    }
                }
                "file" -> Row(
                    Modifier.clickable(enabled = m.path != null) { m.path?.let { openFile(ctx, it) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📄", fontSize = 26.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(m.fileName, color = fg, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            sizeText(m.size) + when {
                                m.progress < 0 -> " · 接收失敗"
                                m.path == null -> " · 接收中 ${m.progress}%"
                                else -> " · 點擊開啟"
                            },
                            color = fg.copy(alpha = 0.75f), fontSize = 11.sp,
                        )
                    }
                }
                else -> Text(m.text, color = fg, fontSize = if (m.text.length <= 2 && m.text.any { Character.isSurrogate(it) }) 34.sp else 15.sp, lineHeight = 21.sp)
            }
        }
        Text(hhmm(m.time), color = C.Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
    }
}

/** 縮圖（避免大圖佔記憶體） */
@Composable
private fun rememberThumb(path: String?, max: Int = 720): ImageBitmap? {
    var bmp by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path == null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (o.outWidth / s > max || o.outHeight / s > max) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

@Composable
private fun ImageViewer(path: String, onClose: () -> Unit) {
    val bmp = rememberThumb(path, 2048)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            else Label("載入中…")
        }
    }
}

private fun openFile(ctx: android.content.Context, path: String) {
    runCatching {
        val f = File(path)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val type = ctx.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** 我的 › 群聊：開關、名稱、代號、群聊 ID */
@Composable
fun ChatSettingsCard(nav: NavController) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(ChatPrefs.userName) }
    var uid by remember { mutableStateOf(ChatPrefs.userId) }
    var gid by remember { mutableStateOf(ChatPrefs.groupId) }
    var msg by remember { mutableStateOf<String?>(null) }
    com.claudeetf.app.ui.Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("群聊功能", color = C.Text, fontSize = 14.sp)
                Label("P2P 直連（藍牙／Wi-Fi），不經伺服器；需在彼此附近")
            }
            androidx.compose.material3.Switch(checked = ChatPrefs.enabled, onCheckedChange = { on ->
                ChatPrefs.setEnabled(on)
                if (!on) ChatEngine.stop()
            })
        }
        if (!ChatPrefs.enabled) return@Card
        OutlinedTextField(name, { name = it }, label = { Text("顯示名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        OutlinedTextField(uid, { uid = it.uppercase() }, label = { Text("使用者代號（英數 4–12 碼）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        OutlinedTextField(gid, { gid = it }, label = { Text("群聊 ID（同 ID 的成員互連）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("儲存", color = C.Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                ChatPrefs.setUserName(name)
                val okId = ChatPrefs.setUserId(uid)
                val groupChanged = gid.trim() != ChatPrefs.groupId
                ChatPrefs.setGroupId(gid)
                msg = if (!okId) "代號需為英數 4–12 碼，其餘已儲存" else "已儲存" + if (groupChanged) "，已切換群組" else ""
                uid = ChatPrefs.userId
                if (ChatEngine.running) ChatEngine.restart(ctx)
            }.padding(vertical = 10.dp).padding(end = 24.dp))
            Text("開啟群聊 ›", color = C.Primary, fontSize = 14.sp, modifier = Modifier.clickable { nav.navigate(com.claudeetf.app.ui.Routes.CHAT) }.padding(vertical = 10.dp))
        }
        msg?.let { Label(it, color = C.Primary) }
        Label("狀態：" + ChatEngine.status + if (ChatEngine.peers.isNotEmpty()) "（" + ChatEngine.peers.values.joinToString("、") { it.name } + "）" else "")
        Text("清除本群聊紀錄", color = C.Red, fontSize = 12.sp, modifier = Modifier.clickable { ChatEngine.clearHistory(); msg = "已清除本機紀錄" }.padding(vertical = 6.dp))
    }
}
