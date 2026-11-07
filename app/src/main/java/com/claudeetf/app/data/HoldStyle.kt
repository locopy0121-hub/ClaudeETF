package com.claudeetf.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 持股卡片顯示格式：與自選行情（WatchStyle）完全獨立，
 * 包含進場標籤、動畫、現價方格等持股專屬設定。
 */
data class HoldStyle(
    /** 0 精簡、1 標準、2 寬鬆（列高） */
    val density: Int = 1,
    /** 0 名稱＋代號、1 代號＋名稱、2 只顯示名稱、3 只顯示代號 */
    val nameMode: Int = 0,
    /** 名稱字級：0 小、1 標準、2 大 */
    val nameSize: Int = 1,
    /** 左側資訊項目順序與隱藏 */
    val items: List<String> = DEFAULT,
    val hidden: Set<String> = HIDDEN,
    /** 右側：市值、損益金額、報酬率膠囊 */
    val showMv: Boolean = true,
    val showPl: Boolean = true,
    val showPct: Boolean = true,
    /** 進場標籤動畫：0 關閉、1 柔和、2 強烈 */
    val entryAnim: Int = 2,
    /** 持有建議文字過長時跑馬燈 */
    val marquee: Boolean = true,
    /** 各列之間的分隔線 */
    val divider: Boolean = true,
) {
    fun shows(id: String) = id !in hidden

    fun toJson(): String = JSONObject()
        .put("v", 1).put("density", density).put("nameMode", nameMode).put("nameSize", nameSize)
        .put("items", JSONArray(items)).put("hidden", JSONArray(hidden.toList()))
        .put("showMv", showMv).put("showPl", showPl).put("showPct", showPct)
        .put("entryAnim", entryAnim).put("marquee", marquee).put("divider", divider)
        .toString()

    companion object {
        val NAMES = linkedMapOf(
            "shares" to "股數・均價",
            "today" to "今日損益",
            "entry" to "進場建議標籤",
            "tip" to "持有建議文字",
            "cost" to "總成本",
            "weight" to "占持股比例",
            "market" to "市場標籤（TW／US）",
        )
        val DEFAULT = NAMES.keys.toList()
        val HIDDEN = setOf("cost", "weight", "market")

        fun fromJson(s: String?): HoldStyle {
            if (s.isNullOrBlank()) return HoldStyle()
            return runCatching {
                val o = JSONObject(s)
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }
                val l = list("items")?.filter { it in NAMES }.orEmpty()
                HoldStyle(
                    o.optInt("density", 1), o.optInt("nameMode", 0), o.optInt("nameSize", 1),
                    l + DEFAULT.filter { it !in l },
                    list("hidden")?.toSet() ?: HIDDEN,
                    o.optBoolean("showMv", true), o.optBoolean("showPl", true), o.optBoolean("showPct", true),
                    o.optInt("entryAnim", 2), o.optBoolean("marquee", true), o.optBoolean("divider", true),
                )
            }.getOrDefault(HoldStyle())
        }
    }
}
