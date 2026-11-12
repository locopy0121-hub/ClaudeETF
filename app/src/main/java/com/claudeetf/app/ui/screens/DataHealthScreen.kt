package com.claudeetf.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.remote.*
import com.claudeetf.app.ui.*
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 資料來源診斷：逐一實際連線各資料來源，顯示是否可用、耗時、資料日期與範例數值，
 * 並比對收盤資料是否落後最近交易日。用來確認資料正確性與排查「抓不到資料」。
 */
object DataHealth {
    enum class Level { OK, WARN, FAIL, SKIP }
    data class Result(val name: String, val source: String, val level: Level, val detail: String, val ms: Long)
    private class Check(val name: String, val source: String, val run: suspend () -> Pair<Level, String>)

    private val TPE = ZoneId.of("Asia/Taipei")

    /** 最近一個「應已有收盤資料」的交易日：平日 14:30 後為今天，否則往前找平日（不含國定假日） */
    fun expectedCloseDate(now: ZonedDateTime = ZonedDateTime.now(TPE)): LocalDate {
        var d = if (now.toLocalTime() >= LocalTime.of(14, 30)) now.toLocalDate() else now.toLocalDate().minusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.minusDays(1)
        return d
    }

    /** 收盤資料日期判斷：等於最近交易日 OK；落後 1～3 天可能是假日（警示）；更久為失敗 */
    private fun dateLevel(d: LocalDate?): Pair<Level, String> {
        if (d == null) return Level.WARN to "無日期"
        val exp = expectedCloseDate()
        val lag = java.time.temporal.ChronoUnit.DAYS.between(d, exp)
        val txt = d.toString().replace("-", "/")
        return when {
            lag <= 0 -> Level.OK to txt
            lag <= 3 -> Level.WARN to "$txt（最近交易日應為 ${exp.toString().replace("-", "/")}，若遇假日屬正常）"
            else -> Level.FAIL to "$txt（落後 $lag 天）"
        }
    }

    private fun quoteText(q: RemoteQuote?): String = q?.let {
        (it.name ?: it.symbol) + " " + Fmt.price(it.price) + "（昨收 " + Fmt.price(it.prevClose) + "）"
    } ?: "找不到範例個股"

    private fun closeCheck(all: Map<String, RemoteQuote>, sample: String): Pair<Level, String> {
        if (all.isEmpty()) return Level.FAIL to "沒有資料"
        val q = all[sample]
        val (lv, dt) = dateLevel(q?.date ?: all.values.firstOrNull()?.date)
        return lv to "${all.size} 檔 · 資料日 $dt · ${quoteText(q)}"
    }

