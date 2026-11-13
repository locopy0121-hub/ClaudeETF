package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AiAnalysis
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.HoldStyle
import com.claudeetf.app.data.WallStyle
import com.claudeetf.app.data.WatchStyle
import com.claudeetf.app.ui.theme.C
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 編輯工具艙共用元件（行情卡片、持股卡片、持股行情牆）：
 * 復原／重做、按住看原樣、A/B 方案、分頁、滑桿步進、B/I/U/S、色票、十字微調、AI 指令。
 */

/** 單一樣式物件的編輯歷史：每次修改記一步（最多 50 步）；開啟面板時記下原樣，供「按住比較」與「取消」 */
class StyleHistory<T>(private val get: () -> T, private val put: (T) -> Unit) {
    private val undo = ArrayDeque<T>()
    private val redo = ArrayDeque<T>()
    var undoCount by mutableIntStateOf(0); private set
    var redoCount by mutableIntStateOf(0); private set
    /** 按住 👁 時暫時顯示的原樣（null＝顯示目前設定） */
    var peek by mutableStateOf<T?>(null)
    private var snapshot: T? = null

    fun begin() { if (snapshot == null) snapshot = get() }
    fun end() { snapshot = null; peek = null }
    fun apply(f: (T) -> T) {
        val cur = get(); val next = f(cur)
        if (next == cur) return
        undo.addLast(cur); if (undo.size > 50) undo.removeFirst()
        redo.clear(); put(next); sync()
    }
    /** 拖曳等連續操作：只在開始時記一步，過程中直接寫入 */
    fun mark() { undo.addLast(get()); if (undo.size > 50) undo.removeFirst(); redo.clear(); sync() }
    fun live(f: (T) -> T) { put(f(get())) }
    fun undo() { val p = undo.removeLastOrNull() ?: return; redo.addLast(get()); put(p); sync() }
    fun redo() { val n = redo.removeLastOrNull() ?: return; undo.addLast(get()); put(n); sync() }
    fun cancel() { snapshot?.let { if (it != get()) apply { _ -> it } }; end() }
    fun peekOn() { peek = snapshot ?: undo.firstOrNull() }
    fun peekOff() { peek = null }
    private fun sync() { undoCount = undo.size; redoCount = redo.size }
}

object Hist {
    val watch = StyleHistory({ AppState.watchStyle }, { AppState.updateWatchStyle(it) })
    val hold = StyleHistory({ AppState.holdStyle }, { AppState.updateHoldStyle(it) })
    val wall = StyleHistory({ AppState.wallStyle }, { AppState.updateWallStyle(it) })
}

/** A/B 兩套方案：每個編輯對象各自保存，一鍵切換比較 */
object AbSlots {
    enum class Target(val key: String) { WATCH("watch"), HOLD("hold"), WALL("wall") }
    var tick by mutableIntStateOf(0); private set
    fun active(t: Target): String = AppState.cacheGet("ab_${t.key}_on") ?: "A"
    private fun current(t: Target): String = when (t) {
        Target.WATCH -> AppState.watchStyle.toJson(); Target.HOLD -> AppState.holdStyle.toJson(); Target.WALL -> AppState.wallStyle.toJson()
    }
    /** 切到另一個方案：目前設定存回原方案，再載入目標方案（第一次使用時複製目前設定） */
    fun switchTo(t: Target, slot: String) {
        val on = active(t)
        if (slot == on) return
        AppState.cachePut("ab_${t.key}_$on", current(t))
        val other = AppState.cacheGet("ab_${t.key}_$slot")
        if (other != null) when (t) {
            Target.WATCH -> Hist.watch.apply { WatchStyle.fromJson(other) }
            Target.HOLD -> Hist.hold.apply { HoldStyle.fromJson(other) }
            Target.WALL -> Hist.wall.apply { WallStyle.fromJson(other) }
        }
        AppState.cachePut("ab_${t.key}_on", slot)
        tick++
    }
}

