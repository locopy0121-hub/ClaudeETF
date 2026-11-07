package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.Market
import com.claudeetf.app.data.StockDirectory
import com.claudeetf.app.ui.HGap
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.theme.Mono
import com.claudeetf.app.ui.theme.C

private val Highlight = Color(0xFFF2B544)

/** 市（上市）／櫃（上櫃）／美（美股）方塊 */
@Composable
private fun BoardBadge(item: StockDirectory.Item) {
    val (t, c) = when {
        item.market == Market.US -> "美" to C.Us
        item.board == "櫃" -> "櫃" to Color(0xFF8E6BBF)
        else -> "市" to Color(0xFFB07A2A)
    }
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(c), contentAlignment = Alignment.Center) {
        Text(t, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** 文字中符合輸入的部分以黃色標示 */
private fun highlighted(text: String, q: String, base: Color) = buildAnnotatedString {
    val i = if (q.isEmpty()) -1 else text.indexOf(q, ignoreCase = true)
    if (i < 0) { withStyle(SpanStyle(color = base)) { append(text) }; return@buildAnnotatedString }
    withStyle(SpanStyle(color = base)) { append(text.substring(0, i)) }
    withStyle(SpanStyle(color = Highlight, fontWeight = FontWeight.Bold)) { append(text.substring(i, i + q.length)) }
    withStyle(SpanStyle(color = base)) { append(text.substring(i + q.length)) }
}

/**
 * 輸入時自動列出相關個股（停 0.3 秒後查詢）。
 * onPick：點整列；trailing：列尾自訂動作（例如 ＋ 加入自選、› 查看）。
 */
@Composable
fun StockSuggestions(
    query: String,
    maxHeight: Dp = 420.dp,
    allowRaw: Boolean = true,
    onPick: (StockDirectory.Item) -> Unit,
    trailing: @Composable RowScope.(StockDirectory.Item) -> Unit = {},
) {
    val q = query.trim()
    var hits by remember { mutableStateOf<List<StockDirectory.Item>>(emptyList()) }
    var looking by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.isEmpty()) { hits = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        looking = true
        hits = runCatching { StockDirectory.search(q) }.getOrDefault(emptyList())
        looking = false
    }
    if (q.isEmpty()) return
    val up = q.uppercase()
    // 目錄找不到、但看起來是代號：仍可直接使用
    val rows = if (allowRaw && !looking && hits.isEmpty() && up.all { it.isLetterOrDigit() || it == '.' })
        listOf(StockDirectory.Item(up, "（目錄查無名稱，仍可使用）", if (up.first().isDigit()) Market.TW else Market.US, "", if (up.first().isDigit()) "市" else "美"))
    else hits
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (looking && hits.isEmpty()) Label("搜尋中…", Modifier.padding(12.dp))
        rows.forEach { it ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Card)
                    .clickable { onPick(it) }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoardBadge(it); HGap(10.dp)
                Text(highlighted(it.symbol, up, C.Text), Modifier.widthIn(min = 70.dp), fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                HGap(8.dp)
                Text(highlighted(it.name, q, C.Text2), Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (it.tag.isNotEmpty()) { Label(it.tag); HGap(4.dp) }
                trailing(it)
            }
        }
        if (!looking && hits.size >= 30) Label("只列出前 30 筆，輸入更多字可縮小範圍", Modifier.padding(4.dp))
    }
}
