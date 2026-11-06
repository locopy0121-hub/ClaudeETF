package com.claudeetf.app.chat

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 群聊（P2P，無伺服器）：Google Nearby Connections，P2P_CLUSTER 網狀連線。
 * - 同一個群聊 ID 的手機互相廣播與搜尋，自動連線（藍牙／BLE／Wi-Fi 直連，不需網路）
 * - 訊息用 JSON（文字、emoji），圖片與檔案用 FILE payload；每則訊息有唯一 id，
 *   收到後轉送給其他已連線成員（洪泛），讓不在彼此範圍內的成員也能收到
 * - 聊天紀錄與檔案只存在本機（files/chat/）
 */
object ChatEngine {
    private const val SERVICE_ID = "com.claudeetf.app.chat"

    data class Message(
        val id: String,
        val fromId: String,
        val fromName: String,
        val time: Long,
        /** text、image、file、system */
        val type: String,
        val text: String = "",
        val fileName: String = "",
        val size: Long = 0,
        /** 本機檔案路徑（收完或自己送出的） */
        val path: String? = null,
        /** 接收進度 0–100，-1 失敗 */
        val progress: Int = 100,
    ) {
        val mine: Boolean get() = fromId == ChatPrefs.userId
    }

    data class Peer(val endpointId: String, val name: String, val userId: String)

    val messages = mutableStateListOf<Message>()
    val peers = mutableStateMapOf<String, Peer>()
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("未啟動")
        private set
    /** 尚未讀的訊息數（不在聊天畫面時累加） */
    var unread by mutableStateOf(0)
    var viewing = false

    private var client: ConnectionsClient? = null
    private lateinit var appCtx: Context
    private val seen = HashSet<String>()
    /** FILE payload id → 訊息 id（等檔案收完再補路徑） */
    private val pendingFile = HashMap<Long, String>()
    private val pendingPayload = HashMap<Long, Payload>()

    private fun dir(): File = File(appCtx.filesDir, "chat").apply { mkdirs() }
    private fun historyFile(): File = File(dir(), "history_" + ChatPrefs.groupId.filter { it.isLetterOrDigit() } + ".json")

    // ---------------- 啟動／停止 ----------------

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (running) return
        if (ChatPrefs.groupId.isBlank()) { status = "請先設定群聊 ID"; return }
        load()
        val c = Nearby.getConnectionsClient(appCtx)
        client = c
        running = true
        status = "搜尋同群組成員中…"
        val name = endpointName()
        c.startAdvertising(name, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { e -> status = "廣播失敗：" + (e.message ?: "") }
        c.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { e -> status = "搜尋失敗：" + (e.message ?: "請確認藍牙、定位與附近裝置權限") }
    }

    fun stop() {
        client?.let { it.stopAdvertising(); it.stopDiscovery(); it.stopAllEndpoints() }
        client = null
        peers.clear()
        running = false
        status = "已停止"
    }

    fun restart(ctx: Context) { stop(); messages.clear(); seen.clear(); start(ctx) }

    /** 廣播名稱：群組|使用者代號|顯示名稱（Nearby 名稱有長度限制，名稱截短） */
    private fun endpointName() = ChatPrefs.groupId + "|" + ChatPrefs.userId + "|" + ChatPrefs.userName.take(20)

    private fun parseName(n: String): Triple<String, String, String>? {
        val p = n.split("|")
        return if (p.size >= 3) Triple(p[0], p[1], p.drop(2).joinToString("|")) else null
    }

