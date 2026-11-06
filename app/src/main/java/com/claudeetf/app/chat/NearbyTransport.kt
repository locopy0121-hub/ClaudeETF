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

    fun start(ctx: Context) {
        ChatEngine.register("n", this)
        val c = Nearby.getConnectionsClient(ctx)
        client = c
        val name = endpointName()
        c.startAdvertising(name, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { e -> ChatEngine.status = "附近廣播失敗：" + (e.message ?: "") }
        c.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnFailureListener { e -> ChatEngine.status = "附近搜尋失敗：" + (e.message ?: "請確認藍牙、定位與附近裝置權限") }
    }

    fun stop() {
        client?.let { it.stopAdvertising(); it.stopDiscovery(); it.stopAllEndpoints() }
        client = null
        connected.clear()
    }

    /** 群組|使用者代號|顯示名稱 */
    private fun endpointName() = ChatPrefs.groupId + "|" + ChatPrefs.userId + "|" + ChatPrefs.userName.take(20)

    private fun parseName(n: String): Triple<String, String, String>? {
        val p = n.split("|")
        return if (p.size >= 3) Triple(p[0], p[1], p.drop(2).joinToString("|")) else null
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
            val (g, uid, _) = parseName(info.endpointName) ?: return
            if (g != ChatPrefs.groupId || uid == ChatPrefs.userId || endpointId in connected) return
            // 已經透過網路連上同一人就不重複連；代號小的一方發起，避免雙方同時連線衝突
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
