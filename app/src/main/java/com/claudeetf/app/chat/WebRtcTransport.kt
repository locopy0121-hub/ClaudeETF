package com.claudeetf.app.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * 跨網傳輸：WebRTC DataChannel（Full Mesh），沒有信令伺服器。
 *
 * 信令（交換 SDP）的兩種方式：
 *  1. 邀請碼：邀請者產生 Offer（含完整 ICE 候選），以 QR Code／文字傳給對方；
 *     對方回一個「回覆碼」（Answer），邀請者掃描或貼上後即連線。
 *  2. 成員轉送：已在群內的成員互相告知名單（peers），未直連的兩人透過共同成員轉送 Offer／Answer，
 *     自動補成全連通網狀；網路切換時也用這條路做 ICE Restart。
 *
 * NAT 打洞：公用 STUN（Google、Cloudflare）只用來查詢公網位址，不經手訊息。
 * 雙方都在對稱型 NAT 時可能無法直連，此時訊息仍可經其他成員洪泛轉送。
 * 為了能把 SDP 放進 QR Code，採「收集完 ICE 候選再送出」（non-trickle）。
 */
object WebRtcTransport : ChatEngine.ChatTransport {
    override val kind = "internet"
    private val main = Handler(Looper.getMainLooper())

    private val iceServers = listOf(
        PeerConnection.IceServer.builder(listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")).createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
    )

    private var factory: PeerConnectionFactory? = null
    private lateinit var appCtx: Context

    /** 每一條 WebRTC 連線 */
    class Session(
        val sid: String,
        val pc: PeerConnection,
        val offerer: Boolean,
        var remoteUser: String? = null,
        var dc: DataChannel? = null,
        var open: Boolean = false,
        var onGathered: ((SessionDescription) -> Unit)? = null,
        val created: Long = System.currentTimeMillis(),
    ) {
        val link get() = "w:$sid"
        var gatherTimer: Runnable? = null
    }

    private val sessions = HashMap<String, Session>()
    /** 畫面顯示用：sid → 狀態文字 */
    val states = mutableStateMapOf<String, String>()

    // =================================================================
    // 初始化
    // =================================================================

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        ChatEngine.register("w", this)
        if (factory == null) {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(appCtx).createInitializationOptions())
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        }
        watchNetwork()
    }

    fun stop() {
        sessions.values.toList().forEach { runCatching { it.dc?.close(); it.pc.close() } }
        sessions.clear(); states.clear()
        unwatchNetwork()
    }

    private fun newSession(sid: String, offerer: Boolean): Session? {
        val f = factory ?: return null
        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            iceCandidatePoolSize = 2
        }
        var holder: Session? = null
        val pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                main.post { holder?.let { onIceState(it, s) } }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) main.post { holder?.let { finishGathering(it) } }
            }
            override fun onIceCandidate(c: IceCandidate?) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) { if (dc != null) main.post { holder?.let { attach(it, dc) } } }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        }) ?: return null
        val s = Session(sid, pc, offerer)
        holder = s
        sessions[sid] = s
        if (offerer) attach(s, pc.createDataChannel("chat", DataChannel.Init().apply { ordered = true }))
        return s
    }

    private fun attach(s: Session, dc: DataChannel) {
        s.dc = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {
                val st = dc.state()
                main.post {
                    if (st == DataChannel.State.OPEN && !s.open) {
                        s.open = true
                        states[s.sid] = "已連線"
                        ChatEngine.onLinkUp(s.link)
                    } else if (st == DataChannel.State.CLOSED && s.open) {
                        s.open = false
                        states[s.sid] = "已中斷"
                        ChatEngine.onLinkDown(s.link)
                    }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                ChatEngine.onPacket(s.link, String(bytes, Charsets.UTF_8))
            }
        })
    }

    // =================================================================
    // SDP 與 ICE 收集（non-trickle）
    // =================================================================

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private fun constraints(iceRestart: Boolean = false) = MediaConstraints().apply {
        if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
    }

    /** 產生本機描述，等 ICE 候選收集完（或 6 秒）後回呼完整 SDP */
    private fun createLocal(s: Session, offer: Boolean, iceRestart: Boolean = false, done: (SessionDescription) -> Unit, fail: (String) -> Unit) {
        val obs = object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) { main.post { fail("無法建立連線描述") }; return }
                s.pc.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        main.post {
                            s.onGathered = done
                            if (s.pc.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) finishGathering(s)
                            else Runnable { finishGathering(s) }.also { s.gatherTimer = it; main.postDelayed(it, 6_000) }
                        }
                    }
                    override fun onSetFailure(e: String?) { main.post { fail(e ?: "設定失敗") } }
                }, d)
            }
            override fun onCreateFailure(e: String?) { main.post { fail(e ?: "建立失敗") } }
        }
        if (offer) s.pc.createOffer(obs, constraints(iceRestart)) else s.pc.createAnswer(obs, constraints())
    }

    private fun finishGathering(s: Session) {
        s.gatherTimer?.let { main.removeCallbacks(it) }; s.gatherTimer = null
        val cb = s.onGathered ?: return
        s.onGathered = null
        s.pc.localDescription?.let(cb)
    }

    private fun setRemote(s: Session, type: SessionDescription.Type, sdp: String, ok: () -> Unit, fail: (String) -> Unit) {
        s.pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() { main.post { ok() } }
            override fun onSetFailure(e: String?) { main.post { fail(e ?: "對方連線描述無效") } }
        }, SessionDescription(type, sdp))
    }

    // =================================================================
    // 邀請碼（手動信令）
    // =================================================================

    private const val PREFIX_INVITE = "CETF-INV1:"
    private const val PREFIX_REPLY = "CETF-ANS1:"

    private fun pack(prefix: String, o: JSONObject): String {
        val raw = o.toString().toByteArray(Charsets.UTF_8)
        val d = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(raw); finish() }
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return prefix + ChatCrypto.b64(out.toByteArray())
    }

    private fun unpack(prefix: String, code: String): JSONObject? = runCatching {
        val body = code.trim().substringAfter(prefix, "").ifBlank { return null }
        val inf = Inflater().apply { setInput(ChatCrypto.unb64(body)) }
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
        inf.end()
        JSONObject(String(out.toByteArray(), Charsets.UTF_8))
    }.getOrNull()

    fun isInvite(code: String) = code.trim().startsWith(PREFIX_INVITE)
    fun isReply(code: String) = code.trim().startsWith(PREFIX_REPLY)

    /** 邀請者：產生邀請碼（10 分鐘內有效） */
    fun createInvite(done: (String) -> Unit, fail: (String) -> Unit) {
        val sid = UUID.randomUUID().toString().take(12)
        val s = newSession(sid, offerer = true) ?: return fail("WebRTC 尚未啟動")
        states[sid] = "等待對方回覆碼"
        createLocal(s, offer = true, done = { d ->
            done(pack(PREFIX_INVITE, JSONObject().put("sid", sid).put("g", ChatPrefs.groupId)
                .put("u", ChatPrefs.userId).put("n", ChatPrefs.userName).put("sdp", d.description)
                .put("exp", System.currentTimeMillis() + 10 * 60_000)))
        }, fail = { e -> fail("產生邀請失敗：$e"); close(s) })
    }

    /** 被邀請者：貼上或掃描邀請碼 → 產生回覆碼 */
    fun acceptInvite(code: String, done: (reply: String, groupId: String, inviterName: String) -> Unit, fail: (String) -> Unit) {
        val o = unpack(PREFIX_INVITE, code) ?: return fail("邀請碼格式不正確")
        if (o.optLong("exp") < System.currentTimeMillis()) return fail("邀請碼已過期（10 分鐘），請對方重新產生")
        val g = o.optString("g")
        if (g != ChatPrefs.groupId) ChatPrefs.updateGroupId(g)   // 加入對方的群組
        val sid = o.optString("sid")
        val s = newSession(sid, offerer = false) ?: return fail("WebRTC 尚未啟動")
        s.remoteUser = o.optString("u")
        states[sid] = "等待連線…"
        setRemote(s, SessionDescription.Type.OFFER, o.optString("sdp"), ok = {
            createLocal(s, offer = false, done = { d ->
                done(pack(PREFIX_REPLY, JSONObject().put("sid", sid).put("u", ChatPrefs.userId).put("n", ChatPrefs.userName)
                    .put("sdp", d.description)), g, o.optString("n"))
            }, fail = { e -> fail("產生回覆碼失敗：$e"); close(s) })
        }, fail = { e -> fail(e); close(s) })
    }

    /** 邀請者：貼上或掃描對方的回覆碼 → 開始連線 */
    fun completeInvite(code: String, done: (String) -> Unit, fail: (String) -> Unit) {
        val o = unpack(PREFIX_REPLY, code) ?: return fail("回覆碼格式不正確")
        val s = sessions[o.optString("sid")] ?: return fail("找不到對應的邀請（可能已過期或 App 重開過），請重新產生邀請碼")
        s.remoteUser = o.optString("u")
        states[s.sid] = "連線中…"
        setRemote(s, SessionDescription.Type.ANSWER, o.optString("sdp"),
            ok = { done(o.optString("n")) }, fail = { e -> fail(e) })
    }

    // =================================================================
    // 成員轉送信令：自動補成網狀、ICE Restart
    // =================================================================

    /** 收到已連線成員的名單：對尚未直連的成員發起連線（代號小的一方發起） */
    fun meshWith(userIds: List<String>, via: String) {
        if (factory == null || !ChatPrefs.useInternet) return
        userIds.filter { it.isNotBlank() && it != ChatPrefs.userId && ChatPrefs.userId < it }
            .filter { u -> !ChatEngine.hasRouteTo(u) && sessions.values.none { it.remoteUser == u && (it.open || System.currentTimeMillis() - it.created < 30_000) } }
            .forEach { u -> relayOffer(u, iceRestart = false, existing = null) }
    }

    private fun relayOffer(user: String, iceRestart: Boolean, existing: Session?) {
        val s = existing ?: newSession(UUID.randomUUID().toString().take(12), offerer = true)?.also { it.remoteUser = user } ?: return
        if (iceRestart) s.pc.restartIce()
        createLocal(s, offer = true, iceRestart = iceRestart, done = { d ->
            ChatEngine.relaySignal(user, JSONObject().put("t", if (iceRestart) "restart" else "offer").put("sid", s.sid + if (iceRestart) ":" + System.currentTimeMillis() else "")
                .put("sess", s.sid).put("sdp", d.description).put("n", ChatPrefs.userName))
        }, fail = {})
    }

    /** ChatEngine 轉來給我的信令 */
    fun onSignal(o: JSONObject, viaLink: String) {
        if (factory == null) return
        val from = o.optString("from")
        val sess = o.optString("sess").ifBlank { o.optString("sid") }
        when (o.optString("t")) {
            "offer", "restart" -> {
                val restart = o.optString("t") == "restart"
                val s = (if (restart) sessions[sess] else null) ?: newSession(sess, offerer = false)?.also { it.remoteUser = from } ?: return
                setRemote(s, SessionDescription.Type.OFFER, o.optString("sdp"), ok = {
                    createLocal(s, offer = false, done = { d ->
                        ChatEngine.relaySignal(from, JSONObject().put("t", "answer").put("sid", s.sid + ":a:" + System.currentTimeMillis())
                            .put("sess", s.sid).put("sdp", d.description))
                    }, fail = {})
                }, fail = {})
            }
            "answer" -> sessions[sess]?.let { s -> setRemote(s, SessionDescription.Type.ANSWER, o.optString("sdp"), ok = {}, fail = {}) }
        }
    }

    private fun onIceState(s: Session, st: PeerConnection.IceConnectionState?) {
        states[s.sid] = when (st) {
            PeerConnection.IceConnectionState.CHECKING -> "打洞中…"
            PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> "已連線"
            PeerConnection.IceConnectionState.DISCONNECTED -> "連線不穩，嘗試恢復…"
            PeerConnection.IceConnectionState.FAILED -> "連線失敗（可能雙方 NAT 無法打洞）"
            PeerConnection.IceConnectionState.CLOSED -> "已關閉"
            else -> states[s.sid] ?: ""
        }
        // 斷線或失敗：發起方經其他成員轉送 ICE Restart
        if ((st == PeerConnection.IceConnectionState.FAILED || st == PeerConnection.IceConnectionState.DISCONNECTED) && s.offerer) {
            val u = s.remoteUser ?: return
            main.postDelayed({
                val now = s.pc.iceConnectionState()
                if (now == PeerConnection.IceConnectionState.DISCONNECTED || now == PeerConnection.IceConnectionState.FAILED) {
                    if (ChatEngine.links.values.any { it.userId != u }) relayOffer(u, iceRestart = true, existing = s)
                }
            }, 3_000)
        }
    }

    private fun close(s: Session) {
        runCatching { s.dc?.close(); s.pc.close() }
        sessions.remove(s.sid); states.remove(s.sid)
    }

    // =================================================================
    // 傳送
    // =================================================================

    override fun send(link: String, json: String) {
        val s = sessions[link.removePrefix("w:")] ?: return
        val dc = s.dc ?: return
        if (dc.state() != DataChannel.State.OPEN) return
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false))
    }

    /** 緩衝超過 1 MB 先等，避免大檔案塞爆 DataChannel */
    override fun awaitWritable(link: String) {
        val dc = sessions[link.removePrefix("w:")]?.dc ?: return
        var waited = 0
        while (dc.bufferedAmount() > 1_000_000 && waited < 30_000) { Thread.sleep(20); waited += 20 }
    }

    override fun close(link: String) { sessions[link.removePrefix("w:")]?.let { close(it) } }

    // =================================================================
    // 網路切換（Wi-Fi ↔ 行動網路）→ ICE Restart
    // =================================================================

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = appCtx.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.postDelayed({
                    sessions.values.filter { it.offerer && it.remoteUser != null && it.pc.iceConnectionState() != PeerConnection.IceConnectionState.CONNECTED }
                        .forEach { s -> relayOffer(s.remoteUser!!, iceRestart = true, existing = s) }
                }, 2_000)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        runCatching { appCtx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        netCallback = null
    }
}