    // ---------------- 連線 ----------------

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val (g, uid, _) = parseName(info.endpointName) ?: return
            if (g != ChatPrefs.groupId || uid == ChatPrefs.userId || peers.containsKey(endpointId)) return
            // 只由代號較小的一方發起，避免雙方同時連線衝突
            if (ChatPrefs.userId < uid) client?.requestConnection(endpointName(), endpointId, lifecycle)
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val parsed = parseName(info.endpointName)
            if (parsed == null || parsed.first != ChatPrefs.groupId) { client?.rejectConnection(endpointId); return }
            pendingNames[endpointId] = parsed
            client?.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val p = pendingNames.remove(endpointId) ?: return
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                peers[endpointId] = Peer(endpointId, p.third, p.second)
                status = "已連線 ${peers.size} 位成員"
                system("${p.third} 加入群聊")
                // 補送最近訊息給新成員（只送文字類，檔案太大不補）
                messages.filter { it.type == "text" }.takeLast(30).forEach { sendBytes(listOf(endpointId), toJson(it)) }
            }
        }

        override fun onDisconnected(endpointId: String) {
            peers.remove(endpointId)?.let { system("${it.name} 離開") }
            status = if (peers.isEmpty()) "搜尋同群組成員中…" else "已連線 ${peers.size} 位成員"
        }
    }
    private val pendingNames = HashMap<String, Triple<String, String, String>>()

    // ---------------- 收送 ----------------

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let { handleJson(endpointId, String(it, Charsets.UTF_8)) }
                Payload.Type.FILE -> pendingPayload[payload.id] = payload
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val msgId = pendingFile[update.payloadId] ?: return
            val i = messages.indexOfFirst { it.id == msgId }
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> if (i >= 0 && update.totalBytes > 0) {
                    messages[i] = messages[i].copy(progress = (update.bytesTransferred * 100 / update.totalBytes).toInt())
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    val p = pendingPayload.remove(update.payloadId)
                    pendingFile.remove(update.payloadId)
                    val saved = p?.let { saveIncoming(it, if (i >= 0) messages[i].fileName else "file") }
                    if (i >= 0) {
                        val m = messages[i].copy(path = saved?.absolutePath, progress = if (saved != null) 100 else -1)
                        messages[i] = m
                        save()
                        // 轉送給其他成員
                        if (saved != null) forward(m, saved, exclude = endpointId)
                    }
                }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                    pendingFile.remove(update.payloadId); pendingPayload.remove(update.payloadId)
                    if (i >= 0) messages[i] = messages[i].copy(progress = -1)
                }
            }
        }
    }

    private fun saveIncoming(p: Payload, name: String): File? = runCatching {
        val out = File(dir(), System.currentTimeMillis().toString() + "_" + name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        val f = p.asFile() ?: return null
        val uri: Uri? = f.asUri()
        if (uri != null) {
            appCtx.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            runCatching { appCtx.contentResolver.delete(uri, null, null) }
        } else {
            @Suppress("DEPRECATION")
            f.asJavaFile()?.let { src -> src.copyTo(out, overwrite = true); src.delete() }
        }
        out.takeIf { it.exists() && it.length() > 0 }
    }.getOrNull()

    private fun handleJson(from: String, json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val m = fromJson(o) ?: return
        if (!seen.add(m.id)) return
        val payloadId = o.optLong("pid", 0L)
        if ((m.type == "image" || m.type == "file") && payloadId != 0L) {
            pendingFile[payloadId] = m.id
            insertSorted(m.copy(path = null, progress = 0))
        } else {
            insertSorted(m)
            // 文字訊息直接轉送
            sendBytes(peers.keys.filter { it != from }, json)
        }
        if (!viewing && m.type != "system") unread++
        save()
    }

    /** 依時間插入（新成員補收的舊訊息不會跑到最後） */
    private fun insertSorted(m: Message) {
        val i = messages.indexOfLast { it.time <= m.time }
        messages.add(i + 1, m)
    }

    private fun sendBytes(to: List<String>, json: String) {
        if (to.isEmpty()) return
        client?.sendPayload(to, Payload.fromBytes(json.toByteArray(Charsets.UTF_8)))
    }

    private fun sendFile(to: List<String>, m: Message, file: File) {
        if (to.isEmpty()) return
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val payload = Payload.fromFile(pfd)
        val header = toJson(m).let { JSONObject(it).put("pid", payload.id).toString() }
        // 先送說明（含檔名、大小），再送檔案
        client?.sendPayload(to, Payload.fromBytes(header.toByteArray(Charsets.UTF_8)))
        client?.sendPayload(to, payload)
    }

    private fun forward(m: Message, file: File, exclude: String) {
        sendFile(peers.keys.filter { it != exclude }, m, file)
    }

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val m = Message(UUID.randomUUID().toString(), ChatPrefs.userId, ChatPrefs.userName, System.currentTimeMillis(), "text", t)
        seen.add(m.id); messages.add(m); save()
        sendBytes(peers.keys.toList(), toJson(m))
    }

    /** 送出圖片或檔案：先複製到本機聊天資料夾 */
    fun sendUri(ctx: Context, uri: Uri, image: Boolean) {
        val cr = ctx.contentResolver
        var name = "file"
        var size = 0L
        cr.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0) size = c.getLong(si)
            }
        }
        val out = File(dir(), System.currentTimeMillis().toString() + "_" + name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return
        if (size <= 0) size = out.length()
        val m = Message(UUID.randomUUID().toString(), ChatPrefs.userId, ChatPrefs.userName, System.currentTimeMillis(),
            if (image) "image" else "file", fileName = name, size = size, path = out.absolutePath)
        seen.add(m.id); messages.add(m); save()
        sendFile(peers.keys.toList(), m, out)
    }

    private fun system(text: String) {
        messages.add(Message(UUID.randomUUID().toString(), "", "系統", System.currentTimeMillis(), "system", text))
    }

    fun clearHistory() {
        messages.clear(); seen.clear()
        runCatching { historyFile().delete() }
    }

    // ---------------- 儲存 ----------------

    private fun toJson(m: Message): String = JSONObject()
        .put("id", m.id).put("f", m.fromId).put("n", m.fromName).put("t", m.time).put("ty", m.type)
        .put("x", m.text).put("fn", m.fileName).put("sz", m.size).toString()

    private fun fromJson(o: JSONObject): Message? {
        val id = o.optString("id").ifBlank { return null }
        return Message(id, o.optString("f"), o.optString("n"), o.optLong("t"), o.optString("ty", "text"),
            o.optString("x"), o.optString("fn"), o.optLong("sz"), o.optString("p").ifBlank { null })
    }

    private fun save() {
        if (!::appCtx.isInitialized) return
        runCatching {
            val arr = JSONArray()
            messages.filter { it.type != "system" }.takeLast(500).forEach { m ->
                arr.put(JSONObject(toJson(m)).put("p", m.path ?: ""))
            }
            historyFile().writeText(arr.toString())
        }
    }

    private fun load() {
        messages.clear(); seen.clear()
        runCatching {
            val f = historyFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { fromJson(it) }?.let { m ->
                messages.add(m.copy(path = m.path?.takeIf { File(it).exists() })); seen.add(m.id)
            }
        }
    }
}
