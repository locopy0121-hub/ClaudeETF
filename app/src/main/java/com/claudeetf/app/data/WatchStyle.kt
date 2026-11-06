package com.claudeetf.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自選個股（卡片與清單）顯示格式。
 * items 決定下方附加資訊的「順序」，hidden 決定哪些不顯示。
 */
data class WatchStyle(
    /** 0 精簡、1 標準、2 大字 */
    val density: Int = 1,
    /** 0 名稱＋代號、1 代號＋名稱、2 只顯示名稱、3 只顯示代號 */
    val nameMode: Int = 0,
    /** 名稱最多行數 1／2 */
    val nameLines: Int = 1,
    /** 卡片價格：0 靠左、1 置中、2 靠右 */
    val priceAlign: Int = 1,
    /** 0 金額＋百分比、1 只顯示百分比、2 只顯示金額 */
    val changeMode: Int = 0,
    /** 0 文字、1 色塊 */
    val changeStyle: Int = 0,
    /** 卡片每列 2／3 張 */
    val cardCols: Int = 2,
    /** 卡片底色依漲跌淡淡上色 */
    val tintBg: Boolean = false,
    /** 進場建議標籤：0 依項目順序、1 名稱旁、2 價格旁 */
    val tagPos: Int = 0,
    val items: List<String> = DEFAULT_ITEMS,
    val hidden: Set<String> = setOf("volume", "amp"),
) {
    fun shows(id: String) = id !in hidden

    fun toJson(): String = JSONObject()
        .put("density", density).put("nameMode", nameMode).put("nameLines", nameLines).put("priceAlign", priceAlign)
        .put("changeMode", changeMode).put("changeStyle", changeStyle).put("cardCols", cardCols).put("tintBg", tintBg)
        .put("tagPos", tagPos).put("items", JSONArray(items)).put("hidden", JSONArray(hidden.toList()))
        .toString()

    companion object {
        /** 項目代號 → 名稱 */
        val ITEM_NAMES = linkedMapOf(
            "spark" to "走勢圖（卡片）",
            "keys" to "三關位置",
            "entry" to "進場建議標籤",
            "limit" to "漲停／跌停",
            "market" to "市場標籤（TW／US）",
            "source" to "報價來源與時間",
            "volume" to "成交量",
            "amp" to "振幅",
            "ext" to "盤前／盤後價（美股）",
        )
        val DEFAULT_ITEMS = ITEM_NAMES.keys.toList()

        fun fromJson(s: String?): WatchStyle {
            if (s.isNullOrBlank()) return WatchStyle()
            return runCatching {
                val o = JSONObject(s)
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }
                val items = list("items")?.filter { it in ITEM_NAMES }.orEmpty()
                WatchStyle(
                    o.optInt("density", 1), o.optInt("nameMode", 0), o.optInt("nameLines", 1), o.optInt("priceAlign", 1),
                    o.optInt("changeMode", 0), o.optInt("changeStyle", 0), o.optInt("cardCols", 2), o.optBoolean("tintBg", false),
                    o.optInt("tagPos", 0),
                    // 新增的項目補到最後
                    items + DEFAULT_ITEMS.filter { it !in items },
                    list("hidden")?.toSet() ?: setOf("volume", "amp"),
                )
            }.getOrDefault(WatchStyle())
        }
    }
}
