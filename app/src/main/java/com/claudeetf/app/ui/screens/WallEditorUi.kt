@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.WallItem
import com.claudeetf.app.data.WallStyle
import com.claudeetf.app.ui.theme.C

/**
 * 行情牆編輯工作階段：選取的項目、復原／重做、完整編輯工具（下方面板）的草稿與取消還原。
 */
object WallEdit {
    var sel by mutableStateOf<String?>(null)
    var sheet by mutableStateOf(false)
    val undoCount get() = Hist.wall.undoCount
    val redoCount get() = Hist.wall.redoCount

    fun select(id: String) { Hist.wall.begin(); sel = if (sel == id && !sheet) null else id }
    /** 每次修改都記一步（最多 50 步） */
    fun apply(f: (WallStyle) -> WallStyle) = Hist.wall.apply(f)
    fun undo() = Hist.wall.undo()
    fun redo() = Hist.wall.redo()
    fun openSheet() { Hist.wall.begin(); DeckTab.tab = 0; sheet = true }
    /** 完成：保留修改 */
    fun done() { Hist.wall.end(); sheet = false }
    /** 取消：還原到開始編輯前 */
    fun cancel() { Hist.wall.cancel(); sheet = false }
}

@Composable
fun WallUndoRedo() {
    @Composable fun b(t: String, on: Boolean, f: () -> Unit) =
        Text(t, color = if (on) C.Primary else C.Border, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = on, onClick = f).padding(horizontal = 8.dp, vertical = 3.dp))
    b("↶ 復原", WallEdit.undoCount > 0) { WallEdit.undo() }
    b("↷ 重做", WallEdit.redoCount > 0) { WallEdit.redo() }
}

/** 快速工具列：選取項目後出現，常用的字級、粗斜底線、顏色、對齊、動畫，✎ 叫出完整編輯工具 */
@Composable
fun WallQuickBar() {
    val id = WallEdit.sel ?: return
    val st = AppState.wallStyle
    val i = st.item(id)
    fun upd(f: (WallItem) -> WallItem) = WallEdit.apply { it.withItem(f(it.item(id))) }
    @Composable fun tb(label: String, on: Boolean = false, style: FontStyle? = null, deco: TextDecoration? = null, bold: Boolean = false, f: () -> Unit) {
        Box(Modifier.size(width = 38.dp, height = 36.dp).clip(RoundedCornerShape(9.dp)).background(if (on) C.Primary.copy(alpha = 0.18f) else Color.Transparent)
            .clickable(onClick = f), contentAlignment = Alignment.Center) {
            Text(label, color = if (on) C.Primary else C.Text, fontSize = 15.sp, fontStyle = style, textDecoration = deco,
                fontWeight = if (bold) FontWeight.Black else FontWeight.Medium)
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(C.Bg).border(1.dp, C.Border, RoundedCornerShape(14.dp)).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(WallStyle.NAMES[id] ?: id, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp).weight(1f), maxLines = 1)
            if (!i.show) Text("已隱藏", color = C.Muted, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
            Text("✎ 完整編輯", color = C.OnPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Primary).clickable { WallEdit.openSheet() }.padding(horizontal = 10.dp, vertical = 5.dp))
            Text("✕", color = C.Muted, fontSize = 15.sp, modifier = Modifier.clickable { WallEdit.sel = null }.padding(horizontal = 10.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            tb("A－") { upd { it.copy(size = (it.size - 1).coerceAtLeast(8)) } }
            Text("${i.size}", color = C.Text, fontSize = 13.sp, modifier = Modifier.widthIn(min = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            tb("A＋") { upd { it.copy(size = (it.size + 1).coerceAtMost(60)) } }
            Spacer(Modifier.width(4.dp))
            tb("B", i.bold, bold = true) { upd { it.copy(bold = !it.bold) } }
            tb("I", i.italic, style = FontStyle.Italic) { upd { it.copy(italic = !it.italic) } }
            tb("U", i.under, deco = TextDecoration.Underline) { upd { it.copy(under = !it.under) } }
            Spacer(Modifier.width(4.dp))
            // 顏色：依序切換（自動、漲跌、主文字、次文字、金色、主色、依等級）
            val colorCycle = listOf(0, 1, 2, 3, 4, 5) + (if (id in setOf("entry", "band", "rank", "kbar")) listOf(6) else emptyList())
            val sw = when (i.color) { 1 -> com.claudeetf.app.ui.theme.Trend.fg(1.0); 2 -> C.Text; 3 -> C.Muted; 4 -> Color(0xFFE6C34A); 5 -> C.Primary; 6 -> Color(0xFFF2B544); else -> C.Text2 }
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).clickable {
                upd { it.copy(color = colorCycle[(colorCycle.indexOf(it.color).coerceAtLeast(0) + 1) % colorCycle.size]) }
            }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(sw).border(1.dp, C.Border, RoundedCornerShape(6.dp)))
            }
            Spacer(Modifier.width(4.dp))
            tb("⇤", i.align == 0) { upd { it.copy(align = 0) } }
            tb("↔", i.align == 1) { upd { it.copy(align = 1) } }
            tb("⇥", i.align == 2) { upd { it.copy(align = 2) } }
            Spacer(Modifier.width(4.dp))
            // 欄寬：每次 ±5%（0＝自動）；「適」切換適寬字體
            tb("寬－", false) { upd { it.copy(bw = if (it.bw <= 10) 0 else it.bw - 5) } }
            Text(if (i.bw == 0) "自動" else "${i.bw}%", color = C.Text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 2.dp))
            tb("寬＋", false) { upd { it.copy(bw = if (it.bw == 0) 50 else (it.bw + 5).coerceAtMost(100)) } }
            tb("適", i.fit) { upd { it.copy(fit = !it.fit, bw = if (!it.fit && it.bw == 0 && it.bh == 0) 50 else it.bw) } }
            Spacer(Modifier.width(4.dp))
            // 動畫：快速切換常用款式（完整 16 款在 ✎）
            val quick = listOf(0, 2, 6, 3, 8, 7)
            tb(if (i.an == 0) "動畫" else com.claudeetf.app.ui.TextFx.nameOf(i.an), i.an != 0) {
                upd { it.copy(an = quick[(quick.indexOf(it.an).coerceAtLeast(0) + 1) % quick.size]) }
            }
            Spacer(Modifier.width(18.dp))
            tb(if (i.show) "👁" else "◌", !i.show) { upd { it.copy(show = !it.show) } }
        }
    }
}

