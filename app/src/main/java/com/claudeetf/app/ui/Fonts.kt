package com.claudeetf.app.ui

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

/** 可選字體（代號 → 名稱）；中文字形依系統，主要影響數字與英文字母 */
val FONT_OPTS = listOf(
    "" to "預設", "sans" to "一般", "mono" to "等寬", "serif" to "襯線", "cond" to "窄體",
    "medium" to "中黑", "light" to "細體", "smono" to "等寬襯線", "casual" to "手寫", "cursive" to "草寫", "caps" to "小型大寫",
)

fun fontOf(key: String): FontFamily? = when (key) {
    "sans" -> FontFamily.SansSerif
    "mono" -> FontFamily.Monospace
    "serif" -> FontFamily.Serif
    "cond" -> FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
    "medium" -> FontFamily(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    "light" -> FontFamily(Typeface.create("sans-serif-light", Typeface.NORMAL))
    "smono" -> FontFamily(Typeface.create("serif-monospace", Typeface.NORMAL))
    "casual" -> FontFamily(Typeface.create("casual", Typeface.NORMAL))
    "cursive" -> FontFamily.Cursive
    "caps" -> FontFamily(Typeface.create("sans-serif-smallcaps", Typeface.NORMAL))
    else -> null
}

/** 底線＋刪除線組合 */
fun decoOf(under: Boolean, strike: Boolean): androidx.compose.ui.text.style.TextDecoration? = when {
    under && strike -> androidx.compose.ui.text.style.TextDecoration.combine(listOf(
        androidx.compose.ui.text.style.TextDecoration.Underline, androidx.compose.ui.text.style.TextDecoration.LineThrough))
    under -> androidx.compose.ui.text.style.TextDecoration.Underline
    strike -> androidx.compose.ui.text.style.TextDecoration.LineThrough
    else -> null
}