    private fun checks(): List<Check> = listOf(
        Check("上市收盤（OpenAPI）", "證交所 openapi.twse.com.tw") { closeCheck(TwseApi.dayAll(), "2330") },
        Check("上市收盤（當日 MI_INDEX）", "證交所 www.twse.com.tw") { closeCheck(TwseApi.latestAll(), "2330") },
        Check("上櫃收盤（OpenAPI）", "櫃買中心 tpex.org.tw/openapi") { closeCheck(TpexApi.dayAll(), "6488") },
        Check("上櫃收盤（當日網站）", "櫃買中心 tpex.org.tw/www") {
            val d = expectedCloseDate()
            val m = TpexApi.dailyClose(d)
            if (m.isEmpty()) Level.WARN to "${d.toString().replace("-", "/")} 尚無資料（App 會自動改用 OpenAPI）" else closeCheck(m, "6488")
        },
        Check("盤中即時（MIS）", "證交所 mis.twse.com.tw") {
            val t = TwseMisApi.fetch(listOf("tse_t00.tw", "tse_2330.tw", "otc_6488.tw"))
            if (t.isEmpty()) Level.FAIL to "沒有回應資料"
            else Level.OK to t.values.joinToString("；") { (it.quote.name ?: it.quote.symbol) + " " + Fmt.price(it.quote.price) } +
                " · " + java.time.Instant.ofEpochMilli(t.values.maxOf { it.time }).atZone(TPE).toLocalTime().withNano(0)
        },
        Check("富果即時（REST）", "富果 api.fugle.tw") {
            val k = AppState.fugleKey
            if (k.isBlank()) Level.SKIP to "未設定富果金鑰"
            else FugleApi.quote("2330", k).let { Level.OK to quoteText(it) }
        },
        Check("富果串流", "富果 WebSocket") {
            if (AppState.fugleKey.isBlank()) Level.SKIP to "未設定富果金鑰"
            else if (FugleStream.connected) Level.OK to "已連線 · " + FugleStream.stats()
            else Level.WARN to "未連線" + (AppState.streamStatus.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "（非盤中或尚未啟動屬正常）")
        },
        Check("三大法人", "證交所 T86") {
            val m = TwInstitutional.latest()
            if (m.isEmpty()) Level.FAIL to "沒有資料"
            else { val d = m["2330"] ?: m.values.first(); val (lv, dt) = dateLevel(d.date)
                lv to "${m.size} 檔 · 資料日 $dt · 2330 外資 ${Fmt.num(d.foreign / 1000.0, 0)} 張" }
        },
        Check("本益比／殖利率", "證交所 BWIBBU") {
            val m = TwOpenData.peAll(); if (m.isEmpty()) Level.FAIL to "沒有資料" else Level.OK to "${m.size} 檔 · 2330 本益比 ${m["2330"]?.let { Fmt.num(it, 2) } ?: "—"}"
        },
        Check("融資融券", "證交所 MI_MARGN") {
            val m = TwOpenData.marginAll(); if (m.isEmpty()) Level.FAIL to "沒有資料" else Level.OK to "${m.size} 檔"
        },
        Check("類股指數", "證交所 OpenAPI") {
            val l = TwOpenData.sectorIndices(); if (l.isEmpty()) Level.FAIL to "沒有資料" else Level.OK to "${l.size} 類 · " + l.take(2).joinToString("、") { it.first + " " + "%+.2f".format(it.second) + "%" }
        },
        Check("證券代號表（ISIN）", "證交所 isin.twse.com.tw") {
            val l = TwIsin.fetch(2); if (l.size < 500) Level.FAIL to "只取得 ${l.size} 筆" else Level.OK to "上市 ${l.size} 筆 · 例：" + (l.firstOrNull { it.code == "2330" }?.let { it.code + " " + it.name } ?: l.first().let { it.code + " " + it.name })
        },
        Check("集保股權分散", "集保 opendata.tdcc.com.tw") {
            val m = TdccOpenData.all(); if (m.isEmpty()) Level.FAIL to "沒有資料" else Level.OK to "${m.size} 檔"
        },
        Check("台股日 K（Yahoo）", "Yahoo Finance") {
            val h = YahooExtra.history("2330.TW", "1mo"); if (h.closes.isEmpty()) Level.FAIL to "沒有 K 線"
            else { val d = java.time.Instant.ofEpochMilli(h.times.last()).atZone(TPE).toLocalDate(); val (lv, dt) = dateLevel(d)
                lv to "${h.closes.size} 根 · 最後一根 $dt · 收 ${Fmt.price(h.closes.last().toDouble())}" }
        },
        Check("美股報價（Yahoo）", "Yahoo Finance") {
            val c = YahooApi.chart("AAPL"); Level.OK to quoteText(c.quote)
        },
        Check("台銀匯率", "臺灣銀行牌告") {
            val f = BotFxApi.usd(); Level.OK to "美元即期 買 ${Fmt.num(f.spotBuy, 3)}／賣 ${Fmt.num(f.spotSell, 3)}"
        },
        Check("新聞", "Yahoo 股市 RSS") {
            val n = YahooExtra.news("2330.TW"); if (n.isEmpty()) Level.WARN to "沒有新聞" else Level.OK to "${n.size} 則 · 最新「${n.first().title.take(18)}」"
        },
    )