/** 完整編輯工具：從下方滑出，上方仍看得到卡片；完成儲存、取消還原 */
@Composable
fun WallEditSheet() {
    if (!WallEdit.sheet) return
    val id = WallEdit.sel ?: run { WallEdit.sheet = false; return }
    val st = AppState.wallStyle
    val name = WallStyle.NAMES[id] ?: id
    val ai = AiSpec(name, listOf("欄寬 60% 適寬", "放大加粗", "拉開主次對比", "修正閱讀對比", "改成金色", "加上膠囊底色", "數字跳動時閃一下"),
        current = { AppState.wallStyle.item(id).toJson() }, schema = wallItemSchema(),
        apply = { p -> mergeWallItem(id, p) },
        cardCurrent = { org.json.JSONObject().also { o -> AppState.wallStyle.items.filter { it.show }.forEach { o.put(it.id, it.toJson()) } } },
        cardApply = { p ->
            val all = mutableListOf<String>()
            WallEdit.apply { s0 ->
                var s1 = s0
                s0.items.filter { it.show }.forEach { it0 ->
                    val pp = p.optJSONObject(it0.id) ?: return@forEach
                    val (n, ch) = patchWallItem(s1.item(it0.id), pp)
                    if (ch.isNotEmpty()) { all += ch; s1 = s1.withItem(n) }
                }
                s1
            }
            all.distinct()
        })
    DesignerPanel("持股行情牆", name, Hist.wall, AbSlots.Target.WALL, ai,
        onCopy = { StylePaint.wall = AppState.wallStyle.item(id) },
        onPaste = { StylePaint.wall?.let { c -> WallEdit.apply { s -> s.withItem(s.item(id).let { cur -> cur.copy(size = c.size, bold = c.bold, color = c.color, mono = c.mono, font = c.font,
            bg = c.bg, ba = c.ba, lc = c.lc, lh = c.lh, px = c.px, py = c.py, italic = c.italic, under = c.under, strike = c.strike, tnum = c.tnum,
            an = c.an, at = c.at, asp = c.asp, apw = c.apw, fit = c.fit, bh = c.bh) }) } } },
        canPaste = StylePaint.wall != null,
        onCancel = { WallEdit.cancel() }, onDone = { WallEdit.done() },
        top = {
            // 切換要編輯的項目（分頁保持不變）
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("☰ 項目", color = C.Muted, fontSize = 11.sp)
                st.items.filter { it.show || it.id == id }.forEach { o ->
                    val on = o.id == id
                    Text(WallStyle.NAMES[o.id] ?: o.id, color = if (on) C.OnPrimary else C.Text2, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) C.Primary else C.Bg).clickable { WallEdit.sel = o.id }
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
        }) {
        WallItemEditor(st.item(id), st) { f -> WallEdit.apply(f) }
    }
}

