package com.claudeetf.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 行情牆單一項目的設定。
 * head：放在標題列（否則在內容區）；inline：與上一個顯示中的項目同一行；
 * align：0 靠左、1 置中、2 靠右；color：0 預設、1 依漲跌、2 主文字、3 次要灰、4 金色、5 主色；
 * label：顯示前綴文字（例如「市值」）。
 */
data class WallItem(
    val id: String,
    val show: Boolean,
    val head: Boolean = false,
    val inline: Boolean = false,
    val align: Int = 0,
    val size: Int = 12,
    val bold: Boolean = false,
    val color: Int = 0,
    val mono: Boolean = false,
    val label: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("s", show).put("h", head).put("i", inline).put("a", align)
        .put("z", size).put("b", bold).put("c", color).put("m", mono).put("l", label)

    companion object {
        fun fromJson(o: JSONObject, d: WallItem) = WallItem(
            d.id, o.optBoolean("s", d.show), o.optBoolean("h", d.head), o.optBoolean("i", d.inline), o.optInt("a", d.align).coerceIn(0, 2),
            o.optInt("z", d.size).coerceIn(8, 60), o.optBoolean("b", d.bold), o.optInt("c", d.color).coerceIn(0, 5), o.optBoolean("m", d.mono), o.optBoolean("l", d.label),
        )
    }
}

/**
 * 持股「行情牆」樣式：版面（格數、內距、間距、行距、字高、標題列、外框）＋可自由排列的項目。
 * 內距、行距可設為負值（更緊湊）；字高＝行高倍率。
 */
