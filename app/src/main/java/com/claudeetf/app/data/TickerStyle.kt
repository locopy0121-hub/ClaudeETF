package com.claudeetf.app.data

import org.json.JSONObject

/**
 * 指數跑馬燈（置頂字條）樣式：各部分字級、字體、粗細、顯示項目、配色與間距。
 */
data class TickerStyle(
    val nameSize: Int = 13,
    val valueSize: Int = 13,
    val changeSize: Int = 12,
    val tagSize: Int = 11,
    /** 名稱字體、數字字體（代號見 FONT_OPTS） */
    val nameFont: String = "",
    val numFont: String = "mono",
    val nameBold: Boolean = false,
    val valueBold: Boolean = true,
    val showTag: Boolean = true,
    val showSession: Boolean = true,
    val showName: Boolean = true,
    val showValue: Boolean = true,
    val showArrow: Boolean = false,
    val showChange: Boolean = true,
    val showPct: Boolean = true,
    /** 數值顏色：0 白、1 依漲跌 */
    val valueColor: Int = 0,
    /** 底色：0 卡片色、1 透明、2 依加權指數漲跌淡色 */
    val bg: Int = 0,
    val padH: Int = 10,
    val padV: Int = 7,
    /** 指數與指數之間距 */
    val gap: Int = 14,
    val radius: Int = 10,
    /** 跑馬燈速度（dp／秒） */
    val speed: Int = 32,
    /** 文字動畫（TextFx）：款式、觸發、速度、強度；套用對象 0 漲跌與漲跌幅、1 指數點數、2 整組 */
    val fx: Int = 0, val fxTrig: Int = 1, val fxSpeed: Int = 1, val fxPower: Int = 1, val fxOn: Int = 0,
) {
    fun toJson(): String = JSONObject().put("ns", nameSize).put("vs", valueSize).put("cs", changeSize).put("ts", tagSize)
        .put("nf", nameFont).put("mf", numFont).put("nb", nameBold).put("vb", valueBold)
        .put("st", showTag).put("ss", showSession).put("sn", showName).put("sv", showValue).put("sa", showArrow).put("sc", showChange).put("sp", showPct)
        .put("vc", valueColor).put("bg", bg).put("ph", padH).put("pv", padV).put("g", gap).put("r", radius).put("spd", speed)
        .put("fx", fx).put("ft", fxTrig).put("fs", fxSpeed).put("fp", fxPower).put("fo", fxOn).toString()

    companion object {
        fun fromJson(s: String?): TickerStyle = runCatching {
            val o = JSONObject(s ?: return TickerStyle()); val d = TickerStyle()
            TickerStyle(
                o.optInt("ns", d.nameSize).coerceIn(8, 28), o.optInt("vs", d.valueSize).coerceIn(8, 28), o.optInt("cs", d.changeSize).coerceIn(8, 28),
                o.optInt("ts", d.tagSize).coerceIn(8, 22), o.optString("nf", d.nameFont), o.optString("mf", d.numFont),
                o.optBoolean("nb", d.nameBold), o.optBoolean("vb", d.valueBold),
                o.optBoolean("st", d.showTag), o.optBoolean("ss", d.showSession), o.optBoolean("sn", d.showName), o.optBoolean("sv", d.showValue),
                o.optBoolean("sa", d.showArrow), o.optBoolean("sc", d.showChange), o.optBoolean("sp", d.showPct),
                o.optInt("vc", d.valueColor).coerceIn(0, 1), o.optInt("bg", d.bg).coerceIn(0, 2),
                o.optInt("ph", d.padH).coerceIn(0, 24), o.optInt("pv", d.padV).coerceIn(0, 20), o.optInt("g", d.gap).coerceIn(4, 40),
                o.optInt("r", d.radius).coerceIn(0, 24), o.optInt("spd", d.speed).coerceIn(10, 120),
                o.optInt("fx", 0).coerceIn(0, 16), o.optInt("ft", 1).coerceIn(0, 3), o.optInt("fs", 1).coerceIn(0, 2), o.optInt("fp", 1).coerceIn(0, 2), o.optInt("fo", 0).coerceIn(0, 2),
            )
        }.getOrDefault(TickerStyle())
    }
}
