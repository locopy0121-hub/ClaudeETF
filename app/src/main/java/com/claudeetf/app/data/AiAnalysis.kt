package com.claudeetf.app.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 支援的 AI 服務：Anthropic 原生，或 OpenAI 相容 /chat/completions */
object AiProviders {
    enum class Kind { ANTHROPIC, OPENAI }
    data class Provider(val id: String, val name: String, val kind: Kind, val baseUrl: String, val models: List<String>, val keyHint: String, val site: String)

    /** 依優先順序：Agnes AI 第一 */
    val ALL = listOf(
        Provider("agnes", "Agnes AI", Kind.OPENAI, "https://apihub.agnes-ai.com/v1",
            listOf("agnes-2.5-flash", "agnes-3.0-flash", "agnes-2.5-pro-alpha"), "Agnes API 金鑰", "https://agnes-ai.com"),
        Provider("claude", "Claude（Anthropic）", Kind.ANTHROPIC, "https://api.anthropic.com/v1",
            listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"), "sk-ant-…", "https://console.anthropic.com"),
        Provider("openai", "OpenAI", Kind.OPENAI, "https://api.openai.com/v1",
            listOf("gpt-4.1-mini", "gpt-4.1", "gpt-4o-mini"), "sk-…", "https://platform.openai.com"),
        Provider("gemini", "Google Gemini", Kind.OPENAI, "https://generativelanguage.googleapis.com/v1beta/openai",
            listOf("gemini-2.5-flash", "gemini-2.5-pro"), "AIza…", "https://aistudio.google.com"),
        Provider("deepseek", "DeepSeek", Kind.OPENAI, "https://api.deepseek.com/v1",
            listOf("deepseek-chat", "deepseek-reasoner"), "sk-…", "https://platform.deepseek.com"),
        Provider("custom", "自訂（OpenAI 相容）", Kind.OPENAI, "",
            listOf(), "金鑰", ""),
    )

    fun byId(id: String) = ALL.firstOrNull { it.id == id }
    fun modelOf(p: Provider) = AppState.aiModels[p.id]?.takeIf { it.isNotBlank() } ?: p.models.firstOrNull().orEmpty()
    fun urlOf(p: Provider) = if (p.id == "custom") AppState.aiCustomUrl.trimEnd('/') else p.baseUrl
    /** 可用：有金鑰、已啟用、自訂需有網址與模型 */
    fun usable(p: Provider) = AppState.aiKey(p.id).isNotBlank() && AppState.aiEnabled[p.id] != false &&
        urlOf(p).isNotBlank() && modelOf(p).isNotBlank()
    /** 依優先順序可用的服務（Agnes AI 優先，失敗依序改用下一家） */
    fun chain() = ALL.filter { usable(it) }
}

/**
 * AI 個股體檢：AI 解讀層＋同交易日快取＋降級容錯。
 * 只把本地算好的 [StockMetrics.ComputedStockMetrics] 傳給 AI；系統提示詞為 assets/stock_analysis_skill.md。
 * 依序嘗試可用的服務（Agnes AI 優先）；都沒有或都失敗時回傳本地規則體檢。
 */
object AiAnalysis {
    data class State(
        val metrics: StockMetrics.ComputedStockMetrics?,
        val report: StockMetrics.StockAnalysisReport?,
        /** 為什麼是本地結果或改用備援服務；null＝第一順位成功或讀快取 */
        val fallback: String? = null,
        val loading: Boolean = false,
    )

    val states = mutableStateMapOf<String, State>()

    private lateinit var ctx: Context
    private val client by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build() }
    private var skill: String? = null

    fun init(c: Context) { if (!::ctx.isInitialized) ctx = c.applicationContext }
    fun hasAny() = AppState.aiOn && AiProviders.chain().isNotEmpty()

    private fun dir() = File(ctx.filesDir, "ai_analysis").apply { mkdirs() }
    private fun cacheFile(sym: String, date: String) = File(dir(), sym.replace(Regex("[^A-Za-z0-9]"), "_") + "_" + date + ".json")

    private fun skillPrompt(): String = skill ?: runCatching {
        ctx.assets.open("stock_analysis_skill.md").bufferedReader().use { it.readText() }
    }.getOrDefault("").also { skill = it }

