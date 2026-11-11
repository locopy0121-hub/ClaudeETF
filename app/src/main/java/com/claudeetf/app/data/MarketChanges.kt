package com.claudeetf.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.claudeetf.app.data.remote.TwIsin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 台股市場異動紀錄：新上市／即將上市、新增交易、下市（或終止）、更名。
 * 由股票目錄每日更新時比對前後兩份清單產生；開啟 App 時提示未確認的異動，確認一次後不再提醒。
 */
object MarketChanges {
    data class Ev(val at: Long, val date: String, val code: String, val name: String, val kind: String, val detail: String, val otc: Boolean = false) {
        fun toJson(): JSONObject = JSONObject().put("at", at).put("d", date).put("c", code).put("n", name).put("k", kind).put("x", detail).put("o", otc)
        companion object {
            fun of(o: JSONObject) = Ev(o.optLong("at"), o.optString("d"), o.optString("c"), o.optString("n"), o.optString("k"), o.optString("x"), o.optBoolean("o"))
        }
    }

    const val NEW = "新上市"
    const val SOON = "即將上市"
    const val ADDED = "新增交易"
    const val GONE = "下市"
    const val RENAME = "更名"
    val KINDS = listOf(NEW, SOON, ADDED, GONE, RENAME)

    val events = mutableStateListOf<Ev>()
    /** 已確認到這個時間點（之前的異動不再提示） */
    var seenAt by mutableLongStateOf(0L)
        private set
    /** 目錄最後更新時間 */
    var updatedAt by mutableLongStateOf(0L)
    var updating by mutableStateOf(false)
    var lastError by mutableStateOf<String?>(null)

    private var file: File? = null
    private var prefs: android.content.SharedPreferences? = null
    private val TZ: ZoneId = ZoneId.of("Asia/Taipei")
    private val YMD = DateTimeFormatter.ofPattern("yyyy/MM/dd")

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "market_changes.json")
        prefs = ctx.getSharedPreferences("claudeetf", Context.MODE_PRIVATE)
        seenAt = prefs?.getLong("mcSeenAt", 0L) ?: 0L
        runCatching {
            val arr = JSONArray(file!!.readText())
            events.clear()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { events += Ev.of(it) }
        }
    }

    val unseen: List<Ev> get() = events.filter { it.at > seenAt }

    fun markSeen() {
        seenAt = System.currentTimeMillis()
        prefs?.edit()?.putLong("mcSeenAt", seenAt)?.apply()
    }

    private fun save() {
        runCatching { file?.writeText(JSONArray().apply { events.take(600).forEach { put(it.toJson()) } }.toString()) }
    }

    fun today(): LocalDate = LocalDate.now(TZ)

    private fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim(), YMD) }.getOrNull()

    /**
     * 比對更新前後的目錄並記錄異動。
     * oldNames／newNames：每日行情的代號 → 名稱；oldIsin／newIsin：ISIN 一覽表代號（空集合表示該次沒取得，不比對下市）。
     */
    @Synchronized
    fun record(
        oldNames: Map<String, String>, newNames: Map<String, String>,
        oldIsin: Set<String>, newIsin: Map<String, TwIsin.Sec>, otc: Set<String>, isinComplete: Boolean = true,
    ) {
        val now = System.currentTimeMillis()
        val day = today()
        val d = day.toString()
        val add = ArrayList<Ev>()
        fun has(code: String, kind: String) = events.any { it.code == code && it.kind == kind } || add.any { it.code == code && it.kind == kind }
        fun nameOf(c: String) = newNames[c] ?: newIsin[c]?.name ?: oldNames[c] ?: c

        // 1) ISIN 上市日：近 10 天上市 → 新上市；未來 30 天內 → 即將上市
        newIsin.values.forEach { s ->
            val ld = parseDate(s.listDate) ?: return@forEach
            val info = listOf(s.type, s.market, s.industry).filter { it.isNotBlank() }.joinToString(" · ")
            when {
                ld.isAfter(day) && !ld.isAfter(day.plusDays(30)) ->
                    if (!has(s.code, SOON)) add += Ev(now, d, s.code, nameOf(s.code), SOON, "預計 " + s.listDate + " 掛牌 · " + info, s.code in otc || s.market.contains("櫃"))
                !ld.isBefore(day.minusDays(10)) && !ld.isAfter(day) ->
                    if (!has(s.code, NEW)) add += Ev(now, d, s.code, nameOf(s.code), NEW, s.listDate + " 掛牌 · " + info, s.code in otc || s.market.contains("櫃"))
            }
        }
        // 2) 每日行情新出現的代號（之前的目錄與 ISIN 都沒有）
        if (oldNames.isNotEmpty()) {
            val fresh = newNames.keys - oldNames.keys - oldIsin
            if (fresh.size <= 80) fresh.forEach { c ->
                if (!has(c, NEW) && !has(c, ADDED)) add += Ev(now, d, c, nameOf(c), ADDED, "首次出現在每日收盤行情", c in otc)
            }
            // 3) 更名
            val renamed = newNames.filter { (c, n) -> oldNames[c]?.let { o -> o.replace(" ", "") != n.replace(" ", "") } == true }
            if (renamed.size <= 60) renamed.forEach { (c, n) ->
                add += Ev(now, d, c, n, RENAME, oldNames[c] + " → " + n, c in otc)
            }
        }
        // 4) ISIN 一覽表消失 → 下市（兩次都完整取得才比對）
        if (isinComplete && oldIsin.size > 500 && newIsin.size > 500) {
            val gone = oldIsin - newIsin.keys
            if (gone.size <= 60) gone.forEach { c ->
                if (!events.any { it.code == c && it.kind == GONE && it.date == d }) add += Ev(now, d, c, oldNames[c] ?: c, GONE, "已不在上市櫃證券一覽表（下市、終止上市或到期）", c in otc)
            }
        }
        if (add.isNotEmpty()) {
            // 即將上市的代號掛牌後，以「新上市」取代
            val listedNow = add.filter { it.kind == NEW }.map { it.code }.toSet()
            events.removeAll { it.kind == SOON && it.code in listedNow }
            events.addAll(0, add.sortedWith(compareBy({ KINDS.indexOf(it.kind) }, { it.code })))
            save()
        }
    }
}