/** 工具艙頂部：標題、取消／完成、復原重做、按住比較、A/B、AI */
@Composable
fun DeckHeader(title: String, subtitle: String, hist: StyleHistory<*>, ab: AbSlots.Target,
               onAi: (() -> Unit)?, onCancel: (() -> Unit)?, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onCancel != null) Text("取消", color = C.Text2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel).padding(horizontal = 4.dp, vertical = 8.dp))
            Column(Modifier.weight(1f).padding(horizontal = 6.dp), horizontalAlignment = if (onCancel != null) Alignment.CenterHorizontally else Alignment.Start) {
                Text("編輯工具・$title", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(subtitle, color = C.Muted, fontSize = 11.sp, maxLines = 1)
            }
            Text("完成", color = C.OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Primary).clickable(onClick = onDone).padding(horizontal = 14.dp, vertical = 7.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolBtn("↶", hist.undoCount > 0) { hist.undo() }
            ToolBtn("↷", hist.redoCount > 0) { hist.redo() }
            // 按住看原樣：放開恢復
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (hist.peek != null) C.Primary else C.Bg)
                .pointerInput(hist) { detectTapGestures(onPress = { hist.peekOn(); tryAwaitRelease(); hist.peekOff() }) }
                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text("👁 按住比較", color = if (hist.peek != null) C.OnPrimary else C.Text2, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            val on = remember(AbSlots.tick, ab) { AbSlots.active(ab) }
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(C.Bg).padding(2.dp)) {
                listOf("A", "B").forEach { s ->
                    Text("方案$s", color = if (on == s) C.OnPrimary else C.Text2, fontSize = 12.sp, fontWeight = if (on == s) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on == s) C.Primary else Color.Transparent)
                            .clickable { AbSlots.switchTo(ab, s) }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            if (onAi != null) Text("✨ AI", color = C.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.Primary.copy(alpha = 0.12f)).clickable(onClick = onAi).padding(horizontal = 10.dp, vertical = 6.dp))
        }
    }
}

@Composable
private fun ToolBtn(t: String, on: Boolean, f: () -> Unit) {
    Text(t, color = if (on) C.Primary else C.Border, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 38.dp).clip(RoundedCornerShape(8.dp)).background(C.Bg).clickable(enabled = on, onClick = f).padding(vertical = 3.dp))
}

/** 膠囊式分頁列（可橫向滑動） */
@Composable
fun DeckTabs(tabs: List<String>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { k, t ->
            val on = k == sel
            Text(t, color = if (on) C.OnPrimary else C.Text2, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) C.Primary else C.Bg).clickable { onSel(k) }
                    .padding(horizontal = 16.dp, vertical = 7.dp))
        }
    }
}

