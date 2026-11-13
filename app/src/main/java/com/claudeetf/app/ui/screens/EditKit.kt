package com.claudeetf.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.ui.theme.C

/**
 * 共用編輯元件：
 * - [CollapsibleItem]：項目清單的一列（名稱｜顯示／隱藏｜↑｜↓），點名稱才展開該項目的細部設定
 * - [ItemEditSheet]：點預覽中的項目時，從下方滑出的編輯工具
 */
@Composable
fun CollapsibleItem(
    name: String,
    shown: Boolean,
    expanded: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onName: () -> Unit,
    onShow: (Boolean) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    summary: String? = null,
    detail: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(shape).background(C.Card)
        .border(1.dp, if (expanded) C.Primary.copy(alpha = 0.6f) else C.Border, shape).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(name + if (expanded) " ▴" else "", on = expanded, strong = true, onClick = onName)
            Pill(if (shown) "顯示" else "隱藏", on = shown) { onShow(!shown) }
            if (shown) {
                Pill("↑", enabled = canUp, onClick = onUp)
                Pill("↓", enabled = canDown, onClick = onDown)
            }
            if (summary != null && !expanded) Text(summary, color = C.Muted, fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(C.Border))
                Text("單項細部：$name", color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                detail()
            }
        }
    }
}

@Composable
private fun Pill(text: String, on: Boolean = false, strong: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.heightIn(min = 34.dp).clip(shape).background(if (on) C.Primary.copy(alpha = 0.12f) else Color.Transparent)
        .border(1.dp, if (on) C.Primary else C.Border, shape).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (!enabled) C.Border else if (on || !strong) C.Primary else C.Text, fontSize = 13.sp,
            fontWeight = if (on || strong) FontWeight.Bold else FontWeight.Medium)
    }
}

/** 預覽區的下緣（視窗座標，px）：編輯工具面板頂端停在這裡，不會蓋住預覽 */
object PreviewEdge {
    var bottom by mutableFloatStateOf(0f)
}

/** 加在預覽外框上，記錄預覽下緣位置 */
fun Modifier.trackPreviewEdge(): Modifier = onGloballyPositioned { PreviewEdge.bottom = it.boundsInWindow().bottom }

/**
 * 停靠在預覽下方的編輯面板：頂端對齊預覽區最底限，高度延伸到畫面底部；
 * 不遮住預覽，可以一邊點預覽切換項目、一邊看修改結果。返回鍵等同完成。
 */
@Composable
fun DockedPanel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onDismiss)
    val view = LocalView.current
    val density = LocalDensity.current
    val rootH = view.rootView.height.toFloat().coerceAtLeast(1f)
    val gap = with(density) { 6.dp.toPx() }
    // 預覽位置未知或太低時，至少保留畫面 45% 給面板
    val top = (if (PreviewEdge.bottom > 0f) PreviewEdge.bottom + gap else rootH * 0.4f).coerceIn(rootH * 0.15f, rootH * 0.55f)
    val heightDp = with(density) { (rootH - top).toDp() }
    val provider = remember(top) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset(0, top.toInt())
        }
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Column(Modifier.fillMaxWidth().height(heightDp)
            .shadow(16.dp, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(C.Card)
            .border(1.dp, C.Border, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .padding(top = 8.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(C.Border))
            content()
        }
    }
}

/** 從預覽點項目叫出的編輯工具：停靠在預覽下方；按完成收起 */
@Composable
fun ItemEditSheet(title: String, subtitle: String, onDone: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DockedPanel(onDismiss = onDone) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("編輯工具・$title", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle, color = C.Muted, fontSize = 11.sp)
                }
                Text("完成", color = C.OnPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Primary).clickable(onClick = onDone).padding(horizontal = 14.dp, vertical = 7.dp))
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 56.dp), content = content)
        }
    }
}
