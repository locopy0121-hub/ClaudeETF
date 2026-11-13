package com.claudeetf.app.data

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import com.claudeetf.app.data.remote.YahooApi
import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * 配息組合試算：任選個股／ETF 組成配置，套用各自最近一年的正式配息，
 * 算出年領、月領分布、殖利率與達成目標所需本金；並提供 App 規則推薦與 AI 推薦組合。
 */
object DivPortfolio {
    /** 單一標的的配息資料（價格、近一年配息、配息月份） */
    data class Info(
        val symbol: String, val name: String, val market: Market, val price: Double,
        /** 最近一筆配息（每股，原幣） */
        val last: Double, val lastTime: Long,
        /** 近 12 個月配息合計（每股，原幣）；沒有時以最近一筆 × 頻率估算 */
        val annual: Double,
        /** 近 12 個月各筆配息：月份（1～12）→ 每股金額 */
        val byMonth: Map<Int, Double>,
        val freq: String,
        val isEtf: Boolean, val bond: Boolean,
    ) {
        val fx: Double get() = if (market == Market.US) AppState.usdTwd else 1.0
        val yieldPct: Double get() = if (price > 0) annual / price * 100 else 0.0
    }

    data class Item(val symbol: String, val weight: Double)

    val infos = mutableStateMapOf<String, Info>()
    private val failed = mutableStateMapOf<String, String>()
    fun error(sym: String) = failed[sym]

    /** 目前組合（持久保存） */
    val combo = mutableStateListOf<Item>()
    private var loaded = false

    fun loadCombo() {
        if (loaded) return
        loaded = true
        runCatching {
            val a = JSONArray(AppState.cacheGet("divCombo") ?: return)
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { combo += Item(it.optString("s"), it.optDouble("w", 0.0)) }
        }
        if (combo.isEmpty()) {
            // 預設：持股中的 ETF 平均配置
            AppState.holdings.map { it.symbol }.filter { it.startsWith("00") }.take(4).let { l ->
                l.forEach { combo += Item(it, 100.0 / l.size) }
            }
        }
    }

    fun saveCombo() = AppState.cachePut("divCombo", JSONArray().apply { combo.forEach { put(JSONObject().put("s", it.symbol).put("w", it.weight)) } }.toString())

    fun setCombo(items: List<Item>) { combo.clear(); combo.addAll(normalize(items)); saveCombo() }

    /** 權重正規化為合計 100% */
    fun normalize(items: List<Item>): List<Item> {
        val s = items.sumOf { it.weight.coerceAtLeast(0.0) }
        if (s <= 0) return items.map { it.copy(weight = 100.0 / items.size.coerceAtLeast(1)) }
        return items.map { it.copy(weight = Math.round(it.weight.coerceAtLeast(0.0) / s * 1000) / 10.0) }
    }

    private fun yahooOf(sym: String, q: Quote?): String = when {
        q != null -> StockInfoRepo.yahooSymbol(q)
        sym.first().isDigit() -> sym + if (StockDirectory.isOtc(sym)) ".TWO" else ".TW"
        else -> sym
    }

    /** 取得（或下載）一檔的配息資料；失敗回傳 null 並記錄原因 */
    suspend fun ensure(sym: String, nameHint: String? = null): Info? = withContext(Dispatchers.IO) {
        infos[sym]?.let { return@withContext it }
        val q = AppState.quote(sym)
        val r = runCatching {
            val ys = yahooOf(sym, q)
            val chart = if (q == null || q.price <= 0) runCatching { YahooApi.chart(ys) }.getOrNull() else null
            val price = q?.price?.takeIf { it > 0 } ?: chart?.quote?.price ?: throw IllegalStateException("取不到價格")
            val name = q?.name ?: nameHint ?: chart?.quote?.name ?: sym
            val market = q?.market ?: if (sym.first().isDigit()) Market.TW else Market.US
            val divs = YahooExtra.dividends(ys).sortedByDescending { it.time }
            val now = System.currentTimeMillis()
            val yr = divs.filter { now - it.time <= 370L * 86_400_000L }
            val freq = if (divs.isEmpty()) "不配息" else EtfTags.freqOf(divs.map { it.time })
            val perYear = when { freq.contains("月") -> 12; freq.contains("季") -> 4; freq.contains("半年") -> 2; else -> 1 }
            val annual = if (yr.isNotEmpty()) yr.sumOf { it.amount } else (divs.firstOrNull()?.amount ?: 0.0) * perYear
            val byMonth = HashMap<Int, Double>()
            (yr.ifEmpty { divs.take(perYear) }).forEach { d ->
                val m = Instant.ofEpochMilli(d.time).atZone(ZoneId.of("Asia/Taipei")).monthValue
                byMonth[m] = (byMonth[m] ?: 0.0) + d.amount
            }
            Info(sym, name, market, price, divs.firstOrNull()?.amount ?: 0.0, divs.firstOrNull()?.time ?: 0L, annual, byMonth, freq,
                sym.startsWith("00") || (q?.let { EtfTags.isEtf(it) } ?: false), sym.endsWith("B"))
        }
        r.getOrNull()?.also { infos[sym] = it; failed.remove(sym) } ?: run {
            failed[sym] = r.exceptionOrNull()?.message ?: "取不到資料"; null
        }
    }

