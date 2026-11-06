package com.claudeetf.app.chat

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 群聊設定：功能開關、使用者代號（自動產生、可改）、顯示名稱、群聊 ID */
object ChatPrefs {
    private const val FILE = "claudeetf_chat"
    private var ctx: Context? = null

    var enabled by mutableStateOf(false)
        private set
    var userId by mutableStateOf("")
        private set
    var userName by mutableStateOf("")
        private set
    var groupId by mutableStateOf("")
        private set
    /** 群組密碼：所有成員相同，用來衍生訊息加密金鑰（空白＝僅以群聊 ID 衍生） */
    var groupPassword by mutableStateOf("")
        private set
    /** 群組顯示名稱、是否公開在附近群組清單 */
    var groupName by mutableStateOf("")
        private set
    var groupPublic by mutableStateOf(true)
        private set
    /** 加入過的群組：ID → 名稱（快速切換） */
    val joined = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** 連線方式：附近（藍牙／Wi-Fi 直連）、跨網（WebRTC） */
    var useNearby by mutableStateOf(true)
        private set
    var useInternet by mutableStateOf(true)
        private set
    /** 新訊息以彈幕顯示在 App 畫面上 */
    var danmaku by mutableStateOf(true)
        private set

    fun init(c: Context) {
        ctx = c.applicationContext
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        enabled = p.getBoolean("enabled", false)
        userId = p.getString("userId", null) ?: newId().also { p.edit().putString("userId", it).apply() }
        userName = p.getString("userName", null) ?: ("使用者" + userId.takeLast(4))
        groupId = p.getString("groupId", "") ?: ""
        groupPassword = p.getString("groupPassword", "") ?: ""
        useNearby = p.getBoolean("useNearby", true)
        useInternet = p.getBoolean("useInternet", true)
        danmaku = p.getBoolean("danmaku", true)
        groupName = p.getString("groupName", "") ?: ""
        groupPublic = p.getBoolean("groupPublic", true)
        runCatching {
            val o = org.json.JSONObject(p.getString("joined", "{}") ?: "{}")
            o.keys().forEach { k -> joined[k] = o.optString(k) }
        }
    }

    private fun newId(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..8).map { chars.random() }.joinToString("")
    }

    private fun put(k: String, v: Any) {
        val e = ctx?.getSharedPreferences(FILE, Context.MODE_PRIVATE)?.edit() ?: return
        when (v) { is Boolean -> e.putBoolean(k, v); is String -> e.putString(k, v) }
        e.apply()
    }

    fun updateEnabled(on: Boolean) { enabled = on; put("enabled", on) }
    fun updateUserName(n: String) { userName = n.trim().replace("|", "／").take(20); put("userName", userName) }
    /** 使用者代號：英數 4–12 碼 */
    fun updateUserId(id: String): Boolean {
        val v = id.trim().uppercase().filter { it.isLetterOrDigit() }
        if (v.length !in 4..12) return false
        userId = v; put("userId", v); return true
    }
    /** 群聊 ID：同一個 ID 的成員互相連線 */
    fun updateGroupId(g: String) { groupId = g.trim().replace("|", "").take(24); put("groupId", groupId) }

    fun updateGroupPassword(pw: String) { groupPassword = pw; put("groupPassword", pw) }
    fun updateUseNearby(on: Boolean) { useNearby = on; put("useNearby", on) }
    fun updateUseInternet(on: Boolean) { useInternet = on; put("useInternet", on) }
    fun updateDanmaku(on: Boolean) { danmaku = on; put("danmaku", on) }

    private fun saveJoined() = put("joined", org.json.JSONObject(joined.toMap() as Map<*, *>).toString())

    /** 切換到某個群組（建立、從清單加入、邀請碼加入都走這裡） */
    fun enterGroup(id: String, name: String, password: String?, public: Boolean = groupPublic) {
        updateGroupId(id)
        groupName = name.trim().replace("|", "／").take(12).ifBlank { id }; put("groupName", groupName)
        groupPublic = public; put("groupPublic", public)
        if (password != null) updateGroupPassword(password)
        joined[groupId] = groupName; saveJoined()
    }

    fun forgetGroup(id: String) { joined.remove(id); saveJoined() }

    /** 新群組 ID：6 碼英數 */
    fun newGroupId(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..6).map { chars.random() }.joinToString("")
    }
}
