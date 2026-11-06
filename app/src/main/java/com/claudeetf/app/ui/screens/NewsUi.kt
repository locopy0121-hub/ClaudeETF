package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.claudeetf.app.data.remote.RssNews
import com.claudeetf.app.data.remote.YahooExtra
import com.claudeetf.app.data.remote.friendly
import com.claudeetf.app.ui.Card
import com.claudeetf.app.ui.Label
import com.claudeetf.app.ui.theme.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun newsTime(t: Long): String =
    if (t <= 0) "" else Instant.ofEpochMilli(t).atZone(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("M/d HH:mm"))

/** 新聞列：標題、來源時間、RSS 摘要兩行；點擊在 App 內閱讀全文 */
@Composable
fun NewsItem(n: YahooExtra.News, onOpen: (YahooExtra.News) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card)
            .clickable { onOpen(n) }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(n.title, color = C.Text, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (n.summary.isNotBlank()) Text(n.summary, color = C.Text2, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Label(listOf(n.publisher, newsTime(n.time)).filter { it.isNotBlank() }.joinToString(" · "))
    }
}

/** App 內閱讀：RSS 摘要先顯示，再載入文章段落純文字（不開外部瀏覽器） */
@Composable
fun NewsReader(n: YahooExtra.News, onClose: () -> Unit) {
    var paras by remember(n.link) { mutableStateOf<List<String>?>(null) }
    var err by remember(n.link) { mutableStateOf<String?>(null) }
    LaunchedEffect(n.link) {
        runCatching { withContext(Dispatchers.IO) { RssNews.article(n.link) } }
            .onSuccess { paras = it }.onFailure { err = it.friendly() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // 全螢幕閱讀：避開狀態列與底部導覽列，最後一段才能完整捲到
        Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(listOf(n.publisher, newsTime(n.time)).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "關閉", tint = C.Text) }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(n.title, color = C.Text, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold) }
                val body = paras
                when {
                    body != null -> {
                        items(body) { p -> Text(p, color = C.Text, fontSize = 16.sp, lineHeight = 26.sp) }
                        item { Label("— 全文完 —", Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).padding(top = 8.dp)) }
                    }
                    else -> {
                        if (n.summary.isNotBlank()) item { Text(n.summary, color = C.Text, fontSize = 16.sp, lineHeight = 26.sp) }
                        item { Label(err?.let { "無法載入全文（$it），以上為 RSS 摘要" } ?: "載入全文中…") }
                    }
                }
            }
        }
    }
}

/** 首頁卡片：Yahoo股市 RSS 分類新聞 */
@Composable
fun NewsCard() {
    var cat by remember { mutableStateOf(RssNews.categories.first().first) }
    var list by remember(cat) { mutableStateOf<List<YahooExtra.News>?>(null) }
    var open by remember { mutableStateOf<YahooExtra.News?>(null) }
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(cat) {
        while (true) {
            list = runCatching { withContext(Dispatchers.IO) { RssNews.category(cat) } }.getOrDefault(list ?: emptyList())
            kotlinx.coroutines.delay(10 * 60_000L)
        }
    }
    open?.let { NewsReader(it) { open = null } }
    Card {
        Text("財經新聞", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RssNews.categories.forEach { (k, name) ->
                val on = k == cat
                Text(name, color = if (on) C.Text else C.Muted, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) C.Selected else C.Bg)
                        .clickable { cat = k }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
        val l = list
        when {
            l == null -> Label("載入中…")
            l.isEmpty() -> Label("目前沒有新聞")
            else -> l.take(if (showAll) 20 else 5).forEach { n ->
                Column(Modifier.fillMaxWidth().clickable { open = n }.padding(vertical = 6.dp)) {
                    Text(n.title, color = C.Text, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Label(listOf(n.publisher, newsTime(n.time)).filter { it.isNotBlank() }.joinToString(" · "))
                }
            }
        }
        if ((l?.size ?: 0) > 5) Text(if (showAll) "收合" else "更多 ›", color = C.Primary, fontSize = 12.sp,
            modifier = Modifier.clickable { showAll = !showAll }.padding(vertical = 4.dp))
        Label("來源：Yahoo股市 RSS，點標題在 App 內閱讀")
    }
}
