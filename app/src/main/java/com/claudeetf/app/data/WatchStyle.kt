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
    /** 單一欄位樣式：鍵為 "c:項目"（卡片）或 "l:項目"（清單） */
    val itemStyles: Map<String, ItemStyle> = emptyMap(),
    /** 繪製時套用的縮放（不存檔，由單一欄位樣式帶入） */
    val scale: Float = 1f,
    /** 卡片外觀：背景、邊框色（0 = 預設）、圓角、內距與卡片間距（dp，-1 = 依版型） */
    val cardBg: Long = 0L,
    val cardBorder: Long = 0L,
    val radius: Int = 12,
    val padDp: Int = -1,
    val gapDp: Int = 8,
    /** 名稱後方顯示股價光譜標籤（特價、便宜…極貴） */
    val bandTag: Boolean = true,
) {
    fun style(key: String): ItemStyle = itemStyles[key] ?: ItemStyle()
    /** 套用某欄位的字級縮放 */
    fun at(key: String): WatchStyle = itemStyles[key]?.let { copy(scale = it.size / 100f) } ?: if (scale != 1f) copy(scale = 1f) else this
    fun shows(id: String) = id !in hidden
    fun listShows(id: String) = id !in listHidden

    fun toJson(): String = JSONObject()
        .put("v", 3)
        .put("density", density).put("nameMode", nameMode).put("nameLines", nameLines).put("bandTag", bandTag).put("priceAlign", priceAlign)
        .put("changeMode", changeMode).put("changeStyle", changeStyle).put("cardCols", cardCols).put("tintBg", tintBg)
        .put("tagPos", tagPos).put("tagAlign", tagAlign)
        .put("items", JSONArray(items)).put("hidden", JSONArray(hidden.toList()))
        .put("listItems", JSONArray(listItems)).put("listHidden", JSONArray(listHidden.toList()))
        .put("itemStyles", JSONObject().apply { itemStyles.filterValues { !it.isDefault }.forEach { (k, v) -> put(k, v.toJson()) } })
        .put("cardBg", cardBg).put("cardBorder", cardBorder).put("radius", radius).put("padDp", padDp).put("gapDp", gapDp)
        .toString()

    companion object {
        /** 卡片可用項目 */
        val CARD_NAMES = linkedMapOf(
            "spark" to "走勢圖",
            "change" to "漲跌",
            "entry" to "進場建議標籤",
            "rank" to "盤後報表排行",
            "keys" to "三關位置",
            "cols" to "欄位／第二欄資訊",
            "limit" to "漲停／跌停",
            "market" to "市場標籤（TW／US）",
            "source" to "報價來源與時間",
            "volume" to "成交量",
            "amp" to "振幅",
            "ext" to "盤前／盤後價（美股）",
            "hold" to "我的持股（股數、均價、損益）",
        )
        /** 清單可用項目（價格與漲跌固定在右側） */
        val LIST_NAMES = linkedMapOf(
            "market" to "市場標籤與代號",
            "source" to "報價來源與時間",
            "entry" to "進場建議標籤",
            "rank" to "盤後報表排行",
            "keys" to "三關位置",
            "volume" to "成交量",
            "amp" to "振幅",
            "limit" to "漲停／跌停",
            "ext" to "盤前／盤後價（美股）",
            "spark" to "迷你走勢圖",
            "hold" to "我的持股（股數、均價、損益）",
        )
        val CARD_DEFAULT = CARD_NAMES.keys.toList()
        val CARD_HIDDEN = setOf("market", "volume", "amp")
        val LIST_DEFAULT = LIST_NAMES.keys.toList()
        /** 清單預設只顯示行情頁「欄位／第二欄」選的資訊；其餘需在設定開啟 */
        val LIST_HIDDEN = setOf("keys", "volume", "amp", "limit", "ext", "spark")

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
                    // v3 起清單預設精簡：舊版的清單隱藏設定重設為新預設
                    if (o.optInt("v", 1) >= 3) list("listHidden")?.toSet() ?: LIST_HIDDEN else LIST_HIDDEN,
                    o.optJSONObject("itemStyles")?.let { m -> m.keys().asSequence().associateWith { ItemStyle.fromJson(m.optJSONObject(it)) } } ?: emptyMap(),
                    1f,
                    o.optLong("cardBg", 0L), o.optLong("cardBorder", 0L), o.optInt("radius", 12), o.optInt("padDp", -1), o.optInt("gapDp", 8),
                    o.optBoolean("bandTag", true),
                )
            }.getOrDefault(WatchStyle())
        }
    }
}

/**
 * 單一欄位樣式。
 * size：字級百分比；weight：字重 100–900（0 = 預設）；
 * color：自訂文字色 ARGB（0 = 預設）；trend：改用漲跌色。
 */
data class ItemStyle(
    val size: Int = 100, val weight: Int = 0, val color: Long = 0L, val trend: Boolean = false,
    /** 文字動畫款式（見 ui/TextFx.STYLES，0＝無） */
    val fx: Int = 0,
    /** 觸發：0 常駐、1 數值變動、2 上漲時、3 下跌時 */
    val trig: Int = 1,
    /** 速度：0 慢、1 正常、2 快；強度：0 弱、1 中、2 強 */
    val speed: Int = 1,
    val power: Int = 1,
    /** 行距：項目上下間距（dp），多行資料的行與行間距也一起加大 */
    val gap: Int = 0,
    /** 內距：項目四周留白（dp） */
    val pad: Int = 0,
    /** 背景色 ARGB（0 = 無） */
    val bg: Long = 0L,
    /** 欄寬（dp，0 = 依內容自動） */
    val width: Int = 0,
    /** 對齊（有設欄寬時）：0 靠左、1 置中、2 靠右 */
    val align: Int = 0,
) {
    val isDefault get() = size == 100 && weight == 0 && color == 0L && !trend && fx == 0 && gap == 0 && pad == 0 && bg == 0L && width == 0 && align == 0
    fun toJson(): JSONObject = JSONObject().put("s", size).put("w", weight).put("c", color).put("t", trend)
        .put("fx", fx).put("tg", trig).put("sp", speed).put("pw", power).put("g", gap).put("pd", pad)
        .put("bg", bg).put("wd", width).put("al", align)
    companion object {
        fun fromJson(o: JSONObject?): ItemStyle = if (o == null) ItemStyle() else
            ItemStyle(o.optInt("s", 100).coerceIn(50, 250), o.optInt("w", 0), o.optLong("c", 0L), o.optBoolean("t", false),
                o.optInt("fx", 0).coerceIn(0, 16), o.optInt("tg", 1).coerceIn(0, 3), o.optInt("sp", 1).coerceIn(0, 2), o.optInt("pw", 1).coerceIn(0, 2),
                o.optInt("g", 0).coerceIn(0, 24), o.optInt("pd", 0).coerceIn(0, 24),
                o.optLong("bg", 0L), o.optInt("wd", 0).coerceIn(0, 240), o.optInt("al", 0).coerceIn(0, 2))
    }
}
