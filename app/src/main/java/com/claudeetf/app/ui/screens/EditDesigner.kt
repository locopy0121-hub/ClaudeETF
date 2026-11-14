package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AiAnalysis
import com.claudeetf.app.data.AppState
import com.claudeetf.app.ui.theme.C
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 設計師工具艙（上下分屏的下半部）：
 * ① 視圖列：路徑（對象 › 項目）、👁 按住比較、方案 A／B、取消／完成
 * ② AI 設計助手列：🎯 單點／🧩 整卡、指令輸入、快捷膠囊（一點即套用）
 * ③ 分頁：⚡ 常用（預設）、排印、版面、色彩、動態
 * ④ 單項性質一列控制區（左名稱、右控制項）
 * ⑤ 底部動作列：↩ 復原、↪ 重做、🖌 吸取／貼上樣式、💾 存為 B 版
 * AI 一律使用 App 目前設定的 AI 服務（設置 › 分析與提醒 › AI 個股體檢），不另設金鑰或寫死模型。
 */

/** AI 修改規格：單點（目前項目）與整卡（同一張卡片的所有項目，可選） */
class AiSpec(
    val target: String,
    val chips: List<String>,
    val current: () -> JSONObject,
    val schema: String,
    val apply: (JSONObject) -> List<String>,
    val cardCurrent: (() -> JSONObject)? = null,
    val cardApply: ((JSONObject) -> List<String>)? = null,
)

/** 呼叫 App 目前設定的 AI 服務，回傳 JSON 修改內容 */
suspend fun aiPatch(target: String, current: JSONObject, schema: String, cmd: String, card: Boolean): JSONObject {
    val sys = "你是 App 介面樣式設計助手。依使用者指令修改「$target」的樣式。" +
        (if (card) "目前樣式是以項目代號為鍵的物件；回傳格式同樣以項目代號為鍵，每個值只放要修改的欄位。" else "只回傳要修改的欄位。") +
        "只能使用下列欄位，數值必須在範圍內，格式為單一 JSON 物件，不要任何說明文字。\n欄位：\n$schema"
    val (out, _) = AiAnalysis.ask(sys, "目前樣式：$current\n指令：$cmd", if (card) 900 else 400)
    val a = out.indexOf('{'); val b = out.lastIndexOf('}')
    if (a < 0 || b <= a) error("AI 沒有回傳可用的設定")
    return JSONObject(out.substring(a, b + 1))
}

/** 樣式剪貼簿（🖌 吸取／貼上） */
object StylePaint {
    var wall by mutableStateOf<com.claudeetf.app.data.WallItem?>(null)
    var item by mutableStateOf<com.claudeetf.app.data.ItemStyle?>(null)
}

/** 動態性格：一次設定速度與強度 */
val MOTION_PRESETS = listOf(Triple("俐落專業", 2, 1), Triple("柔和流暢", 0, 0), Triple("輕快彈性", 1, 2))

