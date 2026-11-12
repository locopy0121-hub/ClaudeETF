package com.claudeetf.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.PriceBand
import com.claudeetf.app.data.TwPicks
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt

/** 推薦清單的解析段落（標題＋逐條內容），App 卡片與匯出共用 */
object PickText {
    data class Section(val title: String, val lines: List<String>)

    /** 以句號／分號切成獨立條目，去除空白與重複 */
    fun split(s: String): List<String> = s.split('。', '；', '\n')
        .map { it.trim().trimEnd('，', '、') }.filter { it.isNotBlank() }.distinct()

    fun sections(p: TwPicks.Pick): List<Section> {
        val out = ArrayList<Section>()
        if (p.reasons.isNotEmpty()) out += Section("分析", p.reasons.flatMap { split(it) }.distinct())
        val adv = split(p.entryReason)
        out += Section("建議", listOf(p.entry + "（K 線" + p.dir + "）") + adv)
        val ops = buildList {
            p.stop?.let { add("停損 " + Fmt.price(it)) }
            p.tp1?.let { add("第一目標 " + Fmt.price(it)) }
            p.tp2?.let { add("第二目標 " + Fmt.price(it)) }
            p.ma60Gap?.let { add("距 60 日均線 " + "%+.1f".format(it) + "%") }
        }
        if (ops.isNotEmpty()) out += Section("操作參考", ops)
        p.signal?.let { sg -> split(sg).filter { it !in adv }.takeIf { it.isNotEmpty() }?.let { out += Section("策略訊號", it) } }
        return out
    }

    /** 一句話摘要（收合時顯示）：K 線方向＋最重要的兩項理由 */
    fun summary(p: TwPicks.Pick): String = p.reasons.flatMap { split(it) }.take(2).joinToString("・")
}

/**
 * 推薦排行卡片：收合時只顯示排名、完整名稱、價格與一句摘要；點一下展開段落分明的完整解析，
 * 展開後可再進入個股頁。上市／上櫃、ETF 與每日盤後報表共用。
 */
@Composable
fun PickCard(
    rank: Int, p: TwPicks.Pick, bull: Boolean,
    showBoard: Boolean = true,
    flat: Boolean = false,
    tags: @Composable RowScope.() -> Unit = {},
    onOpen: () -> Unit,
) {
    var open by rememberSaveable(p.symbol, bull) { mutableStateOf(false) }
    val tone = Trend.fg(if (bull) 1.0 else -1.0)
    val shape = RoundedCornerShape(12.dp)
    Column(
        (if (flat) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().clip(shape).background(C.Card))
            .clickable { open = !open }.padding(horizontal = if (flat) 0.dp else 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // 排名徽章（前三名實心）
            Text(rank.toString(), color = if (rank <= 3) Color.White else tone, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp).size(28.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (rank <= 3) tone else tone.copy(alpha = 0.15f)).wrapContentSize(Alignment.Center))
            HGap(10.dp)
            Column(Modifier.weight(1f)) {
                // 名稱完整顯示，不截斷（過長自動換行）
                Text(p.name, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    NumText(p.symbol, 12.sp, C.Muted)
                    if (showBoard) { HGap(4.dp); Label(if (p.otc) "上櫃" else "上市") }
                    if (p.zone in PriceBand.zoneNames.indices) {
                        HGap(6.dp)
                        val zc = zoneColor(p.zone)
                        Text(PriceBand.zoneNames[p.zone], color = zc, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(zc.copy(alpha = 0.15f)).padding(horizontal = 4.dp))
                    }
                    tags()
                }
            }
            HGap(8.dp)
            val lim = if (p.pct > -9.0 && p.pct < 9.0) 0 else limitDir(p.price, p.price / (1 + p.pct / 100), p.symbol)
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lim != 0) { LimitTag(lim, 10.sp); HGap(4.dp) }
                    LimitPrice(Fmt.price(p.price), 15.sp, C.Text, lim, FontWeight.Normal)
                }
                ChangePill(p.pct)
            }
        }
        // 建議＋分數＋一句摘要
        Row(Modifier.padding(start = 38.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(p.entry, color = tone, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(tone.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp))
            HGap(6.dp)
            Text("分數 " + "%+.1f".format(p.score), color = tone, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(if (open) "收合 ▴" else "詳情 ▾", color = C.Muted, fontSize = 12.sp)
        }
        if (!open) {
            val sum = PickText.summary(p)
            if (sum.isNotBlank()) Text(sum, color = C.Text2, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = 38.dp, top = 4.dp))
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 38.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PickText.sections(p).forEach { s -> PickSection(s, tone) }
                Text("查看個股 ›", color = C.Primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.Selected).clickable(onClick = onOpen)
                        .padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
fun PickSection(s: PickText.Section, tone: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 3.dp, height = 13.dp).clip(RoundedCornerShape(2.dp)).background(tone))
            HGap(6.dp)
            Text(s.title, color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        if (s.title == "操作參考") {
            // 價位用小方塊並排，一眼看清
            FlowRowCompat(s.lines)
        } else s.lines.forEach { line ->
            Row(Modifier.padding(start = 9.dp)) {
                Text("•", color = C.Muted, fontSize = 13.sp, lineHeight = 19.sp)
                HGap(6.dp)
                Text(line, color = C.Text2, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(items: List<String>) {
    FlowRow(Modifier.padding(start = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach {
            Text(it, color = C.Text, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.Selected).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}
