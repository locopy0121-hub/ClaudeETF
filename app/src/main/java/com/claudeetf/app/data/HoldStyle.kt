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
    /** 單一欄位樣式（字級、粗細、顏色、動畫），與行情卡片分開保存 */
    val itemStyles: Map<String, ItemStyle> = emptyMap(),
    /** 外觀：背景、邊框色（0 = 無）、圓角、列與列間距（dp） */
    val rowBg: Long = 0L,
    val rowBorder: Long = 0L,
    val radius: Int = 0,
    val gapDp: Int = 0,
) {
    fun shows(id: String) = id !in hidden
    fun style(id: String): ItemStyle = itemStyles[id] ?: ItemStyle()
    fun withStyle(id: String, s: ItemStyle) = copy(itemStyles = if (s.isDefault) itemStyles - id else itemStyles + (id to s))

    fun toJson(): String = JSONObject()
        .put("v", 1).put("density", density).put("nameMode", nameMode).put("nameSize", nameSize)
        .put("items", JSONArray(items)).put("hidden", JSONArray(hidden.toList()))
        .put("showMv", showMv).put("showPl", showPl).put("showPct", showPct)
        .put("entryAnim", entryAnim).put("marquee", marquee).put("divider", divider)
        .put("rowBg", rowBg).put("rowBorder", rowBorder).put("radius", radius).put("gapDp", gapDp)
        .put("itemStyles", JSONObject().apply { itemStyles.filterValues { !it.isDefault }.forEach { (k, v) -> put(k, v.toJson()) } })
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
        /** 可單獨設定樣式的位置（含固定項目：名稱、市值、損益、報酬率） */
        val STYLE_NAMES = linkedMapOf("name" to "名稱與代號") + NAMES + linkedMapOf("mv" to "市值", "pl" to "損益金額", "pct" to "報酬率")

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
                    o.optJSONObject("itemStyles")?.let { m -> m.keys().asSequence().associateWith { ItemStyle.fromJson(m.optJSONObject(it)) } } ?: emptyMap(),
                    o.optLong("rowBg", 0L), o.optLong("rowBorder", 0L), o.optInt("radius", 0), o.optInt("gapDp", 0),
                )
            }.getOrDefault(HoldStyle())
        }
    }
}
