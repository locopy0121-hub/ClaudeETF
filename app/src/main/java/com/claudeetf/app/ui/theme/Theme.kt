package com.claudeetf.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.ColorMode

/** 一組語意色 */
class Palette(
    val bg: Color, val card: Color, val card2: Color, val line: Color, val border: Color, val selected: Color,
    val text: Color, val text2: Color, val muted: Color, val primary: Color, val onPrimary: Color,
    val red: Color, val green: Color, val redBg: Color, val redFg: Color, val greenBg: Color, val greenFg: Color,
    val tw: Color, val twBg: Color, val us: Color, val usBg: Color, val gray: Color,
)

val DarkPalette = Palette(
    bg = Color(0xFF0E1117), card = Color(0xFF171B24), card2 = Color(0xFF1E2430), line = Color(0xFF232A36),
    border = Color(0xFF2E3644), selected = Color(0xFF2A3242), text = Color(0xFFE8ECF2), text2 = Color(0xFFC9D0DB),
    muted = Color(0xFF9AA3B2), primary = Color(0xFF6EA8FF), onPrimary = Color(0xFF0B1220),
    red = Color(0xFFFF6B6B), green = Color(0xFF3DD68C), redBg = Color(0xFF3A1E22), redFg = Color(0xFFFF8A8A),
    greenBg = Color(0xFF163527), greenFg = Color(0xFF4BE29A), tw = Color(0xFFF2B544), twBg = Color(0xFF3A2E12),
    us = Color(0xFFB9A6FF), usBg = Color(0xFF2A2346), gray = Color(0xFF5B6475),
)

val LightPalette = Palette(
    bg = Color(0xFFF5F7FA), card = Color(0xFFFFFFFF), card2 = Color(0xFFEEF1F5), line = Color(0xFFE3E7ED),
    border = Color(0xFFCBD2DC), selected = Color(0xFFE1E8F5), text = Color(0xFF111827), text2 = Color(0xFF374151),
    muted = Color(0xFF5F6B7A), primary = Color(0xFF2563EB), onPrimary = Color(0xFFFFFFFF),
    red = Color(0xFFD92D20), green = Color(0xFF067647), redBg = Color(0xFFFDECEA), redFg = Color(0xFFB42318),
    greenBg = Color(0xFFE3F5EA), greenFg = Color(0xFF05603A), tw = Color(0xFFB54708), twBg = Color(0xFFFEF0C7),
    us = Color(0xFF6941C6), usBg = Color(0xFFEFE8FD), gray = Color(0xFF98A2B3),
)

/** 0 = 跟隨系統、1 = 深色、2 = 淺色 */
object ThemeState {
    fun isDark(): Boolean = when (AppState.themeMode) {
        1 -> true
        2 -> false
        else -> AppState.systemDark
    }
    val p: Palette get() = if (isDark()) DarkPalette else LightPalette
}

/** 目前主題的語意色（讀取設定狀態，切換主題時畫面自動重繪） */
object C {
    val Bg get() = ThemeState.p.bg
    val Card get() = ThemeState.p.card
    val Card2 get() = ThemeState.p.card2
    val Line get() = ThemeState.p.line
    val Border get() = ThemeState.p.border
    val Selected get() = ThemeState.p.selected
    val Text get() = ThemeState.p.text
    val Text2 get() = ThemeState.p.text2
    val Muted get() = ThemeState.p.muted
    val Primary get() = ThemeState.p.primary
    val OnPrimary get() = ThemeState.p.onPrimary
    val Red get() = ThemeState.p.red
    val Green get() = ThemeState.p.green
    val RedBg get() = ThemeState.p.redBg
    val RedFg get() = ThemeState.p.redFg
    val GreenBg get() = ThemeState.p.greenBg
    val GreenFg get() = ThemeState.p.greenFg
    val Tw get() = ThemeState.p.tw
    val TwBg get() = ThemeState.p.twBg
    val Us get() = ThemeState.p.us
    val UsBg get() = ThemeState.p.usBg
    val Gray get() = ThemeState.p.gray
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
    val sys = isSystemInDarkTheme()
    SideEffect { if (AppState.systemDark != sys) AppState.systemDark = sys }
    val dark = when (AppState.themeMode) { 1 -> true; 2 -> false; else -> sys }
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, background = p.bg, onBackground = p.text,
            surface = p.card, onSurface = p.text, surfaceVariant = p.card2, onSurfaceVariant = p.muted, outline = p.border,
        )
    } else {
        lightColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, background = p.bg, onBackground = p.text,
            surface = p.card, onSurface = p.text, surfaceVariant = p.card2, onSurfaceVariant = p.muted, outline = p.border,
        )
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * AppState.fontScale)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
