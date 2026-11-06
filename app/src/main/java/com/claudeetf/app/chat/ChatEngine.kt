package com.claudeetf.app.chat

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 群聊核心（與傳輸方式無關）
 * =====================================================================
 * 傳輸層（ChatTransport）只負責把 JSON 字串送到「連線（link）」：
 *   - NearbyTransport：附近直連（藍牙／Wi-Fi，不需網路）
 *   - WebRtcTransport：跨網（WebRTC DataChannel，STUN 打洞，邀請碼交換連線資訊）
 *
 * 訊息模型（類 CRDT：只增集合＋版本向量）
 *   - 訊息 id = 作者代號:Lamport，全網唯一；收到重複 id 直接忽略（冪等）
 *   - 排序依 (Lamport, 作者) ，所有成員順序一致
 *   - 同步向量 SyncVector：每位作者已收到的最大 Lamport。連線建立時互換，
 *     對方缺的區間由擁有者補送 → 離線期間的訊息上線後自動補齊
 *   - 文字內容以群組金鑰 AES-GCM 加密（ChatCrypto），中繼者看不到明文
 *   - 訊息洪泛轉送：收到新訊息就轉給其他連線，不在彼此範圍內的成員也收得到
 *
 * 檔案：訊息只帶 fileId（內容 SHA-256），接收方向「給它這則訊息的連線」要檔案，
 *       對方以 12 KB 分塊加密傳送；離線補齊的訊息也能事後要到檔案。
 */
