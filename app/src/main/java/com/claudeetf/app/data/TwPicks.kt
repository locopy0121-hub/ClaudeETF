package com.claudeetf.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.claudeetf.app.data.remote.TpexApi
import com.claudeetf.app.data.remote.TwseApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 台股分析推薦排行：取成交值最大的個股，逐檔做日 K 分析（形態、量價、支撐壓力、趨勢）＋中期動能＋一年股價光譜，
 * 綜合分數排出「看漲（值得投資）」與「看跌（避坑）」各前十名。僅供參考，不是投資建議。
 */
object TwPicks {
    data class Pick(
        val symbol: String, val name: String, val otc: Boolean,
        val price: Double, val pct: Double, val score: Double,
        val dir: String, val entry: String, val entryLevel: Int,
        val zone: Int, val mom20: Double, val reasons: List<String>,
        /** K 線進場建議說明、操作參考（停損、目標）、K 棒策略訊號與進場時機 */
        val entryReason: String = "",
        val stop: Double? = null, val tp1: Double? = null, val tp2: Double? = null,
        val signal: String? = null,
        val ma60Gap: Double? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().put("s", symbol).put("n", name).put("o", otc).put("p", price).put("pc", pct).put("sc", score)
            .put("d", dir).put("e", entry).put("el", entryLevel).put("z", zone).put("m", mom20).put("r", JSONArray(reasons))
            .put("er", entryReason).put("sl", stop ?: JSONObject.NULL).put("t1", tp1 ?: JSONObject.NULL).put("t2", tp2 ?: JSONObject.NULL)
            .put("sg", signal ?: JSONObject.NULL).put("mg", ma60Gap ?: JSONObject.NULL)
        companion object {
            fun of(o: JSONObject) = Pick(o.optString("s"), o.optString("n"), o.optBoolean("o"), o.optDouble("p"), o.optDouble("pc"), o.optDouble("sc"),
                o.optString("d"), o.optString("e"), o.optInt("el"), o.optInt("z", -1), o.optDouble("m"),
                o.optJSONArray("r")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                o.optString("er"), o.optDouble("sl").takeIf { !it.isNaN() }, o.optDouble("t1").takeIf { !it.isNaN() }, o.optDouble("t2").takeIf { !it.isNaN() },
                if (o.isNull("sg")) null else o.optString("sg").ifBlank { null }, o.optDouble("mg").takeIf { !it.isNaN() })
        }
    }

    /** 分析檔數：上市、上櫃各取成交值前 N 名 */
    const val UNIVERSE_TSE = 120
    const val UNIVERSE_OTC = 100

    /** 上市 */
    var bulls by mutableStateOf<List<Pick>>(emptyList()); private set
    var bears by mutableStateOf<List<Pick>>(emptyList()); private set
    /** 上櫃 */
    var bullsOtc by mutableStateOf<List<Pick>>(emptyList()); private set
    var bearsOtc by mutableStateOf<List<Pick>>(emptyList()); private set

    fun list(otc: Boolean, bull: Boolean) = if (otc) (if (bull) bullsOtc else bearsOtc) else (if (bull) bulls else bears)
    var at by mutableLongStateOf(0L); private set
    var dataDate by mutableStateOf(""); private set
    var running by mutableStateOf(false); private set
    var done by mutableIntStateOf(0); private set
    var total by mutableIntStateOf(0); private set
    var error by mutableStateOf<String?>(null); private set