/** 設計師工具艙（停靠在預覽下方） */
@Composable
fun DesignerPanel(
    crumb: String, item: String, hist: StyleHistory<*>, ab: AbSlots.Target, ai: AiSpec?,
    onCopy: (() -> Unit)?, onPaste: (() -> Unit)?, canPaste: Boolean,
    onCancel: () -> Unit, onDone: () -> Unit,
    top: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var aiOpen by remember { mutableStateOf(false) }
    var card by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun runAi(cmd: String) {
        val sp = ai ?: return
        if (busy || cmd.isBlank()) return
        if (!AiAnalysis.hasAny()) { msg = "尚未設定 AI 服務（設置 › 分析與提醒 › AI 個股體檢）"; return }
        val useCard = card && sp.cardCurrent != null && sp.cardApply != null
        busy = true; msg = "AI 設計中…"
        scope.launch {
            val r = runCatching { aiPatch(sp.target, if (useCard) sp.cardCurrent!!() else sp.current(), sp.schema, cmd, useCard) }
            busy = false
            msg = r.fold({ p ->
                val ch = if (useCard) sp.cardApply!!(p) else sp.apply(p)
                if (ch.isEmpty()) "AI 的建議沒有可套用的變更" else "已套用：" + ch.joinToString("、") + "（可 ↩ 復原）"
            }, { "失敗：" + (it.message ?: "未知錯誤") })
        }
    }
    if (aiOpen && ai != null) AiStyleDialog(ai.target, if (card && ai.cardCurrent != null) ai.cardCurrent.invoke() else ai.current(), ai.schema, ai.chips,
        onDismiss = { aiOpen = false }) { p -> if (card && ai.cardApply != null) ai.cardApply.invoke(p) else ai.apply(p) }

    DockedPanel(onDismiss = onDone) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            // ① 視圖列
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("取消", color = C.Text2, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCancel).padding(horizontal = 4.dp, vertical = 8.dp))
                Row(Modifier.weight(1f).padding(horizontal = 6.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("$crumb › ", color = C.Muted, fontSize = 12.sp, maxLines = 1)
                    Text(item, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (hist.peek != null) C.Primary else C.Bg)
                    .pointerInput(hist) { detectTapGestures(onPress = { hist.peekOn(); tryAwaitRelease(); hist.peekOff() }) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)) { Text("👁", fontSize = 13.sp) }
                Spacer(Modifier.width(4.dp))
                val on = remember(AbSlots.tick, ab) { AbSlots.active(ab) }
                Row(Modifier.clip(RoundedCornerShape(8.dp)).background(C.Bg).padding(2.dp)) {
                    listOf("A", "B").forEach { s ->
                        Text(s, color = if (on == s) C.OnPrimary else C.Text2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (on == s) C.Primary else Color.Transparent)
                                .clickable { AbSlots.switchTo(ab, s) }.padding(horizontal = 9.dp, vertical = 4.dp))
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text("完成", color = C.OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Primary).clickable(onClick = onDone).padding(horizontal = 12.dp, vertical = 7.dp))
            }
            // ② AI 設計助手列
            if (ai != null) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (ai.cardCurrent != null) Text(if (card) "🧩 整卡" else "🎯 單點", color = C.Primary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.Primary.copy(alpha = 0.12f)).clickable { card = !card }.padding(horizontal = 8.dp, vertical = 7.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("✨ " + (if (card) "描述整張卡片的風格…" else "如：${ai.target}欄寬 60%、字體填滿…"), color = C.Muted, fontSize = 12.sp, maxLines = 1,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).border(1.dp, C.Border, RoundedCornerShape(16.dp))
                            .clickable { aiOpen = true }.padding(horizontal = 12.dp, vertical = 7.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ai.chips.forEach { c ->
                        Text(c, color = if (busy) C.Muted else C.Primary, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                            .background(C.Primary.copy(alpha = 0.10f)).clickable(enabled = !busy) { runAi(c) }.padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                }
                msg?.let { Text(it, color = if (it.startsWith("失敗") || it.startsWith("尚未")) C.Red else C.Primary, fontSize = 11.sp, maxLines = 2, modifier = Modifier.padding(top = 2.dp)) }
            }
            top?.invoke(this)
            // ③④ 分頁與單項性質列
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 8.dp), content = content)
            // ⑤ 底部動作列
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 40.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ActBtn("↩ 復原", hist.undoCount > 0) { hist.undo() }
                ActBtn("↪ 重做", hist.redoCount > 0) { hist.redo() }
                if (onCopy != null) ActBtn("🖌 吸取", true) { onCopy(); msg = "已吸取「$item」的樣式，點其他項目後按「貼上」" }
                if (onPaste != null) ActBtn("貼上", canPaste) { onPaste() }
                Spacer(Modifier.weight(1f))
                ActBtn("💾 存 B 版", true) { AbSlots.saveTo(ab, "B"); msg = "已把目前設定存為方案 B" }
            }
        }
    }
}

@Composable
private fun ActBtn(t: String, on: Boolean, f: () -> Unit) {
    Text(t, color = if (on) C.Text else C.Border, fontSize = 12.sp, textAlign = TextAlign.Center,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Bg).clickable(enabled = on, onClick = f).padding(horizontal = 10.dp, vertical = 8.dp))
}