/** 行情牆項目給 AI 的欄位說明 */
fun wallItemSchema(): String = buildString {
    appendLine("z: 字級 8~60（sp）；b: 粗體 true/false；it/ul/sk/tn: 斜體/底線/刪除線/等寬數字")
    appendLine("c: 文字顏色 0 預設、1 依漲跌、2 主文字、3 次要灰、4 金色、5 主色、6 依等級")
    appendLine("bg: 底色 0 無、1 依漲跌、2 依等級、3 灰、4 金、5 主色、6 白、7 深色；ba: 底色濃度 10~100")
    appendLine("a: 對齊 0 靠左、1 置中、2 靠右；h: 放在標題列 true/false")
    appendLine("mx/my: 外距 -8~24 / -12~24；px/py: 內距 -10~20 / -10~16（dp）")
    appendLine("ox: 水平位移 -60~60；oy: 垂直位移 -40~40（dp）")
    appendLine("bw: 欄寬 0（自動）或 10~100（格寬 %）；bh: 欄高 0（自動）或 12~160（dp）；fit: 適寬字體 true/false（字級自動填滿欄寬／欄高）")
    appendLine("fl: 懸浮 true/false；fx/fy: 懸浮座標 0~100（%）；fa/fva: 基準點 0 左/上、1 中、2 右/下、3 自動")
    appendLine("an: 文字動畫 " + fxSchema())
    appendLine("at: 觸發 0 常駐、1 數值變動、2 上漲時、3 下跌時、4 進場時；asp: 速度 0~2；apw: 強度 0~2")
}

/** 把 AI 修改合併進行情牆項目（只接受允許欄位；範圍由 WallItem.fromJson 限制）；回傳新項目與改了哪些 */
fun patchWallItem(cur: WallItem, patch: org.json.JSONObject): Pair<WallItem, List<String>> {
    val names = linkedMapOf("z" to "字級", "b" to "粗體", "it" to "斜體", "ul" to "底線", "sk" to "刪除線", "tn" to "等寬數字",
        "c" to "文字顏色", "bg" to "底色", "ba" to "底色濃度", "a" to "對齊", "h" to "標題列", "mx" to "外距", "my" to "外距",
        "px" to "內距", "py" to "內距", "ox" to "水平位移", "oy" to "垂直位移", "fl" to "懸浮", "fx" to "X 座標", "fy" to "Y 座標",
        "fa" to "水平基準點", "fva" to "垂直基準點", "bw" to "欄寬", "bh" to "欄高", "fit" to "適寬字體",
        "an" to "動畫", "at" to "動畫觸發", "asp" to "動畫速度", "apw" to "動畫強度")
    val o = org.json.JSONObject()
    names.keys.forEach { k -> if (patch.has(k)) { val v = patch.get(k); if (v is Number || v is Boolean) o.put(k, v) } }
    var next = WallItem.fromJson(o, cur)
    // 適寬防呆：開啟適寬但未設欄寬與欄高時，自動設欄寬 50%
    if (next.fit && next.bw == 0 && next.bh == 0) next = next.copy(bw = 50)
    if (next == cur) return cur to emptyList()
    return next to names.filterKeys { o.has(it) }.values.distinct()
}

/** 套用 AI 修改到行情牆單一項目；記入復原歷史 */
fun mergeWallItem(id: String, patch: org.json.JSONObject): List<String> {
    val (next, ch) = patchWallItem(AppState.wallStyle.item(id), patch)
    if (ch.isNotEmpty()) WallEdit.apply { it.withItem(next) }
    return ch
}
