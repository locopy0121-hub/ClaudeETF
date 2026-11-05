package com.claudeetf.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import com.claudeetf.app.data.remote.RemoteQuote
import kotlin.math.max

/**
 * App 的單一資料來源。第一階段使用內建示意行情，持股、交易、提醒與設定會存在手機本機。
 * 第二階段把 quotes 換成 Finnhub（美股）與證交所 OpenAPI（台股）的即時資料即可，畫面不用改。
 */
object AppState {
    private var prefs: SharedPreferences? = null

    var colorMode by mutableStateOf(ColorMode.TW)
        private set
    var usdTwd by mutableDoubleStateOf(32.02)
        private set
    var realizedTwdYtd by mutableDoubleStateOf(56_800.0)
        private set
    var dividendTwYtd by mutableDoubleStateOf(61_300.0)
        private set
    var dividendUsGrossYtd by mutableDoubleStateOf(1_120.73)
        private set
    var cashTwd by mutableDoubleStateOf(164_000.0)
        private set
    var hideAmounts by mutableStateOf(false)

    /** 使用者自行填入的 API 金鑰（自動儲存在本機） */
    var fugleKey by mutableStateOf("")
        private set
    var finnhubKey by mutableStateOf("")
        private set
    var refreshing by mutableStateOf(false)
    var fxSource by mutableStateOf("手動輸入")
        private set
    var fxUpdatedAt by mutableLongStateOf(0L)
        private set
    /** 每檔證券的稅務類型與應稅比例（使用者調整後的值） */
    val taxKindOverride = mutableStateMapOf<String, TaxKind>()
    val taxRatioOverride = mutableStateMapOf<String, Double>()

    /** 各市場最近一次更新結果，key：TW / US / FX */
    val sourceStatus = mutableStateMapOf<String, String>()

    val quotes = mutableStateListOf<Quote>().apply { addAll(SampleData.quotes) }
    val holdings = mutableStateListOf<Holding>()
    val trades = mutableStateListOf<Trade>()
    val alerts = mutableStateListOf<AlertRule>()
    val twIndices = mutableStateListOf<IndexQuote>().apply { addAll(SampleData.twIndices) }
    val usIndices = mutableStateListOf<IndexQuote>().apply { addAll(SampleData.usIndices) }
    val macro = mutableStateListOf<IndexQuote>().apply { addAll(SampleData.macro) }

    // ---------- 初始化與儲存 ----------

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("claudeetf", Context.MODE_PRIVATE)
        prefs = p
        colorMode = runCatching { ColorMode.valueOf(p.getString("colorMode", "TW") ?: "TW") }
            .getOrDefault(ColorMode.TW)
        usdTwd = p.getFloat("usdTwd", 32.02f).toDouble()
        realizedTwdYtd = p.getFloat("realized", 56_800f).toDouble()
        dividendTwYtd = p.getFloat("divTw", 61_300f).toDouble()
        dividendUsGrossYtd = p.getFloat("divUs", 1_120.73f).toDouble()
        cashTwd = p.getFloat("cash", 164_000f).toDouble()
        fugleKey = p.getString("fugleKey", "") ?: ""
        finnhubKey = p.getString("finnhubKey", "") ?: ""
        fxSource = p.getString("fxSource", "手動輸入") ?: "手動輸入"
        fxUpdatedAt = p.getLong("fxUpdatedAt", 0L)

        p.getString("taxKinds", null)?.let { json ->
            runCatching {
                val o = JSONObject(json)
                o.keys().forEach { k -> runCatching { taxKindOverride[k] = TaxKind.valueOf(o.getString(k)) } }
            }
        }
        p.getString("taxRatios", null)?.let { json ->
            runCatching {
                val o = JSONObject(json)
                o.keys().forEach { k -> taxRatioOverride[k] = o.getDouble(k) }
            }
        }

        p.getString("quotes", null)?.let { json ->
            val saved = parseQuotes(json)
            if (saved.isNotEmpty()) {
                quotes.clear()
                quotes.addAll(saved)
            }
        }

        holdings.clear()
        val savedHoldings = p.getString("holdings", null)
        if (savedHoldings == null) holdings.addAll(SampleData.holdings)
        else holdings.addAll(parseHoldings(savedHoldings))

        alerts.clear()
        alerts.addAll(SampleData.alerts)
        val enabled = p.getString("alerts", null)
        if (enabled != null) {
            val on = enabled.split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet()
            for (i in alerts.indices) alerts[i] = alerts[i].copy(enabled = alerts[i].id in on)
        }

