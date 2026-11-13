package com.claudeetf.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 文字動畫庫：行情卡片、持股行情牆、指數跑馬燈共用。
 * 動畫值只在 graphicsLayer／drawBehind／drawWithContent 讀取，不觸發重組，清單很多列同時播放也不卡。
 */
object TextFx {
    /** 動畫款式（代號固定，存檔用） */
    data class Style(val id: Int, val name: String, val group: String, val desc: String)

    val STYLES = listOf(
        Style(0, "無", "", "不播放動畫"),
        // 強調
        Style(1, "淡入淡出", "強調", "透明度變化"),
        Style(2, "脈衝", "強調", "微微放大再還原"),
        Style(6, "彈出", "強調", "快速放大後回彈"),
        Style(11, "心跳", "強調", "連續兩下跳動"),
        Style(14, "閃爍", "強調", "明暗切換，最醒目"),
        // 移動
        Style(4, "跳動", "移動", "向上彈起"),
        Style(10, "下沉", "移動", "向下沉再浮回"),
        Style(5, "搖晃", "移動", "左右快速搖動"),
        Style(13, "擺動", "移動", "左右傾斜擺動"),
        Style(9, "翻牌", "移動", "上下翻轉，像機場看板"),
        // 光影
        Style(3, "底色閃動", "光影", "依漲跌色閃一下底色"),
        Style(7, "光暈", "光影", "外圍發光"),
        Style(8, "掃光", "光影", "一道光從左掃到右"),
        Style(12, "變色", "光影", "文字染上漲跌色再褪回"),
        Style(15, "底線掃過", "光影", "下方線條由左延伸"),
        Style(16, "外框呼吸", "光影", "外框忽明忽暗"),
        // 金融
        Style(17, "數字滾動", "金融", "數值變動時新數字由下（漲）或上（跌）捲入"),
        Style(18, "打字機", "金融", "由左到右逐字顯現"),
        Style(19, "跑馬燈", "金融", "文字水平循環捲動"),
        Style(20, "彈性浮現", "金融", "由小彈出到原尺寸，適合進場或訊號標籤"),
    )
    val MAX_ID = 20
    /** 「進入型」動畫：進度從 1 走到 0（剩餘量） */
    fun isEnter(id: Int) = id in 17..20
    fun nameOf(id: Int) = STYLES.firstOrNull { it.id == id }?.name ?: "無"

    val TRIGGERS = listOf(0 to "常駐", 1 to "數值變動", 2 to "上漲時", 3 to "下跌時", 4 to "進場時")
    val SPEEDS = listOf(0 to "慢", 1 to "正常", 2 to "快")
    val POWERS = listOf(0 to "弱", 1 to "中", 2 to "強")
}

/**
 * 套用文字動畫。
 * @param style 款式代號（TextFx.STYLES）
 * @param trig 0 常駐循環、1 數值變動、2 上漲時、3 下跌時
 * @param value 監看的數值（變動時觸發）
 * @param tone 依漲跌的顏色（底色閃動、變色、光暈用）
 */
