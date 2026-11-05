package com.claudeetf.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.ColorMode

object C {
    val Bg = Color(0xFF0E1117)
    val Card = Color(0xFF171B24)
    val Card2 = Color(0xFF1E2430)
    val Line = Color(0xFF232A36)
    val Border = Color(0xFF2E3644)
    val Selected = Color(0xFF2A3242)
    val Text = Color(0xFFE8ECF2)
    val Text2 = Color(0xFFC9D0DB)
    val Muted = Color(0xFF9AA3B2)
    val Primary = Color(0xFF6EA8FF)
    val OnPrimary = Color(0xFF0B1220)
    val Red = Color(0xFFFF6B6B)
    val Green = Color(0xFF3DD68C)
    val RedBg = Color(0xFF3A1E22)
    val RedFg = Color(0xFFFF8A8A)
    val GreenBg = Color(0xFF163527)
    val GreenFg = Color(0xFF4BE29A)
    val Tw = Color(0xFFF2B544)
    val TwBg = Color(0xFF3A2E12)
    val Us = Color(0xFFB9A6FF)
    val UsBg = Color(0xFF2A2346)
    val Gray = Color(0xFF5B6475)
}

val Mono = FontFamily.Monospace

/** 依設定的漲跌配色回傳顏色 */
object Trend {
    private val tw: Boolean get() = AppState.colorMode == ColorMode.TW
    fun fg(v: Double): Color = when {
        v > 0 -> if (tw) C.Red else C.Green
        v < 0 -> if (tw) C.Green else C.Red
        else -> C.Muted
    }
    fun pillBg(v: Double): Color = when {
        v > 0 -> if (tw) C.RedBg else C.GreenBg
        v < 0 -> if (tw) C.GreenBg else C.RedBg
        else -> C.Card2
    }
    fun pillFg(v: Double): Color = when {
        v > 0 -> if (tw) C.RedFg else C.GreenFg
        v < 0 -> if (tw) C.GreenFg else C.RedFg
        else -> C.Muted
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Primary,
            onPrimary = C.OnPrimary,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Card,
            onSurface = C.Text,
            surfaceVariant = C.Card2,
            onSurfaceVariant = C.Muted,
            outline = C.Border,
        ),
        content = content,
    )
}
