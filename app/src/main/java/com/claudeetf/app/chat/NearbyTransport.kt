package com.claudeetf.app.chat

import android.content.Context
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

/**
 * 附近直連傳輸：Google Nearby Connections（P2P_CLUSTER），藍牙／BLE／Wi-Fi，不需網路。
 * 同一個群聊 ID 的手機互相廣播與搜尋、自動連線；封包一律是 JSON 字串（BYTES payload ≤ 32 KB）。
 */
object NearbyTransport : ChatEngine.ChatTransport {
    private const val SERVICE_ID = "com.claudeetf.app.chat"
    override val kind = "nearby"
    private var client: ConnectionsClient? = null
    private val pendingNames = HashMap<String, Triple<String, String, String>>()
    private val connected = HashSet<String>()

    private fun link(endpointId: String) = "n:$endpointId"

    /** 附近的群組（無伺服器的群組清單）：附近正在使用群聊的成員會廣播所在群組 */
    data class NearbyGroup(val id: String, val name: String, val locked: Boolean, val members: Set<String>, val seen: Long)
    val directory = androidx.compose.runtime.mutableStateMapOf<String, NearbyGroup>()
    var browsing by androidx.compose.runtime.mutableStateOf(false)
        private set
    private val endpointInfo = HashMap<String, Pair<String, String>>()   // endpointId → (群組, 使用者)
    private var advertising = false
    private var discovering = false

    private fun clientFor(ctx: Context): ConnectionsClient = client ?: Nearby.getConnectionsClient(ctx).also { client = it }

    /** 只搜尋（瀏覽附近群組），不廣播自己 */
    fun browse(ctx: Context) {
        val c = clientFor(ctx)
        if (discovering) return
        discovering = true; browsing = true
        c.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { e -> discovering = false; browsing = false; ChatEngine.status = "附近搜尋失敗：" + (e.message ?: "請確認藍牙、定位與附近裝置權限") }
    }

    fun start(ctx: Context) {
        ChatEngine.register("n", this)
        val c = clientFor(ctx)
        if (!advertising) {
            advertising = true
            c.startAdvertising(endpointName(), SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener { e -> advertising = false; ChatEngine.status = "附近廣播失敗：" + (e.message ?: "") }
        }
        browse(ctx)
    }

    fun stop() {
        client?.let { it.stopAdvertising(); it.stopDiscovery(); it.stopAllEndpoints() }
        advertising = false; discovering = false; browsing = false
        connected.clear(); endpointInfo.clear(); directory.clear()
    }

    /**
     * 廣播名稱：C2|群組ID|群組名稱|是否有密碼|是否公開|使用者代號|名稱
     * 不公開的群組不帶名稱（清單不顯示），同群組成員仍能互連。
     */
    private fun endpointName(): String {
        val pub = ChatPrefs.groupPublic
        return listOf("C2", ChatPrefs.groupId, if (pub) ChatPrefs.groupName.take(12) else "-",
            if (ChatPrefs.groupPassword.isNotEmpty()) "1" else "0", if (pub) "1" else "0",
            ChatPrefs.userId, ChatPrefs.userName.take(12)).joinToString("|")
    }

    private data class Ad(val group: String, val groupName: String, val locked: Boolean, val public: Boolean, val uid: String, val name: String)

    private fun parseAd(n: String): Ad? {
        val p = n.split("|")
        return when {
            p.size >= 7 && p[0] == "C2" -> Ad(p[1], p[2], p[3] == "1", p[4] == "1", p[5], p.drop(6).joinToString("|"))
            p.size >= 3 -> Ad(p[0], p[0], false, true, p[1], p.drop(2).joinToString("|"))   // 舊版格式
            else -> null
        }
    }

    private fun parseName(n: String): Triple<String, String, String>? = parseAd(n)?.let { Triple(it.group, it.uid, it.name) }

    private fun noteGroup(endpointId: String, ad: Ad) {
        endpointInfo[endpointId] = ad.group to ad.uid
        if (!ad.public) return
        val old = directory[ad.group]
        directory[ad.group] = NearbyGroup(ad.group, ad.groupName.ifBlank { old?.name ?: ad.group }, ad.locked,
            (old?.members ?: emptySet()) + ad.uid, System.currentTimeMillis())
    }

    private fun dropEndpoint(endpointId: String) {
        val (g, uid) = endpointInfo.remove(endpointId) ?: return
        if (endpointInfo.values.any { it.first == g && it.second == uid }) return
        directory[g]?.let { grp ->
            val left = grp.members - uid
            if (left.isEmpty()) directory.remove(g) else directory[g] = grp.copy(members = left)
        }
    }

    override fun send(link: String, json: String) {
        val id = link.removePrefix("n:")
        if (id !in connected) return
        client?.sendPayload(id, Payload.fromBytes(json.toByteArray(Charsets.UTF_8)))
    }

    override fun awaitWritable(link: String) { Thread.sleep(8) }

    override fun close(link: String) { client?.disconnectFromEndpoint(link.removePrefix("n:")) }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val ad = parseAd(info.endpointName) ?: return
            noteGroup(endpointId, ad)
            val g = ad.group; val uid = ad.uid
            if (!advertising || !ChatEngine.running) return
            if (g != ChatPrefs.groupId || uid == ChatPrefs.userId || endpointId in connected) return
            // 已經透過網路連上同一人就不重複連；代號小的一方發起，避免雙方同時連線衝突
            if (ChatPrefs.userId < uid) client?.requestConnection(endpointName(), endpointId, lifecycle)
        }

        override fun onEndpointLost(endpointId: String) { dropEndpoint(endpointId) }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val parsed = parseName(info.endpointName)
            if (parsed == null || parsed.first != ChatPrefs.groupId) { client?.rejectConnection(endpointId); return }
            pendingNames[endpointId] = parsed
            client?.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingNames.remove(endpointId) ?: return
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connected += endpointId
                ChatEngine.onLinkUp(link(endpointId))
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected -= endpointId
            ChatEngine.onLinkDown(link(endpointId))
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) payload.asBytes()?.let { ChatEngine.onPacket(link(endpointId), String(it, Charsets.UTF_8)) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}