@Composable
fun Modifier.textFx(style: Int, trig: Int, speed: Int, power: Int, value: Double, tone: Color): Modifier {
    if (style <= 0) return this
    val dur = when (speed) { 0 -> 1400; 2 -> 450; else -> 800 }
    val pw = when (power) { 0 -> 0.5f; 2 -> 1.6f; else -> 1f }
    val p = remember { Animatable(0f) }
    var dir by remember { mutableIntStateOf(0) }
    val enter = TextFx.isEnter(style)
    // 播放一次：進入型由 1 走到 0；其餘 0→1→0（掃光類 0→1 後歸零）
    suspend fun playOnce() {
        when {
            enter -> { p.snapTo(1f); p.animateTo(0f, if (style == 20) androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 380f) else tween(dur, easing = if (style == 18) LinearEasing else FastOutSlowInEasing)) }
            style == 8 || style == 15 -> { p.snapTo(0f); p.animateTo(1f, tween(dur, easing = LinearEasing)); p.snapTo(0f) }
            else -> { p.snapTo(0f); p.animateTo(1f, tween(dur / 3)); p.animateTo(0f, tween(dur * 2 / 3)) }
        }
    }
    when (trig) {
        0 -> LaunchedEffect(style, dur) {
            while (true) {
                when {
                    style == 19 -> { p.snapTo(0f); p.animateTo(1f, tween(dur * 6, easing = LinearEasing)) }
                    enter -> { dir = if (dir >= 0) -1 else 1; playOnce(); kotlinx.coroutines.delay(dur.toLong() * 2) }
                    style == 8 || style == 15 -> { p.snapTo(0f); p.animateTo(1f, tween(dur, easing = LinearEasing)); kotlinx.coroutines.delay(dur.toLong()) }
                    else -> {
                        p.animateTo(1f, tween(dur / 2, easing = FastOutSlowInEasing))
                        p.animateTo(0f, tween(dur / 2, easing = FastOutSlowInEasing))
                        if (style == 1 || style == 9) kotlinx.coroutines.delay(dur.toLong())
                    }
                }
            }
        }
        4 -> LaunchedEffect(style) { dir = 1; playOnce() }
        else -> {
            val last = remember { doubleArrayOf(value) }
            LaunchedEffect(value) {
                val d = value.compareTo(last[0])
                last[0] = value
                if (d == 0) return@LaunchedEffect
                if ((trig == 2 && d < 0) || (trig == 3 && d > 0)) return@LaunchedEffect
                dir = d
                playOnce()
            }
        }
    }
    val c = if (dir != 0) tone else tone
    return when (style) {
        1 -> graphicsLayer { alpha = 1f - 0.7f * p.value.coerceIn(0f, 1f) }
        2 -> graphicsLayer { val s = 1f + 0.08f * pw * p.value; scaleX = s; scaleY = s }
        3 -> drawBehind { drawRoundRect(c.copy(alpha = (0.35f * pw * p.value).coerceIn(0f, 0.8f)), cornerRadius = CornerRadius(6.dp.toPx())) }
        4 -> graphicsLayer { translationY = -4f * pw * p.value * density }
        5 -> graphicsLayer { translationX = sin(p.value * PI.toFloat() * 6) * 3f * pw * density * p.value.let { if (it > 0f) 1f else 0f } }
        6 -> graphicsLayer { val v = p.value; val s = 1f + 0.22f * pw * v * (1f + 0.3f * sin(v * PI.toFloat())); scaleX = s; scaleY = s }
        7 -> drawBehind {
            val v = p.value
            for (k in 1..3) {
                val g = k * 2.5f * density * pw
                drawRoundRect(c.copy(alpha = (0.18f * v / k).coerceIn(0f, 1f)), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(6.dp.toPx() + g))
            }
        }
        8 -> graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            val v = p.value
            if (v > 0f && v < 1f) {
                val w = size.width * 0.35f
                val x = -w + (size.width + 2 * w) * v
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.75f), Color.Transparent), startX = x - w / 2, endX = x + w / 2),
                    blendMode = BlendMode.SrcAtop)
            }
        }
        9 -> graphicsLayer { rotationX = sin(p.value * PI.toFloat()) * 75f; cameraDistance = 12f * density }
        10 -> graphicsLayer { translationY = 4f * pw * p.value * density; alpha = 1f - 0.35f * p.value }
        11 -> graphicsLayer { val v = p.value; val s = 1f + 0.09f * pw * abs(sin(v * PI.toFloat() * 2)); scaleX = s; scaleY = s }
        12 -> graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
            drawContent()
            drawRect(c.copy(alpha = (0.85f * p.value).coerceIn(0f, 1f)), blendMode = BlendMode.SrcAtop)
        }
        13 -> graphicsLayer { rotationZ = sin(p.value * PI.toFloat() * 4) * 4f * pw }
        14 -> graphicsLayer { alpha = if (p.value > 0.5f) 0.15f else 1f }
        15 -> drawBehind {
            val v = p.value
            if (v > 0f) drawLine(c, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width * v, size.height - 1.dp.toPx()), (1.5f * pw).dp.toPx())
        }
        16 -> drawBehind {
            drawRoundRect(c.copy(alpha = (0.85f * p.value).coerceIn(0f, 1f)), cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke((1.5f * pw).dp.toPx()))
        }
        // 數字滾動：在自身範圍內裁切，新內容由下（漲）或上（跌）捲入
        17 -> graphicsLayer { clip = true; translationY = p.value * size.height * (if (dir < 0) -1f else 1f) * (0.6f + 0.4f * pw.coerceAtMost(1f)) }
        // 打字機：由左到右逐步顯現
        18 -> drawWithContent { androidx.compose.ui.graphics.drawscope.clipRect(right = size.width * (1f - p.value)) { this@drawWithContent.drawContent() } }
        // 跑馬燈：在自身範圍內由右往左循環
        19 -> graphicsLayer { clip = true; translationX = size.width * (1f - 2f * p.value) }
        // 彈性浮現：由小彈到原尺寸（彈簧可略為超過）
        20 -> graphicsLayer { val s = (1f - 0.45f * pw.coerceAtMost(1.3f) * p.value).coerceAtLeast(0.2f); scaleX = s; scaleY = s; alpha = (1f - p.value).coerceIn(0.2f, 1f) }
        else -> this
    }
}

