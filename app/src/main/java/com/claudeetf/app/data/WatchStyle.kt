package com.claudeetf.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自選個股顯示格式：卡片與清單分開設定項目與順序。
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
    /** 卡片附加項目（標籤、三關、成交量…）對齊：0 靠左、1 置中、2 靠右 */
    val tagAlign: Int = 0,
    /** 卡片項目順序與隱藏 */
    val items: List<String> = CARD_DEFAULT,
    val hidden: Set<String> = CARD_HIDDEN,
    /** 清單項目順序與隱藏 */
    val listItems: List<String> = LIST_DEFAULT,
    val listHidden: Set<String> = LIST_HIDDEN,
) {
    fun shows(id: String) = id !in hidden
    fun listShows(id: String) = id !in listHidden

    fun toJson(): String = JSONObject()
        .put("v", 2)
        .put("density", density).put("nameMode", nameMode).put("nameLines", nameLines).put("priceAlign", priceAlign)
        .put("changeMode", changeMode).put("changeStyle", changeStyle).put("cardCols", cardCols).put("tintBg", tintBg)
        .put("tagPos", tagPos).put("tagAlign", tagAlign)
        .put("items", JSONArray(items)).put("hidden", JSONArray(hidden.toList()))
        .put("listItems", JSONArray(listItems)).put("listHidden", JSONArray(listHidden.toList()))
        .toString()

    companion object {
        /** 卡片可用項目 */
        val CARD_NAMES = linkedMapOf(
            "spark" to "走勢圖",
            "change" to "漲跌",
            "entry" to "進場建議標籤",
            "keys" to "三關位置",
            "cols" to "欄位／第二欄資訊",
            "limit" to "漲停／跌停",
            "market" to "市場標籤（TW／US）",
            "source" to "報價來源與時間",
            "volume" to "成交量",
            "amp" to "振幅",
            "ext" to "盤前／盤後價（美股）",
        )
        /** 清單可用項目（價格與漲跌固定在右側） */
        val LIST_NAMES = linkedMapOf(
            "market" to "市場標籤與代號",
            "source" to "報價來源與時間",
            "entry" to "進場建議標籤",
            "keys" to "三關位置",
            "volume" to "成交量",
            "amp" to "振幅",
            "limit" to "漲停／跌停",
            "ext" to "盤前／盤後價（美股）",
            "spark" to "迷你走勢圖",
        )
        val CARD_DEFAULT = CARD_NAMES.keys.toList()
        val CARD_HIDDEN = setOf("market", "volume", "amp")
        val LIST_DEFAULT = LIST_NAMES.keys.toList()
        val LIST_HIDDEN = setOf("volume", "amp", "spark")

        fun fromJson(s: String?): WatchStyle {
            if (s.isNullOrBlank()) return WatchStyle()
            return runCatching {
                val o = JSONObject(s)
                fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }
                val v2 = o.optInt("v", 1) >= 2
                fun order(k: String, names: Map<String, String>, def: List<String>): List<String> {
                    if (!v2) return def
                    val l = list(k)?.filter { it in names }.orEmpty()
                    return l + def.filter { it !in l }
                }
                WatchStyle(
                    o.optInt("density", 1), o.optInt("nameMode", 0), o.optInt("nameLines", 1), o.optInt("priceAlign", 1),
                    o.optInt("changeMode", 0), o.optInt("changeStyle", 0), o.optInt("cardCols", 2), o.optBoolean("tintBg", false),
                    o.optInt("tagPos", 0), o.optInt("tagAlign", 0),
                    order("items", CARD_NAMES, CARD_DEFAULT),
                    if (v2) list("hidden")?.toSet() ?: CARD_HIDDEN else CARD_HIDDEN,
                    order("listItems", LIST_NAMES, LIST_DEFAULT),
                    if (v2) list("listHidden")?.toSet() ?: LIST_HIDDEN else LIST_HIDDEN,
                )
            }.getOrDefault(WatchStyle())
        }
    }
}