    /** 最近一次分析的全部個股（含分數，報表用；不存檔） */
    @Volatile var all: List<Pick> = emptyList(); private set

    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "tw_picks.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            at = o.optLong("at"); dataDate = o.optString("dd")
            bulls = o.optJSONArray("b")?.let { a -> (0 until a.length()).map { Pick.of(a.getJSONObject(it)) } } ?: emptyList()
            bears = o.optJSONArray("x")?.let { a -> (0 until a.length()).map { Pick.of(a.getJSONObject(it)) } } ?: emptyList()
            bullsOtc = o.optJSONArray("bo")?.let { a -> (0 until a.length()).map { Pick.of(a.getJSONObject(it)) } } ?: emptyList()
            bearsOtc = o.optJSONArray("xo")?.let { a -> (0 until a.length()).map { Pick.of(a.getJSONObject(it)) } } ?: emptyList()
            // 舊版（上市櫃混排）資料：重新分析
            if (!o.has("bo")) at = 0L
        }
    }

    /** 不同日或收盤（14:35）後還沒算過就需要重算 */
    fun stale(): Boolean {
        if (at == 0L) return true
        val z = java.time.ZoneId.of("Asia/Taipei")
        val n = java.time.ZonedDateTime.now(z)
        val l = java.time.Instant.ofEpochMilli(at).atZone(z)
        if (n.toLocalDate() != l.toLocalDate()) return true
        val cut = n.toLocalDate().atTime(14, 35).atZone(z)
        return n.isAfter(cut) && l.isBefore(cut)
    }

    fun start(force: Boolean = false) {
        if (running || (!force && !stale())) return
        job = scope.launch { runCatching { run() }.onFailure { error = "分析失敗：" + (it.message ?: it.javaClass.simpleName) }; running = false }
    }

    /** ETF 分析檔數（成交值前 N 檔，排除槓桿 L／反向 R） */
    const val UNIVERSE_ETF = 80
    @Volatile var etfAll: List<Pick> = emptyList(); private set
    @Volatile private var etfAt = 0L
    /** 市場頁 ETF 排行（看漲／看跌各 15 檔）與進度 */
    var etfBulls by mutableStateOf<List<Pick>>(emptyList()); private set
    var etfBears by mutableStateOf<List<Pick>>(emptyList()); private set
    var etfRunning by mutableStateOf(false); private set
    var etfDone by mutableIntStateOf(0); private set
    var etfTotal by mutableIntStateOf(0); private set
    var etfAtState by mutableLongStateOf(0L); private set

    fun startEtf(force: Boolean = false) {
        if (etfRunning) return
        if (!force && etfAll.isNotEmpty() && !staleAt(etfAt)) {
            if (etfBulls.isEmpty() && etfBears.isEmpty()) {
                etfBulls = etfAll.filter { it.score > 0.3 }.sortedByDescending { it.score }.take(15)
                etfBears = etfAll.filter { it.score < -0.3 }.sortedBy { it.score }.take(15)
                etfAtState = etfAt
            }
            return
        }
        scope.launch { try { analyzeEtfs(force) } catch (_: Exception) { } finally { etfRunning = false } }
    }

    /**
     * 台股 ETF 分析（盤後報表用）：上市櫃 ETF 依成交值取前 80 檔（成交 ≥ 200 張），
     * 以 ETF 引擎（母子線、上升三法、恐慌止跌、缺口回補）＋光譜、動能綜合評分。
     */
    suspend fun analyzeEtfs(force: Boolean = false): List<Pick> {
        if (!force && etfAll.isNotEmpty() && !staleAt(etfAt)) return etfAll
        val universe = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val tse = runCatching { TwseApi.latestAll() }.getOrDefault(emptyMap())
            val otc = runCatching { TpexApi.latestAll() }.getOrDefault(emptyMap())
            (tse.values.map { it to false } + otc.values.map { it to true }).filter { (q, _) ->
                q.symbol.startsWith("00") && !q.symbol.endsWith("L") && !q.symbol.endsWith("R") && q.price > 0 && (q.volume ?: 0.0) >= 200_000
            }.sortedByDescending { (q, _) -> q.value ?: (q.price * (q.volume ?: 0.0)) }.take(UNIVERSE_ETF)
        }
        if (universe.isEmpty()) return etfAll
        kotlinx.coroutines.withContext(Dispatchers.Main) { etfRunning = true; etfDone = 0; etfTotal = universe.size }
        val gate = Semaphore(4)
        val cnt = AtomicInteger(0)
        val picks = kotlinx.coroutines.coroutineScope {
            universe.map { (rq, isOtc) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val q = Quote(rq.symbol, rq.name ?: rq.symbol, Market.TW, rq.price, rq.prevClose, isEtf = true,
                            source = if (isOtc) QuoteSource.TPEX else QuoteSource.TWSE)
                        val p = runCatching { score(q, isOtc) }.getOrNull()
                        etfDone = cnt.incrementAndGet()
                        p
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (picks.isNotEmpty()) { etfAll = picks; etfAt = System.currentTimeMillis() }
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            etfBulls = etfAll.filter { it.score > 0.3 }.sortedByDescending { it.score }.take(15)
            etfBears = etfAll.filter { it.score < -0.3 }.sortedBy { it.score }.take(15)
            etfAtState = etfAt; etfRunning = false
        }
        return etfAll
    }

    private fun staleAt(t: Long): Boolean {
        if (t == 0L) return true
        val z = java.time.ZoneId.of("Asia/Taipei")
        val n = java.time.ZonedDateTime.now(z)
        val l = java.time.Instant.ofEpochMilli(t).atZone(z)
        if (n.toLocalDate() != l.toLocalDate()) return true
        val cut = n.toLocalDate().atTime(14, 35).atZone(z)
        return n.isAfter(cut) && l.isBefore(cut)
    }

    /** 等待分析完成並回傳全部結果（盤後報表用）；需要時才重算 */
    suspend fun analyzeAll(force: Boolean = false): List<Pick> {
        if (running) { while (running) kotlinx.coroutines.delay(500) }
        if (force || stale() || all.isEmpty()) {
            kotlinx.coroutines.withContext(Dispatchers.Main) { running = true }
            try { runCatching { run() }.onFailure { error = "分析失敗：" + (it.message ?: it.javaClass.simpleName) } }
            finally { kotlinx.coroutines.withContext(Dispatchers.Main) { running = false } }
        }
        return all
    }

    private fun clamp(v: Double, a: Double) = v.coerceIn(-a, a)

    private suspend fun run() {
        running = true; error = null; done = 0; total = 0
        val (universe, date) = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val tse = runCatching { TwseApi.latestAll() }.getOrDefault(emptyMap())
            val otc = runCatching { TpexApi.latestAll() }.getOrDefault(emptyMap())
            val d = (tse.values.firstOrNull()?.date ?: otc.values.firstOrNull()?.date)?.toString() ?: ""
            fun top(m: Collection<com.claudeetf.app.data.remote.RemoteQuote>, n: Int) = m.filter { q ->
                Regex("^[1-9]\\d{3}$").matches(q.symbol) && q.price >= 10 && (q.volume ?: 0.0) >= 1_000_000
            }.sortedByDescending { q -> q.value ?: (q.price * (q.volume ?: 0.0)) }.take(n)
            (top(tse.values, UNIVERSE_TSE).map { it to false } + top(otc.values, UNIVERSE_OTC).map { it to true }) to d
        }
        if (universe.isEmpty()) { error = "取不到台股收盤資料，請稍後再試"; return }
        total = universe.size
        val gate = Semaphore(4)
        val cnt = AtomicInteger(0)
        val picks = kotlinx.coroutines.coroutineScope {
            universe.map { (rq, isOtc) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val q = Quote(rq.symbol, rq.name ?: rq.symbol, Market.TW, rq.price, rq.prevClose,
                            source = if (isOtc) QuoteSource.TPEX else QuoteSource.TWSE)
                        val p = runCatching { score(q, isOtc) }.getOrNull()
                        done = cnt.incrementAndGet()
                        p
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (picks.isEmpty()) { error = "分析結果為空（K 線資料暫時無法取得）"; return }
        all = picks
        fun bull(l: List<Pick>) = l.filter { it.score > 0.6 }.sortedByDescending { it.score }.take(10)
        fun bear(l: List<Pick>) = l.filter { it.score < -0.6 }.sortedBy { it.score }.take(10)
        val (o, t) = picks.partition { it.otc }
        bulls = bull(t); bears = bear(t); bullsOtc = bull(o); bearsOtc = bear(o)
        at = System.currentTimeMillis(); dataDate = date
        runCatching {
            file?.writeText(JSONObject().put("at", at).put("dd", dataDate)
                .put("b", JSONArray().apply { bulls.forEach { put(it.toJson()) } })
                .put("x", JSONArray().apply { bears.forEach { put(it.toJson()) } })
                .put("bo", JSONArray().apply { bullsOtc.forEach { put(it.toJson()) } })
                .put("xo", JSONArray().apply { bearsOtc.forEach { put(it.toJson()) } }).toString())
        }
    }

    /**
     * 綜合分數：
     *   日 K 分析分數（形態、量價、支撐壓力、趨勢）
     * ＋ 與 60 日均線的距離（±1.5）＋ 近 20 日漲跌（±1）＋ 進場建議等級 × 0.35
     * ＋ 一年股價光譜：特價／便宜加分、昂貴／極貴扣分
     */
    private suspend fun score(q: Quote, otc: Boolean): Pick? {
        val r = KlineAnalysis.load(q, weekly = false, maxAgeMs = 6 * 3_600_000L) ?: return null
        val cs = r.candles
        if (cs.size < 45) return null
        val closes = cs.map { it.c }
        val last = closes.last()
        val ma60 = closes.takeLast(60).average()
        val mom20 = last / closes[closes.size - 21] - 1
        val tail = cs.takeLast(250)
        val band = PriceBand.compute(tail.map { it.t }, tail.map { it.c }, q.price, true)
        val zone = band?.zone ?: -1
        var s = r.score + clamp((last / ma60 - 1) * 8, 1.5) + clamp(mom20 * 6, 1.0) + r.entry.level * 0.35
        s += when (zone) { 0 -> 0.6; 1 -> 0.35; 4 -> -0.35; 5 -> -0.7; else -> 0.0 }
        val reasons = ArrayList<String>()
        reasons += "K 線" + r.dir.label + "（" + r.entry.label + "）"
        reasons += "近 20 日 " + (if (mom20 >= 0) "+" else "") + "%.1f".format(mom20 * 100) + "%，" + (if (last >= ma60) "站上" else "跌破") + " 60 日均線"
        band?.let { reasons += "一年光譜「" + it.zoneName + "」" }
        r.latest.distinctBy { it.pat }.take(3).takeIf { it.isNotEmpty() }?.let { l -> reasons += "近期形態：" + l.joinToString("、") { it.pat.cn } }
        reasons += "趨勢：" + r.ctx.trendLabel + "，支撐 " + com.claudeetf.app.util.Fmt.price(r.ctx.support) + "、壓力 " + com.claudeetf.app.util.Fmt.price(r.ctx.resistance)
        val vr = cs.takeLast(21).dropLast(1).map { it.v }.average().takeIf { it > 0 }?.let { cs.last().v / it }
        vr?.let { reasons += "成交量為 20 日均量 " + "%.1f".format(it) + " 倍" }
        val sig = r.signals.firstOrNull()?.let { g ->
            g.strategy.cn + (if (g.bullish) "（做多" else "（偏空") + "，" + g.status.cn + "，信心 " + g.confidenceScore + "）" +
                g.entryTimingType.cn + "：" + g.entryTimingDescription
        }
        return Pick(q.symbol, q.name, otc, q.price, q.changePct, s, r.dir.label, r.entry.label, r.entry.level, zone, mom20 * 100, reasons,
            r.entry.reason, r.plan?.stop, r.plan?.tp1, r.plan?.tp2, sig, (last / ma60 - 1) * 100)
    }
}
