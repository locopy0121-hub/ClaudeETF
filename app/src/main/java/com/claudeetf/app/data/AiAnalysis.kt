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
        for (p in chain) {
            val r = withContext(Dispatchers.IO) { runCatching { call(p, m) } }
            rep = r.getOrNull()
            if (rep != null) break
            errors += p.name + "：" + (r.exceptionOrNull()?.message ?: "未知錯誤")
            com.claudeetf.app.util.ErrorLog.record("AI 體檢", sym + " " + errors.last())
        }
        withContext(Dispatchers.Main) {
            states[sym] = if (rep != null) {
                runCatching { cf.writeText(rep.toJson().toString()) }
                dir().listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L }?.forEach { it.delete() }
                State(m, rep, if (errors.isNotEmpty()) "已改用備援服務（" + errors.joinToString("；") + "）" else null, false)
            } else State(m, local, "AI 解讀失敗（" + errors.joinToString("；") + "），改顯示本地規則體檢", false)
            EntryAdvisor.applyAi(sym, states[sym]?.report)
        }
    }

    private val userPrefix = "以下是本地預計算的 ComputedStockMetrics（所有數字皆已算好，請勿自行推估其他數字），請依規範只輸出單一 JSON：\n"

    private fun call(p: AiProviders.Provider, m: StockMetrics.ComputedStockMetrics): StockMetrics.StockAnalysisReport {
        val key = AppState.aiKey(p.id)
        val model = AiProviders.modelOf(p)
        val user = userPrefix + m.toJson().toString()
        val req = when (p.kind) {
            AiProviders.Kind.ANTHROPIC -> {
                val body = JSONObject().put("model", model).put("max_tokens", 1500).put("temperature", 0.1)
                    .put("system", JSONArray().put(JSONObject().put("type", "text").put("text", skillPrompt())
                        .put("cache_control", JSONObject().put("type", "ephemeral"))))
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
                Request.Builder().url(AiProviders.urlOf(p) + "/messages")
                    .header("x-api-key", key).header("anthropic-version", "2023-06-01")
                    .header("anthropic-beta", "prompt-caching-2024-07-31")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            }
            AiProviders.Kind.OPENAI -> {
                val body = JSONObject().put("model", model).put("temperature", 0.1).put("max_tokens", 1500)
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
                else "失敗 HTTP ${r.code}：" + (runCatching { JSONObject(t).optJSONObject("error")?.optString("message") }.getOrNull() ?: t.take(120))
            }
        }.getOrElse { "失敗：" + (it.message ?: it.javaClass.simpleName) }
    }
}