data class WallStyle(
    val cols: Int = 2,
    /** 格內內距（dp，可負，實際＝10 + 此值，最小 0） */
    val pad: Int = 0,
    /** 格與格之間距（dp） */
    val gap: Int = 6,
    /** 格內各行間距（dp，可負） */
    val lineGap: Int = 0,
    /** 字高：行高倍率（%） */
    val lineHeight: Int = 100,
    /** 標題列底色 */
    val header: Boolean = true,
    /** 外框依漲跌上色 */
    val border: Boolean = true,
    /** 項目（依順序排列） */
    val items: List<WallItem> = DEFAULT_ITEMS,
) {
    fun item(id: String) = items.firstOrNull { it.id == id } ?: DEFAULT_ITEMS.first { it.id == id }
    fun withItem(it2: WallItem) = copy(items = items.map { if (it.id == it2.id) it2 else it })

    fun toJson(): String = JSONObject().put("v", 2).put("c", cols).put("p", pad).put("g", gap).put("lg", lineGap).put("lh", lineHeight)
        .put("h", header).put("b", border).put("items", JSONArray().apply { items.forEach { put(it.toJson()) } }).toString()

    companion object {
        /** 項目代號 → 名稱 */
        val NAMES = linkedMapOf(
            "name" to "名稱", "code" to "代號", "price" to "現價", "arrow" to "漲跌箭頭", "chg" to "漲跌", "pct" to "漲跌幅",
            "entry" to "進場建議", "band" to "光譜位階", "mv" to "持股市值", "day" to "今日損益", "ret" to "報酬率",
            "pl" to "未實現損益", "shares" to "股數", "avg" to "均價", "cost" to "持有成本", "weight" to "持股占比", "vol" to "成交量",
        )

        val DEFAULT_ITEMS = listOf(
            WallItem("name", true, head = true, size = 15),
            WallItem("code", true, head = true, inline = true, align = 2, size = 12),
            WallItem("price", true, align = 1, size = 34, bold = true, mono = true),
            WallItem("arrow", true, size = 13),
            WallItem("chg", true, inline = true, align = 2, size = 16, mono = true),
            WallItem("pct", true, inline = true, align = 2, size = 16, mono = true),
            WallItem("entry", false, align = 1, size = 12, bold = true),
            WallItem("band", false, inline = true, align = 2, size = 12),
            WallItem("mv", false, align = 2, size = 11),
            WallItem("day", false, align = 2, size = 11),
            WallItem("ret", false, inline = true, align = 2, size = 11),
            WallItem("pl", false, align = 2, size = 11),
            WallItem("shares", false, align = 0, size = 11),
            WallItem("avg", false, inline = true, align = 2, size = 11),
            WallItem("cost", false, align = 2, size = 11),
            WallItem("weight", false, align = 2, size = 11),
            WallItem("vol", false, align = 2, size = 11),
        )

        /** 預設組合 */
        fun preset(kind: Int): WallStyle = when (kind) {
            // 精簡：名稱＋現價＋漲跌幅
            1 -> WallStyle(cols = 3, pad = -4, lineGap = -2, items = DEFAULT_ITEMS.map {
                when (it.id) {
                    "code", "arrow", "chg" -> it.copy(show = false)
                    "price" -> it.copy(size = 24)
                    "pct" -> it.copy(inline = false, align = 1, size = 13)
                    "name" -> it.copy(size = 13)
                    else -> it
                }
            })
            // 完整資訊：加上進場建議、光譜、市值、今日損益、報酬率
            2 -> WallStyle(items = DEFAULT_ITEMS.map {
                when (it.id) {
                    "entry", "band", "mv", "day", "ret" -> it.copy(show = true)
                    "price" -> it.copy(size = 28)
                    else -> it
                }
            })
            // 大字看盤：單欄、超大現價
            3 -> WallStyle(cols = 1, items = DEFAULT_ITEMS.map {
                when (it.id) {
                    "price" -> it.copy(size = 52)
                    "chg", "pct" -> it.copy(size = 22)
                    "name" -> it.copy(size = 18)
                    else -> it
                }
            })
            else -> WallStyle()
        }

        fun fromJson(s: String?): WallStyle = runCatching {
            val o = JSONObject(s ?: return WallStyle())
            val d = WallStyle()
            val base = WallStyle(
                o.optInt("c", d.cols).coerceIn(1, 4), o.optInt("p", d.pad).coerceIn(-10, 24), o.optInt("g", d.gap).coerceIn(0, 16),
                o.optInt("lg", d.lineGap).coerceIn(-12, 16), o.optInt("lh", d.lineHeight).coerceIn(70, 170),
                o.optBoolean("h", d.header), o.optBoolean("b", d.border),
            )
            val arr = o.optJSONArray("items")
            if (arr == null) {
                // v1：各項目的開關與字級轉成項目設定
                fun dd(id: String) = DEFAULT_ITEMS.first { it.id == id }
                val extra = o.optInt("x", 0); val xs = o.optInt("xs", 11)
                val extraId = listOf("", "mv", "day", "ret", "shares").getOrElse(extra) { "" }
                base.copy(items = DEFAULT_ITEMS.map { i ->
                    when (i.id) {
                        "name" -> i.copy(size = o.optInt("ns", 15))
                        "code" -> i.copy(show = o.optBoolean("sc", true), size = o.optInt("cs", 12))
                        "price" -> i.copy(size = o.optInt("ps", 34), bold = o.optBoolean("pb", true), align = if (o.optInt("pa", 0) == 1) 2 else 1,
                            color = if (o.optInt("pcol", 0) == 1) 2 else 0)
                        "arrow" -> i.copy(show = o.optBoolean("sa", true))
                        "chg" -> i.copy(show = o.optBoolean("sch", true), size = o.optInt("chs", 16))
                        "pct" -> i.copy(show = o.optBoolean("spc", true), size = o.optInt("chs", 16))
                        extraId -> i.copy(show = true, inline = false, size = xs)
                        else -> i
                    }
                }.also { dd("name") })
            } else {
                val got = (0 until arr.length()).mapNotNull { k -> arr.optJSONObject(k) }.mapNotNull { j ->
                    DEFAULT_ITEMS.firstOrNull { it.id == j.optString("id") }?.let { WallItem.fromJson(j, it) }
                }
                // 新增的項目補在最後
                base.copy(items = got + DEFAULT_ITEMS.filter { di -> got.none { it.id == di.id } })
            }
        }.getOrDefault(WallStyle())
    }
}
