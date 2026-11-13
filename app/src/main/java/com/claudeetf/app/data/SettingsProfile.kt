package com.claudeetf.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 設定檔：把各板塊模組的樣式與設定（不含交易、持股、金額、金鑰）存成一份 JSON，
 * 可在 App 內命名保存多份、匯出成檔案，或讀取檔案後選擇模組套用。
 */
object SettingsProfile {
    private const val PREFS = "claudeetf"
    private const val LIST_KEY = "styleProfiles"
    const val MAX_SAVED = 20

    /** 一個板塊模組：名稱、說明、對應的偏好設定鍵（cache: 開頭表示存在 cache_ 前綴） */
    data class Module(val id: String, val title: String, val desc: String, val keys: List<String>)

    val MODULES = listOf(
        Module("watch", "行情卡片／清單", "版面、欄位、每項樣式與文字動畫",
            listOf("watchStyle", "watchLayout", "watchColumn", "watchColumn2", "showScoreBadge")),
        Module("hold", "持股行情牆／清單", "格數、懸浮座標、對齊、持股卡片樣式、排序",
            listOf("wallStyle", "holdStyle", "holdView", "holdingSort")),
        Module("ticker", "指數跑馬燈", "字級、配色、速度、顯示項目、排程與文字動畫",
            listOf("tickerStyle", "tickerShow", "tickerSymbols", "tickerAuto", "tickerPinned", "tickerSched",
                "tickerWeekdays", "tickerWindows", "marqueeDesc", "indexShowChart", "indexShowPoints", "indexShowRange")),
        Module("home", "首頁卡片", "卡片順序與顯示",
            listOf("homeOrder", "homeHidden", "homeFutAdded", "homeReportAdded", "homeTwiiAdded")),
        Module("stock", "個股頁與進場角色", "懸浮徽章、動畫、訊號列、價格框、漲跌停標示",
            listOf("efBubble", "efCorner", "efScale", "efX", "efY", "entryAnim", "entryFloatOn", "entryFloatOs",
                "entryFloatPin", "entryFloatStyle", "showCardEntry", "showFinalSignal", "showKeyLevels", "showLiveSignal",
                "showTradeMarks", "priceBoxFont", "priceBoxLabel", "priceBoxShow", "priceBoxSize", "priceBoxWeight",
                "limitAxis", "limitFill", "limitTag", "usExtendedChart")),
        Module("widget", "桌面小工具", "主題、透明度、顯示項目", listOf("widgetStyle")),
        Module("look", "主題與配色", "深淺色、漲跌色、字級、熱力圖、標籤外框",
            listOf("themeMode", "colorMode", "customUp", "customDown", "customFlat", "fontScale",
                "heatColor", "heatLabel", "heatSize", "heatValue", "cache:tagOutline")),
        Module("analysis", "分析參數", "分析範圍與權重、K 棒條件、AI 共識次數",
            listOf("cache:analysisCfg", "cache:aiVotes", "kconds", "kcondAutoApply", "kcondNotify", "kcondNsfOfficial")),
        Module("source", "資料來源與監控", "行情來源、更新頻率、新聞來源、盤中監控",
            listOf("twIntradaySource", "twOfficialClose", "usRefreshMs", "futSource", "futProduct", "fxSource",
                "newsSources", "customFeeds", "monOn", "monInterval", "monLimit", "monPnlStep", "monStepPct", "monUs")),
    )

    private fun prefKey(k: String) = if (k.startsWith("cache:")) "cache_" + k.removePrefix("cache:") else k

    data class Profile(val name: String, val savedAt: Long, val data: JSONObject) {
        /** 檔案中有哪些模組 */
        val modules: List<String> get() = data.keys().asSequence().toList().filter { id -> MODULES.any { it.id == id } }
        fun toJson(): JSONObject = JSONObject()
            .put("app", "ClaudeETF").put("kind", "profile").put("format", 1)
            .put("name", name).put("savedAt", savedAt).put("modules", data)
    }

