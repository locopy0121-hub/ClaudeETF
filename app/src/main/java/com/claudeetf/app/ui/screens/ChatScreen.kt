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
import com.claudeetf.app.ui.Segmented
import androidx.compose.foundation.verticalScroll
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
        if (!res.values.all { it }) permMsg = "未授權附近裝置／藍牙／定位：只能用「邀請」跨網連線"
        ChatEngine.start(ctx)
    }
    DisposableEffect(Unit) {
        ChatEngine.viewing = true; ChatEngine.unread = 0
        onDispose { ChatEngine.viewing = false }
    }
    // 只有「附近」連線需要藍牙、定位權限；只用跨網時直接啟動
    fun connect() { if (ChatPrefs.useNearby) permLauncher.launch(chatPermissions()) else ChatEngine.start(ctx) }
    LaunchedEffect(ChatPrefs.enabled, ChatPrefs.groupId) {
        if (ChatPrefs.enabled && ChatPrefs.groupId.isNotBlank() && !ChatEngine.running) connect()
    }
    var inviting by remember { mutableStateOf(false) }
    if (inviting) InviteDialog { inviting = false }
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
            Spacer(Modifier.width(6.dp))
            if (ChatPrefs.enabled && ChatPrefs.useInternet) Text(
                "邀請", color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(C.Card).clickable {
                    if (!ChatEngine.running) ChatEngine.start(ctx); inviting = true
                }.padding(horizontal = 10.dp, vertical = 5.dp),
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
            if (!ChatEngine.running) Text("連線", color = C.Primary, fontSize = 12.sp, modifier = Modifier.clickable { connect() }.padding(4.dp))
        }
        if (showPeers) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(C.Card).padding(12.dp)) {
                Text("成員", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Label("我：" + ChatPrefs.userName + "（" + ChatPrefs.userId + "）")
                if (ChatEngine.peers.isEmpty()) Label("還沒有連上的成員：附近成員會自動連線，遠端成員請按「邀請」")
                ChatEngine.links.values.groupBy { it.userId }.forEach { (uid, ls) ->
                    Label("● " + ls.first().name + "（" + uid + "）· " + ls.map { it.via }.distinct().joinToString("＋") { if (it == "nearby") "附近" else "網路" }, color = C.Green)
                }
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
                        m.progress < 0 -> Text("圖片接收失敗，點此重試：" + m.fileName, color = fg, fontSize = 13.sp,
                            modifier = Modifier.clickable { ChatEngine.retryFile(m) })
                        else -> Text("接收圖片中… ${m.progress}%", color = fg, fontSize = 13.sp)
                    }
                }
                "file" -> Row(
                    Modifier.clickable { if (m.path != null) openFile(ctx, m.path) else if (m.progress < 0) ChatEngine.retryFile(m) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("📄", fontSize = 26.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(m.fileName, color = fg, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            sizeText(m.size) + when {
                                m.progress < 0 -> " · 接收失敗，點此重試"
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
    var pw by remember { mutableStateOf(ChatPrefs.groupPassword) }
    var msg by remember { mutableStateOf<String?>(null) }
    com.claudeetf.app.ui.Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("群聊功能", color = C.Text, fontSize = 14.sp)
                Label("P2P，不經伺服器：附近自動直連；遠端用邀請碼跨網連線")
            }
            androidx.compose.material3.Switch(checked = ChatPrefs.enabled, onCheckedChange = { on ->
                ChatPrefs.updateEnabled(on)
                if (!on) ChatEngine.stop()
            })
        }
        if (!ChatPrefs.enabled) return@Card
        OutlinedTextField(name, { name = it }, label = { Text("顯示名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        OutlinedTextField(uid, { uid = it.uppercase() }, label = { Text("使用者代號（英數 4–12 碼）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        OutlinedTextField(gid, { gid = it }, label = { Text("群聊 ID（同 ID 的成員互連）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
        OutlinedTextField(pw, { pw = it }, label = { Text("群組密碼（加密訊息，成員需相同；可空白）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        OptToggle("附近直連（藍牙／Wi-Fi，不需網路）", ChatPrefs.useNearby) { ChatPrefs.updateUseNearby(it); if (ChatEngine.running) ChatEngine.restart(ctx) }
        OptToggle("跨網連線（WebRTC，用邀請碼加入）", ChatPrefs.useInternet) { ChatPrefs.updateUseInternet(it); if (ChatEngine.running) ChatEngine.restart(ctx) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("儲存", color = C.Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                ChatPrefs.updateUserName(name)
                val okId = ChatPrefs.updateUserId(uid)
                val groupChanged = gid.trim() != ChatPrefs.groupId
                ChatPrefs.updateGroupId(gid)
                ChatPrefs.updateGroupPassword(pw)
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

/** 文字 → QR Code 圖（邀請碼約 1–2 KB，用低容錯等級讓碼不會太密） */
private fun qrBitmap(text: String, size: Int = 900): ImageBitmap? = runCatching {
    val hints = mapOf(
        com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L,
        com.google.zxing.EncodeHintType.MARGIN to 1,
        com.google.zxing.EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    android.graphics.Bitmap.createBitmap(px, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()

private fun shareText(ctx: android.content.Context, text: String, title: String) {
    runCatching {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * 跨網邀請（無信令伺服器：兩步交換）
 *  我邀請：產生邀請碼 → 對方掃描 → 對方給我回覆碼 → 我掃描或貼上 → 連線
 *  我加入：掃描或貼上邀請碼 → 顯示回覆碼給邀請者掃描 → 連線
 */
@Composable
private fun InviteDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var tab by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf<String?>(null) }     // 要給對方的碼（邀請碼或回覆碼）
    var input by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun handleInput(text: String) {
        val t = text.trim()
        msg = null
        when {
            com.claudeetf.app.chat.WebRtcTransport.isInvite(t) -> {
                busy = true; tab = 1
                com.claudeetf.app.chat.WebRtcTransport.acceptInvite(t, done = { reply, g, inviter ->
                    busy = false; code = reply
                    msg = "已加入群聊「$g」。請把下方回覆碼給 $inviter 掃描或貼上，完成後自動連線。"
                }, fail = { e -> busy = false; msg = e })
            }
            com.claudeetf.app.chat.WebRtcTransport.isReply(t) -> {
                busy = true
                com.claudeetf.app.chat.WebRtcTransport.completeInvite(t, done = { name ->
                    busy = false; msg = "已收到 $name 的回覆碼，正在打洞連線…（幾秒內成員清單會出現對方）"
                }, fail = { e -> busy = false; msg = e })
            }
            t.isNotEmpty() -> msg = "這不是邀請碼或回覆碼（應以 CETF- 開頭）"
        }
    }

    val scan = rememberLauncherForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        r.contents?.let { input = it; handleInput(it) }
    }
    fun startScan() = scan.launch(com.journeyapps.barcodescanner.ScanOptions().apply {
        setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
        setPrompt("掃描對方畫面上的邀請碼或回覆碼"); setBeepEnabled(false); setOrientationLocked(false)
    })

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(18.dp)).background(C.Bg).padding(16.dp)
            .verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("跨網邀請", Modifier.weight(1f), color = C.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("關閉", color = C.Muted, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClose).padding(6.dp))
            }
            Segmented(listOf("我邀請別人", "我要加入"), tab, { tab = it; code = null; msg = null }, background = C.Card)
            Spacer(Modifier.height(10.dp))
            if (tab == 0) {
                Label("1. 產生邀請碼，讓對方用他的 App 掃描（或傳文字給他）\n2. 對方會出現「回覆碼」，掃描或貼上回覆碼即可連線\n邀請碼 10 分鐘內有效，一個碼邀請一人")
                if (code == null) {
                    Text(if (busy) "產生中（收集網路位址約 3–6 秒）…" else "產生邀請碼", color = C.Primary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable(enabled = !busy) {
                            busy = true; msg = null
                            com.claudeetf.app.chat.WebRtcTransport.createInvite(done = { busy = false; code = it }, fail = { busy = false; msg = it })
                        }.padding(vertical = 12.dp))
                }
            } else {
                Label("掃描邀請者畫面上的 QR Code，或貼上對方傳來的邀請碼文字")
            }
            code?.let { c -> CodePanel(c, if (tab == 0) "邀請碼" else "回覆碼", onCopy = { clip.setText(androidx.compose.ui.text.AnnotatedString(c)); msg = "已複製" },
                onShare = { shareText(ctx, c, "分享" + if (tab == 0) "邀請碼" else "回覆碼") }) }
            // 邀請者這一步要輸入回覆碼；加入者這一步要輸入邀請碼
            if (tab == 1 && code == null || tab == 0 && code != null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AttachButton("📷", "掃描" + if (tab == 0) "回覆碼" else "邀請碼", Modifier.weight(1f)) { startScan() }
                    AttachButton("📋", "貼上", Modifier.weight(1f)) {
                        clip.getText()?.text?.let { input = it; handleInput(it) } ?: run { msg = "剪貼簿沒有文字" }
                    }
                }
                OutlinedTextField(input, { input = it }, label = { Text("或手動貼上碼") }, maxLines = 3, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
                Text("確認", color = C.Primary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(enabled = input.isNotBlank() && !busy) { handleInput(input) }.padding(vertical = 10.dp))
            }
            msg?.let { Text(it, color = C.Text2, fontSize = 13.sp, lineHeight = 19.sp) }
            Spacer(Modifier.height(6.dp))
            Label("連線不經伺服器：只用公用 STUN 查詢公網位址，訊息由手機間直接傳送並以群組密碼加密。若雙方網路都無法打洞（部分行動網路），可改經其他已連線成員轉送。")
            com.claudeetf.app.chat.WebRtcTransport.states.takeIf { it.isNotEmpty() }?.let { st ->
                Spacer(Modifier.height(6.dp))
                st.values.forEach { Label("· $it") }
            }
        }
    }
}

@Composable
private fun CodePanel(code: String, title: String, onCopy: () -> Unit, onShare: () -> Unit) {
    val bmp = remember(code) { qrBitmap(code) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Card).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("我的$title", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        if (bmp != null) Image(bmp, title, Modifier.fillMaxWidth(0.85f).aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp))
        else Label("碼太長無法產生 QR Code，請用複製／分享")
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 8.dp)) {
            Text("複製", color = C.Primary, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onCopy).padding(6.dp))
            Text("分享（LINE 等）", color = C.Primary, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onShare).padding(6.dp))
        }
        Label("長度 ${code.length} 字")
    }
}