    // ---------------- 試算 ----------------
    data class Row(val info: Info, val weight: Double, val shares: Double, val cost: Double, val annualTwd: Double, val months: Map<Int, Double>)
    data class Result(val rows: List<Row>, val invested: Double, val annual: Double, val monthly: DoubleArray, val yieldPct: Double) {
        val monthAvg: Double get() = annual / 12
        val emptyMonths: List<Int> get() = (1..12).filter { monthly[it - 1] <= 0 }
    }

    /** 依總本金（台幣）與權重試算；台股可選整張或零股 */
    fun simulate(capital: Double, items: List<Item>, wholeLots: Boolean): Result {
        val rows = items.mapNotNull { it2 ->
            val inf = infos[it2.symbol] ?: return@mapNotNull null
            val budget = capital * it2.weight / 100
            val unit = inf.price * inf.fx
            var sh = if (unit > 0) Math.floor(budget / unit) else 0.0
            if (wholeLots && inf.market == Market.TW) sh = Math.floor(sh / 1000) * 1000
            val months = inf.byMonth.mapValues { it.value * sh * inf.fx }
            Row(inf, it2.weight, sh, sh * unit, sh * inf.annual * inf.fx, months)
        }
        val monthly = DoubleArray(12)
        rows.forEach { r -> r.months.forEach { (m, v) -> monthly[m - 1] += v } }
        val invested = rows.sumOf { it.cost }
        val annual = rows.sumOf { it.annualTwd }
        return Result(rows, invested, annual, monthly, if (invested > 0) annual / invested * 100 else 0.0)
    }

    // ---------------- 推薦 ----------------
    /** 推薦候選：常見配息 ETF（股票型、債券型）＋持股與自選中的 ETF */
    val POOL = listOf(
        "0056", "00878", "00919", "00713", "00915", "00918", "00929", "00934", "00939", "00940", "00946", "00900", "00701", "00731",
        "0050", "006208", "00692", "00850",
        "00679B", "00687B", "00720B", "00937B", "00772B", "00751B", "00795B",
    )

    suspend fun loadPool(): List<Info> = coroutineScope {
        val syms = (POOL + AppState.holdings.map { it.symbol } + AppState.quotes.map { it.symbol }.filter { it.startsWith("00") }).distinct()
        syms.map { s -> async(Dispatchers.IO) { runCatching { ensure(s) }.getOrNull() } }.awaitAll().filterNotNull()
            .filter { it.annual > 0 && it.yieldPct in 0.5..15.0 }
    }

    data class Pick(val title: String, val desc: String, val items: List<Item>, val source: String = "App")

