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
    private val undo = ArrayDeque<WallStyle>()
    private val redo = ArrayDeque<WallStyle>()
    var undoCount by mutableIntStateOf(0); private set
    var redoCount by mutableIntStateOf(0); private set
    private var snapshot: WallStyle? = null

    fun select(id: String) { sel = if (sel == id && !sheet) null else id }

    /** 每次修改都記一步（最多 50 步） */
    fun apply(f: (WallStyle) -> WallStyle) {
        val cur = AppState.wallStyle
        val next = f(cur)
        if (next == cur) return
        undo.addLast(cur); if (undo.size > 50) undo.removeFirst()
        redo.clear()
        AppState.updateWallStyle(next)
        sync()
    }
    fun undo() { val p = undo.removeLastOrNull() ?: return; redo.addLast(AppState.wallStyle); AppState.updateWallStyle(p); sync() }
    fun redo() { val n = redo.removeLastOrNull() ?: return; undo.addLast(AppState.wallStyle); AppState.updateWallStyle(n); sync() }
    private fun sync() { undoCount = undo.size; redoCount = redo.size }

    fun openSheet() { snapshot = AppState.wallStyle; sheet = true }
    /** 完成：保留修改 */
    fun done() { snapshot = null; sheet = false }
    /** 取消：還原到開啟面板前 */
    fun cancel() { snapshot?.let { if (it != AppState.wallStyle) apply { _ -> it } }; snapshot = null; sheet = false }
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
    DockedPanel(onDismiss = { WallEdit.done() }) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("取消", color = C.Text2, fontSize = 15.sp, modifier = Modifier.clickable { WallEdit.cancel() }.padding(vertical = 8.dp, horizontal = 4.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("編輯工具・" + (WallStyle.NAMES[id] ?: id), color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("持股行情牆 · 套用到全部格子", color = C.Muted, fontSize = 11.sp)
                }
                Text("完成", color = C.OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Primary).clickable { WallEdit.done() }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                WallUndoRedo()
                Spacer(Modifier.weight(1f))
                Text("切換項目 ›", color = C.Muted, fontSize = 11.sp)
            }
            // 切換要編輯的項目（獨立一列，分頁保持不變）
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                st.items.filter { it.show || it.id == id }.forEach { o ->
                    val on = o.id == id
                    Text(WallStyle.NAMES[o.id] ?: o.id, color = if (on) C.OnPrimary else C.Text2, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) C.Primary else C.Bg).clickable { WallEdit.sel = o.id }
                            .padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 56.dp)) {
                WallItemEditor(st.item(id), st) { f -> WallEdit.apply(f) }
            }
        }
    }
}
