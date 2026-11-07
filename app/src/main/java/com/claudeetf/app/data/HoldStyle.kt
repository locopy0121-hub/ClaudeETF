package com.claudeetf.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 持股卡片顯示格式（v2：自由版面）：與自選行情（WatchStyle）完全獨立。
 * 卡片分左／中／右三欄，每個項目可指定所在欄、對齊、是否與上一項同一行，
 * 各欄寬度可設自動或百分比；每個項目另有字級、行距、內距、粗細、顏色、動畫。
 */
data class HoldStyle(
    /** 0 精簡、1 標準、2 寬鬆（列高） */
    val density: Int = 1,
    /** 項目順序（全部項目） */
    val items: List<String> = DEFAULT,
    val hidden: Set<String> = HIDDEN,
    /** 項目所在欄：0 左、1 中、2 右 */
    val zone: Map<String, Int> = DEFAULT_ZONE,
    /** 項目對齊：0 靠左、1 置中、2 靠右（同一行以第一個項目為準） */
    val align: Map<String, Int> = emptyMap(),
    /** 與上一個項目排在同一行 */
    val inline: Set<String> = DEFAULT_INLINE,
    /** 欄寬：左、中、右（%；0 = 自動依內容） */
    val colW: List<Int> = listOf(0, 0, 0),
    /** 進場標籤動畫：0 關閉、1 柔和、2 強烈 */
    val entryAnim: Int = 2,
    /** 持有建議文字過長時跑馬燈 */
    val marquee: Boolean = true,
    /** 各列之間的分隔線 */
    val divider: Boolean = true,
    /** 單一欄位樣式（字級、行距、內距、粗細、顏色、動畫） */
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
    fun zoneOf(id: String) = zone[id] ?: DEFAULT_ZONE[id] ?: 0
    /** 預設對齊：左欄靠左、中欄置中、右欄靠右 */
    fun alignOf(id: String) = align[id] ?: when (zoneOf(id)) { 1 -> 1; 2 -> 2; else -> 0 }

    fun toJson(): String = JSONObject()
        .put("v", 2).put("density", density)
        .put("items", JSONArray(items)).put("hidden", JSONArray(hidden.toList()))
        .put("zone", JSONObject(zone as Map<*, *>)).put("align", JSONObject(align as Map<*, *>))
        .put("inline", JSONArray(inline.toList())).put("colW", JSONArray(colW))
        .put("entryAnim", entryAnim).put("marquee", marquee).put("divider", divider)
        .put("rowBg", rowBg).put("rowBorder", rowBorder).put("radius", radius).put("gapDp", gapDp)
        .put("itemStyles", JSONObject().apply { itemStyles.filterValues { !it.isDefault }.forEach { (k, v) -> put(k, v.toJson()) } })
        .toString()

    companion object {
        val NAMES = linkedMapOf(
            "market" to "市場標籤（TW／US）",
            "code" to "代號",
            "name" to "名稱",
            "today" to "今日損益",
            "entry" to "進場建議標籤",
            "tip" to "持有建議文字",
            "shares" to "股數・均價",
            "cost" to "總成本",
            "weight" to "占持股比例",
            "price" to "現價方格",
            "mv" to "市值",
            "pl" to "損益金額",
            "pct" to "報酬率",
        )
        val DEFAULT = NAMES.keys.toList()
        val HIDDEN = setOf("cost", "weight")
        val DEFAULT_ZONE = mapOf("price" to 2, "mv" to 2, "pl" to 2, "pct" to 2)
        val DEFAULT_INLINE = setOf("code", "name", "tip", "pct")
        val ZONE_NAMES = listOf("左欄", "中欄", "右欄")
        /** 不能整塊改色的項目（本身有底色） */
        val NO_RECOLOR = setOf("entry", "market", "pct", "price")

        fun fromJson(s: String?): HoldStyle {
            if (s.isNullOrBlank()) return HoldStyle()
            return runCatching {
                val o = JSONObject(s)
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }
                fun intMap(k: String) = o.optJSONObject(k)?.let { m -> m.keys().asSequence().associateWith { m.optInt(it) } } ?: emptyMap()
                val styles = o.optJSONObject("itemStyles")?.let { m -> m.keys().asSequence().associateWith { ItemStyle.fromJson(m.optJSONObject(it)) } } ?: emptyMap()
                if (o.optInt("v", 1) >= 2) {
                    val l = list("items")?.filter { it in NAMES }.orEmpty()
                    HoldStyle(
                        o.optInt("density", 1), l + DEFAULT.filter { it !in l }, list("hidden")?.toSet() ?: HIDDEN,
                        intMap("zone"), intMap("align"), list("inline")?.toSet() ?: DEFAULT_INLINE,
                        list("colW")?.map { it.toIntOrNull() ?: 0 }?.let { (it + listOf(0, 0, 0)).take(3) } ?: listOf(0, 0, 0),
                        o.optInt("entryAnim", 2), o.optBoolean("marquee", true), o.optBoolean("divider", true), styles,
                        o.optLong("rowBg", 0L), o.optLong("rowBorder", 0L), o.optInt("radius", 0), o.optInt("gapDp", 0),
                    )
                } else {
                    // v1 → v2：名稱模式拆成代號／名稱兩個項目，右側開關轉成隱藏
                    val nameMode = o.optInt("nameMode", 0)
                    val oldItems = list("items").orEmpty()
                    val oldHidden = list("hidden")?.toSet() ?: setOf("cost", "weight", "market")
                    val left = oldItems.filter { it in NAMES }
                    val head = if (nameMode == 1) listOf("market", "code", "name") else listOf("market", "name", "code")
                    val items = head + left.filter { it !in head } + listOf("price", "mv", "pl", "pct")
                    val hidden = oldHidden.toMutableSet().apply {
                        if (nameMode == 2) add("code"); if (nameMode == 3) add("name")
                        if (!o.optBoolean("showMv", true)) add("mv"); if (!o.optBoolean("showPl", true)) add("pl"); if (!o.optBoolean("showPct", true)) add("pct")
                    }
                    val sized = styles.toMutableMap()
                    when (o.optInt("nameSize", 1)) { 0 -> 93; 2 -> 115; else -> null }?.let { sz -> sized["name"] = (sized["name"] ?: ItemStyle()).copy(size = sz) }
                    HoldStyle(
                        o.optInt("density", 1), items.distinct() + DEFAULT.filter { it !in items }, hidden,
                        DEFAULT_ZONE, emptyMap(), DEFAULT_INLINE + (if (nameMode == 1) setOf("name") else setOf("code")), listOf(0, 0, 0),
                        o.optInt("entryAnim", 2), o.optBoolean("marquee", true), o.optBoolean("divider", true), sized,
                        o.optLong("rowBg", 0L), o.optLong("rowBorder", 0L), o.optInt("radius", 0), o.optInt("gapDp", 0),
                    )
                }
            }.getOrDefault(HoldStyle())
        }
    }
}
