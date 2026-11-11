package com.claudeetf.app.data

import org.json.JSONObject

/**
 * 持股「行情牆」樣式：每格一檔，標題列（名稱＋代號）、大字現價、漲跌與幅度，可加一行附加資訊。
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
    val nameSize: Int = 15,
    val showCode: Boolean = true,
    val codeSize: Int = 12,
    val priceSize: Int = 34,
    val priceBold: Boolean = true,
    /** 現價對齊：0 置中、1 靠右 */
    val priceAlign: Int = 0,
    val chgSize: Int = 16,
    val showChg: Boolean = true,
    val showPct: Boolean = true,
    val showArrow: Boolean = true,
    /** 標題列底色 */
    val header: Boolean = true,
    /** 外框依漲跌上色 */
    val border: Boolean = true,
    /** 現價顏色：0 依漲跌、1 白（主題文字色） */
    val priceColor: Int = 0,
    /** 附加資訊：0 無、1 持股市值、2 今日損益、3 報酬率、4 持股股數 */
    val extra: Int = 0,
    val extraSize: Int = 11,
) {
    fun toJson(): String = JSONObject().put("c", cols).put("p", pad).put("g", gap).put("lg", lineGap).put("lh", lineHeight)
        .put("ns", nameSize).put("sc", showCode).put("cs", codeSize).put("ps", priceSize).put("pb", priceBold).put("pa", priceAlign)
        .put("chs", chgSize).put("sch", showChg).put("spc", showPct).put("sa", showArrow).put("h", header).put("b", border)
        .put("pcol", priceColor).put("x", extra).put("xs", extraSize).toString()

    companion object {
        fun fromJson(s: String?): WallStyle = runCatching {
            val o = JSONObject(s ?: return WallStyle())
            val d = WallStyle()
            WallStyle(
                o.optInt("c", d.cols).coerceIn(1, 4), o.optInt("p", d.pad).coerceIn(-10, 24), o.optInt("g", d.gap).coerceIn(0, 16),
                o.optInt("lg", d.lineGap).coerceIn(-12, 16), o.optInt("lh", d.lineHeight).coerceIn(70, 170),
                o.optInt("ns", d.nameSize).coerceIn(9, 24), o.optBoolean("sc", d.showCode), o.optInt("cs", d.codeSize).coerceIn(8, 20),
                o.optInt("ps", d.priceSize).coerceIn(14, 56), o.optBoolean("pb", d.priceBold), o.optInt("pa", d.priceAlign).coerceIn(0, 1),
                o.optInt("chs", d.chgSize).coerceIn(9, 28), o.optBoolean("sch", d.showChg), o.optBoolean("spc", d.showPct), o.optBoolean("sa", d.showArrow),
                o.optBoolean("h", d.header), o.optBoolean("b", d.border), o.optInt("pcol", d.priceColor).coerceIn(0, 1),
                o.optInt("x", d.extra).coerceIn(0, 4), o.optInt("xs", d.extraSize).coerceIn(8, 18),
            )
        }.getOrDefault(WallStyle())
    }
}