/** 單項性質一列：左名稱、右控制項（最小高 46dp，列間細線） */
@Composable
fun PropertyRow(label: String, sub: String? = null, content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(84.dp)) {
                Text(label, color = C.Text, fontSize = 13.sp, maxLines = 2)
                if (sub != null) Text(sub, color = C.Muted, fontSize = 10.sp, maxLines = 2)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Border.copy(alpha = 0.5f)))
    }
}

/** 單列滑桿＋步進（右側全寬） */
@Composable
fun SliderRow(label: String, value: Int, min: Int, max: Int, step: Int = 1, unit: String = "", sub: String? = null,
              show: ((Int) -> String)? = null, onChange: (Int) -> Unit) {
    PropertyRow(label, sub) { Box(Modifier.weight(1f)) { StepperSlider("", value, min, max, step, unit, show, onChange) } }
}

@Composable
fun SwitchRow(label: String, sub: String? = null, on: Boolean, set: (Boolean) -> Unit) {
    PropertyRow(label, sub) {
        Spacer(Modifier.weight(1f))
        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = C.Primary))
    }
}

/** 單列選項膠囊（可橫向滑動） */
@Composable
fun <T> ChipsRow(label: String, opts: List<Pair<T, String>>, sel: T, sub: String? = null, onPick: (T) -> Unit) {
    PropertyRow(label, sub) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            opts.forEach { (v, t) ->
                val on = v == sel
                Text(t, color = if (on) C.OnPrimary else C.Text2, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) C.Primary else C.Bg).clickable { onPick(v) }
                        .padding(horizontal = 11.dp, vertical = 7.dp))
            }
        }
    }
}

/** 單軸微調：[◀] 數值 [▶]，步進可切換，點數值歸零 */
@Composable
fun NudgeRow(label: String, value: Int, unit: String, minus: String, plus: String, steps: List<Int>, onMove: (Int) -> Unit, onReset: () -> Unit) {
    var step by remember { mutableIntStateOf(steps.first()) }
    PropertyRow(label) {
        NBtn(minus) { onMove(-step) }
        Text("$value $unit", color = C.Text, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).clickable(onClick = onReset).padding(vertical = 7.dp))
        NBtn(plus) { onMove(step) }
        Spacer(Modifier.weight(1f))
        Text("步進 $step", color = C.Primary, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Bg)
            .clickable { step = steps[(steps.indexOf(step) + 1) % steps.size] }.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

@Composable
private fun NBtn(t: String, f: () -> Unit) {
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(C.Bg).border(1.dp, C.Border, RoundedCornerShape(10.dp)).clickable(onClick = f),
        contentAlignment = Alignment.Center) { Text(t, color = C.Primary, fontSize = 14.sp) }
}

/** 閱讀對比：文字色對底色的對比值（WCAG），≥4.5 合格 */
fun contrastOf(fg: Color, bg: Color): Float {
    val a = fg.compositeOver(bg).luminance() + 0.05f; val b = bg.luminance() + 0.05f
    return if (a > b) a / b else b / a
}

@Composable
fun ContrastBadge(fg: Color, bg: Color, onFix: (() -> Unit)?) {
    val r = contrastOf(fg, bg)
    val ok = r >= 4.5f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text((if (ok) "✓ " else "⚠ ") + "對比 " + "%.1f".format(r) + ":1", color = if (ok) C.Green else C.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        if (!ok && onFix != null) Text("修正", color = C.Primary, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp))
            .background(C.Primary.copy(alpha = 0.12f)).clickable(onClick = onFix).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** 動態性格列：選擇後一次設定動畫速度與強度 */
@Composable
fun MotionRow(anim: Int, speed: Int, power: Int, set: (speed: Int, power: Int) -> Unit) {
    ChipsRow("動態性格", MOTION_PRESETS.map { (it.second to it.third) to it.first }, speed to power,
        sub = if (anim == 0) "尚未選動畫" else com.claudeetf.app.ui.TextFx.nameOf(anim)) { (s, p) -> set(s, p) }
}