/** 分組卡片 */
@Composable
fun DeckGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(C.Bg).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title != null) Text(title, color = C.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

/** 滑桿＋步進：［－］滑桿［＋］數值；－／＋ 每次精準加減一個單位 */
@Composable
fun StepperSlider(label: String, value: Int, min: Int, max: Int, step: Int = 1, unit: String = "",
                  show: ((Int) -> String)? = null, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(56.dp), color = C.Text, fontSize = 13.sp, maxLines = 2)
        StepBtn("－", value > min) { onChange((value - step).coerceAtLeast(min)) }
        var drag by remember(value) { mutableFloatStateOf(value.toFloat()) }
        val n = (max - min) / step
        Slider(drag, { v -> drag = v; val r = (min + ((v - min) / step).roundToInt() * step).coerceIn(min, max); if (r != value) onChange(r) },
            Modifier.weight(1f).padding(horizontal = 2.dp), valueRange = min.toFloat()..max.toFloat(), steps = if (n in 2..40) n - 1 else 0,
            colors = SliderDefaults.colors(thumbColor = C.Primary, activeTrackColor = C.Primary, inactiveTrackColor = C.Border,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
        StepBtn("＋", value < max) { onChange((value + step).coerceAtMost(max)) }
        Text(show?.invoke(value) ?: "$value$unit", Modifier.widthIn(min = 52.dp).padding(start = 4.dp).clip(RoundedCornerShape(10.dp)).background(C.Card)
            .padding(horizontal = 6.dp, vertical = 4.dp), color = C.Text, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun StepBtn(t: String, on: Boolean, f: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(C.Card).border(1.dp, C.Border, CircleShape).clickable(enabled = on, onClick = f),
        contentAlignment = Alignment.Center) { Text(t, color = if (on) C.Primary else C.Border, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}

/** 文字樣式切換：粗體、斜體、底線、刪除線、等寬數字 */
@Composable
fun StyleToggles(bold: Boolean, italic: Boolean, under: Boolean, strike: Boolean, tnum: Boolean,
                 onBold: (Boolean) -> Unit, onItalic: (Boolean) -> Unit, onUnder: (Boolean) -> Unit, onStrike: (Boolean) -> Unit, onTnum: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        @Composable fun t(label: String, on: Boolean, w: FontWeight = FontWeight.Normal, st: FontStyle = FontStyle.Normal, d: TextDecoration? = null, f: (Boolean) -> Unit) =
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.Primary else C.Card)
                .border(1.dp, if (on) C.Primary else C.Border, RoundedCornerShape(10.dp)).clickable { f(!on) }, contentAlignment = Alignment.Center) {
                Text(label, color = if (on) C.OnPrimary else C.Text, fontSize = 16.sp, fontWeight = w, fontStyle = st, textDecoration = d)
            }
        t("B", bold, FontWeight.Black, f = onBold)
        t("I", italic, st = FontStyle.Italic, f = onItalic)
        t("U", under, d = TextDecoration.Underline, f = onUnder)
        t("S", strike, d = TextDecoration.LineThrough, f = onStrike)
        Spacer(Modifier.width(4.dp))
        Text("123 等寬數字", color = if (tnum) C.OnPrimary else C.Text2, fontSize = 12.sp,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (tnum) C.Primary else C.Card)
                .border(1.dp, if (tnum) C.Primary else C.Border, RoundedCornerShape(10.dp)).clickable { onTnum(!tnum) }.padding(horizontal = 10.dp, vertical = 10.dp))
    }
}

/** 常用色票 */
val SWATCHES = listOf(0xFFEF4444, 0xFF10B981, 0xFFF2B544, 0xFF6EA8FF, 0xFFFFFFFF, 0xFF9AA3B2, 0xFF111827, 0xFFA855F7)

/** 色票列：預設／（漲跌色）／常用色／🎨 自訂 */
@Composable
fun SwatchRow(label: String, value: Long, trend: Boolean?, onPick: (Long, Boolean) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    if (picking) ColorPickerDialog(title = label, initial = if (value != 0L) Color(value.toInt()) else C.Text,
        onPick = { c -> onPick(c.toArgb().toLong() and 0xFFFFFFFFL, false); picking = false },
        onReset = { onPick(0L, false); picking = false }, onDismiss = { picking = false })
    Column {
        Text(label, color = C.Text, fontSize = 13.sp)
        Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val none = value == 0L && trend != true
            Text("預設", color = if (none) C.OnPrimary else C.Text2, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (none) C.Primary else C.Card).clickable { onPick(0L, false) }.padding(horizontal = 10.dp, vertical = 6.dp))
            if (trend != null) Text("漲跌色", color = if (trend) C.OnPrimary else C.Text2, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (trend) C.Primary else C.Card).clickable { onPick(0L, !trend) }.padding(horizontal = 10.dp, vertical = 6.dp))
            SWATCHES.forEach { c ->
                val on = value == c
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color(c.toInt())).border(if (on) 3.dp else 1.dp, if (on) C.Primary else C.Border, CircleShape)
                    .clickable { onPick(c, false) })
            }
            Box(Modifier.size(28.dp).clip(CircleShape).background(C.Card).border(1.dp, C.Border, CircleShape).clickable { picking = true }, contentAlignment = Alignment.Center) {
                Text("🎨", fontSize = 13.sp)
            }
        }
    }
}