        trades.clear()
        p.getString("trades", null)?.let { trades.addAll(parseTrades(it)) }

        // 交易中新增過的股票也要放回行情清單
        for (h in holdings) ensureQuote(h.symbol, h.avgCost)
    }

    private fun save() {
        val p = prefs ?: return
        p.edit()
            .putString("colorMode", colorMode.name)
            .putFloat("usdTwd", usdTwd.toFloat())
            .putFloat("realized", realizedTwdYtd.toFloat())
            .putFloat("divTw", dividendTwYtd.toFloat())
            .putFloat("divUs", dividendUsGrossYtd.toFloat())
            .putFloat("cash", cashTwd.toFloat())
            .putString("holdings", holdingsJson())
            .putString("trades", tradesJson())
            .putString("alerts", alerts.filter { it.enabled }.joinToString(",") { it.id.toString() })
            .putString("fugleKey", fugleKey)
            .putString("finnhubKey", finnhubKey)
            .putString("fxSource", fxSource)
            .putLong("fxUpdatedAt", fxUpdatedAt)
            .putString("quotes", quotesJson())
            .putString("taxKinds", JSONObject().apply { taxKindOverride.forEach { (k, v) -> put(k, v.name) } }.toString())
            .putString("taxRatios", JSONObject().apply { taxRatioOverride.forEach { (k, v) -> put(k, v) } }.toString())
            .apply()
    }

    // ---------- 查詢 ----------

    fun quote(symbol: String): Quote? = quotes.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }

    fun marketOf(symbol: String): Market =
        quote(symbol)?.market ?: if (symbol.firstOrNull()?.isDigit() == true) Market.TW else Market.US

    /** 持股市值（原幣） */
    fun marketValue(h: Holding): Double = h.shares * (quote(h.symbol)?.price ?: h.avgCost)

    fun fxFor(market: Market): Double = if (market == Market.US) usdTwd else 1.0

    fun marketValueTwd(h: Holding): Double = marketValue(h) * fxFor(marketOf(h.symbol))

    fun costTwd(h: Holding): Double =
        h.shares * h.avgCost * (if (marketOf(h.symbol) == Market.US) h.avgFx else 1.0)

    fun holdingsOf(market: Market) = holdings.filter { marketOf(it.symbol) == market }

    fun totalMarketValueTwd(market: Market? = null): Double =
        holdings.filter { market == null || marketOf(it.symbol) == market }.sumOf { marketValueTwd(it) }

    fun totalCostTwd(): Double = holdings.sumOf { costTwd(it) }

    fun totalAssetsTwd(): Double = totalMarketValueTwd() + cashTwd

    fun todayPnlTwd(): Double {
        var total = 0.0
        for (h in holdings) {
            val q = quote(h.symbol) ?: continue
            total += h.shares * q.change * fxFor(q.market)
        }
        return total
    }

    /** 美股損益拆成「股價」與「匯兌」兩部分（台幣） */
    fun usPnlBreakdown(): Pair<Double, Double> {
        var pricePart = 0.0
        var fxPart = 0.0
        for (h in holdingsOf(Market.US)) {
            val q = quote(h.symbol) ?: continue
            pricePart += h.shares * (q.price - h.avgCost) * usdTwd
            fxPart += h.shares * h.avgCost * (usdTwd - h.avgFx)
        }
        return pricePart to fxPart
    }

    // ---------- 行情資料 ----------

    fun updateFugleKey(key: String) {
        fugleKey = key.trim()
        save()
    }

    fun updateFinnhubKey(key: String) {
        finnhubKey = key.trim()
        save()
    }

    fun setStatus(market: String, text: String) {
        sourceStatus[market] = text
    }

    /** 把遠端報價合併進清單（保留 ETF 標記與基本面） */
    fun applyRemote(
        rq: RemoteQuote,
        source: QuoteSource,
        now: Long,
        persist: Boolean = false,
        sparkOverride: List<Float>? = null,
    ) {
        val i = quotes.indexOfFirst { it.symbol.equals(rq.symbol, ignoreCase = true) }
        if (i < 0) return
        val old = quotes[i]
        // 沒有逐筆走勢時，用 昨收→開盤→低/高→高/低→現價 畫出當日輪廓
        val up = rq.price >= rq.prevClose
        val spark = listOfNotNull(
            rq.prevClose, rq.open,
            if (up) rq.low else rq.high,
            if (up) rq.high else rq.low,
            rq.price,
        ).map { it.toFloat() }.let { if (sparkOverride != null && sparkOverride.size >= 2) sparkOverride else it }
        quotes[i] = old.copy(
            name = if (old.name == old.symbol && rq.name != null) rq.name else old.name,
            price = rq.price,
            prevClose = rq.prevClose,
            spark = spark,
            source = source,
            updatedAt = now,
        )
        if (persist) save()
    }

    /** 更新指數（依 symbol 對應到 twIndices／usIndices／macro） */
    fun applyIndex(symbol: String, value: Double, prev: Double, now: Long) {
        val pct = if (prev > 0) (value - prev) / prev * 100 else 0.0
        for (list in listOf(twIndices, usIndices, macro)) {
            val i = list.indexOfFirst { it.symbol == symbol }
            if (i >= 0) list[i] = list[i].copy(value = value, changePct = pct, updatedAt = now)
        }
    }

    fun indexSymbols(): List<String> = (twIndices + usIndices + macro).map { it.symbol }.filter { it.isNotEmpty() }

    fun setFx(mid: Double, source: String, now: Long) {
        if (mid <= 0) return
        usdTwd = Math.round(mid * 1000) / 1000.0
        fxSource = source
        fxUpdatedAt = now
        save()
    }

    fun persist() = save()

    /** kind = null 代表恢復自動判斷 */
    fun setTaxKind(symbol: String, kind: TaxKind?) {
        if (kind == null) taxKindOverride.remove(symbol) else taxKindOverride[symbol] = kind
        taxRatioOverride.remove(symbol) // 換類型時比例回到該類型預設
        save()
    }

    fun setTaxRatio(symbol: String, ratio: Double?) {
        if (ratio == null) taxRatioOverride.remove(symbol) else taxRatioOverride[symbol] = ratio.coerceIn(0.0, 1.0)
        save()
    }

    fun isWatched(symbol: String) = quote(symbol) != null

    /** 加入自選；台股代號以數字開頭 */
    fun addWatch(symbol: String, name: String? = null): Boolean {
        val s = symbol.trim().uppercase()
        if (s.isEmpty() || quote(s) != null) return false
        val market = if (s.first().isDigit()) Market.TW else Market.US
        quotes.add(Quote(s, name ?: s, market, 0.0, 0.0, isEtf = market == Market.TW && s.startsWith("00")))
        save()
        return true
    }

    /** 移出自選（仍有持股的不移除） */
    fun removeWatch(symbol: String): Boolean {
        if (holdings.any { it.symbol.equals(symbol, true) }) return false
        val removed = quotes.removeAll { it.symbol.equals(symbol, true) }
        if (removed) save()
        return removed
    }

    // ---------- 動作 ----------

    fun updateColorMode(mode: ColorMode) {
        colorMode = mode
        save()
    }

    fun updateUsdTwd(value: Double) {
        if (value > 0) {
            usdTwd = value
            fxSource = "手動輸入"
            save()
        }
    }

    fun toggleAlert(id: Int) {
        val i = alerts.indexOfFirst { it.id == id }
        if (i >= 0) {
            alerts[i] = alerts[i].copy(enabled = !alerts[i].enabled)
            save()
        }
    }

    private fun ensureQuote(symbol: String, price: Double) {
        if (quote(symbol) != null) return
        val market = if (symbol.firstOrNull()?.isDigit() == true) Market.TW else Market.US
        quotes.add(Quote(symbol.uppercase(), symbol.uppercase(), market, price, price))
    }

    /** 回傳錯誤訊息；成功回傳 null */
    fun addTrade(t: Trade): String? {
        if (t.symbol.isBlank()) return "請輸入股票代號"
        if (t.shares <= 0 && t.type != TradeType.SPLIT) return "股數必須大於 0"
        if (t.price <= 0) return if (t.type == TradeType.SPLIT) "請輸入拆股比例" else "請輸入價格"
        val symbol = t.symbol.trim().uppercase()
        ensureQuote(symbol, t.price)
        val market = marketOf(symbol)
        val fx = if (market == Market.US) t.fx else 1.0
        val idx = holdings.indexOfFirst { it.symbol == symbol }
        val existing = if (idx >= 0) holdings[idx] else null

        when (t.type) {
            TradeType.BUY -> {
                val addCost = t.shares * t.price + t.fee
                val oldCost = (existing?.shares ?: 0.0) * (existing?.avgCost ?: 0.0)
                val newShares = (existing?.shares ?: 0.0) + t.shares
                val newAvg = (oldCost + addCost) / newShares
                val newFx = if (market == Market.US) {
                    val oldFxCost = oldCost * (existing?.avgFx ?: fx)
                    (oldFxCost + addCost * fx) / (oldCost + addCost)
                } else 1.0
                val h = Holding(symbol, newShares, newAvg, newFx)
                if (idx >= 0) holdings[idx] = h else holdings.add(h)
                cashTwd = max(0.0, cashTwd - addCost * fx)
            }
            TradeType.SELL -> {
                if (existing == null) return "目前沒有 $symbol 的持股"
                if (t.shares > existing.shares + 1e-9) return "賣出股數超過持有股數"
                val proceeds = t.shares * t.price - t.fee - t.tax
                val costFx = if (market == Market.US) existing.avgFx else 1.0
                realizedTwdYtd += proceeds * fx - t.shares * existing.avgCost * costFx
                cashTwd += proceeds * fx
                val left = existing.shares - t.shares
                if (left <= 1e-9) holdings.removeAt(idx) else holdings[idx] = existing.copy(shares = left)
            }
            TradeType.DIVIDEND -> {
                // 股數 × 每股股息；美股預扣 30%
                val gross = t.shares * t.price
                if (market == Market.US) {
                    dividendUsGrossYtd += gross
                    cashTwd += gross * 0.7 * fx
                } else {
                    dividendTwYtd += gross - t.fee
                    cashTwd += gross - t.fee
                }
            }
            TradeType.SPLIT -> {
                if (existing == null) return "目前沒有 $symbol 的持股"
                val ratio = t.price
                holdings[idx] = existing.copy(shares = existing.shares * ratio, avgCost = existing.avgCost / ratio)
            }
        }
        trades.add(0, t.copy(symbol = symbol))
        save()
        return null
    }

    // ---------- JSON ----------

    private fun quotesJson(): String = JSONArray().apply {
        quotes.forEach {
            put(
                JSONObject().put("s", it.symbol).put("n", it.name).put("m", it.market.name)
                    .put("p", it.price).put("pc", it.prevClose).put("etf", it.isEtf)
                    .put("src", it.source.name).put("t", it.updatedAt)
                    .put("sp", JSONArray(it.spark.map { v -> v.toDouble() }))
            )
        }
    }.toString()

    private fun parseQuotes(json: String): List<Quote> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val sym = o.getString("s")
            val sp = o.optJSONArray("sp")
            Quote(
                symbol = sym,
                name = o.optString("n", sym),
                market = Market.valueOf(o.optString("m", "US")),
                price = o.optDouble("p", 0.0),
                prevClose = o.optDouble("pc", 0.0),
                isEtf = o.optBoolean("etf", false),
                spark = if (sp == null) emptyList() else (0 until sp.length()).map { sp.getDouble(it).toFloat() },
                stats = SampleData.quotes.firstOrNull { it.symbol == sym }?.stats,
                source = runCatching { QuoteSource.valueOf(o.optString("src", "SAMPLE")) }.getOrDefault(QuoteSource.SAMPLE),
                updatedAt = o.optLong("t", 0L),
            )
        }
    }.getOrDefault(emptyList())

    private fun holdingsJson(): String = JSONArray().apply {
        holdings.forEach {
            put(JSONObject().put("s", it.symbol).put("n", it.shares).put("c", it.avgCost).put("f", it.avgFx))
        }
    }.toString()

    private fun parseHoldings(json: String): List<Holding> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Holding(o.getString("s"), o.getDouble("n"), o.getDouble("c"), o.optDouble("f", 1.0))
        }
    }.getOrDefault(SampleData.holdings)

    private fun tradesJson(): String = JSONArray().apply {
        trades.take(500).forEach {
            put(
                JSONObject().put("t", it.type.name).put("s", it.symbol).put("d", it.date)
                    .put("n", it.shares).put("p", it.price).put("x", it.fx)
                    .put("fee", it.fee).put("tax", it.tax)
            )
        }
    }.toString()

    private fun parseTrades(json: String): List<Trade> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Trade(
                TradeType.valueOf(o.getString("t")), o.getString("s"), o.getString("d"),
                o.getDouble("n"), o.getDouble("p"), o.getDouble("x"), o.getDouble("fee"), o.getDouble("tax"),
            )
        }
    }.getOrDefault(emptyList())
}
