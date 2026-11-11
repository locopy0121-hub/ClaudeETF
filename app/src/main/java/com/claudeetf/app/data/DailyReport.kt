package com.claudeetf.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.claudeetf.app.data.remote.RemoteQuote
import com.claudeetf.app.data.remote.TpexApi
import com.claudeetf.app.data.remote.TwOpenData
import com.claudeetf.app.data.remote.TwseApi
import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 台股每日盤後報表：收盤後自動彙整市場狀況（加權／櫃買指數、漲跌家數、漲跌停、成交值、漲跌幅分布、類股、排行），
 * 加上綜合分析的看漲、看跌各 25 檔，存成一天一份（保留 120 天），首頁卡片與報表頁顯示。
 *
 * 資料基準：證交所、櫃買中心當日收盤行情（全部上市櫃證券）；指數走勢取 Yahoo 近 3 個月日線；
 * 類股取證交所類股指數；推薦排行取上市成交值前 120 檔＋上櫃前 100 檔個股的綜合分析（日 K、60 日均線、20 日動能、進場等級、一年光譜）。
 */
object DailyReport {
    data class Mover(val symbol: String, val name: String, val otc: Boolean, val price: Double, val pct: Double, val valueYi: Double) {
        fun toJson(): JSONObject = JSONObject().put("s", symbol).put("n", name).put("o", otc).put("p", price).put("c", pct).put("v", valueYi)
        companion object { fun of(o: JSONObject) = Mover(o.optString("s"), o.optString("n"), o.optBoolean("o"), o.optDouble("p"), o.optDouble("c"), o.optDouble("v")) }
    }

