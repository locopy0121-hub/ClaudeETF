package com.claudeetf.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 行情牆單一項目的設定。
 * head：放在標題列（否則在內容區）；inline：與上一個顯示中的項目同一行；
 * align：0 靠左、1 置中、2 靠右；color：0 預設、1 依漲跌、2 主文字、3 次要灰、4 金色、5 主色；
 * label：顯示前綴文字（例如「市值」）。
 * mx/my：外距（左右／上下，可負）；px/py：內距（底色內留白）；lg：與上一行的行距（可負，僅行首項目有效）；
 * lh：字高（行高倍率 %，0＝跟隨版面）；bg：底色 0 無、1 依漲跌、2 依等級、3 灰、4 金、5 主色、6 白、7 深色；
 * ba：底色濃度 %；lc：前綴文字顏色 0 同項目、1 灰、2 白、3 金、4 主色、5 依漲跌；color 另有 6 依等級（進場建議／光譜）。
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
    val mx: Int = 0,
    val my: Int = 0,
    val px: Int = 0,
    val py: Int = 0,
    val lg: Int = 0,
    val lh: Int = 0,
    val bg: Int = 0,
    val ba: Int = 25,
    val lc: Int = 0,
    /** 懸浮：不佔版面，自由放在格內（fx、fy＝水平、垂直位置 %） */
    val fl: Boolean = false,
    val fx: Int = 100,
    val fy: Int = 30,
    /** 字體（FONT_OPTS 代號；空白＝依「等寬」設定） */
    val font: String = "",
    /** 懸浮對齊基準點：水平 0 左緣、1 中心、2 右緣、3 自動（依位置）；垂直 0 上緣、1 中線、2 下緣、3 自動 */
    val fa: Int = 3,
    val fva: Int = 3,
    /** 位置微調（dp，可負）：流動排版的項目也能逐一對齊上下其他項目 */
    val ox: Int = 0,
    val oy: Int = 0,
    /** 文字動畫（TextFx）：款式、觸發、速度、強度 */
    val an: Int = 0,
    val at: Int = 1,
    val asp: Int = 1,
    val apw: Int = 1,
    /** 斜體、底線 */
    val italic: Boolean = false,
    val under: Boolean = false,
    /** 刪除線、等寬數字（數字寬度固定，跳動時不左右晃） */
    val strike: Boolean = false,
    val tnum: Boolean = false,
    /** 欄寬（格寬的 %，0＝自動）、欄高（dp，0＝自動）、適寬字體（字級自動填滿欄寬／欄高） */
    val bw: Int = 0,
    val bh: Int = 0,
    val fit: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("s", show).put("h", head).put("i", inline).put("a", align)
        .put("z", size).put("b", bold).put("c", color).put("m", mono).put("l", label)
        .put("mx", mx).put("my", my).put("px", px).put("py", py).put("lg", lg).put("lh", lh).put("bg", bg).put("ba", ba).put("lc", lc).put("fl", fl).put("fx", fx).put("fy", fy).put("f", font)
        .put("fa", fa).put("fva", fva).put("ox", ox).put("oy", oy).put("an", an).put("at", at).put("asp", asp).put("apw", apw).put("it", italic).put("ul", under).put("sk", strike).put("tn", tnum).put("bw", bw).put("bh", bh).put("fit", fit)

    companion object {
        fun fromJson(o: JSONObject, d: WallItem) = WallItem(
            d.id, o.optBoolean("s", d.show), o.optBoolean("h", d.head), o.optBoolean("i", d.inline), o.optInt("a", d.align).coerceIn(0, 2),
            o.optInt("z", d.size).coerceIn(8, 60), o.optBoolean("b", d.bold), o.optInt("c", d.color).coerceIn(0, 6), o.optBoolean("m", d.mono), o.optBoolean("l", d.label),
            o.optInt("mx", d.mx).coerceIn(-8, 24), o.optInt("my", d.my).coerceIn(-12, 24), o.optInt("px", d.px).coerceIn(-10, 20), o.optInt("py", d.py).coerceIn(-10, 16),
            o.optInt("lg", d.lg).coerceIn(-12, 16), o.optInt("lh", d.lh).let { if (it == 0) 0 else it.coerceIn(70, 170) },
            o.optInt("bg", d.bg).coerceIn(0, 7), o.optInt("ba", d.ba).coerceIn(10, 100), o.optInt("lc", d.lc).coerceIn(0, 5),
            o.optBoolean("fl", d.fl), o.optInt("fx", d.fx).coerceIn(0, 100), o.optInt("fy", d.fy).coerceIn(0, 100),
            o.optString("f", d.font),
            o.optInt("fa", d.fa).coerceIn(0, 3), o.optInt("fva", d.fva).coerceIn(0, 3),
            o.optInt("ox", d.ox).coerceIn(-60, 60), o.optInt("oy", d.oy).coerceIn(-40, 40),
            o.optInt("an", d.an).coerceIn(0, 20), o.optInt("at", d.at).coerceIn(0, 4), o.optInt("asp", d.asp).coerceIn(0, 2), o.optInt("apw", d.apw).coerceIn(0, 2),
            o.optBoolean("it", d.italic), o.optBoolean("ul", d.under),
            o.optBoolean("sk", d.strike), o.optBoolean("tn", d.tnum),
            o.optInt("bw", d.bw).coerceIn(0, 100), o.optInt("bh", d.bh).coerceIn(0, 160), o.optBoolean("fit", d.fit),
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
    /** 漲停、跌停時整格反白（底色依漲跌、文字白色） */
    val limitFill: Boolean = true,
    /** 項目（依順序排列） */
    val items: List<WallItem> = DEFAULT_ITEMS,
    /** 手機橫向時每列格數（0＝自動：直向格數＋1，最多 4） */
    val landCols: Int = 0,
) {
    /** 依方向取得實際每列格數 */
    fun colsFor(landscape: Boolean) = if (!landscape) cols else if (landCols > 0) landCols else (cols + 1).coerceAtMost(4)

    fun item(id: String) = items.firstOrNull { it.id == id } ?: DEFAULT_ITEMS.first { it.id == id }
    fun withItem(it2: WallItem) = copy(items = items.map { if (it.id == it2.id) it2 else it })

    fun toJson(): String = JSONObject().put("v", 2).put("c", cols).put("p", pad).put("g", gap).put("lg", lineGap).put("lh", lineHeight)
        .put("h", header).put("b", border).put("lf", limitFill).put("lcols", landCols).put("items", JSONArray().apply { items.forEach { put(it.toJson()) } }).toString()

    companion object {
        /** 項目代號 → 名稱 */
        val NAMES = linkedMapOf(
            "name" to "名稱", "code" to "代號", "price" to "現價", "arrow" to "漲跌箭頭", "chg" to "漲跌", "pct" to "漲跌幅",
            "entry" to "進場建議", "band" to "光譜位階", "mv" to "持股市值", "day" to "今日損益", "ret" to "報酬率",
            "pl" to "未實現損益", "shares" to "股數", "avg" to "均價", "cost" to "持有成本", "weight" to "持股占比", "vol" to "成交量", "limit" to "漲跌停標籤", "rank" to "報表排行", "kbar" to "即時 K 棒",
        )

        val DEFAULT_ITEMS = listOf(
            WallItem("name", true, head = true, size = 15),
            WallItem("code", true, head = true, inline = true, align = 2, size = 12),
            WallItem("price", true, align = 1, size = 34, bold = true, mono = true),
            WallItem("arrow", true, size = 13),
            WallItem("chg", true, inline = true, align = 2, size = 16, mono = true),
            WallItem("pct", true, inline = true, align = 2, size = 16, mono = true),
            WallItem("entry", false, align = 1, size = 12, bold = true, px = 6, py = 1, bg = 2, ba = 100),
            WallItem("band", false, inline = true, align = 2, size = 12, color = 6, px = 5, py = 1, bg = 2, ba = 22),
            WallItem("mv", false, align = 2, size = 11),
            WallItem("day", false, align = 2, size = 11),
            WallItem("ret", false, inline = true, align = 2, size = 11),
            WallItem("pl", false, align = 2, size = 11),
            WallItem("shares", false, align = 0, size = 11),
            WallItem("avg", false, inline = true, align = 2, size = 11),
            WallItem("cost", false, align = 2, size = 11),
            WallItem("weight", false, align = 2, size = 11),
            WallItem("vol", false, align = 2, size = 11),
            WallItem("limit", true, head = true, inline = true, align = 1, size = 11, bold = true, px = 5, py = 1, bg = 1, ba = 100),
            WallItem("kbar", false, fl = true, fx = 100, fy = 50, size = 30),
            WallItem("rank", false, align = 0, size = 11, bold = true, px = 5, py = 1, bg = 2, ba = 22, color = 6),
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
                o.optBoolean("h", d.header), o.optBoolean("b", d.border), o.optBoolean("lf", d.limitFill),
            ).copy(landCols = o.optInt("lcols", 0).coerceIn(0, 6))
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