    /** App 規則推薦：月月配（配息月份錯開）、高股息、股債平衡、市值型＋高息 */
    fun appPicks(pool: List<Info>): List<Pick> {
        val out = ArrayList<Pick>()
        val eq = pool.filter { !it.bond }
        val bonds = pool.filter { it.bond }
        // 1. 月月配：從季配／月配中挑出能覆蓋 12 個月、平均殖利率最高的組合（最多 4 檔）
        run {
            val cands = eq.filter { it.byMonth.size >= 4 }.sortedByDescending { it.yieldPct }.take(12)
            var best: List<Info>? = null; var bestScore = -1.0
            fun cover(l: List<Info>) = l.flatMap { it.byMonth.keys }.toSet().size
            for (i in cands.indices) for (j in i + 1 until cands.size) for (k in j + 1 until cands.size) {
                val l = listOf(cands[i], cands[j], cands[k])
                if (cover(l) == 12) { val sc = l.sumOf { it.yieldPct }; if (sc > bestScore) { bestScore = sc; best = l } }
            }
            if (best == null) cands.firstOrNull { it.byMonth.size >= 12 }?.let { best = listOf(it) }
            best?.let { l -> out += Pick("月月配", "配息月份錯開，每個月都有現金流：" + l.joinToString("、") { it.symbol + " " + it.name.take(6) },
                l.map { Item(it.symbol, 100.0 / l.size) }) }
        }
        // 2. 高股息：殖利率最高的 4 檔股票型
        eq.sortedByDescending { it.yieldPct }.take(4).takeIf { it.size >= 2 }?.let { l ->
            out += Pick("高股息", "近一年殖利率最高的 ${l.size} 檔股票型，平均配置", l.map { Item(it.symbol, 100.0 / l.size) })
        }
        // 3. 股債平衡：高息股票型 2 檔 60%、債券型 2 檔 40%
        val e2 = eq.sortedByDescending { it.yieldPct }.take(2); val b2 = bonds.sortedByDescending { it.yieldPct }.take(2)
        if (e2.size == 2 && b2.isNotEmpty()) out += Pick("股債平衡", "股票型 60%、債券型 40%，波動較低",
            e2.map { Item(it.symbol, 30.0) } + b2.map { Item(it.symbol, 40.0 / b2.size) })
        // 4. 市值＋高息：市值型 50%（成長）＋高股息 50%（現金流）
        val core = pool.firstOrNull { it.symbol == "0050" } ?: pool.firstOrNull { it.symbol == "006208" }
        val inc = eq.filter { it.symbol != core?.symbol && it.symbol !in setOf("0050", "006208") }.maxByOrNull { it.yieldPct }
        if (core != null && inc != null) out += Pick("市值＋高息", "市值型追求長期成長、高股息提供現金流，各半",
            listOf(Item(core.symbol, 50.0), Item(inc.symbol, 50.0)))
        return out
    }

    /** AI 推薦：把候選的價格、殖利率、配息月份交給 AI，請它依目標挑組合（只能從候選中選） */
    suspend fun aiPick(pool: List<Info>, capital: Double, monthlyTarget: Double, pref: String): Pick {
        val data = JSONArray().apply {
            pool.sortedByDescending { it.yieldPct }.take(30).forEach { i ->
                put(JSONObject().put("symbol", i.symbol).put("name", i.name).put("type", if (i.bond) "債券ETF" else if (i.isEtf) "股票ETF" else "個股")
                    .put("yield_pct", Math.round(i.yieldPct * 100) / 100.0).put("freq", i.freq).put("pay_months", JSONArray(i.byMonth.keys.sorted())))
            }
        }
        val sys = "你是台灣的資產配置助理。只能從使用者提供的候選清單中挑選 2～5 檔組成配息組合，權重合計 100。" +
            "不得編造清單外的代號或數字。只輸出 JSON：{\"title\":\"組合名稱（10字內）\",\"items\":[{\"symbol\":\"代號\",\"weight\":數字}],\"reason\":\"繁體中文理由（80字內）\"}"
        val user = "本金：${Math.round(capital)} 元台幣；每月目標配息：${Math.round(monthlyTarget)} 元；偏好：$pref。\n候選（近一年殖利率與配息月份已算好）：\n$data"
        val (text, by) = AiAnalysis.ask(sys, user, 800)
        val js = text.substring(text.indexOf('{').coerceAtLeast(0), (text.lastIndexOf('}') + 1).coerceAtLeast(1))
        val o = JSONObject(js)
        val valid = pool.map { it.symbol }.toSet()
        val arr = o.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .map { Item(it.optString("symbol").trim().uppercase(), it.optDouble("weight", 0.0)) }
            .filter { it.symbol in valid && it.weight > 0 }.distinctBy { it.symbol }
        if (items.isEmpty()) throw IllegalStateException("AI 回應的組合不在候選清單中")
        return Pick("AI：" + o.optString("title", "推薦組合").take(12), o.optString("reason").take(160), normalize(items), by)
    }
}