/** 十字微調鍵：▲▼◀▶ 依步進移動，中央座標點一下歸零 */
@Composable
fun DPad(x: Int, y: Int, unit: String, steps: List<Int>, onMove: (dx: Int, dy: Int) -> Unit, onReset: () -> Unit) {
    var step by remember { mutableIntStateOf(steps.first()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PadKey("▲") { onMove(0, -step) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PadKey("◀") { onMove(-step, 0) }
                Text("$x, $y", color = C.Text, fontSize = 11.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp).width(64.dp).clip(RoundedCornerShape(10.dp)).background(C.Card)
                        .border(1.dp, C.Border, RoundedCornerShape(10.dp)).clickable(onClick = onReset).padding(vertical = 8.dp))
                PadKey("▶") { onMove(step, 0) }
            }
            PadKey("▼") { onMove(0, step) }
        }
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("步進", color = C.Muted, fontSize = 11.sp)
            steps.forEach { s ->
                Text("$s $unit", color = if (step == s) C.OnPrimary else C.Text2, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (step == s) C.Primary else C.Card).clickable { step = s }.padding(horizontal = 12.dp, vertical = 5.dp))
            }
            Text("點中央座標歸零", color = C.Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun PadKey(t: String, f: () -> Unit) {
    Box(Modifier.padding(2.dp).size(44.dp).clip(RoundedCornerShape(12.dp)).background(C.Card).border(1.dp, C.Border, RoundedCornerShape(12.dp)).clickable(onClick = f),
        contentAlignment = Alignment.Center) { Text(t, color = C.Primary, fontSize = 16.sp) }
}

/**
 * AI 指令：使用者以文字描述要怎麼改，AI 只回傳允許欄位的 JSON，
 * 由 [apply] 做邊界檢查後套用（記入復原歷史）。不送出任何持股、金額或金鑰。
 */
@Composable
fun AiStyleDialog(target: String, current: JSONObject, schema: String, chips: List<String>,
                  onDismiss: () -> Unit, apply: (JSONObject) -> List<String>) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    fun run(cmd: String) {
        if (cmd.isBlank() || busy) return
        if (!AiAnalysis.hasAny()) { msg = "尚未開啟 AI 或未設定 AI 服務金鑰（設置 › 分析與提醒 › AI 個股體檢）"; return }
        busy = true; msg = null
        scope.launch {
            val r = runCatching {
                val sys = "你是 App 介面樣式編輯助手。依使用者指令修改「$target」的樣式。" +
                    "只能使用下列欄位，數值必須在範圍內，只回傳要修改的欄位，格式為單一 JSON 物件，不要任何說明文字。\n欄位：\n$schema"
                val (out, _) = AiAnalysis.ask(sys, "目前樣式：$current\n指令：$cmd", 400)
                val a = out.indexOf('{'); val b = out.lastIndexOf('}')
                if (a < 0 || b <= a) error("AI 沒有回傳可用的設定")
                JSONObject(out.substring(a, b + 1))
            }
            busy = false
            r.onSuccess { patch ->
                val changed = apply(patch)
                msg = if (changed.isEmpty()) "AI 的建議沒有可套用的變更" else "已套用：" + changed.joinToString("、") + "（可按 ↶ 復原）"
                if (changed.isNotEmpty()) text = ""
            }.onFailure { msg = "失敗：" + (it.message ?: "未知錯誤") }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Card,
        title = { Text("✨ AI 修改「$target」", color = C.Text, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it.take(120) }, Modifier.fillMaxWidth(), placeholder = { Text("例：放大一點、加粗、改成金色、數字跳動時閃一下") }, maxLines = 3)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chips.forEach { c ->
                        Text(c, color = C.Primary, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(C.Primary.copy(alpha = 0.12f))
                            .clickable(enabled = !busy) { run(c) }.padding(horizontal = 10.dp, vertical = 6.dp))
                    }
                }
                if (busy) Text("AI 修改中…", color = C.Muted, fontSize = 12.sp)
                msg?.let { Text(it, color = if (it.startsWith("失敗") || it.startsWith("尚未")) C.Red else C.Primary, fontSize = 12.sp) }
                Text("只傳送此項目的樣式設定給 AI，不含持股、金額或金鑰；修改會先經過範圍檢查。", color = C.Muted, fontSize = 10.sp)
            }
        },
        confirmButton = { TextButton(enabled = !busy && text.isNotBlank(), onClick = { run(text) }) { Text("送出", color = if (!busy && text.isNotBlank()) C.Primary else C.Muted) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("關閉", color = C.Muted) } },
    )
}

/** 解析 AI 給的顏色："#RRGGBB"、"#AARRGGBB" 或 0 */
fun aiColor(v: Any?): Long? = when (v) {
    is Number -> if (v.toLong() == 0L) 0L else null
    is String -> v.trim().removePrefix("#").let { h ->
        when (h.length) { 6 -> h.toLongOrNull(16)?.let { it or 0xFF000000L }; 8 -> h.toLongOrNull(16); else -> if (h == "0") 0L else null }
    }
    else -> null
}

/** 動畫款式清單（給 AI 的說明） */
fun fxSchema(): String = com.claudeetf.app.ui.TextFx.STYLES.joinToString("、") { "${it.id}=${it.name}" }
