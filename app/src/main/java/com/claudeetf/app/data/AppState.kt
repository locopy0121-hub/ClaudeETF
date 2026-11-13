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
    /** 自訂漲／跌／平顏色（ARGB）；0 = 使用台式或美式預設 */
    var customUp by mutableStateOf(0L)
    var customDown by mutableStateOf(0L)
    var customFlat by mutableStateOf(0L)
    fun updateCustomColor(which: String, argb: Long) {
        when (which) { "up" -> customUp = argb; "down" -> customDown = argb; else -> customFlat = argb }
        save()
    }
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
    val allHomeCards = listOf("assets" to "總資產與走勢", "alloc" to "資產配置", "movers" to "持股焦點（今日漲跌／市值前五）",
        "session" to "美股交易時段", "index" to "主要指數", "source" to "資料來源", "goal" to "資產目標",
        "pnlsplit" to "今日損益（台股／美股）", "watch" to "自選前五檔",
        "keys" to "持股三關位置", "divcal" to "持股除息行事曆", "alerts" to "最近通知", "fx" to "匯率與總經",
        "fut" to "台指期近月", "news" to "財經新聞（RSS）", "report" to "每日盤後報表")
    /** v0.1.45 新增的固定卡片：舊使用者預設隱藏，到設定勾選 */
    private val newHomeCards = setOf("pnlsplit", "watch", "holdings", "keys", "divcal", "alerts", "fx", "fut", "news")

    /** 自訂卡片：idx:代號（指數）、stk:代號（個股） */
    fun isCustomCard(id: String) = id.startsWith("idx:") || id.startsWith("stk:")

    fun homeCardName(id: String): String = when {
        id.startsWith("idx:") -> id.removePrefix("idx:").let { s -> (twIndices + usIndices + macro).firstOrNull { it.symbol == s }?.name ?: s } + "（指數）"
        id.startsWith("stk:") -> id.removePrefix("stk:").let { s -> s + " " + (quote(s)?.name ?: "") + "（個股）" }
        else -> allHomeCards.firstOrNull { it.first == id }?.second ?: id
    }

    fun addHomeCard(id: String) {
        if (id in homeOrder) { homeHidden.remove(id); save(); return }
        homeOrder.add(0, id); save()
    }

    fun removeHomeCard(id: String) {
        homeOrder.remove(id); homeHidden.remove(id); save()
    }
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

    /** 盤後以證交所／櫃買官方收盤行情確認所有台股價格 */
    var twOfficialClose by mutableStateOf(true)
    fun updateTwOfficialClose(v: Boolean) { twOfficialClose = v; save() }

    /** K 棒分析條件（國安基金進場、RSI 超賣…）：代號 → 設定 */
    var kconds by mutableStateOf<Map<String, com.claudeetf.app.data.KCondSetting>>(emptyMap())
    fun updateKCond(id: String, v: com.claudeetf.app.data.KCondSetting) { kconds = kconds + (id to v); save() }
    fun resetKConds() { kconds = emptyMap(); save() }
    fun resetKCondsTo(m: Map<String, com.claudeetf.app.data.KCondSetting>) { kconds = m; save() }
    /** 每 6 小時依線上資訊自動套用 K 棒條件設定 */
    var kcondAutoApply by mutableStateOf(true)
    fun updateKCondAutoApply(v: Boolean) { kcondAutoApply = v; save() }
    /** 國安基金：官方宣布進場（新聞）時直接視為條件達成 */
    var kcondNsfOfficial by mutableStateOf(true)
    fun updateKCondNsfOfficial(v: Boolean) { kcondNsfOfficial = v; save() }
    /** 小型快取（JSON 字串），例如國安基金新聞 */
    fun cacheGet(key: String): String? = prefs?.getString("cache_$key", null)
    fun cachePut(key: String, v: String) { prefs?.edit()?.putString("cache_$key", v)?.apply() }
    /** 條件達成時通知（持股；國安基金條件看大盤） */
    var kcondNotify by mutableStateOf(false)
    fun updateKCondNotify(v: Boolean) { kcondNotify = v; save() }
    /** 已通知過的條件（日期|代號|條件），避免同一天重複 */
    fun kcondSent(key: String): Boolean = prefs?.getString("kcondSent", "")?.split(",")?.contains(key) == true
    fun markKCondSent(key: String) {
        val p = prefs ?: return
        val list = (p.getString("kcondSent", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()).takeLast(300) + key
        p.edit().putString("kcondSent", list.joinToString(",")).apply()
    }
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
    data class DayStat(val high: Double?, val low: Double?, val volume: Double?, val at: Long, val open: Double? = null)
    val dayStats = mutableStateMapOf<String, DayStat>()

    /** 逐筆成交明細（最新在前，每檔最多 1500 筆，跨日清空） */
    val tape = mutableStateMapOf<String, List<TapeTick>>()
    fun recordTape(symbol: String, t: TapeTick) {
        if (t.lots <= 0 || t.price <= 0) return
        val tpe = java.time.ZoneId.of("Asia/Taipei")
        val day = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(tpe).toLocalDate() }
        val old = tape[symbol].orEmpty().let { l -> if (l.isNotEmpty() && day(l.first().time) != day(t.time)) emptyList() else l }
        if (old.any { it.time == t.time && it.price == t.price && it.lots == t.lots }) return
        if (old.isNotEmpty() && t.time < old.first().time) return
        tape[symbol] = (listOf(t) + old).take(1500)
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
    /** 除權息行事曆（App 開啟時自動更新） */
    val divPlans = mutableStateMapOf<String, DividendCalendar.Plan>()
    var divPlansUpdatedAt by mutableStateOf(0L)
    val institutional = mutableStateMapOf<String, com.claudeetf.app.data.remote.TwInstitutional.Day>()
    val twDaily = mutableStateMapOf<String, WatchExtras.TwDaily>()
    /** 本益比（自選欄位） */
    val peMap = mutableStateMapOf<String, Double>()
    var showKeyLevels by mutableStateOf(true)
    /** 台股分時 Y 軸用漲跌停價 */
    var limitAxis by mutableStateOf(true)
    fun updateLimitAxis(on: Boolean) { limitAxis = on; save() }
    fun updateShowKeyLevels(on: Boolean) { showKeyLevels = on; save() }
    /** 自選版面：list 清單、card 卡片 */
    var watchLayout by mutableStateOf("list")
    fun updateWatchLayout(l: String) { watchLayout = l; save() }

    /** 持股排序（0 市值、1 今日損益、2 今日漲跌、3 報酬率、4 代號） */
    var holdingSort by mutableIntStateOf(0)
    /** 持股頁檢視：0 清單、1 行情牆、2 熱度圖 */
    var holdView by mutableIntStateOf(0)
    fun updateHoldView(v: Int) { holdView = v; save() }
    var wallStyle by mutableStateOf(WallStyle())
    fun updateWallStyle(w: WallStyle) { wallStyle = w; save() }
    fun updateHoldingSort(i: Int) { holdingSort = i; save() }
    /** 新聞來源（RssNews.builtIn 的 id，自訂來源為 c:名稱） */
    val newsSources = mutableStateListOf("yahoo", "google")
    /** 自訂 RSS：名稱 → 網址 */
    val customFeeds = mutableStateListOf<Pair<String, String>>()
    fun toggleNewsSource(id: String) { if (id in newsSources) newsSources.remove(id) else newsSources.add(id); save() }
    fun addCustomFeed(name: String, url: String) {
        val n = name.trim().ifBlank { "自訂 ${customFeeds.size + 1}" }
        customFeeds.removeAll { it.first == n }; customFeeds.add(n to url.trim())
        if ("c:$n" !in newsSources) newsSources.add("c:$n")
        save()
    }
    fun removeCustomFeed(name: String) { customFeeds.removeAll { it.first == name }; newsSources.remove("c:$name"); save() }

    /** 個股頁標題顯示投資分析指數 */
    var showScoreBadge by mutableStateOf(true)
    /** 每檔預計投入資金（台幣），用來把參考投入比例換算成金額；0 = 未設定 */
    var investBudget by mutableStateOf(0.0)
    fun updateInvestBudget(v: Double) { investBudget = v.coerceAtLeast(0.0); save() }
    fun updateShowScoreBadge(on: Boolean) { showScoreBadge = on; save() }

    /** 個股頁右上角的進場動畫徽章：開關、樣式（0–4）、懸浮置頂 */
    var entryFloatOn by mutableStateOf(true)
    var entryFloatStyle by mutableStateOf(0)
    var entryFloatPin by mutableStateOf(true)
    /** 位置：角落（0 右上、1 左上、2 右下、3 左下）、位移（dp，右與下為正）、大小（%）、OS 泡泡方向（0 自動朝內、1 左、2 右、3 下） */
    var entryFloatCorner by mutableStateOf(0)
    var entryFloatX by mutableStateOf(0)
    var entryFloatY by mutableStateOf(0)
    var entryFloatScale by mutableStateOf(100)
    var entryFloatBubble by mutableStateOf(0)
    fun updateEntryFloatPos(corner: Int = entryFloatCorner, x: Int = entryFloatX, y: Int = entryFloatY, scale: Int = entryFloatScale, bubble: Int = entryFloatBubble) {
        entryFloatCorner = corner.coerceIn(0, 3); entryFloatX = x.coerceIn(-320, 320); entryFloatY = y.coerceIn(-200, 700)
        entryFloatScale = scale.coerceIn(60, 160); entryFloatBubble = bubble.coerceIn(0, 3); save()
    }
    /** 角色旁的內心 OS 對話框 */
    var entryFloatOs by mutableStateOf(true)
    fun updateEntryFloatOs(v: Boolean) { entryFloatOs = v; save() }
    fun updateEntryFloat(on: Boolean = entryFloatOn, style: Int = entryFloatStyle, pin: Boolean = entryFloatPin) {
        entryFloatOn = on; entryFloatStyle = style.coerceIn(0, 15); entryFloatPin = pin; save()
    }

    /** 行情價下方的即時 K 棒訊號（盤中） */
    var showLiveSignal by mutableStateOf(true)
    /** 收盤後仍顯示「收盤定案」的最終進場標籤；關閉則收盤後自動隱藏 */
    var showFinalSignal by mutableStateOf(true)
    fun updateShowLiveSignal(on: Boolean) { showLiveSignal = on; save() }

    /** 美股盤中更新頻率（毫秒）；0 = 即時（Finnhub 逐筆串流，沒有金鑰時改為每秒輪詢） */
    var usRefreshMs by mutableStateOf(5_000L)
    fun updateUsRefreshMs(v: Long) { usRefreshMs = v; save() }
    /** 股息發放日入帳提醒（11:00 後） */
    var divPayAlert by mutableStateOf(true)
    /** 不提醒入帳的代號 */
    val divPayMuted = mutableStateListOf<String>()
    fun updateDivPayAlert(on: Boolean) { divPayAlert = on; save() }
    fun toggleDivPayMuted(symbol: String) { if (!divPayMuted.remove(symbol)) divPayMuted.add(symbol); save() }

    /** 自選個股（卡片／清單）顯示格式 */
    var watchStyle by mutableStateOf(WatchStyle())
    fun updateWatchStyle(ws: WatchStyle) { watchStyle = ws; save() }
    /** 桌面小工具樣式 */
    var widgetStyle by mutableStateOf(com.claudeetf.app.widget.WidgetStyle())
    fun updateWidgetStyle(ws: com.claudeetf.app.widget.WidgetStyle) { widgetStyle = ws; save() }

    /** 盤中持股監控：開關、更新秒數、個股漲跌幅提醒級距（%）、漲跌停提醒、今日損益提醒級距（元，0 關閉）、含美股時段 */
    var monOn by mutableStateOf(false)
    var monInterval by mutableIntStateOf(30)
    var monStepPct by mutableStateOf(3.0)
    var monLimit by mutableStateOf(true)
    var monPnlStep by mutableStateOf(0.0)
    var monUs by mutableStateOf(false)
    fun updateMonitor(on: Boolean = monOn, interval: Int = monInterval, step: Double = monStepPct, limit: Boolean = monLimit,
                      pnlStep: Double = monPnlStep, us: Boolean = monUs) {
        monOn = on; monInterval = interval; monStepPct = step; monLimit = limit; monPnlStep = pnlStep; monUs = us; save()
    }

    /** 持股、自選行情頁頂部的指數跑馬燈 */
    var tickerShow by mutableStateOf(true)
    /** 星號股：富果即時串流（含成交量）的第二優先（持股之後） */
    val starred = androidx.compose.runtime.mutableStateListOf<String>()
    fun isStarred(symbol: String) = symbol in starred
    fun toggleStar(symbol: String) { if (symbol in starred) starred.remove(symbol) else starred.add(symbol); save() }
    var tickerStyle by mutableStateOf(TickerStyle())
    fun updateTickerStyle(s: TickerStyle) { tickerStyle = s; save() }
    var tickerSymbols by mutableStateOf(listOf("^TWII", "^DJI", "^IXIC", "^GSPC", "^SOX"))
    /** 固定置頂（不隨頁面捲動）／隨頁面捲動 */
    var tickerPinned by mutableStateOf(true)
    /** 自動跑馬燈捲動；關閉時固定不動、可手指左右滑 */
    var tickerAuto by mutableStateOf(true)
    /** 時段排程：開啟時只在設定的時段內顯示（「HH:mm-HH:mm」，可跨午夜），可限平日 */
    var tickerSched by mutableStateOf(false)
    var tickerWindows by mutableStateOf(listOf("08:30-14:00", "21:00-05:00"))
    var tickerWeekdays by mutableStateOf(true)
    fun updateTicker(
        show: Boolean = tickerShow, symbols: List<String> = tickerSymbols, pinned: Boolean = tickerPinned, auto: Boolean = tickerAuto,
        sched: Boolean = tickerSched, windows: List<String> = tickerWindows, weekdays: Boolean = tickerWeekdays,
    ) {
        tickerShow = show; tickerSymbols = symbols; tickerPinned = pinned; tickerAuto = auto
        tickerSched = sched; tickerWindows = windows; tickerWeekdays = weekdays; save()
    }
    /** 持股卡片格式（與自選行情分開） */
    var holdStyle by mutableStateOf(HoldStyle())
    fun updateHoldStyle(hs: HoldStyle) { holdStyle = hs; save() }

    /** 持股現價方格樣式 */
    var priceBoxShow by mutableStateOf(true)
    var priceBoxLabel by mutableStateOf(true)
    /** 漲停／跌停：價格反白（實心色塊）與標籤 */
    var limitFill by mutableStateOf(true)
    var limitTag by mutableStateOf(true)
    fun updateLimit(fill: Boolean = limitFill, tag: Boolean = limitTag) { limitFill = fill; limitTag = tag; save() }
    /** mono 等寬、sans 一般、serif 襯線、cond 窄體 */
    var priceBoxFont by mutableStateOf("mono")
    /** 300 細、400 標準、500 中等、700 粗、900 特粗 */
    var priceBoxWeight by mutableIntStateOf(700)
    var priceBoxSize by mutableIntStateOf(13)
    fun updatePriceBox(show: Boolean = priceBoxShow, label: Boolean = priceBoxLabel, font: String = priceBoxFont, weight: Int = priceBoxWeight, size: Int = priceBoxSize) {
        priceBoxShow = show; priceBoxLabel = label; priceBoxFont = font; priceBoxWeight = weight; priceBoxSize = size.coerceIn(10, 22); save()
    }

    /** 開啟 App 時自動檢查線上更新 */
    var autoUpdateCheck by mutableStateOf(true)
    fun updateAutoUpdateCheck(on: Boolean) { autoUpdateCheck = on; save() }
    var usStreamStatus by mutableStateOf("")
    /** 狀態列用的說明文字 */
    fun usRefreshLabel(): String = when {
        usRefreshMs == 0L && finnhubKey.isNotBlank() && com.claudeetf.app.data.remote.FinnhubStream.fresh() -> "美股逐筆即時"
        usRefreshMs == 0L -> "美股每 1 秒"
        else -> "美股每 " + (usRefreshMs / 1000) + " 秒"
    }
    /** 自選卡片、清單與持股列顯示進場建議標籤 */
    var showCardEntry by mutableStateOf(true)
    fun updateShowCardEntry(on: Boolean) { showCardEntry = on; save() }
    /** 可進場標籤動畫：0 關閉、1 柔和、2 強烈 */
    var entryAnim by mutableStateOf(2)
    fun updateEntryAnim(v: Int) { entryAnim = v; save() }
    var marqueeDesc by mutableStateOf(true)
    fun updateMarqueeDesc(on: Boolean) { marqueeDesc = on; save() }
    fun updateShowFinalSignal(on: Boolean) { showFinalSignal = on; save() }

    /** 走勢圖標示我的買賣點 */
    var showTradeMarks by mutableStateOf(true)
    fun updateShowTradeMarks(on: Boolean) { showTradeMarks = on; save() }

    /** 首頁期貨卡片商品：TXF 台指、MXF 小台、EXF 電子、FXF 金融 */
    var futProduct by mutableStateOf("TXF")
    fun updateFutProduct(p: String) { futProduct = p; save() }

    /** 期貨資料來源：auto 自動（富果優先、失敗改期交所）、fugle 只用富果、taifex 只用期交所日行情、off 關閉 */
    var futSource by mutableStateOf("auto")
    fun updateFutSource(s: String) { futSource = s; save() }

    /** 熱度圖：方塊大小 0 市值 1 成本 2 今日損益；顏色 0 今日漲跌 1 未實現報酬率；標籤 0 名稱 1 代號 */
    var heatSize by mutableIntStateOf(0)
    var heatColor by mutableIntStateOf(0)
    var heatLabel by mutableIntStateOf(0)
    /** 熱度圖是否顯示數值（漲跌幅或金額） */
    var heatValue by mutableStateOf(true)
    fun updateHeat(size: Int = heatSize, color: Int = heatColor, label: Int = heatLabel, value: Boolean = heatValue) {
        heatSize = size; heatColor = color; heatLabel = label; heatValue = value; save()
    }

    /** 指數卡片顯示內容 */
    var indexShowChart by mutableStateOf(true)
    var indexShowPoints by mutableStateOf(true)
    var indexShowRange by mutableStateOf(false)
    fun updateIndexCard(chart: Boolean = indexShowChart, points: Boolean = indexShowPoints, range: Boolean = indexShowRange) {
        indexShowChart = chart; indexShowPoints = points; indexShowRange = range; save()
    }

    /** 美股 1 日走勢含盤前盤後 */
    var usExtendedChart by mutableStateOf(true)
    fun updateUsExtendedChart(on: Boolean) { usExtendedChart = on; save() }

    /** 明細分頁篩選：0 全部、1 大單、2 外盤、3 內盤 */
    var tapeFilter by mutableIntStateOf(0)

    /** 市場頁類股：false 方塊、true 依漲跌排行 */
    var sectorRanked by mutableStateOf(false)

    /** 資產目標（TWD，0 = 未設定）與目標年月 yyyy-MM */
    var goalAmount by mutableDoubleStateOf(0.0)
    var goalMonth by mutableStateOf("")
    fun updateGoal(amount: Double, month: String) { goalAmount = amount; goalMonth = month; save() }

    /** 自選清單中間欄：spark 走勢、volume 成交量、amp 振幅、pe 本益比、pnl 持股損益 */
    var watchColumn by mutableStateOf("spark")
    fun updateWatchColumn(c: String) { watchColumn = c; if (watchColumn2 == c) watchColumn2 = ""; save() }
    /** 第二欄（空字串 = 不顯示） */
    var watchColumn2 by mutableStateOf("")
    fun updateWatchColumn2(c: String) { watchColumn2 = c; save() }
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
    /** 現金收支紀錄（新到舊） */
    val cashFlows = mutableStateListOf<CashFlow>()
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
        appCtx = context.applicationContext
        secretOk = SecretStore.probe(context.applicationContext)
        customUp = p.getLong("customUp", 0L); customDown = p.getLong("customDown", 0L); customFlat = p.getLong("customFlat", 0L)
        colorMode = runCatching { ColorMode.valueOf(p.getString("colorMode", "TW") ?: "TW") }
            .getOrDefault(ColorMode.TW)
        usdTwd = p.getD("usdTwd", 32.02f)
        themeMode = p.getInt("themeMode", 1)
        onboarded = p.getBoolean("onboarded", false)
        dailySummary = p.getBoolean("dailySummary", true)
        appLock = p.getBoolean("appLock", false)
        locked = appLock
        lastBackupAt = p.getLong("lastBackupAt", 0L)
        homeOrder.clear()
        val rawOrder = p.getString("homeOrder", null)
        val savedOrder = rawOrder?.split(",")?.filter { id -> isCustomCard(id) || allHomeCards.any { it.first == id } } ?: emptyList()
        val missing = allHomeCards.map { it.first }.filter { it !in savedOrder }
        homeOrder.addAll(savedOrder + missing)
        homeHidden.clear()
        p.getString("homeHidden", null)?.split(",")?.filter { it.isNotBlank() }?.let { homeHidden.addAll(it) }
        // 新加入的固定卡片：預設隱藏（不打亂既有首頁），到「設置 › 首頁卡片」勾選
        missing.filter { it in newHomeCards }.forEach { if (it !in homeHidden) homeHidden.add(it) }
        // 一次性：首頁最上方加入加權指數大卡（可在設定移除）
        if (!p.getBoolean("homeTwiiAdded", false)) {
            if ("idx:^TWII" !in homeOrder) homeOrder.add(0, "idx:^TWII")
            p.edit().putBoolean("homeTwiiAdded", true).apply()
        }
        // 一次性：每日盤後報表卡片放在首頁上方
        if (!p.getBoolean("homeReportAdded", false)) {
            homeOrder.remove("report"); homeHidden.remove("report")
            homeOrder.add(minOf(1, homeOrder.size), "report")
            p.edit().putBoolean("homeReportAdded", true).apply()
        }
        // 一次性：台指期卡片放在加權指數下方
        if (!p.getBoolean("homeFutAdded", false)) {
            homeOrder.remove("fut"); homeHidden.remove("fut")
            homeOrder.add(minOf(1, homeOrder.size), "fut")
            p.edit().putBoolean("homeFutAdded", true).apply()
        }
        fontScale = p.getFloat("fontScale", 1f)
        watchColumn = p.getString("watchColumn", "spark") ?: "spark"
        watchColumn2 = p.getString("watchColumn2", "") ?: ""
        goalAmount = p.getLong("goalAmount", 0L).toDouble()
        goalMonth = p.getString("goalMonth", "") ?: ""
        realizedTwdYtd = p.getD("realized", 56_800f)
        dividendTwYtd = p.getD("divTw", 61_300f)
        dividendUsGrossYtd = p.getD("divUs", 1_120.73f)
        cashTwd = p.getD("cash", 164_000f)
        fugleKey = loadSecret(p, "fugleKey")
        com.claudeetf.app.data.AiProviders.ALL.forEach { pv ->
            loadSecret(p, "aiKey_" + pv.id).takeIf { it.isNotBlank() }?.let { aiKeys[pv.id] = it }
        }
        // 舊版 Claude 金鑰搬到新的多服務設定
        if (aiKey("claude").isBlank()) loadSecret(p, "claudeKey").takeIf { it.isNotBlank() }?.let { aiKeys["claude"] = it; saveSecret("aiKey_claude", it) }
        runCatching { org.json.JSONObject(p.getString("aiModels", "{}") ?: "{}").let { o -> o.keys().forEach { k -> aiModels[k] = o.optString(k) } } }
        runCatching { org.json.JSONObject(p.getString("aiEnabled", "{}") ?: "{}").let { o -> o.keys().forEach { k -> aiEnabled[k] = o.optBoolean(k, true) } } }
        aiCustomUrl = p.getString("aiCustomUrl", "") ?: ""
        // 舊版沒有總開關：已存過金鑰的視為開啟
        aiOn = if (p.contains("aiOn")) p.getBoolean("aiOn", false) else aiKeys.values.any { it.isNotBlank() }
        twIntradaySource = p.getString("twIntradaySource", "fugle") ?: "fugle"
        twOfficialClose = p.getBoolean("twOfficialClose", true)
        kcondNotify = p.getBoolean("kcondNotify", false)
        kcondNsfOfficial = p.getBoolean("kcondNsfOfficial", true)
        kcondAutoApply = p.getBoolean("kcondAutoApply", true)
        kconds = com.claudeetf.app.data.KCondSetting.parseAll(p.getString("kconds", "") ?: "")
        monthlyReport = p.getBoolean("monthlyReport", true)
        showKeyLevels = p.getBoolean("showKeyLevels", true)
        limitAxis = p.getBoolean("limitAxis", true)
        usExtendedChart = p.getBoolean("usExtendedChart", true)
        holdingSort = p.getInt("holdingSort", 0)
        holdView = p.getInt("holdView", 0)
        wallStyle = WallStyle.fromJson(p.getString("wallStyle", null))
        futProduct = p.getString("futProduct", "TXF") ?: "TXF"
        futSource = p.getString("futSource", "auto") ?: "auto"
        showTradeMarks = p.getBoolean("showTradeMarks", true)
        showScoreBadge = p.getBoolean("showScoreBadge", true)
        entryFloatOn = p.getBoolean("entryFloatOn", true)
        entryFloatStyle = p.getInt("entryFloatStyle", 0)
        entryFloatPin = p.getBoolean("entryFloatPin", true)
        entryFloatOs = p.getBoolean("entryFloatOs", true)
        entryFloatCorner = p.getInt("efCorner", 0); entryFloatX = p.getInt("efX", 0); entryFloatY = p.getInt("efY", 0)
        entryFloatScale = p.getInt("efScale", 100); entryFloatBubble = p.getInt("efBubble", 0)
        showLiveSignal = p.getBoolean("showLiveSignal", true)
        usRefreshMs = p.getLong("usRefreshMs", 5_000L)
        autoUpdateCheck = p.getBoolean("autoUpdateCheck", true)
        watchStyle = WatchStyle.fromJson(p.getString("watchStyle", null))
        holdStyle = HoldStyle.fromJson(p.getString("holdStyle", null)).let { hs ->
            // 首次升級：沿用原本共用的「進場標籤」開關與動畫設定
            if (p.contains("holdStyle")) hs else hs.copy(
                entryAnim = p.getInt("entryAnim", 2), marquee = p.getBoolean("marqueeDesc", true),
                hidden = if (p.getBoolean("showCardEntry", true)) hs.hidden else hs.hidden + "entry" + "tip",
            )
        }
        // 當日高低與成交量：重開 App 後保留（休市時仍顯示最後交易日的量）
        runCatching {
            val o = org.json.JSONObject(p.getString("dayStats", "{}") ?: "{}")
            o.keys().forEach { k ->
                val a = o.optJSONArray(k) ?: return@forEach
                fun v(i: Int) = a.optDouble(i, -1.0).takeIf { it >= 0 }
                dayStats[k] = DayStat(v(0), v(1), v(2), a.optLong(3), v(4))
            }
        }
        divPayAlert = p.getBoolean("divPayAlert", true)
        divPayMuted.clear(); divPayMuted.addAll((p.getString("divPayMuted", "") ?: "").split(",").filter { it.isNotBlank() })
        priceBoxShow = p.getBoolean("priceBoxShow", true)
        widgetStyle = com.claudeetf.app.widget.WidgetStyle.fromJson(p.getString("widgetStyle", null))
        monOn = p.getBoolean("monOn", false)
        monInterval = p.getInt("monInterval", 30)
        monStepPct = p.getFloat("monStepPct", 3f).toDouble()
        monLimit = p.getBoolean("monLimit", true)
        monPnlStep = p.getFloat("monPnlStep", 0f).toDouble()
        monUs = p.getBoolean("monUs", false)
        tickerShow = p.getBoolean("tickerShow", true)
        starred.clear(); starred.addAll(p.getString("starred", "")!!.split(",").filter { it.isNotBlank() })
        tickerStyle = TickerStyle.fromJson(p.getString("tickerStyle", null))
        tickerPinned = p.getBoolean("tickerPinned", true)
        tickerAuto = p.getBoolean("tickerAuto", true)
        tickerSched = p.getBoolean("tickerSched", false)
        p.getString("tickerWindows", null)?.let { v -> tickerWindows = v.split(",").filter { it.contains("-") } }
        tickerWeekdays = p.getBoolean("tickerWeekdays", true)
        p.getString("tickerSymbols", null)?.let { v -> tickerSymbols = v.split(",").filter { it.isNotBlank() } }
        // 現價方格的顯示改由持股卡片項目開關控制：舊的「不顯示」轉成隱藏該項目
        if (!priceBoxShow) { holdStyle = holdStyle.copy(hidden = holdStyle.hidden + "price"); priceBoxShow = true }
        priceBoxLabel = p.getBoolean("priceBoxLabel", true)
        limitFill = p.getBoolean("limitFill", true)
        limitTag = p.getBoolean("limitTag", true)
        priceBoxFont = p.getString("priceBoxFont", "mono") ?: "mono"
        priceBoxWeight = p.getInt("priceBoxWeight", 700)
        priceBoxSize = p.getInt("priceBoxSize", 13)
        showCardEntry = p.getBoolean("showCardEntry", true)
        entryAnim = p.getInt("entryAnim", 2)
        marqueeDesc = p.getBoolean("marqueeDesc", true)
        showFinalSignal = p.getBoolean("showFinalSignal", true)
        investBudget = p.getD("investBudget", 0f)
        p.getString("newsSources", null)?.let { j ->
            runCatching { val a = JSONArray(j); newsSources.clear(); for (k in 0 until a.length()) newsSources.add(a.getString(k)) }
        }
        p.getString("customFeeds", null)?.let { j ->
            runCatching {
                val a = JSONArray(j); customFeeds.clear()
                for (k in 0 until a.length()) a.optJSONObject(k)?.let { o -> customFeeds.add(o.optString("n") to o.optString("u")) }
            }
        }
        heatSize = p.getInt("heatSize", 0)
        heatColor = p.getInt("heatColor", 0)
        heatLabel = p.getInt("heatLabel", 0)
        heatValue = p.getBoolean("heatValue", true)
        indexShowChart = p.getBoolean("indexShowChart", true)
        indexShowPoints = p.getBoolean("indexShowPoints", true)
        indexShowRange = p.getBoolean("indexShowRange", false)
        watchLayout = p.getString("watchLayout", "list") ?: "list"
        industries.clear()
        p.getString("industries", null)?.let { json ->
            runCatching { val o = JSONObject(json); o.keys().forEach { k -> industries[k] = o.getString(k) } }
        }
        finnhubKey = loadSecret(p, "finnhubKey")
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
        cashFlows.clear()
        p.getString("cashFlows", null)?.let { cashFlows.addAll(parseCashFlows(it)) }

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

        pnlDirty = p.getBoolean("pnlDirty", false)
        pnlHistory.clear()
        p.getString("pnlHist", null)?.let { json -> runCatching { val o = JSONObject(json); o.keys().forEach { k -> pnlHistory[k] = o.getDouble(k) } } }
        navHistory.clear()
        p.getString("nav", null)?.let { json ->
            runCatching { val o = JSONObject(json); o.keys().forEach { k -> navHistory[k] = o.getDouble(k) } }
        }
        mvCostHistory.clear()
        p.getString("mvCost", null)?.let { json ->
            runCatching { val o = JSONObject(json); o.keys().forEach { k -> o.getJSONArray(k).let { a -> mvCostHistory[k] = a.getDouble(0) to a.getDouble(1) } } }
        }

        // 交易中新增過的股票也要放回行情清單
        for (h in holdings) ensureQuote(h.symbol, h.avgCost)
        migrateLedger(p)
    }

    /**
     * 金額以 Double 原始位元（Long）精確保存：Float 只有約 7 位有效數字，
     * 千萬級的現金、帳本基準存成 Float 會有數元到數十元的誤差。
     * 同時保留 Float 舊鍵讓舊版本／備份仍可讀；讀取時優先用精確值。
     */
    private fun SharedPreferences.Editor.putD(k: String, v: Double): SharedPreferences.Editor =
        putLong(k + "_d", java.lang.Double.doubleToRawLongBits(v)).putFloat(k, v.toFloat())
    private fun SharedPreferences.getD(k: String, def: Float): Double =
        if (contains(k + "_d")) java.lang.Double.longBitsToDouble(getLong(k + "_d", 0L)) else getFloat(k, def).toDouble()

    private var appCtx: Context? = null
    /** Keystore 可用（加密儲存讀寫驗證成功）；不可用時退回舊的明文儲存，避免金鑰遺失 */
    private var secretOk = false

    /** 讀 API 金鑰：舊版明文存在主 prefs 的先搬進 SecretStore，驗證可讀回才從明文移除 */
    private fun loadSecret(p: SharedPreferences, name: String): String {
        val ctx = appCtx ?: return p.getString(name, "") ?: ""
        val legacy = p.getString(name, null)
        if (legacy != null) {
            if (secretOk) SecretStore.put(ctx, name, legacy)
            if (SecretStore.get(ctx, name) != legacy && legacy.isNotEmpty()) secretOk = false
            return legacy
        }
        return SecretStore.get(ctx, name) ?: ""
    }

    private fun saveSecret(name: String, value: String) {
        appCtx?.let { SecretStore.put(it, name, value) }
    }

    /** 備份還原：把備份檔中的金鑰寫入加密儲存 */
    fun restoreSecret(context: Context, name: String, value: String) = SecretStore.put(context.applicationContext, name, value)

    /**
     * 存檔合併：短時間內多次變更（逐筆行情、連續輸入）只在 400ms 後寫一次，
     * 並固定在主執行緒序列化，避免背景執行緒讀到改到一半的狀態。
     * App 進背景（onStop）與背景工作結束時呼叫 flush() 立即寫入，不會遺失。
     */
    private val saveHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var savePending = false
    private val saveTask = Runnable { if (savePending) { savePending = false; writeNow() } }

    private fun save() {
        if (prefs == null) return
        savePending = true
        saveHandler.removeCallbacks(saveTask)
        saveHandler.postDelayed(saveTask, 400)
    }

    /** 立即寫入尚未存檔的變更（主執行緒以外會切到主執行緒並等待完成） */
    fun flush() {
        if (!savePending) return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            saveHandler.removeCallbacks(saveTask); saveTask.run()
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            saveHandler.post { saveHandler.removeCallbacks(saveTask); saveTask.run(); latch.countDown() }
            runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
        }
    }

    private fun writeNow() {
        val p = prefs ?: return
        p.edit()
            .putString("colorMode", colorMode.name)
            .putLong("customUp", customUp).putLong("customDown", customDown).putLong("customFlat", customFlat)
            .putD("usdTwd", usdTwd)
            .putInt("themeMode", themeMode)
            .putBoolean("dailySummary", dailySummary)
            .putBoolean("appLock", appLock)
            .putLong("lastBackupAt", lastBackupAt)
            .putString("homeOrder", homeOrder.joinToString(","))
            .putString("homeHidden", homeHidden.joinToString(","))
            .putFloat("fontScale", fontScale)
            .putString("watchColumn", watchColumn)
            .putString("watchColumn2", watchColumn2)
            .putLong("goalAmount", Math.round(goalAmount))
            .putString("goalMonth", goalMonth)
            .putD("realized", realizedTwdYtd)
            .putD("divTw", dividendTwYtd)
            .putD("divUs", dividendUsGrossYtd)
            .putD("cash", cashTwd)
            .putString("holdings", holdingsJson())
            .putString("trades", tradesJson())
            .putString("cashFlows", cashFlowsJson())
            .putString("alertsV2", alertsJson())
            .putString("alertHistory", JSONArray().apply { alertHistory.take(60).forEach { put(JSONObject().put("t", it.time).put("x", it.text)) } }.toString())
            .apply { if (secretOk) remove("fugleKey") else putString("fugleKey", fugleKey) }
            .putString("twIntradaySource", twIntradaySource)
            .putBoolean("twOfficialClose", twOfficialClose)
            .putBoolean("kcondNotify", kcondNotify)
            .putBoolean("kcondNsfOfficial", kcondNsfOfficial)
            .putBoolean("kcondAutoApply", kcondAutoApply)
            .putString("kconds", com.claudeetf.app.data.KCondSetting.toJsonAll(kconds))
            .putBoolean("monthlyReport", monthlyReport)
            .putBoolean("showKeyLevels", showKeyLevels)
            .putBoolean("limitAxis", limitAxis)
            .putBoolean("usExtendedChart", usExtendedChart)
            .putInt("holdingSort", holdingSort)
            .putInt("holdView", holdView)
            .putString("wallStyle", wallStyle.toJson())
            .putString("futProduct", futProduct)
            .putString("futSource", futSource)
            .putBoolean("showTradeMarks", showTradeMarks)
            .putBoolean("showScoreBadge", showScoreBadge)
            .putBoolean("entryFloatOn", entryFloatOn)
            .putInt("entryFloatStyle", entryFloatStyle)
            .putBoolean("entryFloatPin", entryFloatPin)
            .putBoolean("entryFloatOs", entryFloatOs)
            .putInt("efCorner", entryFloatCorner).putInt("efX", entryFloatX).putInt("efY", entryFloatY)
            .putInt("efScale", entryFloatScale).putInt("efBubble", entryFloatBubble)
            .putBoolean("showLiveSignal", showLiveSignal)
            .putLong("usRefreshMs", usRefreshMs)
            .putBoolean("autoUpdateCheck", autoUpdateCheck)
            .putString("watchStyle", watchStyle.toJson())
            .putString("holdStyle", holdStyle.toJson())
            .putString("widgetStyle", widgetStyle.toJson())
            .putBoolean("monOn", monOn).putInt("monInterval", monInterval).putFloat("monStepPct", monStepPct.toFloat())
            .putBoolean("monLimit", monLimit).putFloat("monPnlStep", monPnlStep.toFloat()).putBoolean("monUs", monUs)
            .putString("tickerStyle", tickerStyle.toJson())
            .putString("starred", starred.joinToString(","))
            .putString("aiModels", org.json.JSONObject(aiModels.toMap() as Map<*, *>).toString())
            .putString("aiEnabled", org.json.JSONObject(aiEnabled.toMap() as Map<*, *>).toString())
            .putString("aiCustomUrl", aiCustomUrl)
            .putBoolean("aiOn", aiOn)
            .putBoolean("tickerShow", tickerShow).putString("tickerSymbols", tickerSymbols.joinToString(","))
            .putBoolean("tickerPinned", tickerPinned).putBoolean("tickerAuto", tickerAuto).putBoolean("tickerSched", tickerSched)
            .putString("tickerWindows", tickerWindows.joinToString(",")).putBoolean("tickerWeekdays", tickerWeekdays)
            .putString("dayStats", org.json.JSONObject().also { o ->
                dayStats.forEach { (k, d) -> o.put(k, org.json.JSONArray().put(d.high ?: -1.0).put(d.low ?: -1.0).put(d.volume ?: -1.0).put(d.at).put(d.open ?: -1.0)) }
            }.toString())
            .putBoolean("divPayAlert", divPayAlert)
            .putString("divPayMuted", divPayMuted.joinToString(","))
            .putBoolean("priceBoxShow", priceBoxShow)
            .putBoolean("priceBoxLabel", priceBoxLabel)
            .putBoolean("limitFill", limitFill)
            .putBoolean("limitTag", limitTag)
            .putString("priceBoxFont", priceBoxFont)
            .putInt("priceBoxWeight", priceBoxWeight)
            .putInt("priceBoxSize", priceBoxSize)
            .putBoolean("showCardEntry", showCardEntry)
            .putInt("entryAnim", entryAnim)
            .putBoolean("marqueeDesc", marqueeDesc)
            .putBoolean("showFinalSignal", showFinalSignal)
            .putD("investBudget", investBudget)
            .putString("newsSources", JSONArray(newsSources.toList()).toString())
            .putString("customFeeds", JSONArray().apply { customFeeds.forEach { put(JSONObject().put("n", it.first).put("u", it.second)) } }.toString())
            .putInt("heatSize", heatSize)
            .putInt("heatColor", heatColor)
            .putInt("heatLabel", heatLabel)
            .putBoolean("heatValue", heatValue)
            .putBoolean("indexShowChart", indexShowChart)
            .putBoolean("indexShowPoints", indexShowPoints)
            .putBoolean("indexShowRange", indexShowRange)
            .putString("watchLayout", watchLayout)
            .putString("industries", JSONObject().apply { industries.forEach { (k, v) -> put(k, v) } }.toString())
            .apply { if (secretOk) remove("finnhubKey") else putString("finnhubKey", finnhubKey) }
            .putString("fxSource", fxSource)
            .putLong("fxUpdatedAt", fxUpdatedAt)
            .putString("quotes", quotesJson())
            .putString("taxKinds", JSONObject().apply { taxKindOverride.forEach { (k, v) -> put(k, v.name) } }.toString())
            .putString("taxRatios", JSONObject().apply { taxRatioOverride.forEach { (k, v) -> put(k, v) } }.toString())
            .putD("baseCash", base.cash)
            .putInt("baseYear", base.year)
            .putD("baseRealized", base.realized)
            .putD("baseDivTw", base.divTw)
            .putD("baseDivUs", base.divUsGross)
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
            .putBoolean("pnlDirty", pnlDirty)
            .putString("pnlHist", JSONObject().apply { pnlHistory.forEach { (k, v) -> put(k, v) } }.toString())
            .putString("mvCost", JSONObject().apply { mvCostHistory.forEach { (k, v) -> put(k, org.json.JSONArray().put(v.first).put(v.second)) } }.toString())
            .apply()
    }

    // ---------- 查詢 ----------

    fun quote(symbol: String): Quote? = quotes.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }

    fun marketOf(symbol: String): Market =
        quote(symbol)?.market ?: if (symbol.firstOrNull()?.isDigit() == true) Market.TW else Market.US

    /** 持股市值（原幣） */
    fun marketValue(h: Holding): Double = valueOf(h.symbol, h.shares, quote(h.symbol)?.price ?: h.avgCost)

    /**
     * 市值 = 股數 × 價格。台股比照集保：以十進位精確相乘後「無條件捨去」到整數元
     * （例：50 股 × 48.95 = 2,447.5 → 2,447，不四捨五入）；美股保留原值。
     */
    fun valueOf(symbol: String, shares: Double, price: Double): Double =
        if (marketOf(symbol) == Market.US) shares * price
        else java.math.BigDecimal.valueOf(shares).multiply(java.math.BigDecimal.valueOf(price))
            .setScale(0, java.math.RoundingMode.DOWN).toDouble()

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

    /** AI 個股體檢：多家服務（Agnes AI 優先），各自的金鑰加密保存、模型可自訂 */
    val aiKeys = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val aiModels = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val aiEnabled = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    var aiCustomUrl by mutableStateOf("")
    /** AI 分析總開關：關閉時不顯示 AI 分頁、不呼叫 AI、進場建議不做 AI 比對 */
    var aiOn by mutableStateOf(false)
    fun updateAiOn(on: Boolean) { aiOn = on; save() }
    /** 刪除某家服務的金鑰（安全儲存區一併清除） */
    fun clearAiKey(id: String) { aiKeys.remove(id); saveSecret("aiKey_$id", ""); save() }
    fun aiKey(id: String) = aiKeys[id].orEmpty()
    fun updateAi(id: String, key: String? = null, model: String? = null, enabled: Boolean? = null) {
        key?.let { aiKeys[id] = it.trim(); saveSecret("aiKey_$id", it.trim()) }
        model?.let { aiModels[id] = it.trim() }
        enabled?.let { aiEnabled[id] = it }
        save()
    }
    fun updateAiCustomUrl(url: String) { aiCustomUrl = url.trim(); save() }

    fun updateFugleKey(key: String) {
        fugleKey = key.trim()
        saveSecret("fugleKey", fugleKey)
        save()
    }

    fun updateFinnhubKey(key: String) {
        finnhubKey = key.trim()
        saveSecret("finnhubKey", finnhubKey)
        save()
    }

    fun setStatus(market: String, text: String) {
        sourceStatus[market] = text
    }

    /** 把遠端報價合併進清單（保留 ETF 標記與基本面） */
    private fun hasCjk(s: String) = s.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    /** 異常報價計數：symbol → (價格, 連續次數) */
    private val oddTicks = HashMap<String, Pair<Double, Int>>()

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
        // 異常報價防護：盤中一筆價格同時偏離上一筆與昨收 60% 以上，視為錯誤資料先略過；連續 3 次相同才採用
        if (live && old.price > 0 && rq.price > 0 && old.updatedAt > 0 && now - old.updatedAt < 86_400_000L) {
            val jump = kotlin.math.abs(rq.price / old.price - 1)
            val vsPrev = if (rq.prevClose > 0) kotlin.math.abs(rq.price / rq.prevClose - 1) else jump
            if (jump > 0.6 && vsPrev > 0.6) {
                val n = (oddTicks[old.symbol]?.takeIf { it.first == rq.price }?.second ?: 0) + 1
                oddTicks[old.symbol] = rq.price to n
                if (n < 3) return
            } else oddTicks.remove(old.symbol)
        }
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
            // 開盤價：同一交易日沿用（跨日由新的開盤價取代）
            val sameDay = prevStat != null && java.time.Instant.ofEpochMilli(prevStat.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate() ==
                java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            dayStats[old.symbol] = DayStat(rq.high ?: prevStat?.high, rq.low ?: prevStat?.low, rq.volume ?: prevStat?.volume, now,
                rq.open ?: if (sameDay) prevStat?.open else null)
        }
        quotes[i] = old.copy(
            // 台股：名稱沒有中文（如從 Yahoo 搜尋加入的英文名稱）時，改用證交所／櫃買／MIS 的完整中文名稱；美股不變
            name = when {
                rq.name.isNullOrBlank() -> old.name
                old.name == old.symbol -> rq.name
                old.market == Market.TW && !hasCjk(old.name) && hasCjk(rq.name) -> rq.name
                else -> old.name
            },
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
            if (i >= 0) list[i] = list[i].copy(value = value, changePct = pct, updatedAt = now, prev = if (prev > 0) prev else list[i].prev)
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

    /** 更新自選名稱（搜尋目錄的中文名稱） */
    fun renameQuote(symbol: String, name: String) {
        val i = quotes.indexOfFirst { it.symbol == symbol }
        if (i >= 0 && name.isNotBlank()) { quotes[i] = quotes[i].copy(name = name); save() }
    }

    /** 復原剛移除的自選（放回原位置） */
    fun restoreWatch(q: Quote, index: Int) {
        if (quote(q.symbol) != null) return
        quotes.add(index.coerceIn(0, quotes.size), q)
        save()
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
        alertHistory.add(0, AlertHit(now, com.claudeetf.app.alerts.AlertEngine.wrap(text)))
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
        // 名稱優先用搜尋目錄的中文／英文名稱，不再只顯示代號
        quotes.add(Quote(symbol.uppercase(), StockDirectory.cachedName(symbol) ?: symbol.uppercase(), market, price, price))
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
            pnlDirty = true
            trades.clear()
            trades.addAll(candidate.sortedByDescending { Ledger.sortKey(it) })
        }
        holdings.clear(); holdings.addAll(res.holdings)
        cashTwd = res.cash + cashFlows.sumOf { it.amount }
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

    // ---------- 現金收支 ----------

    /** 現金收支的淨額合計（收入 − 支出） */
    val cashFlowNet: Double get() = cashFlows.sumOf { it.amount }

    fun saveCashFlow(f: CashFlow) {
        val item = if (f.id == 0L) f.copy(id = (cashFlows.maxOfOrNull { it.id } ?: 0L) + 1) else f
        val list = cashFlows.filter { it.id != item.id } + item
        cashFlows.clear()
        cashFlows.addAll(list.sortedWith(compareByDescending<CashFlow> { it.date }.thenByDescending { it.id }))
        pnlDirty = true
        applyLedger(trades.toList())
    }

    fun deleteCashFlow(id: Long) {
        cashFlows.removeAll { it.id == id }
        pnlDirty = true
        applyLedger(trades.toList())
    }

    private fun cashFlowsJson(): String = JSONArray().apply {
        cashFlows.forEach { put(JSONObject().put("id", it.id).put("d", it.date).put("a", it.amount).put("c", it.category).put("n", it.note)) }
    }.toString()

    private fun parseCashFlows(json: String): List<CashFlow> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            CashFlow(o.getLong("id"), o.getString("d"), o.getDouble("a"), o.optString("c"), o.optString("n"))
        }
    }.getOrDefault(emptyList())

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
        val fee = feeFor(plan.account, q.market, tradeGross(q.symbol, shares, q.price))
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
                    (t.gross + t.fee) / t.shares, t.fx, t.origin == TradeOrigin.OPENING,
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
    /** 把目前排序結果套用為自訂順序（未列出的維持在後面） */
    fun applyQuoteOrder(symbols: List<String>) {
        val head = symbols.mapNotNull { s -> quotes.firstOrNull { it.symbol == s } }
        val rest = quotes.filter { it.symbol !in symbols }
        quotes.clear(); quotes.addAll(head + rest); save()
    }

    /** 以目前清單建立新群組 */
    fun groupFrom(name: String, symbols: List<String>): Int {
        val id = (groups.maxOfOrNull { it.id } ?: 0) + 1
        groups.add(WatchGroup(id, name.trim().ifBlank { "群組 $id" }, symbols))
        save()
        return id
    }

    /** 首頁卡片移到最上方 */
    fun moveHomeCardTop(id: String) {
        if (homeOrder.remove(id)) { homeOrder.add(0, id); save() }
    }

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
            com.claudeetf.app.util.TwRules.fee(amount, discount, a?.minFee ?: 20.0)
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
        // 還原備份：丟棄舊狀態尚未寫入的變更，避免蓋掉剛還原的資料
        savePending = false
        saveHandler.removeCallbacks(saveTask)
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
        mvCostHistory.clear()
        applyLedger(emptyList(), LedgerBase(0.0, year(), 0.0, 0.0, 0.0))
        alertHistory.clear()
        save()
    }

    /** 舊版資料轉成帳本：目前持股變成期初交易，舊交易保留但不重算 */
    private fun migrateLedger(p: SharedPreferences) {
        if (p.getInt("ledgerV", 0) >= 1) {
            base = LedgerBase(
                p.getD("baseCash", 0f),
                p.getInt("baseYear", year()),
                p.getD("baseRealized", 0f),
                p.getD("baseDivTw", 0f),
                p.getD("baseDivUs", 0f),
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
    /** 日期 → (持股市值, 持股成本) */
    val mvCostHistory = mutableStateMapOf<String, Pair<Double, Double>>()

    /** 重建的每日損益（日期 yyyy-MM-dd → 台幣）；空的時候損益日曆退回用總資產差額 */
    val pnlHistory = mutableStateMapOf<String, Double>()
    fun setPnlHistory(m: Map<String, Double>) { pnlHistory.clear(); pnlHistory.putAll(m); save() }
    /** 交易紀錄有變動：每日損益與資產走勢需要重建 */
    var pnlDirty by mutableStateOf(false)

    /** 以重建的每日持股市值取代 [from] 之後的資產紀錄（＋目前現金） */
    fun rebuildNav(mvByDay: Map<String, Double>, from: String) {
        navHistory.keys.filter { it >= from }.toList().forEach { navHistory.remove(it) }
        mvByDay.forEach { (k, v) -> navHistory[k] = Math.round(v + cashTwd).toDouble() }
        mvCostHistory.keys.filter { it >= from && it !in mvByDay }.toList().forEach { mvCostHistory.remove(it) }
        mvByDay.forEach { (k, v) -> val c = mvCostHistory[k]?.second ?: totalCostTwd(); mvCostHistory[k] = Math.round(v).toDouble() to c }
        recordNav()
    }

    private fun quoteDay(q: Quote): String? = if (q.updatedAt <= 0) null else
        java.time.Instant.ofEpochMilli(q.updatedAt).atZone(java.time.ZoneId.of("Asia/Taipei")).toLocalDate().toString()

    fun recordNav() {
        val total = totalAssetsTwd()
        if (total <= 0) return
        val key = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).toString()
        navHistory[key] = Math.round(total).toDouble()
        // 有重建過的每日損益：今天以「持股股數 × 今日漲跌」記錄（只算股價變動，不受買賣、入金影響）
        val dow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei")).dayOfWeek.value
        if (pnlHistory.isNotEmpty() && dow <= 5 && holdings.any { quote(it.symbol)?.let { q -> q.prevClose > 0 && quoteDay(q) == key } == true })
            pnlHistory[key] = Math.round(todayPnlTwd()).toDouble()
        if (navHistory.size > 900) navHistory.keys.sorted().take(navHistory.size - 900).forEach { navHistory.remove(it) }
        // 持股市值與成本（台幣）：分析頁「成本與市值走勢」
        val mv = totalMarketValueTwd(); val cost = totalCostTwd()
        if (mv > 0 || cost > 0) mvCostHistory[key] = Math.round(mv).toDouble() to Math.round(cost).toDouble()
        if (mvCostHistory.size > 900) mvCostHistory.keys.sorted().take(mvCostHistory.size - 900).forEach { mvCostHistory.remove(it) }
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