    private fun entryOf(v: Any?): JSONObject? = when (v) {
        is String -> JSONObject().put("t", "s").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(v.map { it.toString() }))
        else -> null
    }

    /** 擷取目前選定模組的設定 */
    fun capture(context: Context, name: String, moduleIds: Collection<String>): Profile {
        AppState.flush(); AppState.persist()
        val all = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
        val data = JSONObject()
        MODULES.filter { it.id in moduleIds }.forEach { m ->
            val o = JSONObject()
            m.keys.forEach { k -> entryOf(all[prefKey(k)])?.let { o.put(k, it) } }
            data.put(m.id, o)
        }
        return Profile(name.ifBlank { "我的設定" }, System.currentTimeMillis(), data)
    }

    /** 解析設定檔文字；回傳設定檔或錯誤訊息 */
    fun parse(text: String): Pair<Profile?, String?> {
        val root = runCatching { JSONObject(text) }.getOrElse { return null to "不是有效的設定檔" }
        if (root.optString("app") != "ClaudeETF") return null to "這不是本 App 的設定檔"
        if (root.optString("kind") != "profile") {
            return null to if (root.has("data")) "這是完整備份檔，請到「備份與匯入」還原" else "這不是設定檔"
        }
        val data = root.optJSONObject("modules") ?: return null to "設定檔內容是空的"
        val p = Profile(root.optString("name", "匯入的設定"), root.optLong("savedAt", System.currentTimeMillis()), data)
        if (p.modules.isEmpty()) return null to "設定檔裡沒有可套用的模組"
        return p to null
    }

    /**
     * 套用設定檔中選定的模組：模組內檔案有的鍵寫入，沒有的鍵還原預設。
     * 套用前自動把目前設定存成「套用前」，可以隨時還原。
     */
    fun apply(context: Context, p: Profile, moduleIds: Collection<String>): Int {
        val ids = moduleIds.filter { it in p.modules }
        if (ids.isEmpty()) return 0
        saveLocal(capture(context, "套用前自動保存", MODULES.map { it.id }), replaceName = true)
        AppState.flush(); AppState.persist()
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        ids.forEach { id ->
            val m = MODULES.first { it.id == id }
            val o = p.data.optJSONObject(id) ?: JSONObject()
            m.keys.forEach { k ->
                val pk = prefKey(k)
                val entry = o.optJSONObject(k)
                if (entry == null) { e.remove(pk); return@forEach }
                when (entry.optString("t")) {
                    "s" -> e.putString(pk, entry.optString("v"))
                    "i" -> e.putInt(pk, entry.optInt("v"))
                    "l" -> e.putLong(pk, entry.optLong("v"))
                    "f" -> e.putFloat(pk, entry.optDouble("v").toFloat())
                    "b" -> e.putBoolean(pk, entry.optBoolean("v"))
                    "ss" -> {
                        val a = entry.optJSONArray("v") ?: JSONArray()
                        e.putStringSet(pk, (0 until a.length()).map { a.optString(it) }.toSet())
                    }
                }
            }
        }
        e.commit()
        AppState.reload(context)
        AnalysisConfig.reloadFromPrefs()
        runCatching { com.claudeetf.app.widget.WidgetUpdater.update(context) }
        return ids.size
    }

    // ── App 內保存的設定檔清單 ──
    fun saved(): List<Profile> {
        val a = runCatching { JSONArray(AppState.cacheGet(LIST_KEY) ?: "[]") }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { parse(it.toString()).first } }
    }

    private fun write(list: List<Profile>) {
        val a = JSONArray(); list.take(MAX_SAVED).forEach { a.put(it.toJson()) }
        AppState.cachePut(LIST_KEY, a.toString())
    }

    /** 存入清單；同名覆蓋 */
    fun saveLocal(p: Profile, replaceName: Boolean = true) {
        val rest = saved().filterNot { replaceName && it.name == p.name }
        write(listOf(p) + rest)
    }

    fun delete(name: String, savedAt: Long) = write(saved().filterNot { it.name == name && it.savedAt == savedAt })

    fun fileName(name: String): String =
        "ClaudeETF-設定檔-" + name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(30) + ".json"
}
