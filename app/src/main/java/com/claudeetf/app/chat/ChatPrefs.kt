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

    fun init(c: Context) {
        ctx = c.applicationContext
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        enabled = p.getBoolean("enabled", false)
        userId = p.getString("userId", null) ?: newId().also { p.edit().putString("userId", it).apply() }
        userName = p.getString("userName", null) ?: ("使用者" + userId.takeLast(4))
        groupId = p.getString("groupId", "") ?: ""
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

    fun setEnabled(on: Boolean) { enabled = on; put("enabled", on) }
    fun setUserName(n: String) { userName = n.trim().replace("|", "／").take(20); put("userName", userName) }
    /** 使用者代號：英數 4–12 碼 */
    fun setUserId(id: String): Boolean {
        val v = id.trim().uppercase().filter { it.isLetterOrDigit() }
        if (v.length !in 4..12) return false
        userId = v; put("userId", v); return true
    }
    /** 群聊 ID：同一個 ID 的成員互相連線 */
    fun setGroupId(g: String) { groupId = g.trim().replace("|", "").take(24); put("groupId", groupId) }
}