    data class Report(
        val date: String,
        val at: Long,
        val twii: Double, val twiiChg: Double, val twiiPct: Double, val twiiSeries: List<Double>,
        val otcIdx: Double?, val otcPct: Double?,
        val up: Int, val down: Int, val flat: Int, val limitUp: Int, val limitDown: Int,
        val tseValue: Double, val otcValue: Double,
        /** 漲跌幅分布：≤−9、−9～−6、−6～−3、−3～−1、−1～0、0、0～1、1～3、3～6、6～9、≥9 */
        val hist: List<Int>,
        val sectors: List<Pair<String, Double>>,
        val gainers: List<Mover>, val losers: List<Mover>, val actives: List<Mover>,
        val bulls: List<TwPicks.Pick>, val bears: List<TwPicks.Pick>,
        val analyzed: Int,
        val mood: Int,
        val summary: List<String>,
    ) {
        val moodLabel: String get() = when (mood) { 2 -> "強勢偏多"; 1 -> "偏多"; -1 -> "偏空"; -2 -> "弱勢偏空"; else -> "中性震盪" }

        fun toJson(): JSONObject = JSONObject().put("d", date).put("at", at)
            .put("ix", twii).put("ixc", twiiChg).put("ixp", twiiPct).put("ixs", JSONArray(twiiSeries))
            .put("ox", otcIdx ?: JSONObject.NULL).put("oxp", otcPct ?: JSONObject.NULL)
            .put("u", up).put("dn", down).put("f", flat).put("lu", limitUp).put("ld", limitDown).put("tv", tseValue).put("ov", otcValue)
            .put("h", JSONArray(hist))
            .put("sec", JSONArray().apply { sectors.forEach { put(JSONArray(listOf(it.first, it.second))) } })
            .put("g", JSONArray().apply { gainers.forEach { put(it.toJson()) } })
            .put("l", JSONArray().apply { losers.forEach { put(it.toJson()) } })
            .put("a", JSONArray().apply { actives.forEach { put(it.toJson()) } })
            .put("b", JSONArray().apply { bulls.forEach { put(it.toJson()) } })
            .put("x", JSONArray().apply { bears.forEach { put(it.toJson()) } })
            .put("n", analyzed).put("m", mood).put("sum", JSONArray(summary))

        companion object {
            fun of(o: JSONObject): Report {
                fun arrD(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optDouble(it) } } ?: emptyList()
                fun movers(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { Mover.of(a.getJSONObject(it)) } } ?: emptyList()
                fun picks(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { TwPicks.Pick.of(a.getJSONObject(it)) } } ?: emptyList()
                return Report(
                    o.optString("d"), o.optLong("at"), o.optDouble("ix"), o.optDouble("ixc"), o.optDouble("ixp"), arrD("ixs"),
                    if (o.isNull("ox")) null else o.optDouble("ox"), if (o.isNull("oxp")) null else o.optDouble("oxp"),
                    o.optInt("u"), o.optInt("dn"), o.optInt("f"), o.optInt("lu"), o.optInt("ld"), o.optDouble("tv"), o.optDouble("ov"),
                    o.optJSONArray("h")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList(),
                    o.optJSONArray("sec")?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.optString(0) to p.optDouble(1) } } } ?: emptyList(),
                    movers("g"), movers("l"), movers("a"), picks("b"), picks("x"),
                    o.optInt("n"), o.optInt("m"),
                    o.optJSONArray("sum")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                )
            }
        }
    }

    const val TOP = 25
    val HIST_LABELS = listOf("≤-9", "-9", "-6", "-3", "-1", "0", "+1", "+3", "+6", "+9", "≥9")

    var latest by mutableStateOf<Report?>(null); private set
    val dates = mutableStateListOf<String>()
    var generating by mutableStateOf(false); private set
    var stage by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set

    private var dir: File? = null
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Mutex()
    private val TPE = ZoneId.of("Asia/Taipei")

    var autoOn: Boolean
        get() = prefs?.getBoolean("reportAuto", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("reportAuto", v)?.apply() }
    var notifyOn: Boolean
        get() = prefs?.getBoolean("reportNotify", true) ?: true
        set(v) { prefs?.edit()?.putBoolean("reportNotify", v)?.apply() }

    fun init(ctx: Context) {
        if (dir != null) return
        dir = File(ctx.filesDir, "reports").apply { mkdirs() }
        prefs = ctx.getSharedPreferences("claudeetf", Context.MODE_PRIVATE)
        TwPicks.init(ctx)
        refreshList()
        dates.firstOrNull()?.let { latest = load(it) }
    }

    private fun refreshList() {
        val l = dir?.listFiles { f -> f.name.endsWith(".json") }?.map { it.name.removeSuffix(".json") }?.sortedDescending() ?: emptyList()
        dates.clear(); dates.addAll(l)
    }

    fun load(date: String): Report? = runCatching { Report.of(JSONObject(File(dir, "$date.json").readText())) }.getOrNull()

    /** 收盤資料公布後（平日 14:45 起）且今天的報表還沒產生 */
    fun due(now: ZonedDateTime = ZonedDateTime.now(TPE)): Boolean {
        if (now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY) return false
        if (now.toLocalTime() < LocalTime.of(14, 45)) return false
        return now.toLocalDate().toString() !in dates
    }

    /** 自動產生（App 前景與背景監控共用）；產生後依設定通知 */
    suspend fun ensureToday(ctx: Context): Report? {
        init(ctx)
        if (!autoOn || !due()) return null
        val r = generate() ?: return null
        if (r.date == java.time.LocalDate.now(TPE).toString() && notifyOn) runCatching {
            com.claudeetf.app.alerts.AlertEngine.notify(ctx, 905, "台股盤後報表 " + r.date.substring(5).replace("-", "/"), r.summary.take(3).joinToString("\n"))
        }
        return r
    }

    private fun limitOf(q: RemoteQuote): Int {
        val st = com.claudeetf.app.util.TwSession.limitState(q.price, q.prevClose, q.symbol.startsWith("00")) ?: return 0
        return if (st == "漲停") 1 else -1
    }

    private fun bin(p: Double): Int = when {
        p <= -9 -> 0; p <= -6 -> 1; p <= -3 -> 2; p <= -1 -> 3; p < 0 -> 4; p == 0.0 -> 5
        p < 1 -> 6; p < 3 -> 7; p < 6 -> 8; p < 9 -> 9; else -> 10
    }

    /** 產生（或重新產生）最新交易日的報表 */
    suspend fun generate(force: Boolean = false): Report? = lock.withLock {
        withContext(Dispatchers.Main) { generating = true; error = null; stage = "取得收盤行情…" }
        try {
            val (tse, otc) = withContext(Dispatchers.IO) {
                runCatching { TwseApi.dayAll() }.getOrDefault(emptyMap()) to runCatching { TpexApi.dayAll() }.getOrDefault(emptyMap())
            }
            if (tse.isEmpty()) { withContext(Dispatchers.Main) { error = "取不到證交所收盤資料，請稍後再試" }; return@withLock null }
            val date = (tse.values.firstOrNull { it.date != null }?.date ?: java.time.LocalDate.now(TPE)).toString()
            if (!force && date in dates) return@withLock load(date).also { withContext(Dispatchers.Main) { latest = it } }

            val all = tse.values.map { it to false } + otc.values.map { it to true }
            val traded = all.filter { (q, _) -> q.price > 0 && q.prevClose > 0 && (q.volume ?: 0.0) > 0 }
            val pct = { q: RemoteQuote -> (q.price / q.prevClose - 1) * 100 }
            val up = traded.count { pct(it.first) > 0.0001 }
            val down = traded.count { pct(it.first) < -0.0001 }
            val flat = traded.size - up - down
            val stocks = traded.filter { (q, _) -> Regex("^[1-9]\\d{3}$").matches(q.symbol) || q.symbol.startsWith("00") }
            val limitUp = stocks.count { limitOf(it.first) == 1 }
            val limitDown = stocks.count { limitOf(it.first) == -1 }
            val hist = MutableList(11) { 0 }
            stocks.forEach { (q, _) -> hist[bin(pct(q))]++ }
            val tseValue = tse.values.sumOf { it.value ?: 0.0 } / 1e8
            val otcValue = otc.values.sumOf { it.value ?: 0.0 } / 1e8
            val common = traded.filter { (q, _) -> Regex("^[1-9]\\d{3}$").matches(q.symbol) && (q.volume ?: 0.0) >= 500_000 }
            fun mv(p: Pair<RemoteQuote, Boolean>) = Mover(p.first.symbol, p.first.name ?: p.first.symbol, p.second, p.first.price, pct(p.first), (p.first.value ?: 0.0) / 1e8)
            val gainers = common.sortedByDescending { pct(it.first) }.take(10).map(::mv)
            val losers = common.sortedBy { pct(it.first) }.take(10).map(::mv)
            val actives = common.sortedByDescending { it.first.value ?: 0.0 }.take(10).map(::mv)

            withContext(Dispatchers.Main) { stage = "取得指數與類股…" }
            val (ix, ox, sectors) = withContext(Dispatchers.IO) {
                Triple(
                    runCatching { YahooExtra.history("^TWII", "3mo") }.getOrNull(),
                    runCatching { YahooExtra.history("^TWOII", "1mo") }.getOrNull(),
                    runCatching { TwOpenData.sectorIndices() }.getOrDefault(emptyList()),
                )
            }
            val ixs = ix?.closes?.map { it.toDouble() }?.filter { !it.isNaN() && it > 0 } ?: emptyList()
            val twii = ixs.lastOrNull() ?: 0.0
            val twPrev = ixs.getOrNull(ixs.size - 2) ?: twii
            val oxs = ox?.closes?.map { it.toDouble() }?.filter { !it.isNaN() && it > 0 } ?: emptyList()
            val otcIdx = oxs.lastOrNull()
            val otcPct = if (oxs.size >= 2) (oxs.last() / oxs[oxs.size - 2] - 1) * 100 else null

            withContext(Dispatchers.Main) { stage = "分析個股（上市 ${TwPicks.UNIVERSE_TSE}＋上櫃 ${TwPicks.UNIVERSE_OTC} 檔）…" }
            val picks = TwPicks.analyzeAll(force = false)
            val bulls = picks.filter { it.score > 0.3 }.sortedByDescending { it.score }.take(TOP)
            val bears = picks.filter { it.score < -0.3 }.sortedBy { it.score }.take(TOP)

            // ---- 市場氣氛：指數漲跌、漲跌家數比、看漲／看跌檔數
            val twPct = if (twPrev > 0) (twii / twPrev - 1) * 100 else 0.0
            val breadth = if (up + down > 0) (up - down).toDouble() / (up + down) else 0.0
            val pickTilt = if (picks.isNotEmpty()) (picks.count { it.score > 0.6 } - picks.count { it.score < -0.6 }).toDouble() / picks.size else 0.0
            val m = twPct / 1.0 + breadth * 2 + pickTilt * 2
            val mood = when { m >= 2.2 -> 2; m >= 0.7 -> 1; m <= -2.2 -> -2; m <= -0.7 -> -1; else -> 0 }
            val ma20 = ixs.takeLast(20).takeIf { it.size == 20 }?.average()
            val ma60 = ixs.takeLast(60).takeIf { it.size == 60 }?.average()

            val sec = sectors.sortedByDescending { it.second }
            val sum = ArrayList<String>()
            sum += "加權指數收 " + "%,.2f".format(twii) + "，" + (if (twii >= twPrev) "上漲 " else "下跌 ") + "%,.2f".format(kotlin.math.abs(twii - twPrev)) + " 點（" + "%+.2f".format(twPct) + "%）" +
                (otcIdx?.let { "；櫃買指數 " + "%,.2f".format(it) + "（" + "%+.2f".format(otcPct ?: 0.0) + "%）" } ?: "") + "。"
            sum += "上漲 $up 家、下跌 $down 家、平盤 $flat 家；漲停 $limitUp 家、跌停 $limitDown 家。"
            sum += "成交值：上市 " + "%,.0f".format(tseValue) + " 億、上櫃 " + "%,.0f".format(otcValue) + " 億。"
            if (ma20 != null) sum += "指數" + (if (twii >= ma20) "站上" else "跌破") + "月線 " + "%,.0f".format(ma20) +
                (ma60?.let { "，" + (if (twii >= it) "位於" else "跌破") + "季線 " + "%,.0f".format(it) + (if (twii >= it) "之上" else "") } ?: "") + "。"
            if (sec.isNotEmpty()) sum += "類股最強：" + sec.take(3).joinToString("、") { it.first + " " + "%+.2f".format(it.second) + "%" } +
                "；最弱：" + sec.takeLast(3).reversed().joinToString("、") { it.first + " " + "%+.2f".format(it.second) + "%" } + "。"
            if (picks.isNotEmpty()) sum += "分析 ${picks.size} 檔：偏多 ${picks.count { it.score > 0.6 }} 檔、偏空 ${picks.count { it.score < -0.6 }} 檔。" +
                (bulls.take(3).takeIf { it.isNotEmpty() }?.let { l -> "看漲前三：" + l.joinToString("、") { it.name } + "。" } ?: "") +
                (bears.take(3).takeIf { it.isNotEmpty() }?.let { l -> "看跌前三：" + l.joinToString("、") { it.name } + "。" } ?: "")
            sum += "市場氣氛：" + when (mood) { 2 -> "強勢偏多"; 1 -> "偏多"; -1 -> "偏空"; -2 -> "弱勢偏空"; else -> "中性震盪" } + "。"

            val r = Report(date, System.currentTimeMillis(), twii, twii - twPrev, twPct, ixs.takeLast(60), otcIdx, otcPct,
                up, down, flat, limitUp, limitDown, tseValue, otcValue, hist, sec, gainers, losers, actives, bulls, bears, picks.size, mood, sum)
            withContext(Dispatchers.IO) {
                runCatching { File(dir, "$date.json").writeText(r.toJson().toString()) }
                // 保留 120 份
                dir?.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name }?.drop(120)?.forEach { it.delete() }
            }
            withContext(Dispatchers.Main) { refreshList(); latest = load(dates.firstOrNull() ?: date) ?: r }
            r
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { error = "報表產生失敗：" + (e.message ?: e.javaClass.simpleName) }
            null
        } finally {
            withContext(Dispatchers.Main) { generating = false; stage = "" }
        }
    }
}
