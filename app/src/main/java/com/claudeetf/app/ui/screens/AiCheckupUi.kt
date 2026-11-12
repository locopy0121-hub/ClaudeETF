package com.claudeetf.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.AiAnalysis
import com.claudeetf.app.data.AiProviders
import com.claudeetf.app.data.Quote
import com.claudeetf.app.data.StockMetrics
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.ui.theme.Trend
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.launch

private fun signalColor(s: String): Color = when (s) {
    "強勢多頭" -> Trend.fg(1.0)
    "多頭回檔" -> Color(0xFFFF8A65)
    "轉弱防守" -> Color(0xFF4FC3F7)
    "偏空警示" -> Trend.fg(-1.0)
    else -> Color(0xFFF2B544)
}

/** 個股頁 › 分析：AI 多維度體檢卡（本地指標＋Claude 解讀；無金鑰或失敗時顯示本地規則體檢） */
@Composable
fun AiCheckupCard(q0: Quote) {
    val q = AppState.quote(q0.symbol) ?: q0
    val scope = rememberCoroutineScope()
    val st = AiAnalysis.states[q.symbol]
    var tab by remember { mutableIntStateOf(0) }
    var showMetrics by remember { mutableStateOf(false) }
    LaunchedEffect(q.symbol) { if (st?.report == null) runCatching { AiAnalysis.load(q, useAi = AiAnalysis.hasAny()) } }
    Card(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI 多維度體檢", color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Label(when {
                    st?.loading == true -> "計算中…"
                    st?.report?.source == "ai" || st?.report?.source == "claude" -> "AI 解讀（" + st.report.model + "）· 資料日 " + (st.metrics?.date ?: "")
                    st?.report != null -> "本地規則體檢 · 資料日 " + (st.metrics?.date ?: "")
                    else -> "技術、K 棒、籌碼、估值四維度"
                })
            }
            Text(if (st?.loading == true) "分析中" else if (!AiAnalysis.hasAny()) "重新計算" else "AI 重新分析",
                color = if (st?.loading == true) C.Muted else C.Primary, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Bg).clickable(enabled = st?.loading != true) {
                    scope.launch { runCatching { AiAnalysis.load(q, useAi = AiAnalysis.hasAny(), force = true) } }
                }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        st?.fallback?.let { Text(it, color = Color(0xFFF2B544), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) }
        val r = st?.report
        if (r == null) { if (st?.loading != true) Label("尚無資料", Modifier.padding(top = 6.dp)); return@Card }
        val sc = signalColor(r.overallSignal)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(r.overallSignal, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(sc).padding(horizontal = 10.dp, vertical = 4.dp))
            HGap(10.dp)
            Text("信心 ${r.confidence}", color = sc, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(r.verdict, color = C.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Segmented(listOf("技術與K棒", "籌碼動向", "估值與風險"), tab, { tab = it })
        when (tab) {
            0 -> {
                KeyValue("趨勢", r.trend)
                Text(r.candleInsight, color = C.Text2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) { Label("支撐"); NumText(r.support?.let { Fmt.price(it) } ?: "—", 16.sp, Trend.fg(-1.0), FontWeight.Bold) }
                    Column(Modifier.weight(1f)) { Label("壓力"); NumText(r.resistance?.let { Fmt.price(it) } ?: "—", 16.sp, Trend.fg(1.0), FontWeight.Bold) }
                }
                st.metrics?.patterns?.takeIf { it.isNotEmpty() }?.let { ps ->
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ps.take(4).forEach { Text(it, color = C.Text, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(C.Bg).padding(horizontal = 6.dp, vertical = 2.dp)) }
                    }
                }
            }
            1 -> {
                KeyValue("籌碼狀態", r.chipStatus)
                Text(r.chipSummary, color = C.Text2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
            }
            else -> {
                KeyValue("估值位階", r.valuationStatus)
                Text(r.peerComparison, color = C.Text2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
                Text("風險提示", color = C.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                Text(r.riskWarning, color = if (r.riskWarning.startsWith("目前無")) C.Text2 else Trend.fg(-1.0), fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        // 本地指標（永遠可看，與 AI 無關）
        st.metrics?.let { m ->
            Text(if (showMetrics) "收合本地指標 ▲" else "查看本地計算指標 ▼", color = C.Primary, fontSize = 12.sp,
                modifier = Modifier.clickable { showMetrics = !showMetrics }.padding(top = 8.dp, bottom = 2.dp))
            if (showMetrics) {
                fun d(v: Double?, n: Int = 2) = v?.let { Fmt.num(it, n) } ?: "—"
                KeyValue("MA5／10／20／60", d(m.ma5) + "／" + d(m.ma10) + "／" + d(m.ma20) + "／" + d(m.ma60))
                KeyValue("20 日乖離率", m.bias20Pct?.let { "%+.2f".format(it) + "%" } ?: "—")
                KeyValue("RSI(14)", d(m.rsi14, 1))
                KeyValue("KD(9,3,3)", d(m.k, 1) + "／" + d(m.d, 1))
                KeyValue("5 日均量比", m.volumeRatioToMa5?.let { "%.2f".format(it) + " 倍" } ?: "—")
                KeyValue("量價", m.priceVolumeState + if (m.isNewHigh20) "（創 20 日新高）" else "")
                if (m.foreignNet5Lots != null) KeyValue("近 5 日 外資／投信", "%+,d".format(m.foreignNet5Lots) + "／" + "%+,d".format(m.trustNet5Lots ?: 0) + " 張")
                if (m.trustConsecutiveDays != null) KeyValue("投信連買", "${m.trustConsecutiveDays} 日")
                KeyValue("本益比／淨值比／殖利率", d(m.pe, 1) + "／" + d(m.pb) + "／" + (m.yieldPct?.let { "%.2f".format(it) + "%" } ?: "—"))
            }
        }
        Label("數字全部由 App 本地計算；AI 只負責解讀，不推估任何價格。僅供參考，非投資建議。", Modifier.padding(top = 6.dp))
    }
}

/** 設置 › 分析與提醒 › AI 個股體檢：多家 AI 服務（Agnes AI 優先），各自金鑰、模型、啟用與測試 */
@Composable
fun AiSettings() {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>("agnes") }
    val testMsg = remember { mutableStateMapOf<String, String>() }
    Column(Modifier.fillMaxWidth()) {
    Card(padding = 14.dp) {
        Text("AI 個股體檢", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Label("個股頁 › 分析 的「AI 多維度體檢」：指標全部在手機計算，只把算好的數字交給 AI 解讀。可設定多家服務，依下列順序使用（Agnes AI 優先），前一家失敗自動改用下一家；都沒設定時用本地規則體檢。")
        val chain = AiProviders.chain()
        Text(if (chain.isEmpty()) "目前：本地規則體檢（未設定任何 AI 服務）" else "目前使用順序：" + chain.joinToString(" → ") { it.name },
            color = if (chain.isEmpty()) C.Muted else C.Primary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
    AiProviders.ALL.forEachIndexed { idx, p ->
        val on = open == p.id
        val has = AppState.aiKey(p.id).isNotBlank()
        val enabled = AppState.aiEnabled[p.id] != false
        Card(padding = 12.dp, modifier = Modifier.padding(top = 8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = if (on) null else p.id }, verticalAlignment = Alignment.CenterVertically) {
                Text("${idx + 1}", color = C.Muted, fontSize = 12.sp, modifier = Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name + if (idx == 0) "（優先）" else "", color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Label(if (!has) "未設定金鑰" else if (!enabled) "已停用" else "使用中 · " + AiProviders.modelOf(p))
                }
                if (has) Text(if (enabled) "●" else "○", color = if (enabled) C.Green else C.Muted, fontSize = 14.sp)
                HGap(8.dp); Text(if (on) "▲" else "▼", color = C.Muted, fontSize = 12.sp)
            }
            if (on) {
                var key by remember(p.id) { mutableStateOf(AppState.aiKey(p.id)) }
                var model by remember(p.id) { mutableStateOf(AiProviders.modelOf(p)) }
                var url by remember(p.id) { mutableStateOf(AppState.aiCustomUrl) }
                if (p.id == "custom") OutlinedTextField(url, { url = it; AppState.updateAiCustomUrl(it) }, singleLine = true,
                    label = { Text("API 網址（例如 https://xxx/v1）") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = fieldColors())
                OutlinedTextField(key, { key = it; AppState.updateAi(p.id, key = it) }, singleLine = true,
                    label = { Text("API 金鑰（" + p.keyHint + "）") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = fieldColors())
                OutlinedTextField(model, { model = it; AppState.updateAi(p.id, model = it) }, singleLine = true,
                    label = { Text("模型") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), colors = fieldColors())
                if (p.models.isNotEmpty()) Row(Modifier.padding(top = 6.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.models.forEach { mid ->
                        Text(mid, color = if (model == mid) C.OnPrimary else C.Text2, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (model == mid) C.Primary else C.Bg).clickable { model = mid; AppState.updateAi(p.id, model = mid) }
                            .padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                OptToggle("啟用", enabled) { AppState.updateAi(p.id, enabled = it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("測試連線", color = if (has) C.Primary else C.Muted, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Bg)
                        .clickable(enabled = has) { testMsg[p.id] = "測試中…"; scope.launch { testMsg[p.id] = AiAnalysis.test(p) } }
                        .padding(horizontal = 10.dp, vertical = 6.dp))
                    HGap(8.dp)
                    testMsg[p.id]?.let { Text(it, Modifier.weight(1f), color = if (it.startsWith("連線成功")) C.Green else C.Text2, fontSize = 12.sp) }
                }
                if (p.site.isNotBlank()) Label("申請金鑰：" + p.site)
            }
        }
    }
    Label("金鑰以手機安全晶片加密保存。同一檔同一交易日只呼叫一次（結果快取），按「AI 重新分析」才會再呼叫。Claude 啟用 Prompt Caching；其他服務使用 OpenAI 相容介面。", Modifier.padding(top = 8.dp))
    }
}