object ChatEngine {
    interface ChatTransport {
        val kind: String                      // nearby、internet
        fun send(link: String, json: String)
        /** 送大量分塊前呼叫：緩衝滿時阻塞等待（在背景執行緒呼叫） */
        fun awaitWritable(link: String) {}
        fun close(link: String) {}
    }

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
        val path: String? = null,
        /** 接收進度 0–100，-1 失敗 */
        val progress: Int = 100,
        val lamport: Long = 0,
        val fileId: String = "",
        /** 無法解密（群組密碼不同） */
        val locked: Boolean = false,
    ) {
        val mine: Boolean get() = fromId == ChatPrefs.userId
    }

    /** 已完成握手的連線 */
    data class Link(val link: String, val userId: String, val name: String, val via: String, val since: Long)

    val messages = mutableStateListOf<Message>()
    /** link → 成員 */
    val links = mutableStateMapOf<String, Link>()
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("未啟動")
    var unread by mutableStateOf(0)
    /** 是否正在看聊天畫面；開啟時把別人的訊息標成已讀 */
    var viewing = false
        set(v) { field = v; if (v) main.post { markAllRead() } }

    /** 已讀回條：訊息 id → 已送達／已讀的成員代號 */
    val delivered = mutableStateMapOf<String, Set<String>>()
    val readBy = mutableStateMapOf<String, Set<String>>()
    private val readSent = HashSet<String>()

    /** 相容舊介面：線上成員（依使用者去重） */
    val peers: Map<String, Link> get() = links.values.groupBy { it.userId }.mapValues { it.value.first() }

    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val transports = HashMap<String, ChatTransport>()   // link 前綴 → 傳輸
    private val seen = HashSet<String>()
    private var lamport = 0L
    private val vector = HashMap<String, Long>()
    /** 正在接收的檔案：fileId → 狀態 */
    private val incoming = HashMap<String, Incoming>()
    private class Incoming(val file: File, val size: Long, val chunks: Int, val got: BooleanArray, var count: Int = 0)

    private const val CHUNK = 12 * 1024
    private const val TTL = 6

    fun ctx(): Context = appCtx
    fun isReady() = ::appCtx.isInitialized

    private fun dir(): File = File(appCtx.filesDir, "chat").apply { mkdirs() }
    private fun historyFile(): File = File(dir(), "history_" + ChatPrefs.groupId.filter { it.isLetterOrDigit() } + ".json")
    private fun fileFor(fileId: String): File? = dir().listFiles()?.firstOrNull { it.name.startsWith("f_" + fileId.take(16) + "_") && !it.name.endsWith(".part") }

    // =================================================================
    // 啟動／停止
    // =================================================================

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (running) return
        if (ChatPrefs.groupId.isBlank()) { status = "請先設定群聊 ID"; return }
        load()
        running = true
        io.execute { ChatCrypto.warmUp() }
        // 附近直連需要藍牙、定位、附近裝置權限；沒授權就先不啟動，避免 8034 等錯誤
        if (ChatPrefs.useNearby && hasNearbyPermissions(appCtx)) runCatching { NearbyTransport.start(appCtx) }.onFailure { status = "附近連線無法啟動：" + (it.message ?: "") }
        if (ChatPrefs.useInternet) runCatching { WebRtcTransport.start(appCtx) }.onFailure { status = "跨網連線無法啟動：" + (it.message ?: "") }
        refreshStatus()
    }

    /** 附近直連所需權限是否都已授權 */
    fun hasNearbyPermissions(ctx: Context): Boolean = buildList {
        if (android.os.Build.VERSION.SDK_INT >= 31) { add(android.Manifest.permission.BLUETOOTH_SCAN); add(android.Manifest.permission.BLUETOOTH_ADVERTISE); add(android.Manifest.permission.BLUETOOTH_CONNECT) }
        if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        add(android.Manifest.permission.ACCESS_COARSE_LOCATION); add(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }.all { androidx.core.content.ContextCompat.checkSelfPermission(ctx, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

    fun stop() {
        runCatching { NearbyTransport.stop() }
        runCatching { WebRtcTransport.stop() }
        links.clear()
        running = false
        status = "已停止"
    }

    fun restart(ctx: Context) { stop(); start(ctx) }

    fun register(prefix: String, t: ChatTransport) { transports[prefix] = t }
    private fun transportOf(link: String): ChatTransport? = transports[link.substringBefore(':')]

    fun refreshStatus() {
        val n = peers.size
        status = when {
            !running -> "已停止"
            n > 0 -> "已連線 $n 位成員（" + links.values.map { it.via }.distinct().joinToString("、") { if (it == "nearby") "附近" else "網路" } + "）"
            ChatPrefs.useInternet && !ChatPrefs.useNearby -> "尚無連線：用「邀請」與成員交換邀請碼"
            ChatPrefs.useNearby && !hasNearbyPermissions(appCtx) -> "附近直連尚未授權（藍牙、定位、附近裝置）"
            else -> "搜尋附近成員中…；不在附近可用「邀請」跨網連線"
        }
    }

    // =================================================================
    // 連線事件（傳輸層呼叫，可在任意執行緒）
    // =================================================================

    /** 傳輸層連線建立：送出 hello */
    fun onLinkUp(link: String) = main.post {
        send(link, JSONObject().put("k", "hello").put("id", ChatPrefs.userId).put("n", ChatPrefs.userName)
            .put("g", ChatPrefs.groupId).put("v", 2))
    }

    fun onLinkDown(link: String) = main.post {
        links.remove(link)?.let { l ->
            if (peers[l.userId] == null) system("${l.name} 離線")
        }
        refreshStatus()
    }

    /** 收到任一傳輸的封包 */
    fun onPacket(link: String, json: String) = main.post { handle(link, json) }

    private fun send(link: String, o: JSONObject) {
        val t = transportOf(link) ?: return
        val s = o.toString()
        runCatching { t.send(link, s) }
    }

    private fun broadcast(o: JSONObject, except: String? = null) {
        links.keys.filter { it != except }.forEach { send(it, o) }
    }

    // =================================================================
    // 封包處理
    // =================================================================

    private fun handle(link: String, json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (o.optString("k")) {
            "hello" -> {
                if (o.optString("g") != ChatPrefs.groupId) { transportOf(link)?.close(link); return }
                val uid = o.optString("id")
                if (uid.isBlank() || uid == ChatPrefs.userId) { transportOf(link)?.close(link); return }
                val isNew = peers[uid] == null
                links[link] = Link(link, uid, o.optString("n", uid), transportOf(link)?.kind ?: "?", System.currentTimeMillis())
                if (isNew) system("${o.optString("n", uid)} 已連線")
                refreshStatus()
                // 交換同步向量，補齊離線訊息；告知已連線成員（WebRTC 網狀擴展）
                send(link, JSONObject().put("k", "vec").put("v", JSONObject(vector as Map<*, *>)))
                // 對方離線期間我讀過的訊息，補送已讀回條
                val myReads = messages.filter { it.fromId == uid && it.id in readSent }.takeLast(200).map { it.id }
                if (myReads.isNotEmpty()) send(link, JSONObject().put("k", "rcpt").put("by", ChatPrefs.userId).put("t", "r").put("ids", JSONArray(myReads)))
                if (viewing) markAllRead()
                send(link, JSONObject().put("k", "peers").put("list", JSONArray(peers.keys.filter { it != uid })))
            }
            "vec" -> {
                val theirs = o.optJSONObject("v") ?: JSONObject()
                val missing = messages.filter { m ->
                    m.lamport > 0 && m.type != "system" && m.lamport > theirs.optLong(m.fromId, 0L)
                }.takeLast(500)
                missing.forEach { send(link, wire(it)) }
            }
            "peers" -> {
                val arr = o.optJSONArray("list") ?: return
                val ids = (0 until arr.length()).map { arr.optString(it) }
                WebRtcTransport.meshWith(ids, via = link)
            }
            "sig" -> {
                // WebRTC 信令經成員轉送（無信令伺服器）
                if (!seen.add("sig:" + o.optString("sid") + ":" + o.optString("t"))) return
                val to = o.optString("to")
                if (to == ChatPrefs.userId) WebRtcTransport.onSignal(o, viaLink = link)
                else if (o.optInt("ttl", 0) > 0) {
                    o.put("ttl", o.optInt("ttl") - 1)
                    val direct = links.values.firstOrNull { it.userId == to }?.link
                    if (direct != null) send(direct, o) else broadcast(o, except = link)
                }
            }
            "msg" -> onMessage(link, o)
            "freq" -> serveFile(link, o.optString("fid"))
            "fc" -> onChunk(o)
            "rcpt" -> {
                // 回條：{by, t: d 送達／r 已讀, ids[]}；洪泛轉送給其他成員
                val by = o.optString("by"); val t = o.optString("t")
                val arr = o.optJSONArray("ids") ?: return
                if (!seen.add("rc:" + by + ":" + t + ":" + arr.optString(arr.length() - 1) + ":" + arr.length())) return
                val target = if (t == "r") readBy else delivered
                var changed = false
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (messages.none { it.id == id }) continue
                    val cur = target[id].orEmpty()
                    if (by !in cur) { target[id] = cur + by; changed = true }
                    if (t == "r") { val d = delivered[id].orEmpty(); if (by !in d) delivered[id] = d + by }
                }
                if (changed) save()
                broadcast(o, except = link)
            }
            "fnone" -> {
                val fid = o.optString("fid")
                // 這條連線沒有檔案：改向其他連線要
                links.keys.filter { it != link }.firstOrNull()?.let { send(it, JSONObject().put("k", "freq").put("fid", fid)) }
            }
        }
    }

    /** 訊息上線格式：文字與檔名加密 */
    private fun wire(m: Message): JSONObject = JSONObject()
        .put("k", "msg").put("id", m.id).put("f", m.fromId).put("n", m.fromName).put("t", m.time)
        .put("l", m.lamport).put("ty", m.type).put("sz", m.size).put("fid", m.fileId)
        .put("e", ChatCrypto.encText(JSONObject().put("x", m.text).put("fn", m.fileName).toString()))

    private fun onMessage(link: String, o: JSONObject) {
        val id = o.optString("id").ifBlank { return }
        if (!seen.add(id)) return
        val inner = o.optString("e").takeIf { it.isNotBlank() }?.let { ChatCrypto.decText(it) }?.let { runCatching { JSONObject(it) }.getOrNull() }
        val locked = o.has("e") && inner == null
        val l = o.optLong("l", 0L)
        val author = o.optString("f")
        lamport = maxOf(lamport, l)
        if (l > (vector[author] ?: 0L)) vector[author] = l
        val type = o.optString("ty", "text")
        val fid = o.optString("fid")
        val existing = if (fid.isNotBlank()) fileFor(fid) else null
        val m = Message(
            id, author, o.optString("n"), o.optLong("t"), type,
            text = if (locked) "🔒 無法解密（群組密碼不同）" else inner?.optString("x").orEmpty(),
            fileName = inner?.optString("fn").orEmpty(), size = o.optLong("sz"),
            path = existing?.absolutePath, progress = if (fid.isBlank() || existing != null) 100 else 0,
            lamport = l, fileId = fid, locked = locked,
        )
        insertSorted(m)
        // 轉送給其他連線（洪泛）
        broadcast(o, except = link)
        // 需要檔案：向送來這則訊息的連線要
        if (fid.isNotBlank() && existing == null && !locked) send(link, JSONObject().put("k", "freq").put("fid", fid))
        if (!viewing) {
            unread++
            if (ChatPrefs.danmaku && !locked) pushDanmaku(m)
        }
        // 回條：已送達；正在看畫面就直接已讀
        if (!locked) receipt(if (viewing) "r" else "d", listOf(id))
        save()
    }

    // =================================================================
    // 發送
    // =================================================================

    private fun nextId(): Pair<String, Long> {
        lamport += 1
        vector[ChatPrefs.userId] = lamport
        return (ChatPrefs.userId + ":" + lamport) to lamport
    }

    /** 彈幕：最多同時 6 則，分 3 條軌道 */
    data class Danmaku(val id: String, val name: String, val text: String, val lane: Int)
    val danmaku = mutableStateListOf<Danmaku>()
    private var lane = 0

    private fun pushDanmaku(m: Message) {
        val text = when (m.type) {
            "image" -> "🖼️ 傳了一張圖片"
            "file" -> "📄 " + m.fileName.ifBlank { "檔案" }
            else -> m.text.replace('\n', ' ').take(40)
        }
        lane = (lane + 1) % 3
        danmaku.add(Danmaku(m.id, m.fromName, text, lane))
        while (danmaku.size > 6) danmaku.removeAt(0)
    }

    fun dismissDanmaku(id: String) { danmaku.removeAll { it.id == id } }

    private fun receipt(t: String, ids: List<String>) {
        val mine = ids.filter { id -> messages.firstOrNull { it.id == id }?.let { !it.mine && it.type != "system" } == true }
            .filter { t != "r" || readSent.add(it) }
        if (mine.isEmpty()) return
        broadcast(JSONObject().put("k", "rcpt").put("by", ChatPrefs.userId).put("t", t).put("ids", JSONArray(mine.takeLast(200))))
    }

    /** 開啟聊天畫面：把別人的訊息全部回已讀 */
    fun markAllRead() {
        if (links.isEmpty()) return
        receipt("r", messages.filter { !it.mine && it.type != "system" && it.lamport > 0 && it.id !in readSent }.map { it.id })
    }

    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val (id, l) = nextId()
        val m = Message(id, ChatPrefs.userId, ChatPrefs.userName, System.currentTimeMillis(), "text", t, lamport = l)
        seen.add(id); messages.add(m); save()
        broadcast(wire(m))
    }

    /** 圖片或檔案：複製到本機、算 SHA-256 當 fileId，訊息只帶 id，對方再來要 */
    fun sendUri(ctx: Context, uri: Uri, image: Boolean) {
        val cr = ctx.contentResolver
        var name = "file"
        cr.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && ni >= 0) name = c.getString(ni) ?: name
        }
        val tmp = File(dir(), "tmp_" + System.currentTimeMillis())
        val md = MessageDigest.getInstance("SHA-256")
        cr.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n); out.write(buf, 0, n) }
            }
        } ?: return
        val fid = md.digest().joinToString("") { "%02x".format(it) }
        val out = File(dir(), "f_" + fid.take(16) + "_" + safe(name))
        if (!out.exists()) tmp.renameTo(out) else tmp.delete()
        val (id, l) = nextId()
        val m = Message(id, ChatPrefs.userId, ChatPrefs.userName, System.currentTimeMillis(), if (image) "image" else "file",
            fileName = name, size = out.length(), path = out.absolutePath, lamport = l, fileId = fid)
        seen.add(id); messages.add(m); save()
        broadcast(wire(m))
    }

    private fun safe(n: String) = n.replace(Regex("[\\\\/:*?\"<>|]"), "_").takeLast(80)

    // =================================================================
    // 檔案分塊傳輸
    // =================================================================

    private fun serveFile(link: String, fid: String) {
        val f = fileFor(fid)
        if (f == null) { send(link, JSONObject().put("k", "fnone").put("fid", fid)); return }
        val t = transportOf(link) ?: return
        val total = ((f.length() + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)
        io.execute {
            runCatching {
                RandomAccessFile(f, "r").use { raf ->
                    val buf = ByteArray(CHUNK)
                    for (i in 0 until total) {
                        raf.seek(i.toLong() * CHUNK)
                        val n = raf.read(buf).coerceAtLeast(0)
                        val enc = ChatCrypto.encrypt(buf.copyOf(n))
                        t.awaitWritable(link)
                        t.send(link, JSONObject().put("k", "fc").put("fid", fid).put("i", i).put("n", total)
                            .put("sz", f.length()).put("d", ChatCrypto.b64(enc)).toString())
                    }
                }
            }
        }
    }

    private fun onChunk(o: JSONObject) {
        val fid = o.optString("fid")
        val i = o.optInt("i"); val total = o.optInt("n"); val size = o.optLong("sz")
        val msgIdx = messages.indexOfFirst { it.fileId == fid && it.path == null }
        if (msgIdx < 0) return
        val m = messages[msgIdx]
        val inc = incoming.getOrPut(fid) {
            Incoming(File(dir(), "f_" + fid.take(16) + "_" + safe(m.fileName.ifBlank { "file" }) + ".part"), size, total, BooleanArray(total))
        }
        if (i !in 0 until inc.chunks || inc.got[i]) return
        val data = runCatching { ChatCrypto.decrypt(ChatCrypto.unb64(o.optString("d"))) }.getOrNull()
            ?: run { messages[msgIdx] = m.copy(progress = -1); incoming.remove(fid); return }
        RandomAccessFile(inc.file, "rw").use { it.seek(i.toLong() * CHUNK); it.write(data) }
        inc.got[i] = true; inc.count++
        messages[msgIdx] = m.copy(progress = inc.count * 100 / inc.chunks)
        if (inc.count == inc.chunks) {
            incoming.remove(fid)
            val final = File(inc.file.path.removeSuffix(".part"))
            inc.file.renameTo(final)
            val ok = runCatching {
                val md = MessageDigest.getInstance("SHA-256")
                final.inputStream().use { s -> val b = ByteArray(64 * 1024); while (true) { val n = s.read(b); if (n <= 0) break; md.update(b, 0, n) } }
                md.digest().joinToString("") { "%02x".format(it) } == fid
            }.getOrDefault(false)
            if (!ok) final.delete()
            messages.indices.filter { messages[it].fileId == fid }.forEach { k ->
                messages[k] = messages[k].copy(path = if (ok) final.absolutePath else null, progress = if (ok) 100 else -1)
            }
            save()
        }
    }

    /** 重新要檔案（失敗時點擊重試） */
    fun retryFile(m: Message) {
        val i = messages.indexOfFirst { it.id == m.id }
        if (i >= 0) messages[i] = m.copy(progress = 0)
        incoming.remove(m.fileId)
        links.keys.firstOrNull()?.let { send(it, JSONObject().put("k", "freq").put("fid", m.fileId)) }
    }

    // =================================================================
    // WebRTC 信令經成員轉送
    // =================================================================

    fun relaySignal(to: String, o: JSONObject) = main.post {
        o.put("k", "sig").put("to", to).put("from", ChatPrefs.userId).put("ttl", TTL)
        seen.add("sig:" + o.optString("sid") + ":" + o.optString("t"))
        val direct = links.values.firstOrNull { it.userId == to }?.link
        if (direct != null) send(direct, o) else broadcast(o)
    }

    fun hasRouteTo(userId: String) = links.values.any { it.userId == userId }

    // =================================================================
    // 紀錄
    // =================================================================

    private fun insertSorted(m: Message) {
        val i = messages.indexOfLast { compare(it, m) <= 0 }
        messages.add(i + 1, m)
    }

    private fun compare(a: Message, b: Message): Int = when {
        a.lamport == 0L || b.lamport == 0L -> a.time.compareTo(b.time)
        a.lamport != b.lamport -> a.lamport.compareTo(b.lamport)
        else -> a.fromId.compareTo(b.fromId)
    }

    fun system(text: String) {
        messages.add(Message(UUID.randomUUID().toString(), "", "系統", System.currentTimeMillis(), "system", text))
    }

    fun clearHistory() {
        messages.clear(); seen.clear(); vector.clear(); readBy.clear(); delivered.clear(); readSent.clear()
        runCatching { historyFile().delete() }
    }

    private fun save() {
        if (!isReady()) return
        val snapshot = messages.filter { it.type != "system" }.takeLast(800)
        io.execute {
            runCatching {
                val arr = JSONArray()
                snapshot.forEach { m ->
                    arr.put(JSONObject().put("id", m.id).put("f", m.fromId).put("n", m.fromName).put("t", m.time)
                        .put("ty", m.type).put("x", if (m.locked) "" else m.text).put("fn", m.fileName).put("sz", m.size)
                        .put("p", m.path ?: "").put("l", m.lamport).put("fid", m.fileId).put("lk", m.locked)
                        .put("rd", JSONArray(readBy[m.id].orEmpty().toList())).put("dv", JSONArray(delivered[m.id].orEmpty().toList()))
                        .put("rs", m.id in readSent))
                }
                historyFile().writeText(arr.toString())
            }
        }
    }

    private fun load() {
        messages.clear(); seen.clear(); vector.clear(); lamport = 0
        readBy.clear(); delivered.clear(); readSent.clear()
        runCatching {
            val f = historyFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                val l = o.optLong("l", 0L)
                val path = o.optString("p").ifBlank { null }?.takeIf { File(it).exists() }
                val m = Message(id, o.optString("f"), o.optString("n"), o.optLong("t"), o.optString("ty", "text"),
                    o.optString("x"), o.optString("fn"), o.optLong("sz"), path,
                    progress = if (o.optString("fid").isBlank() || path != null) 100 else -1,
                    lamport = l, fileId = o.optString("fid"), locked = o.optBoolean("lk"))
                messages.add(m); seen.add(id)
                fun set(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
                set("rd").takeIf { it.isNotEmpty() }?.let { readBy[id] = it }
                set("dv").takeIf { it.isNotEmpty() }?.let { delivered[id] = it }
                if (o.optBoolean("rs")) readSent.add(id)
                if (l > 0) { lamport = maxOf(lamport, l); if (l > (vector[m.fromId] ?: 0L)) vector[m.fromId] = l }
            }
        }
    }
}