    suspend fun runAll(onEach: (Result) -> Unit) = coroutineScope {
        checks().map { c ->
            async(Dispatchers.IO) {
                val t0 = System.currentTimeMillis()
                val r = runCatching { withTimeout(30_000) { c.run() } }
                val ms = System.currentTimeMillis() - t0
                val res = r.fold({ Result(c.name, c.source, it.first, it.second, ms) }, { e ->
                    if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                    val msg = if (e is kotlinx.coroutines.TimeoutCancellationException) "逾時（30 秒）" else e.friendly()
                    com.claudeetf.app.util.ErrorLog.record("診斷・" + c.name, msg)
                    Result(c.name, c.source, Level.FAIL, msg, ms)
                })
                withContext(Dispatchers.Main) { onEach(res) }
            }
        }.awaitAll()
    }

    fun names(): List<Pair<String, String>> = checks().map { it.name to it.source }
}

@Composable
fun DataHealthScreen(nav: NavController) {
    val ctx = LocalContext.current
    val results = remember { mutableStateMapOf<String, DataHealth.Result>() }
    var run by remember { mutableIntStateOf(1) }
    var running by remember { mutableStateOf(false) }
    val list = remember { DataHealth.names() }
    LaunchedEffect(run) {
        results.clear(); running = true
        runCatching { DataHealth.runAll { results[it.name] = it } }
        running = false
    }
    val ok = results.values.count { it.level == DataHealth.Level.OK }
    val warn = results.values.count { it.level == DataHealth.Level.WARN }
    val fail = results.values.count { it.level == DataHealth.Level.FAIL }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        TopBar("資料來源診斷", onBack = { nav.popBackStack() }) {
            Text(if (running) "檢查中…" else "重新檢查", color = if (running) C.Muted else C.Primary, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !running) { run++ }.padding(12.dp))
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正常 $ok", color = Color(0xFF3A9B4A), fontSize = 15.sp, fontWeight = FontWeight.Bold); HGap(14.dp)
                        Text("注意 $warn", color = Color(0xFFE3A21B), fontSize = 15.sp, fontWeight = FontWeight.Bold); HGap(14.dp)
                        Text("失敗 $fail", color = C.Red, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Label("${results.size}／${list.size}")
                    }
                    Label("最近交易日（應有收盤資料）：" + DataHealth.expectedCloseDate().toString().replace("-", "/") + "（以台灣時間計，不含國定假日判斷）")
                    if (!running && results.isNotEmpty()) Text("分享診斷結果 ›", color = C.Primary, fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            val ver = com.claudeetf.app.BuildConfigInfo.versionName(ctx)
                            val body = "台美股資產 $ver 資料來源診斷 " + java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Taipei")).toLocalDateTime().withNano(0) + "\n\n" +
                                list.mapNotNull { (n, _) -> results[n] }.joinToString("\n") { r ->
                                    (when (r.level) { DataHealth.Level.OK -> "✅"; DataHealth.Level.WARN -> "⚠️"; DataHealth.Level.FAIL -> "❌"; DataHealth.Level.SKIP -> "⏭" }) +
                                        " " + r.name + "（" + r.ms + " ms）：" + r.detail
                                }
                            runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body), "分享診斷結果")) }
                        }.padding(vertical = 4.dp))
                }
            }
            items(list, key = { it.first }) { (name, source) ->
                val r = results[name]
                val (dot, col) = when (r?.level) {
                    null -> "…" to C.Muted
                    DataHealth.Level.OK -> "●" to Color(0xFF3A9B4A)
                    DataHealth.Level.WARN -> "●" to Color(0xFFE3A21B)
                    DataHealth.Level.FAIL -> "●" to C.Red
                    DataHealth.Level.SKIP -> "○" to C.Muted
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(dot, color = col, fontSize = 14.sp); HGap(8.dp)
                        Text(name, color = C.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (r != null) Label("${r.ms} ms")
                    }
                    Label(source, Modifier.padding(start = 22.dp))
                    Text(r?.detail ?: "檢查中…", color = if (r?.level == DataHealth.Level.FAIL) C.Red else C.Text2, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.padding(start = 22.dp, top = 2.dp))
                }
            }
            item { Label("診斷只讀取公開資料，不會修改任何設定。失敗的項目會同時記到「錯誤紀錄 › 載入錯誤」。") }
        }
    }
}
