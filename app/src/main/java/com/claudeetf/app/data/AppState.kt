package com.claudeetf.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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

    /** 主題：0 跟隨系統、1 深色、2 淺色；字級倍率 */
    var themeMode by mutableIntStateOf(1)
        private set
    var fontScale by mutableFloatStateOf(1f)
        private set
    var systemDark by mutableStateOf(true)

    /** 是否完成首次使用引導；網路是否連線 */
    var onboarded by mutableStateOf(true)
        private set
    var online by mutableStateOf(true)

    /** App 安全鎖：開啟時需指紋或裝置密碼；locked 為目前是否鎖住 */
    var appLock by mutableStateOf(false)
        private set
    var locked by mutableStateOf(false)
    var backgroundAt = 0L
    fun updateAppLock(on: Boolean) { appLock = on; save() }

    /** 上次匯出完整備份的時間 */
    var lastBackupAt by mutableLongStateOf(0L)
        private set
    fun markBackup() { lastBackupAt = System.currentTimeMillis(); save() }

    /** 每日收盤摘要通知 */
    var dailySummary by mutableStateOf(true)
        private set
    fun updateDailySummary(on: Boolean) { dailySummary = on; save() }

    /** 已送出的摘要（避免重複）：key 為 TW-日期、US-日期 */
    fun summarySent(key: String): Boolean = prefs?.getString("sumSent", "")?.split(",")?.contains(key) == true
    fun markSummarySent(key: String) {
        val p = prefs ?: return
        val list = (p.getString("sumSent", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()).takeLast(6) + key
        p.edit().putString("sumSent", list.joinToString(",")).apply()
    }

    /** 首頁卡片順序與隱藏 */
    val allHomeCards = listOf("assets" to "總資產與走勢", "alloc" to "資產配置", "movers" to "今日持股漲跌",
        "session" to "美股交易時段", "index" to "主要指數", "source" to "資料來源", "goal" to "資產目標")
    val homeOrder = mutableStateListOf<String>()
    val homeHidden = mutableStateListOf<String>()

    fun homeCards(): List<String> = homeOrder.filter { it !in homeHidden }

    fun toggleHomeCard(id: String) {
        if (id in homeHidden) homeHidden.remove(id) else homeHidden.add(id)
        save()
    }

    fun moveHomeCard(id: String, delta: Int) {
        val i = homeOrder.indexOf(id); val j = i + delta
        if (i < 0 || j < 0 || j >= homeOrder.size) return
        homeOrder.removeAt(i); homeOrder.add(j, id); save()
    }

    fun finishOnboarding() { onboarded = true; prefs?.edit()?.putBoolean("onboarded", true)?.apply() }

    fun updateTheme(mode: Int) { themeMode = mode; save() }
    fun updateFontScale(scale: Float) { fontScale = scale; save() }

    /** 使用者自行填入的 API 金鑰（自動儲存在本機） */
    var fugleKey by mutableStateOf("")
        private set

    /** 台股盤中來源：fugle 僅用富果（預設）、twse 以證交所即時為主 */
    var twIntradaySource by mutableStateOf("fugle")
    fun updateTwIntradaySource(s: String) { twIntradaySource = s; save() }
    val twUseFugle: Boolean get() = twIntradaySource != "twse"
    var finnhubKey by mutableStateOf("")
        private set
    var refreshing by mutableStateOf(false)
    /** 盤中逐筆價格（急漲急跌偵測用，只保留 10 分鐘） */
    val ticks = HashMap<String, ArrayDeque<Pair<Long, Double>>>()

    private fun recordTick(symbol: String, price: Double) {
        val now = System.currentTimeMillis()
        val dq = ticks.getOrPut(symbol) { ArrayDeque() }
        if (dq.isEmpty() || dq.last().second != price || now - dq.last().first > 30_000) dq.addLast(now to price)
        while (dq.isNotEmpty() && now - dq.first().first > 10 * 60_000) dq.removeFirst()
    }

    /** 5 分鐘前（含）最接近的價格 */
    fun priceMinutesAgo(symbol: String, minutes: Int): Double? {
        val dq = ticks[symbol] ?: return null
        val cutoff = System.currentTimeMillis() - minutes * 60_000L
        if (dq.isEmpty() || dq.first().first > cutoff + 60_000) return null // 資料不足 4 分鐘
        return dq.lastOrNull { it.first <= cutoff }?.second ?: dq.first().second
    }

    /** 美股延長盤：代號 → (盤前／盤後, 價格, 漲跌%, 時間) */
    data class ExtQuote(val label: String, val price: Double, val changePct: Double, val time: Long)

    /** 當日高低與成交量（股），自選欄位與小工具使用 */
    data class DayStat(val high: Double?, val low: Double?, val volume: Double?, val at: Long)
    val dayStats = mutableStateMapOf<String, DayStat>()

    /** 逐筆成交明細（最新在前，每檔最多 200 筆，跨日清空） */
    val tape = mutableStateMapOf<String, List<TapeTick>>()
    fun recordTape(symbol: String, t: TapeTick) {
        if (t.lots <= 0 || t.price <= 0) return
        val tpe = java.time.ZoneId.of("Asia/Taipei")
        val day = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(tpe).toLocalDate() }
        val old = tape[symbol].orEmpty().let { l -> if (l.isNotEmpty() && day(l.first().time) != day(t.time)) emptyList() else l }
        if (old.any { it.time == t.time && it.price == t.price && it.lots == t.lots }) return
        if (old.isNotEmpty() && t.time < old.first().time) return
        tape[symbol] = (listOf(t) + old).take(200)
    }

    /** 持股產業（代號 → 產業），存在本機 */
    val industries = mutableStateMapOf<String, String>()
    fun putIndustries(m: Map<String, String>) { if (m.isEmpty()) return; industries.putAll(m); save() }

    /** 每月 1 日推播上月投資月報；lastMonthlyReport 記錄已推播的月份 */
    var monthlyReport by mutableStateOf(true)
    fun updateMonthlyReport(on: Boolean) { monthlyReport = on; save() }

    /** 三關價（代號 → 當日上中下關），個股走勢與自選欄位共用 */
    val keyPrices = mutableStateMapOf<String, StockInfoRepo.KeyPrices>()
    /** 自選除權息、法人欄位 */
    val divBriefs = mutableStateMapOf<String, WatchExtras.DivBrief>()
    val institutional = mutableStateMapOf<String, com.claudeetf.app.data.remote.TwInstitutional.Day>()
    val twDaily = mutableStateMapOf<String, WatchExtras.TwDaily>()
    var showKeyLevels by mutableStateOf(true)
    /** 台股分時 Y 軸用漲跌停價 */
    var limitAxis by mutableStateOf(true)
    fun updateLimitAxis(on: Boolean) { limitAxis = on; save() }
    fun updateShowKeyLevels(on: Boolean) { showKeyLevels = on; save() }
    /** 自選版面：list 清單、card 卡片 */
    var watchLayout by mutableStateOf("list")
    fun updateWatchLayout(l: String) { watchLayout = l; save() }

    /** 市場頁類股：false 方塊、true 依漲跌排行 */
    var sectorRanked by mutableStateOf(false)

    /** 資產目標（TWD，0 = 未設定）與目標年月 yyyy-MM */
    var goalAmount by mutableDoubleStateOf(0.0)
    var goalMonth by mutableStateOf("")
    fun updateGoal(amount: Double, month: String) { goalAmount = amount; goalMonth = month; save() }

    /** 自選清單中間欄：spark 走勢、volume 成交量、amp 振幅、pe 本益比、pnl 持股損益 */
    var watchColumn by mutableStateOf("spark")
    fun updateWatchColumn(c: String) { watchColumn = c; save() }
    val extended = mutableStateMapOf<String, ExtQuote>()

    /** 富果串流狀態文字 */
    var streamStatus by mutableStateOf("")

    /** 最近一次即時輪詢時間 */
    var lastLiveAt by mutableLongStateOf(0L)
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
    val accounts = mutableStateListOf<Account>()
    val dcaPlans = mutableStateListOf<DcaPlan>()
    val groups = mutableStateListOf<WatchGroup>()
    val marks = mutableStateMapOf<String, PriceMark>()
    /** 偵測到尚未記錄的除息與拆股；已略過的項目 */
    val pendingActions = mutableStateListOf<CorporateActions.Pending>()
    val dismissedActions = mutableStateListOf<String>()

    /** 已推播過的偵測項目（避免重複通知） */
    fun actionNotified(key: String) = prefs?.getString("actNotified", "")?.split("|")?.contains(key) == true
    fun markActionNotified(keys: List<String>) {
        val p = prefs ?: return
        val list = (p.getString("actNotified", "")?.split("|")?.filter { it.isNotBlank() } ?: emptyList()) + keys
        p.edit().putString("actNotified", list.takeLast(300).joinToString("|")).apply()
    }

    fun dismissAction(key: String) {
        if (key !in dismissedActions) dismissedActions.add(key)
        while (dismissedActions.size > 300) dismissedActions.removeAt(0)
        save()
    }

    /** 個股筆記：代號 → (內容, 更新時間) */
    val notes = mutableStateMapOf<String, Pair<String, Long>>()

    fun setNote(symbol: String, text: String) {
        if (text.isBlank()) notes.remove(symbol) else notes[symbol] = text to System.currentTimeMillis()
        save()
    }
    /** 代號 → 目標配置 %（再平衡用） */
    val targets = mutableStateMapOf<String, Double>()
    /** 最近觸發的提醒（新到舊，最多 30 筆） */
    val alertHistory = mutableStateListOf<AlertHit>()
    val twIndices = mutableStateListOf<IndexQuote>().apply { addAll(SampleData.twIndices) }
    val usIndices = mutableStateListOf<IndexQuote>().apply { addAll(SampleData.usIndices) }
    val macro = mutableStateListOf<IndexQuote>().apply { addAll(SampleData.macro) }

    // ---------- 初始化與儲存 ----------

    fun init(context: Context) {
        if (prefs != null) return
        com.claudeetf.app.data.remote.Http.cacheDir = java.io.File(context.applicationContext.cacheDir, "http")
        val p = context.getSharedPreferences("claudeetf", Context.MODE_PRIVATE)
        prefs = p
        colorMode = runCatching { ColorMode.valueOf(p.getString("colorMode", "TW") ?: "TW") }
            .getOrDefault(ColorMode.TW)
        usdTwd = p.getFloat("usdTwd", 32.02f).toDouble()
        themeMode = p.getInt("themeMode", 1)
        onboarded = p.getBoolean("onboarded", false)
        dailySummary = p.getBoolean("dailySummary", true)
        appLock = p.getBoolean("appLock", false)
        locked = appLock
        lastBackupAt = p.getLong("lastBackupAt", 0L)
        homeOrder.clear()
        val savedOrder = p.getString("homeOrder", null)?.split(",")?.filter { id -> allHomeCards.any { it.first == id } } ?: emptyList()
        homeOrder.addAll(savedOrder + allHomeCards.map { it.first }.filter { it !in savedOrder })
        homeHidden.clear()
        p.getString("homeHidden", null)?.split(",")?.filter { it.isNotBlank() }?.let { homeHidden.addAll(it) }
        fontScale = p.getFloat("fontScale", 1f)
        watchColumn = p.getString("watchColumn", "spark") ?: "spark"
        goalAmount = p.getLong("goalAmount", 0L).toDouble()
        goalMonth = p.getString("goalMonth", "") ?: ""
        realizedTwdYtd = p.getFloat("realized", 56_800f).toDouble()
        dividendTwYtd = p.getFloat("divTw", 61_300f).toDouble()
        dividendUsGrossYtd = p.getFloat("divUs", 1_120.73f).toDouble()
        cashTwd = p.getFloat("cash", 164_000f).toDouble()
        fugleKey = p.getString("fugleKey", "") ?: ""
        twIntradaySource = p.getString("twIntradaySource", "fugle") ?: "fugle"
        monthlyReport = p.getBoolean("monthlyReport", true)
        showKeyLevels = p.getBoolean("showKeyLevels", true)
        limitAxis = p.getBoolean("limitAxis", true)
        watchLayout = p.getString("watchLayout", "list") ?: "list"
        industries.clear()
        p.getString("industries", null)?.let { json ->
            runCatching { val o = JSONObject(json); o.keys().forEach { k -> industries[k] = o.getString(k) } }
        }
        finnhubKey = p.getString("finnhubKey", "") ?: ""
        fxSource = p.getString("fxSource", "手動輸入") ?: "手動輸入"
        fxUpdatedAt = p.getLong("fxUpdatedAt", 0L)

        taxKindOverride.clear()
        taxRatioOverride.clear()
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
        val savedAlerts = p.getString("alertsV2", null)
        if (savedAlerts == null) alerts.addAll(SampleData.alerts) else alerts.addAll(parseAlerts(savedAlerts))
        alertHistory.clear()
        p.getString("alertHistory", null)?.let { json ->
            runCatching {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    alertHistory.add(AlertHit(o.getLong("t"), o.getString("x")))
                }
            }
        }

        trades.clear()
        p.getString("trades", null)?.let { trades.addAll(parseTrades(it)) }

        accounts.clear()
        p.getString("accounts", null)?.let { json ->
            runCatching {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    accounts.add(
                        Account(
                            o.getInt("id"), o.getString("n"), Market.valueOf(o.getString("m")),
                            o.optDouble("d", 1.0), o.optDouble("min", 20.0), o.optDouble("r", 0.001), o.optDouble("umin", 0.0),
                        )
                    )
                }
            }
        }
        dcaPlans.clear()
        p.getString("dca", null)?.let { json ->
            runCatching {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    dcaPlans.add(
                        DcaPlan(
                            o.getInt("id"), o.getString("s"), o.getDouble("a"), o.getInt("d"), o.optInt("acc", 0),
                            o.optBoolean("on", true), o.optString("done"), o.optString("ntf"),
                        )
                    )
                }
            }
        }
        dismissedActions.clear()
        p.getString("dismissed", null)?.split("|")?.filter { it.isNotBlank() }?.let { dismissedActions.addAll(it) }
        notes.clear()
        p.getString("notes", null)?.let { json ->
            runCatching {
                val o = JSONObject(json)
                o.keys().forEach { k -> val n = o.getJSONObject(k); notes[k] = n.getString("x") to n.optLong("t") }
            }
        }
        marks.clear()
        p.getString("marks", null)?.let { json ->
            runCatching {
                val o = JSONObject(json)
                o.keys().forEach { k ->
                    val m = o.getJSONObject(k)
                    marks[k] = PriceMark(
                        m.optDouble("t", Double.NaN).takeIf { !it.isNaN() }, m.optDouble("s", Double.NaN).takeIf { !it.isNaN() },
                        m.optInt("ta", 0), m.optInt("sa", 0),
                    )
                }
            }
        }
        groups.clear()
        p.getString("groups", null)?.let { json ->
            runCatching {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val syms = o.optJSONArray("s")
                    groups.add(WatchGroup(o.getInt("id"), o.getString("n"), if (syms == null) emptyList() else (0 until syms.length()).map { syms.getString(it) }))
                }
            }
        }
        targets.clear()
        p.getString("targets", null)?.let { json ->
            runCatching { val o = JSONObject(json); o.keys().forEach { k -> targets[k] = o.getDouble(k) } }
        }
        if (accounts.isEmpty()) {
            accounts.add(Account(1, "台股帳戶", Market.TW))
            accounts.add(Account(2, "美股帳戶", Market.US))
        }

        navHistory.clear()
        p.getString("nav", null)?.let { json ->
            runCatching { val o = JSONObject(json); o.keys().forEach { k -> navHistory[k] = o.getDouble(k) } }
        }

        // 交易中新增過的股票也要放回行情清單
        for (h in holdings) ensureQuote(h.symbol, h.avgCost)
        migrateLedger(p)
    }

    private fun save() {
        val p = prefs ?: return
        p.edit()
            .putString("colorMode", colorMode.name)
            .putFloat("usdTwd", usdTwd.toFloat())
            .putInt("themeMode", themeMode)
            .putBoolean("dailySummary", dailySummary)
            .putBoolean("appLock", appLock)
            .putLong("lastBackupAt", lastBackupAt)
            .putString("homeOrder", homeOrder.joinToString(","))
            .putString("homeHidden", homeHidden.joinToString(","))
            .putFloat("fontScale", fontScale)
            .putString("watchColumn", watchColumn)
            .putLong("goalAmount", Math.round(goalAmount))
            .putString("goalMonth", goalMonth)
            .putFloat("realized", realizedTwdYtd.toFloat())
            .putFloat("divTw", dividendTwYtd.toFloat())
            .putFloat("divUs", dividendUsGrossYtd.toFloat())
            .putFloat("cash", cashTwd.toFloat())
            .putString("holdings", holdingsJson())
            .putString("trades", tradesJson())
            .putString("alertsV2", alertsJson())
            .putString("alertHistory", JSONArray().apply { alertHistory.take(60).forEach { put(JSONObject().put("t", it.time).put("x", it.text)) } }.toString())
            .putString("fugleKey", fugleKey)
            .putString("twIntradaySource", twIntradaySource)
            .putBoolean("monthlyReport", monthlyReport)
            .putBoolean("showKeyLevels", showKeyLevels)
            .putBoolean("limitAxis", limitAxis)
            .putString("watchLayout", watchLayout)
            .putString("industries", JSONObject().apply { industries.forEach { (k, v) -> put(k, v) } }.toString())
            .putString("finnhubKey", finnhubKey)
            .putString("fxSource", fxSource)
            .putLong("fxUpdatedAt", fxUpdatedAt)
            .putString("quotes", quotesJson())
            .putString("taxKinds", JSONObject().apply { taxKindOverride.forEach { (k, v) -> put(k, v.name) } }.toString())
            .putString("taxRatios", JSONObject().apply { taxRatioOverride.forEach { (k, v) -> put(k, v) } }.toString())
            .putFloat("baseCash", base.cash.toFloat())
            .putInt("baseYear", base.year)
            .putFloat("baseRealized", base.realized.toFloat())
            .putFloat("baseDivTw", base.divTw.toFloat())
            .putFloat("baseDivUs", base.divUsGross.toFloat())
            .putString("accounts", JSONArray().apply {
                accounts.forEach {
                    put(JSONObject().put("id", it.id).put("n", it.name).put("m", it.market.name).put("d", it.discount)
                        .put("min", it.minFee).put("r", it.usRate).put("umin", it.usMinFee))
                }
            }.toString())
            .putString("dca", JSONArray().apply {
                dcaPlans.forEach {
                    put(JSONObject().put("id", it.id).put("s", it.symbol).put("a", it.amount).put("d", it.day)
                        .put("acc", it.account).put("on", it.enabled).put("done", it.lastDone).put("ntf", it.lastNotified))
                }
            }.toString())
            .putString("dismissed", dismissedActions.joinToString("|"))
            .putString("notes", JSONObject().apply { notes.forEach { (k, v) -> put(k, JSONObject().put("x", v.first).put("t", v.second)) } }.toString())
            .putString("marks", JSONObject().apply {
                marks.forEach { (k, m) ->
                    put(k, JSONObject().apply {
                        m.target?.let { put("t", it) }; m.stop?.let { put("s", it) }
                        put("ta", m.targetAlert); put("sa", m.stopAlert)
                    })
                }
            }.toString())
            .putString("groups", JSONArray().apply {
                groups.forEach { put(JSONObject().put("id", it.id).put("n", it.name).put("s", JSONArray(it.symbols))) }
            }.toString())
            .putString("targets", JSONObject().apply { targets.forEach { (k, v) -> put(k, v) } }.toString())
            .putString("nav", JSONObject().apply { navHistory.forEach { (k, v) -> put(k, v) } }.toString())
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
        /** 盤中即時更新：保留當日走勢，接上最新價格 */
        live: Boolean = false,
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
        ).map { it.toFloat() }.let {
            when {
                sparkOverride != null && sparkOverride.size >= 2 -> sparkOverride
                live && old.spark.size >= 2 && old.updatedAt > 0 &&
                    java.time.Instant.ofEpochMilli(old.updatedAt).atZone(java.time.ZoneId.of("Asia/Taipei")).toLocalDate() ==
                    java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")) ->
                    (if (old.price == rq.price) old.spark else old.spark + rq.price.toFloat()).takeLast(120)
                else -> it
            }
        }
        if (live) recordTick(old.symbol, rq.price)
        if (rq.high != null || rq.low != null || rq.volume != null) {
            val prevStat = dayStats[old.symbol]
            dayStats[old.symbol] = DayStat(rq.high ?: prevStat?.high, rq.low ?: prevStat?.low, rq.volume ?: prevStat?.volume, now)
        }
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
            alerts[i] = alerts[i].copy(enabled = !alerts[i].enabled, lastFired = "")
            save()
        }
    }

    fun addAlert(symbol: String, type: AlertType, value: Double) {
        val id = (alerts.maxOfOrNull { it.id } ?: 0) + 1
        alerts.add(0, AlertRule(id, symbol.trim().uppercase(), type, value, true))
        save()
    }

    fun deleteAlert(id: Int) {
        if (alerts.removeAll { it.id == id }) save()
    }

    /** 標記已觸發並寫入歷史 */
    fun markFired(id: Int, key: String, text: String, now: Long) {
        val i = alerts.indexOfFirst { it.id == id }
        if (i >= 0) alerts[i] = alerts[i].copy(lastFired = key)
        logNotice(text, now)
    }

    /** 通知紀錄（所有推播，最多 60 筆） */
    fun logNotice(text: String, now: Long = System.currentTimeMillis()) {
        alertHistory.add(0, AlertHit(now, text))
        while (alertHistory.size > 60) alertHistory.removeAt(alertHistory.lastIndex)
        save()
    }

    private fun alertsJson(): String = JSONArray().apply {
        alerts.forEach {
            put(
                JSONObject().put("id", it.id).put("s", it.symbol).put("t", it.type.name)
                    .put("v", it.value).put("on", it.enabled).put("f", it.lastFired)
            )
        }
    }.toString()

    private fun parseAlerts(json: String): List<AlertRule> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val type = runCatching { AlertType.valueOf(o.getString("t")) }.getOrNull() ?: return@mapNotNull null
            AlertRule(o.getInt("id"), o.optString("s"), type, o.optDouble("v", 0.0), o.optBoolean("on", true), o.optString("f"))
        }
    }.getOrDefault(SampleData.alerts)

    private fun ensureQuote(symbol: String, price: Double) {
        if (quote(symbol) != null) return
        val market = if (symbol.firstOrNull()?.isDigit() == true) Market.TW else Market.US
        quotes.add(Quote(symbol.uppercase(), symbol.uppercase(), market, price, price))
    }

    // ---------- 帳本 ----------

    private var base = LedgerBase(0.0, java.time.LocalDate.now().year, 0.0, 0.0, 0.0)
    /** 賣出的已實現損益、股息實收（台幣），交易紀錄頁使用 */
    val realizedById = mutableStateMapOf<Long, Double>()
    val dividendById = mutableStateMapOf<Long, Double>()

    private fun year() = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).year

    /** 以候選交易重算；成功時套用並回傳 null，失敗回傳錯誤 */
    private fun applyLedger(candidate: List<Trade>, newBase: LedgerBase = base): String? {
        val (res, err) = Ledger.compute(newBase, candidate, year()) { marketOf(it) }
        if (res == null) return err ?: "計算失敗"
        base = newBase
        if (trades.toList() != candidate) {
            trades.clear()
            trades.addAll(candidate.sortedByDescending { Ledger.sortKey(it) })
        }
        holdings.clear(); holdings.addAll(res.holdings)
        cashTwd = res.cash
        realizedTwdYtd = res.realizedYtd
        dividendTwYtd = res.divTwYtd
        dividendUsGrossYtd = res.divUsGrossYtd
        realizedById.clear(); realizedById.putAll(res.realizedById)
        dividendById.clear(); dividendById.putAll(res.dividendById)
        for (h in holdings) ensureQuote(h.symbol, h.avgCost)
        save()
        return null
    }

    fun recompute() { applyLedger(trades.toList()) }

    private fun nextId(): Long = (trades.maxOfOrNull { it.id } ?: 0L) + 1

    private fun validate(t: Trade): String? {
        if (t.symbol.isBlank()) return "請輸入股票代號"
        if (t.shares <= 0 && t.type != TradeType.SPLIT) return "股數必須大於 0"
        if (t.price <= 0) return if (t.type == TradeType.SPLIT) "請輸入拆股比例" else "請輸入價格"
        if (!Regex("^\\d{4}/\\d{2}/\\d{2}$").matches(t.date)) return "日期格式請用 yyyy/MM/dd"
        return null
    }

    /** 回傳錯誤訊息；成功回傳 null */
    fun addTrade(t: Trade): String? {
        validate(t)?.let { return it }
        val symbol = t.symbol.trim().uppercase()
        ensureQuote(symbol, t.price)
        val fixed = t.copy(symbol = symbol, id = nextId(), origin = TradeOrigin.NORMAL)
        return applyLedger(trades.toList() + fixed)
    }

    fun updateTrade(t: Trade): String? {
        validate(t)?.let { return it }
        val symbol = t.symbol.trim().uppercase()
        ensureQuote(symbol, t.price)
        val fixed = t.copy(symbol = symbol)
        return applyLedger(trades.map { if (it.id == t.id) fixed else it })
    }

    fun deleteTrade(id: Long): String? = applyLedger(trades.filter { it.id != id })

    fun trade(id: Long): Trade? = trades.firstOrNull { it.id == id }

    // ---------- 定期定額 ----------

    private fun month() = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Taipei")).toString()

    fun saveDca(plan: DcaPlan) {
        val i = dcaPlans.indexOfFirst { it.id == plan.id }
        if (i >= 0) dcaPlans[i] = plan
        else dcaPlans.add(plan.copy(id = (dcaPlans.maxOfOrNull { it.id } ?: 0) + 1, symbol = plan.symbol.trim().uppercase()))
        if (!isWatched(plan.symbol)) addWatch(plan.symbol)
        save()
    }

    fun deleteDca(id: Int) { if (dcaPlans.removeAll { it.id == id }) save() }

    /** 本月已到扣款日、尚未記錄的計畫 */
    fun dueDca(): List<DcaPlan> {
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"))
        val m = month()
        return dcaPlans.filter { it.enabled && it.lastDone != m && today.dayOfMonth >= minOf(it.day, today.lengthOfMonth()) }
    }

    /** 依現價算出股數並記錄買進；台股取整股、美股到小數 4 位 */
    fun recordDca(plan: DcaPlan): String? {
        val q = quote(plan.symbol) ?: return "找不到 ${plan.symbol} 的報價"
        if (q.price <= 0) return "${plan.symbol} 尚未取得報價，請先更新行情"
        val isUs = q.market == Market.US
        val fee0 = feeFor(plan.account, q.market, plan.amount)
        val budget = plan.amount - fee0
        val shares = if (isUs) Math.floor(budget / q.price * 10000) / 10000 else Math.floor(budget / q.price)
        if (shares <= 0) return "金額不足以買進 1 股"
        val fee = feeFor(plan.account, q.market, shares * q.price)
        val date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))
        val err = addTrade(Trade(TradeType.BUY, plan.symbol, date, shares, q.price, if (isUs) usdTwd else 1.0, fee, 0.0, note = "定期定額", account = plan.account))
        if (err != null) return err
        val i = dcaPlans.indexOfFirst { it.id == plan.id }
        if (i >= 0) dcaPlans[i] = dcaPlans[i].copy(lastDone = month())
        save()
        return null
    }

    fun skipDca(plan: DcaPlan) {
        val i = dcaPlans.indexOfFirst { it.id == plan.id }
        if (i >= 0) { dcaPlans[i] = dcaPlans[i].copy(lastDone = month()); save() }
    }

    fun markDcaNotified(id: Int) {
        val i = dcaPlans.indexOfFirst { it.id == id }
        if (i >= 0) { dcaPlans[i] = dcaPlans[i].copy(lastNotified = month()); save() }
    }

    // ---------- 目標價、停損價、分批 ----------

    /** 設定目標價／停損價，並同步建立價格提醒（null = 清除） */
    fun setMark(symbol: String, target: Double?, stop: Double?) {
        val old = marks[symbol]
        old?.let { alerts.removeAll { a -> a.id == it.targetAlert || a.id == it.stopAlert } }
        var nextId = (alerts.maxOfOrNull { it.id } ?: 0) + 1
        var ta = 0; var sa = 0
        if (target != null && target > 0) { ta = nextId++; alerts.add(0, AlertRule(ta, symbol, AlertType.PRICE_ABOVE, target, true)) }
        if (stop != null && stop > 0) { sa = nextId++; alerts.add(0, AlertRule(sa, symbol, AlertType.PRICE_BELOW, stop, true)) }
        if (ta == 0 && sa == 0) marks.remove(symbol) else marks[symbol] = PriceMark(target, stop, ta, sa)
        save()
    }

    /** 依交易紀錄以先進先出計算目前剩餘的各批成本 */
    fun lots(symbol: String): List<Lot> {
        val lots = ArrayList<Lot>()
        for (t in trades.filter { it.symbol == symbol && it.origin != TradeOrigin.LEGACY }.sortedBy { Ledger.sortKey(it) }) {
            when (t.type) {
                TradeType.BUY -> if (t.shares > 0) lots += Lot(
                    if (t.origin == TradeOrigin.OPENING) "期初" else t.date, t.shares,
                    (t.shares * t.price + t.fee) / t.shares, t.fx, t.origin == TradeOrigin.OPENING,
                )
                TradeType.SELL -> {
                    var left = t.shares
                    while (left > 1e-9 && lots.isNotEmpty()) {
                        val f = lots[0]
                        if (f.shares <= left + 1e-9) { left -= f.shares; lots.removeAt(0) }
                        else { lots[0] = f.copy(shares = f.shares - left); left = 0.0 }
                    }
                }
                TradeType.SPLIT -> if (t.price > 0) for (i in lots.indices) lots[i] = lots[i].copy(shares = lots[i].shares * t.price, unitCost = lots[i].unitCost / t.price)
                TradeType.DIVIDEND -> {}
            }
        }
        return lots
    }

    // ---------- 自選群組與排序 ----------

    fun addGroup(name: String): Int {
        val id = (groups.maxOfOrNull { it.id } ?: 0) + 1
        groups.add(WatchGroup(id, name.trim().ifBlank { "群組 $id" }, emptyList()))
        save()
        return id
    }

    fun deleteGroup(id: Int) { if (groups.removeAll { it.id == id }) save() }

    fun toggleInGroup(groupId: Int, symbol: String) {
        val i = groups.indexOfFirst { it.id == groupId }
        if (i < 0) return
        val g = groups[i]
        groups[i] = g.copy(symbols = if (symbol in g.symbols) g.symbols - symbol else g.symbols + symbol)
        save()
    }

    /** 自訂排序：delta = -1 上移、+1 下移 */
    fun moveQuote(symbol: String, delta: Int) {
        val i = quotes.indexOfFirst { it.symbol == symbol }
        val j = i + delta
        if (i < 0 || j < 0 || j >= quotes.size) return
        val q = quotes.removeAt(i)
        quotes.add(j, q)
        save()
    }

    fun setTarget(symbol: String, pct: Double?) {
        if (pct == null || pct <= 0) targets.remove(symbol) else targets[symbol] = pct
        save()
    }

    // ---------- 帳戶 ----------

    fun account(id: Int): Account? = accounts.firstOrNull { it.id == id }

    fun accountsFor(market: Market) = accounts.filter { it.market == market }

    fun saveAccount(a: Account) {
        val i = accounts.indexOfFirst { it.id == a.id }
        if (i >= 0) accounts[i] = a
        else accounts.add(a.copy(id = (accounts.maxOfOrNull { it.id } ?: 0) + 1))
        save()
    }

    fun deleteAccount(id: Int) {
        if (accounts.removeAll { it.id == id }) save()
    }

    /** 依帳戶設定計算買賣手續費（台股無條件捨去到元、套用最低收費） */
    fun feeFor(accountId: Int, market: Market, amount: Double): Double {
        if (amount <= 0) return 0.0
        val a = account(accountId)
        return if (market == Market.US) {
            val rate = a?.usRate ?: 0.001
            maxOf(a?.usMinFee ?: 0.0, Math.round(amount * rate * 100) / 100.0)
        } else {
            val discount = a?.discount ?: 1.0
            maxOf(a?.minFee ?: 20.0, Math.floor(amount * 0.001425 * discount))
        }
    }

    /** 一次匯入多筆交易（全部成功才套用） */
    fun importTrades(list: List<Trade>): String? {
        var id = nextId()
        val fixed = list.map { t ->
            validate(t)?.let { return "${t.date} ${t.symbol}：$it" }
            ensureQuote(t.symbol, t.price)
            t.copy(id = id++, origin = TradeOrigin.NORMAL)
        }
        return applyLedger(trades.toList() + fixed)
    }

    /** 從備份還原後重新讀取全部資料 */
    fun reload(context: Context) {
        prefs = null
        init(context)
    }

    /** 調整現金餘額（改期初現金，讓目前現金等於輸入值） */
    fun setCash(value: Double) {
        applyLedger(trades.toList(), base.copy(cash = base.cash + (value - cashTwd)))
    }

    /** 清除示意資料：持股、交易、股息與已實現歸零，保留自選清單與設定 */
    fun clearSampleData() {
        navHistory.clear()
        applyLedger(emptyList(), LedgerBase(0.0, year(), 0.0, 0.0, 0.0))
        alertHistory.clear()
        save()
    }

    /** 舊版資料轉成帳本：目前持股變成期初交易，舊交易保留但不重算 */
    private fun migrateLedger(p: SharedPreferences) {
        if (p.getInt("ledgerV", 0) >= 1) {
            base = LedgerBase(
                p.getFloat("baseCash", 0f).toDouble(),
                p.getInt("baseYear", year()),
                p.getFloat("baseRealized", 0f).toDouble(),
                p.getFloat("baseDivTw", 0f).toDouble(),
                p.getFloat("baseDivUs", 0f).toDouble(),
            )
            recompute()
            return
        }
        val legacy = trades.mapIndexed { i, t -> t.copy(id = (i + 1).toLong(), origin = TradeOrigin.LEGACY) }
        var id = legacy.size.toLong()
        val opening = holdings.map { h ->
            id++
            Trade(TradeType.BUY, h.symbol, "1900/01/01", h.shares, h.avgCost, h.avgFx, 0.0, 0.0, id, TradeOrigin.OPENING, "期初持股")
        }
        val b = LedgerBase(cashTwd, year(), realizedTwdYtd, dividendTwYtd, dividendUsGrossYtd)
        applyLedger(legacy + opening, b)
        p.edit().putInt("ledgerV", 1).apply()
    }

    // ---------- 每日資產淨值 ----------

    /** 日期（yyyy-MM-dd）→ 總資產（台幣） */
    val navHistory = mutableStateMapOf<String, Double>()

    fun recordNav() {
        val total = totalAssetsTwd()
        if (total <= 0) return
        val key = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).toString()
        navHistory[key] = Math.round(total).toDouble()
        if (navHistory.size > 900) navHistory.keys.sorted().take(navHistory.size - 900).forEach { navHistory.remove(it) }
        save()
    }

    fun navSeries(days: Int?): List<Pair<String, Double>> {
        val all = navHistory.entries.sortedBy { it.key }.map { it.key to it.value }
        if (days == null) return all
        val cutoff = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).minusDays(days.toLong()).toString()
        return all.filter { it.first >= cutoff }
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
        trades.forEach {
            put(
                JSONObject().put("t", it.type.name).put("s", it.symbol).put("d", it.date)
                    .put("n", it.shares).put("p", it.price).put("x", it.fx)
                    .put("fee", it.fee).put("tax", it.tax)
                    .put("id", it.id).put("o", it.origin).put("note", it.note).put("acc", it.account)
                    .put("att", JSONArray(it.attachments)).put("tags", JSONArray(it.tags))
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
                o.optLong("id", 0L), o.optString("o", ""), o.optString("note", ""), o.optInt("acc", 0),
                o.optJSONArray("att")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                o.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        }
    }.getOrDefault(emptyList())
}