    suspend fun load(q: Quote, useAi: Boolean, force: Boolean = false) {
        if (!::ctx.isInitialized) return
        val sym = q.symbol
        withContext(Dispatchers.Main) { states[sym] = (states[sym] ?: State(null, null)).copy(loading = true) }
        val m = runCatching { StockMetrics.compute(q) }.getOrNull()
        if (m == null) {
            withContext(Dispatchers.Main) { states[sym] = State(null, null, "K 線資料暫時無法取得", false) }
            return
        }
        val local = StockMetrics.localReport(m)
        val cf = cacheFile(sym, m.date)
        if (!force) withContext(Dispatchers.IO) { runCatching { StockMetrics.StockAnalysisReport.parse(cf.readText(), "ai") }.getOrNull() }?.let { cached ->
            withContext(Dispatchers.Main) { states[sym] = State(m, cached, null, false); EntryAdvisor.applyAi(sym, cached) }
            return
        }
        val chain = AiProviders.chain()
        if (!useAi || chain.isEmpty()) {
            withContext(Dispatchers.Main) { states[sym] = State(m, local, if (chain.isEmpty()) "未設定 AI 服務金鑰，顯示本地規則體檢" else null, false); EntryAdvisor.applyAi(sym, local) }
            return
        }
        val errors = ArrayList<String>()
        var rep: StockMetrics.StockAnalysisReport? = null
        val n = votes()
        for (p in chain) {
            // 同一份數據依序分析 n 次取共識（AI 每次回答會有差異，多數決＋信心取中位數讓結果穩定）。
            // 依序並間隔呼叫，避免免費方案的頻率上限；遇到上限（429）時先等待重試，已有結果就停止追加。
            val limited = (rateLimitedUntil[p.id] ?: 0L) > System.currentTimeMillis()
            val want = if (limited) 1 else n
            val ok = ArrayList<StockMetrics.StockAnalysisReport>()
            var lastErr: Throwable? = null
            withContext(Dispatchers.IO) {
                var i = 0; var retry = 0
                while (i < want) {
                    if (i > 0 || retry > 0) kotlinx.coroutines.delay(if (retry > 0) 4_000L * retry else 1_200L)
                    val r = runCatching { call(p, m) }
                    val e = r.exceptionOrNull()
                    if (e == null) { ok += r.getOrThrow(); i++; retry = 0; continue }
                    lastErr = e
                    if (isRateLimit(e)) {
                        rateLimitedUntil[p.id] = System.currentTimeMillis() + 10 * 60_000L
                        if (ok.isNotEmpty() || retry >= 2) break
                        retry++
                    } else { if (ok.isNotEmpty()) break; i++ }
                }
            }
            rep = consensus(ok, want)
            if (rep != null) break
            errors += p.name + "：" + friendlyAi(lastErr)
            com.claudeetf.app.util.ErrorLog.record("AI 體檢", sym + " " + errors.last())
        }
        withContext(Dispatchers.Main) {
            states[sym] = if (rep != null) {
                runCatching { cf.writeText(rep.toJson().toString()) }
                dir().listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L }?.forEach { it.delete() }
                State(m, rep, if (errors.isNotEmpty()) "已改用備援服務（" + errors.joinToString("；") + "）" else null, false)
            } else {
                // 失敗時優先沿用最近一次的 AI 結果（7 天內），沒有才用本地規則體檢
                val prev = runCatching {
                    dir().listFiles()?.filter { it.name.startsWith(sym.replace(Regex("[^A-Za-z0-9]"), "_") + "_") }?.maxByOrNull { it.lastModified() }
                        ?.let { StockMetrics.StockAnalysisReport.parse(it.readText(), "ai") }
                }.getOrNull()
                if (prev != null) State(m, prev, "AI 暫時無法分析（" + errors.joinToString("；") + "），沿用上次的 AI 結果", false)
                else State(m, local, "AI 暫時無法分析（" + errors.joinToString("；") + "），改顯示本地規則體檢", false)
            }
            EntryAdvisor.applyAi(sym, states[sym]?.report)
        }
    }

    /** 被頻率上限擋下的服務：10 分鐘內每次只呼叫 1 次 */
    private val rateLimitedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun isRateLimit(e: Throwable) = e.message?.let { it.startsWith("HTTP 429") || it.contains("rate limit", true) } == true

    /** AI 服務錯誤轉成中文說明 */
    fun friendlyAi(e: Throwable?): String {
        val msg = e?.message ?: return "未知錯誤"
        return when {
            isRateLimit(e) -> "請求太頻繁，已達服務的頻率上限（免費方案限制較嚴），請稍後再試"
            msg.startsWith("HTTP 401") -> "金鑰無效或已過期，請到設置重新輸入"
            msg.startsWith("HTTP 402") || msg.contains("quota", true) || msg.contains("insufficient", true) -> "額度不足，請確認帳戶餘額或方案"
            msg.startsWith("HTTP 403") -> "沒有使用此模型的權限"
            msg.startsWith("HTTP 404") -> "找不到模型或網址，請確認模型名稱"
            msg.startsWith("HTTP 5") -> "服務暫時故障，稍後再試"
            e is java.net.SocketTimeoutException || msg.contains("timeout", true) -> "連線逾時，稍後再試"
            e is java.net.UnknownHostException -> "無法連線（請檢查網路）"
            else -> msg.take(80)
        }
    }

    /** 每次分析呼叫 AI 的次數（取共識）：1、3、5，預設 3 */
    fun votes(): Int = AppState.cacheGet("aiVotes")?.toIntOrNull()?.takeIf { it in setOf(1, 3, 5) } ?: 3
    fun setVotes(v: Int) = AppState.cachePut("aiVotes", v.toString())

    /** 多次結果取共識：訊號多數決（同票取較保守者），信心取該組中位數，文字取最接近中位數的一份 */
    fun consensus(list: List<StockMetrics.StockAnalysisReport>, asked: Int): StockMetrics.StockAnalysisReport? {
        if (list.isEmpty()) return null
        if (list.size == 1 && asked == 1) return list[0]
        val order = listOf("偏空警示", "轉弱防守", "區間震盪", "多頭回檔", "強勢多頭")
        // 保守度：越接近「區間震盪」越保守
        fun cons(sig: String) = order.indexOf(sig).let { if (it < 0) 0 else kotlin.math.abs(it - 2) }
        val groups = list.groupBy { it.overallSignal }
        val top = groups.values.maxOf { it.size }
        val win = groups.filterValues { it.size == top }.entries.minBy { cons(it.key) }.value
        val confs = win.map { it.confidence }.sorted()
        val med = confs[confs.size / 2]
        val rep = win.minBy { kotlin.math.abs(it.confidence - med) }
        // 意見分歧時信心打折：一致比例越低，信心越低
        val agree = win.size.toDouble() / list.size
        val conf = (med * (0.6 + 0.4 * agree)).toInt().coerceIn(0, 100)
        return rep.copy(confidence = conf, model = rep.model + " · ${list.size} 次共識 ${win.size}/${list.size}")
    }

    private val userPrefix = "以下是本地預計算的 ComputedStockMetrics（所有數字皆已算好，請勿自行推估其他數字），請依規範只輸出單一 JSON：\n"

    private fun call(p: AiProviders.Provider, m: StockMetrics.ComputedStockMetrics): StockMetrics.StockAnalysisReport {
        val key = AppState.aiKey(p.id)
        val model = AiProviders.modelOf(p)
        val user = userPrefix + m.toJson().toString()
        val req = when (p.kind) {
            AiProviders.Kind.ANTHROPIC -> {
                val body = JSONObject().put("model", model).put("max_tokens", 1500).put("temperature", 0.0)
                    .put("system", JSONArray().put(JSONObject().put("type", "text").put("text", skillPrompt())
                        .put("cache_control", JSONObject().put("type", "ephemeral"))))
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                Request.Builder().url(AiProviders.urlOf(p) + "/messages")
                    .header("x-api-key", key).header("anthropic-version", "2023-06-01")
                    .header("anthropic-beta", "prompt-caching-2024-07-31")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            }
            AiProviders.Kind.OPENAI -> {
                val body = JSONObject().put("model", model).put("temperature", 0.0).put("max_tokens", 1500)
                    .put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", skillPrompt()))
                        .put(JSONObject().put("role", "user").put("content", user)))
                Request.Builder().url(AiProviders.urlOf(p) + "/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            }
        }
        client.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(txt).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("HTTP ${r.code}" + (msg?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""))
            }
            val o = JSONObject(txt)
            val text = when (p.kind) {
                AiProviders.Kind.ANTHROPIC -> o.optJSONArray("content")?.let { c ->
                    (0 until c.length()).mapNotNull { c.optJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                }
                AiProviders.Kind.OPENAI -> o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            }.orEmpty()
            if (text.isBlank()) throw IllegalStateException("回應沒有內容")
            return StockMetrics.StockAnalysisReport.parse(text, "ai", p.name + " · " + model) ?: throw IllegalStateException("回應不是有效 JSON")
        }
    }

    /**
     * 通用 AI 問答（配息組合推薦等）：依優先順序嘗試已設定的服務，回傳（文字, 服務名稱 · 模型）。
     * 只傳入本地整理好的數據，不讓 AI 自行推估數字。
     */
    suspend fun ask(system: String, user: String, maxTokens: Int = 1500): Pair<String, String> = withContext(Dispatchers.IO) {
        if (!AppState.aiOn) throw IllegalStateException("AI 功能未開啟")
        val chain = AiProviders.chain()
        if (chain.isEmpty()) throw IllegalStateException("未設定 AI 服務金鑰")
        var last: Throwable? = null
        for (p in chain) {
            val r = runCatching {
                val key = AppState.aiKey(p.id); val model = AiProviders.modelOf(p)
                val req = when (p.kind) {
                    AiProviders.Kind.ANTHROPIC -> Request.Builder().url(AiProviders.urlOf(p) + "/messages")
                        .header("x-api-key", key).header("anthropic-version", "2023-06-01")
                        .post(JSONObject().put("model", model).put("max_tokens", maxTokens).put("temperature", 0.2)
                            .put("system", system)
                            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user))).toString()
                            .toRequestBody("application/json".toMediaType())).build()
                    AiProviders.Kind.OPENAI -> Request.Builder().url(AiProviders.urlOf(p) + "/chat/completions")
                        .header("Authorization", "Bearer $key")
                        .post(JSONObject().put("model", model).put("max_tokens", maxTokens).put("temperature", 0.2)
                            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
                                .put(JSONObject().put("role", "user").put("content", user))).toString()
                            .toRequestBody("application/json".toMediaType())).build()
                }
                client.newCall(req).execute().use { r ->
                    val txt = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code} " + (runCatching { JSONObject(txt).optJSONObject("error")?.optString("message") }.getOrNull() ?: ""))
                    val o = JSONObject(txt)
                    val text = when (p.kind) {
                        AiProviders.Kind.ANTHROPIC -> o.optJSONArray("content")?.let { c -> (0 until c.length()).mapNotNull { c.optJSONObject(it) }.joinToString("") { it.optString("text") } }
                        AiProviders.Kind.OPENAI -> o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    }.orEmpty()
                    if (text.isBlank()) throw IllegalStateException("回應沒有內容")
                    text to (p.name + " · " + model)
                }
            }
            r.getOrNull()?.let { return@withContext it }
            last = r.exceptionOrNull()
        }
        throw IllegalStateException(friendlyAi(last))
    }

    /** 設定頁「測試連線」：送一個極短請求確認金鑰與模型可用 */
    suspend fun test(p: AiProviders.Provider): String = withContext(Dispatchers.IO) {
        runCatching {
            val key = AppState.aiKey(p.id); val model = AiProviders.modelOf(p)
            val req = when (p.kind) {
                AiProviders.Kind.ANTHROPIC -> Request.Builder().url(AiProviders.urlOf(p) + "/messages")
                    .header("x-api-key", key).header("anthropic-version", "2023-06-01")
                    .post(JSONObject().put("model", model).put("max_tokens", 5)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))).toString()
                        .toRequestBody("application/json".toMediaType())).build()
                AiProviders.Kind.OPENAI -> Request.Builder().url(AiProviders.urlOf(p) + "/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .post(JSONObject().put("model", model).put("max_tokens", 5)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))).toString()
                        .toRequestBody("application/json".toMediaType())).build()
            }
            client.newCall(req).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (r.isSuccessful) "連線成功（$model）"
                else "失敗：" + friendlyAi(IllegalStateException("HTTP ${r.code} " + (runCatching { JSONObject(t).optJSONObject("error")?.optString("message") }.getOrNull() ?: t.take(120))))
            }
        }.getOrElse { "失敗：" + (it.message ?: it.javaClass.simpleName) }
    }
}
