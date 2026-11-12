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
        // ---- ETF
        val etfUp: Int = 0, val etfDown: Int = 0, val etfFlat: Int = 0, val etfValue: Double = 0.0,
        val etfGainers: List<Mover> = emptyList(), val etfLosers: List<Mover> = emptyList(), val etfActives: List<Mover> = emptyList(),
        val etfBulls: List<TwPicks.Pick> = emptyList(), val etfBears: List<TwPicks.Pick> = emptyList(), val etfAnalyzed: Int = 0,
        /** 前一份報表推薦名單到本日收盤的實際表現 */
        val perf: Perf? = null,
        /** 三大法人：買賣超金額（億元，以收盤價估算）與外資、投信買超／賣超前十 */
        val instDate: String = "", val foreignYi: Double = 0.0, val trustYi: Double = 0.0, val dealerYi: Double = 0.0,
        val fBuy: List<Mover> = emptyList(), val fSell: List<Mover> = emptyList(), val tBuy: List<Mover> = emptyList(), val tSell: List<Mover> = emptyList(),
    ) {
        val hasInst: Boolean get() = instDate.isNotEmpty()
        val hasEtf: Boolean get() = etfUp + etfDown + etfFlat > 0
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
            .put("eu", etfUp).put("ed", etfDown).put("ef", etfFlat).put("ev", etfValue)
            .put("eg", JSONArray().apply { etfGainers.forEach { put(it.toJson()) } })
            .put("el", JSONArray().apply { etfLosers.forEach { put(it.toJson()) } })
            .put("ea", JSONArray().apply { etfActives.forEach { put(it.toJson()) } })
            .put("eb", JSONArray().apply { etfBulls.forEach { put(it.toJson()) } })
            .put("ex", JSONArray().apply { etfBears.forEach { put(it.toJson()) } })
            .put("en", etfAnalyzed).put("pf", perf?.toJson() ?: JSONObject.NULL)
            .put("id", instDate).put("fy", foreignYi).put("ty", trustYi).put("dy", dealerYi)
            .put("fb", JSONArray().apply { fBuy.forEach { put(it.toJson()) } }).put("fs", JSONArray().apply { fSell.forEach { put(it.toJson()) } })
            .put("tb", JSONArray().apply { tBuy.forEach { put(it.toJson()) } }).put("ts", JSONArray().apply { tSell.forEach { put(it.toJson()) } })

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
                    o.optInt("eu"), o.optInt("ed"), o.optInt("ef"), o.optDouble("ev", 0.0),
                    movers("eg"), movers("el"), movers("ea"), picks("eb"), picks("ex"), o.optInt("en"),
                    o.optJSONObject("pf")?.let { Perf.of(it) },
                    o.optString("id"), o.optDouble("fy", 0.0), o.optDouble("ty", 0.0), o.optDouble("dy", 0.0),
                    movers("fb"), movers("fs"), movers("tb"), movers("ts"),
                )
            }
        }
    }

    /** 單一名單的表現：平均報酬、勝率（看漲＝上漲比例；看跌＝下跌比例） */
    data class Group(val n: Int, val avg: Double, val win: Int, val best: List<Pair<String, Double>>, val worst: List<Pair<String, Double>>) {
        val winRate: Double get() = if (n > 0) win * 100.0 / n else 0.0
        fun toJson(): JSONObject = JSONObject().put("n", n).put("a", avg).put("w", win)
            .put("b", JSONArray().apply { best.forEach { put(JSONArray(listOf(it.first, it.second))) } })
            .put("x", JSONArray().apply { worst.forEach { put(JSONArray(listOf(it.first, it.second))) } })
        companion object {
            fun of(o: JSONObject): Group {
                fun l(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.optString(0) to p.optDouble(1) } } } ?: emptyList()
                return Group(o.optInt("n"), o.optDouble("a"), o.optInt("w"), l("b"), l("x"))
            }
        }
    }

    data class Perf(val fromDate: String, val bull: Group?, val bear: Group?, val etfBull: Group?, val etfBear: Group?, val market: Double) {
        fun toJson(): JSONObject = JSONObject().put("f", fromDate).put("m", market)
            .put("b", bull?.toJson() ?: JSONObject.NULL).put("x", bear?.toJson() ?: JSONObject.NULL)
            .put("eb", etfBull?.toJson() ?: JSONObject.NULL).put("ex", etfBear?.toJson() ?: JSONObject.NULL)
        companion object {
            fun of(o: JSONObject) = Perf(o.optString("f"), o.optJSONObject("b")?.let { Group.of(it) }, o.optJSONObject("x")?.let { Group.of(it) },
                o.optJSONObject("eb")?.let { Group.of(it) }, o.optJSONObject("ex")?.let { Group.of(it) }, o.optDouble("m"))
        }
    }

    /** 歷史累計：所有已存報表的前次推薦表現加總 */
    data class Track(val days: Int, val bullAvg: Double, val bullWin: Double, val bearAvg: Double, val bearWin: Double, val excess: Double)

    fun track(limit: Int = 60): Track? {
        val ps = dates.take(limit).mapNotNull { load(it)?.perf }
        if (ps.isEmpty()) return null
        val b = ps.mapNotNull { it.bull }; val x = ps.mapNotNull { it.bear }
        fun avg(l: List<Group>) = if (l.isEmpty()) 0.0 else l.sumOf { it.avg * it.n } / l.sumOf { it.n }.coerceAtLeast(1)
        fun win(l: List<Group>) = if (l.isEmpty()) 0.0 else l.sumOf { it.win } * 100.0 / l.sumOf { it.n }.coerceAtLeast(1)
        return Track(ps.size, avg(b), win(b), avg(x), win(x), avg(b) - ps.map { it.market }.average())
    }

    private fun group(list: List<TwPicks.Pick>, now: Map<String, Double>, bull: Boolean): Group? {
        val rs = list.mapNotNull { p -> now[p.symbol]?.takeIf { p.price > 0 && it > 0 }?.let { p to (it / p.price - 1) * 100 } }
        if (rs.isEmpty()) return null
        val sorted = rs.sortedByDescending { it.second }
        return Group(rs.size, rs.map { it.second }.average(), rs.count { if (bull) it.second > 0 else it.second < 0 },
            sorted.take(3).map { it.first.name to it.second }, sorted.takeLast(3).reversed().map { it.first.name to it.second })
    }

    /** 我的持股／自選出現在推薦名單中 */
    data class Mine(val symbol: String, val name: String, val held: Boolean, val list: String, val bull: Boolean, val rank: Int, val pick: TwPicks.Pick)

    fun mine(r: Report): List<Mine> {
        val held = AppState.holdings.map { it.symbol }.toSet()
        val watched = AppState.quotes.map { it.symbol }.toSet()
        val out = ArrayList<Mine>()
        listOf(Triple("個股看漲", true, r.bulls), Triple("個股看跌", false, r.bears), Triple("ETF 看漲", true, r.etfBulls), Triple("ETF 看跌", false, r.etfBears))
            .forEach { (n, bull, l) ->
                l.forEachIndexed { i, p ->
                    if (p.symbol in held || p.symbol in watched) out += Mine(p.symbol, p.name, p.symbol in held, n, bull, i + 1, p)
                }
            }
        // 持股優先、看跌優先
        return out.sortedWith(compareBy({ !it.held }, { it.bull }, { it.rank }))
    }

    /** 連續上榜天數：key＝"B:代號"（看漲）或 "S:代號"（看跌），以該份報表往前連續計算（最多 20 份） */
    private val streakCache = HashMap<String, Map<String, Int>>()

    fun streaks(r: Report): Map<String, Int> = streakCache.getOrPut(r.date) {
        val past = dates.filter { it < r.date }.take(19).map { load(it) }
        fun keys(x: Report?) = x?.let { rep ->
            (rep.bulls + rep.etfBulls).map { "B:" + it.symbol }.toSet() + (rep.bears + rep.etfBears).map { "S:" + it.symbol }.toSet()
        } ?: emptySet()
        keys(r).associateWith { k ->
            var n = 1
            for (p in past) { if (k in keys(p)) n++ else break }
            n
        }
    }

    fun streakOf(r: Report, symbol: String, bull: Boolean): Int = streaks(r)[(if (bull) "B:" else "S:") + symbol] ?: 0

    /** 某檔在最新一份報表的名次（看漲／看跌、個股或 ETF 名單） */
    data class Rank(val bull: Boolean, val rank: Int, val total: Int, val etf: Boolean, val pick: TwPicks.Pick, val date: String)

    fun rankOf(symbol: String, r: Report? = latest): Rank? {
        r ?: return null
        listOf(Triple(true, false, r.bulls), Triple(false, false, r.bears), Triple(true, true, r.etfBulls), Triple(false, true, r.etfBears)).forEach { (bull, etf, l) ->
            val i = l.indexOfFirst { it.symbol == symbol }
            if (i >= 0) return Rank(bull, i + 1, l.size, etf, l[i], r.date)
        }
        return null
    }

    val TOP get() = AnalysisConfig.cfg.reportTop
    val TOP_ETF get() = AnalysisConfig.cfg.reportEtfTop
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
            // 持股被列入看跌名單：另外提醒
            val risk = mine(r).filter { it.held && !it.bull }
            if (risk.isNotEmpty()) com.claudeetf.app.alerts.AlertEngine.notify(ctx, 906, "持股風險提醒：${risk.size} 檔列入看跌名單",
                risk.joinToString("\n") { it.symbol + " " + it.name + "：" + it.list + "第 " + it.rank + " 名（" + it.pick.entry + "）" })
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
            val (tse, otc0) = withContext(Dispatchers.IO) {
                runCatching { TwseApi.latestAll() }.getOrDefault(emptyMap()) to runCatching { TpexApi.latestAll() }.getOrDefault(emptyMap())
            }
            if (tse.isEmpty()) { withContext(Dispatchers.Main) { error = "取不到證交所收盤資料，請稍後再試" }; return@withLock null }
            // 報表日期一律以台灣時間的資料日為準
            val date = (tse.values.firstOrNull { it.date != null }?.date ?: java.time.LocalDate.now(TPE)).toString()
            // 上櫃資料日期與上市不同（尚未更新）時不混用
            val otc = otc0.filterValues { it.date == null || it.date.toString() == date }
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

            // ---- ETF（代號 00 開頭）
            val etfs = traded.filter { (q, _) -> q.symbol.startsWith("00") }
            val etfUp = etfs.count { pct(it.first) > 0.0001 }
            val etfDown = etfs.count { pct(it.first) < -0.0001 }
            val etfFlat = etfs.size - etfUp - etfDown
            val etfValue = etfs.sumOf { it.first.value ?: 0.0 } / 1e8
            val etfLiquid = etfs.filter { (q, _) -> (q.volume ?: 0.0) >= 200_000 }
            val etfGainers = etfLiquid.sortedByDescending { pct(it.first) }.take(10).map(::mv)
            val etfLosers = etfLiquid.sortedBy { pct(it.first) }.take(10).map(::mv)
            val etfActives = etfLiquid.sortedByDescending { it.first.value ?: 0.0 }.take(10).map(::mv)

            // ---- 前一份報表的推薦，到本日收盤的實際表現
            val nowPx = all.associate { (q, _) -> q.symbol to q.price }
            val prevRep = dates.firstOrNull { it < date }?.let { load(it) }
            val perf0 = prevRep?.let { pr ->
                Perf(pr.date, group(pr.bulls, nowPx, true), group(pr.bears, nowPx, false), group(pr.etfBulls, nowPx, true), group(pr.etfBears, nowPx, false), 0.0)
            }

            // ---- 三大法人（證交所＋櫃買；金額以收盤價估算）
            withContext(Dispatchers.Main) { stage = "取得三大法人買賣超…" }
            val inst = withContext(Dispatchers.IO) { runCatching { com.claudeetf.app.data.remote.TwInstitutional.latest() }.getOrDefault(emptyMap()) }
            val byCode = all.associateBy { it.first.symbol }
            val instRows = inst.mapNotNull { (code, d) -> byCode[code]?.let { Triple(it, d, it.first.price) } }.filter { it.third > 0 }
            fun yi(sh: Long, px: Double) = sh * px / 1e8
            val foreignYi = instRows.sumOf { yi(it.second.foreign, it.third) }
            val trustYi = instRows.sumOf { yi(it.second.trust, it.third) }
            val dealerYi = instRows.sumOf { yi(it.second.dealer, it.third) }
            fun mvNet(t: Triple<Pair<RemoteQuote, Boolean>, com.claudeetf.app.data.remote.TwInstitutional.Day, Double>, sh: Long) =
                Mover(t.first.first.symbol, t.first.first.name ?: t.first.first.symbol, t.first.second, t.third, pct(t.first.first), yi(sh, t.third))
            val fBuy = instRows.sortedByDescending { yi(it.second.foreign, it.third) }.take(10).filter { it.second.foreign > 0 }.map { mvNet(it, it.second.foreign) }
            val fSell = instRows.sortedBy { yi(it.second.foreign, it.third) }.take(10).filter { it.second.foreign < 0 }.map { mvNet(it, it.second.foreign) }
            val tBuy = instRows.sortedByDescending { yi(it.second.trust, it.third) }.take(10).filter { it.second.trust > 0 }.map { mvNet(it, it.second.trust) }
            val tSell = instRows.sortedBy { yi(it.second.trust, it.third) }.take(10).filter { it.second.trust < 0 }.map { mvNet(it, it.second.trust) }
            val instDate = inst.values.firstOrNull()?.date?.toString() ?: ""

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
            withContext(Dispatchers.Main) { stage = "分析 ETF（成交值前 ${TwPicks.UNIVERSE_ETF} 檔）…" }
            val etfPicks = runCatching { TwPicks.analyzeEtfs() }.getOrDefault(emptyList())
            val etfBulls = etfPicks.filter { it.score > 0.3 }.sortedByDescending { it.score }.take(TOP_ETF)
            val etfBears = etfPicks.filter { it.score < -0.3 }.sortedBy { it.score }.take(TOP_ETF)

            // ---- 市場氣氛：指數漲跌、漲跌家數比、看漲／看跌檔數
            val twPct = if (twPrev > 0) (twii / twPrev - 1) * 100 else 0.0
            val perf = perf0?.copy(market = if (prevRep != null && prevRep.twii > 0 && twii > 0) (twii / prevRep.twii - 1) * 100 else 0.0)
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
            if (etfs.isNotEmpty()) sum += "ETF：上漲 $etfUp 檔、下跌 $etfDown 檔、平盤 $etfFlat 檔，成交值 " + "%,.0f".format(etfValue) + " 億（占上市櫃 " +
                "%.1f".format(if (tseValue + otcValue > 0) etfValue / (tseValue + otcValue) * 100 else 0.0) + "%）" +
                (etfActives.take(3).takeIf { it.isNotEmpty() }?.let { l -> "；成交最熱：" + l.joinToString("、") { it.name } } ?: "") + "。" +
                (if (etfPicks.isNotEmpty()) "分析 ${etfPicks.size} 檔 ETF" +
                    (etfBulls.take(3).takeIf { it.isNotEmpty() }?.let { l -> "，看漲前三：" + l.joinToString("、") { it.name } } ?: "") +
                    (etfBears.take(3).takeIf { it.isNotEmpty() }?.let { l -> "，看跌前三：" + l.joinToString("、") { it.name } } ?: "") + "。" else "")
            perf?.bull?.let { b ->
                sum += "前次（" + perf.fromDate.substring(5).replace("-", "/") + "）看漲名單平均 " + "%+.2f".format(b.avg) + "%、上漲比例 " + "%.0f".format(b.winRate) + "%" +
                    (perf.bear?.let { x -> "；看跌名單平均 " + "%+.2f".format(x.avg) + "%、下跌比例 " + "%.0f".format(x.winRate) + "%" } ?: "") +
                    "（同期加權 " + "%+.2f".format(perf.market) + "%）。"
            }
            if (instDate.isNotEmpty()) sum += "三大法人（" + instDate.substring(5).replace("-", "/") + "，以收盤價估算）：外資 " + "%+,.1f".format(foreignYi) + " 億、投信 " +
                "%+,.1f".format(trustYi) + " 億、自營商 " + "%+,.1f".format(dealerYi) + " 億" +
                (fBuy.take(3).takeIf { it.isNotEmpty() }?.let { l -> "；外資買超前三：" + l.joinToString("、") { it.name } } ?: "") + "。"
            sum += "市場氣氛：" + when (mood) { 2 -> "強勢偏多"; 1 -> "偏多"; -1 -> "偏空"; -2 -> "弱勢偏空"; else -> "中性震盪" } + "。"

            val r = Report(date, System.currentTimeMillis(), twii, twii - twPrev, twPct, ixs.takeLast(60), otcIdx, otcPct,
                up, down, flat, limitUp, limitDown, tseValue, otcValue, hist, sec, gainers, losers, actives, bulls, bears, picks.size, mood, sum,
                etfUp, etfDown, etfFlat, etfValue, etfGainers, etfLosers, etfActives, etfBulls, etfBears, etfPicks.size, perf,
                instDate, foreignYi, trustYi, dealerYi, fBuy, fSell, tBuy, tSell)
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