/**
 * 文字動畫選擇器（行情卡片、行情牆、跑馬燈共用）：分組列出全部款式，附即時預覽；選了款式才顯示觸發、速度、強度。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FxPicker(style: Int, trig: Int, speed: Int, power: Int, onChange: (style: Int, trig: Int, speed: Int, power: Int) -> Unit) {
    @Composable fun chip(text: String, on: Boolean, onClick: () -> Unit) {
        androidx.compose.material3.Text(text, color = if (on) com.claudeetf.app.ui.theme.C.OnPrimary else com.claudeetf.app.ui.theme.C.Text2,
            fontSize = 12.sp, maxLines = 1,
            modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .background(if (on) com.claudeetf.app.ui.theme.C.Primary else com.claudeetf.app.ui.theme.C.Bg)
                .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp))
    }
    androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Text("文字動畫", color = com.claudeetf.app.ui.theme.C.Text, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        chip("無", style == 0) { onChange(0, trig, speed, power) }
        TextFx.STYLES.filter { it.id != 0 }.groupBy { it.group }.forEach { (g, l) ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                androidx.compose.material3.Text(g, color = com.claudeetf.app.ui.theme.C.Muted, fontSize = 11.sp, modifier = Modifier.width(34.dp).padding(top = 6.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    l.forEach { s -> chip(s.name, style == s.id) { onChange(s.id, if (style == 0 && trig == 0 && s.id != 0) 1 else trig, speed, power) } }
                }
            }
        }
        if (style > 0) {
            // 即時預覽：常駐播放所選款式
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(com.claudeetf.app.ui.theme.C.Bg).padding(10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Text("預覽", color = com.claudeetf.app.ui.theme.C.Muted, fontSize = 11.sp)
                androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                // 常駐：持續循環；其他觸發：按「▶ 重播」模擬一次觸發
                var replay by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
                val down = trig == 3
                val tone = com.claudeetf.app.ui.theme.Trend.fg(if (down) -1.0 else 1.0)
                androidx.compose.runtime.key(if (trig == 4) replay else 0) {
                    androidx.compose.material3.Text(if (down) "▼ 1,234.50" else "▲ 1,234.50", color = tone, fontSize = 18.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.textFx(style, trig, speed, power, (if (down) -replay else replay).toDouble(), tone).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Text(TextFx.STYLES.firstOrNull { it.id == style }?.desc ?: "", color = com.claudeetf.app.ui.theme.C.Muted, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                if (trig != 0) chip("▶ 重播", false) { replay++ }
            }
            androidx.compose.material3.Text("觸發", color = com.claudeetf.app.ui.theme.C.Text, fontSize = 12.sp)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                TextFx.TRIGGERS.forEach { (v, n) -> chip(n, trig == v) { onChange(style, v, speed, power) } }
            }
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Text("速度", color = com.claudeetf.app.ui.theme.C.Text, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                TextFx.SPEEDS.forEach { (v, n) -> chip(n, speed == v) { onChange(style, trig, v, power) }; androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp)) }
            }
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Text("強度", color = com.claudeetf.app.ui.theme.C.Text, fontSize = 12.sp, modifier = Modifier.width(40.dp))
                TextFx.POWERS.forEach { (v, n) -> chip(n, power == v) { onChange(style, trig, speed, v) }; androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp)) }
            }
        }
    }
}
