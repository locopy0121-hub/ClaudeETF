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

/**
 * AI 個股體檢：Claude API 解讀層＋同交易日快取＋降級容錯。
 * 只把本地算好的 [StockMetrics.ComputedStockMetrics] 傳給 Claude；系統提示詞為 assets/stock_analysis_skill.md（開啟 Prompt Caching）。
 * 結果以「代號＋最新交易日」快取，同一天不重複呼叫；沒有金鑰或失敗時回傳本地規則體檢。
 */
object ClaudeAnalysis {
    data class State(
        val metrics: StockMetrics.ComputedStockMetrics?,
        val report: StockMetrics.StockAnalysisReport?,
        /** 為什麼是本地結果（未設金鑰、API 失敗原因）；null＝Claude 解讀成功或讀快取 */
        val fallback: String? = null,
        val loading: Boolean = false,
    )

    val states = mutableStateMapOf<String, State>()

    val MODELS = listOf("claude-haiku-5-5" to "Haiku（快、省）", "claude-sonnet-5-5" to "Sonnet（均衡）", "claude-opus-5-5" to "Opus（最深入）")

    private lateinit var ctx: Context
    private val client by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build() }
    private var skill: String? = null

    fun init(c: Context) { if (!::ctx.isInitialized) ctx = c.applicationContext }

    private fun dir() = File(ctx.filesDir, "ai_analysis").apply { mkdirs() }
    private fun cacheFile(sym: String, date: String) = File(dir(), sym.replace(Regex("[^A-Za-z0-9]"), "_") + "_" + date + ".json")

    private fun skillPrompt(): String = skill ?: runCatching {
        ctx.assets.open("stock_analysis_skill.md").bufferedReader().use { it.readText() }
    }.getOrDefault("").also { skill = it }

    /**
     * 載入體檢：先算本地指標；有快取用快取；useAi 且有金鑰時呼叫 Claude；否則（或失敗）用本地規則。
     * force：忽略快取重新呼叫 Claude。
     */
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
        // 同交易日快取
        val cf = cacheFile(sym, m.date)
        if (!force) withContext(Dispatchers.IO) { runCatching { StockMetrics.StockAnalysisReport.parse(cf.readText(), "claude") }.getOrNull() }?.let { cached ->
            withContext(Dispatchers.Main) { states[sym] = State(m, cached, null, false) }
            return
        }
        val key = AppState.claudeKey
        if (!useAi || key.isBlank()) {
            withContext(Dispatchers.Main) { states[sym] = State(m, local, if (key.isBlank()) "未設定 Claude API 金鑰，顯示本地規則體檢" else null, false) }
            return
        }
        val res = withContext(Dispatchers.IO) { runCatching { call(key, AppState.claudeModel, m) } }
        val rep = res.getOrNull()
        withContext(Dispatchers.Main) {
            states[sym] = if (rep != null) {
                runCatching { cf.writeText(rep.toJson().toString()) }
                // 舊快取只留 7 天
                dir().listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L }?.forEach { it.delete() }
                State(m, rep, null, false)
            } else State(m, local, "Claude 解讀失敗：" + (res.exceptionOrNull()?.message ?: "未知錯誤") + "，改顯示本地規則體檢", false)
        }
    }

    private fun call(key: String, model: String, m: StockMetrics.ComputedStockMetrics): StockMetrics.StockAnalysisReport {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1500)
            .put("temperature", 0.1)
            .put("system", JSONArray().put(JSONObject().put("type", "text").put("text", skillPrompt())
                .put("cache_control", JSONObject().put("type", "ephemeral"))))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content",
                "以下是本地預計算的 ComputedStockMetrics（所有數字皆已算好，請勿自行推估其他數字），請依規範輸出單一 JSON：\n" + m.toJson().toString())))
        val req = Request.Builder().url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .header("anthropic-beta", "prompt-caching-2024-07-31")
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(txt).optJSONObject("error")?.optString("message") }.getOrNull()
                throw IllegalStateException("HTTP ${r.code}" + (msg?.let { "：$it" } ?: ""))
            }
            val content = JSONObject(txt).optJSONArray("content") ?: throw IllegalStateException("回應沒有內容")
            val text = (0 until content.length()).mapNotNull { content.optJSONObject(it) }.filter { it.optString("type") == "text" }
                .joinToString("") { it.optString("text") }
            return StockMetrics.StockAnalysisReport.parse(text, "claude", model) ?: throw IllegalStateException("回應不是有效 JSON")
        }
    }
}
