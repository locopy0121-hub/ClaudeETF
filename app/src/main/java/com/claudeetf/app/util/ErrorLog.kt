package com.claudeetf.app.util

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 非閃退的錯誤紀錄（資料載入失敗、API 錯誤等）：保留最近 200 筆，可在「設置 › 關於與更新 › 錯誤紀錄」查看與分享。
 * 也負責「重試」：畫面上的重試按鈕與網路恢復時遞增 [retryTick]，各頁的載入以它為 key 重新執行。
 */
object ErrorLog {
    data class Entry(val at: Long, val where: String, val message: String) {
        fun text(): String = FMT.format(Instant.ofEpochMilli(at)) + "  [" + where + "] " + message
    }

    private const val FILE = "errors.txt"
    private const val MAX = 200
    private val FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss").withZone(ZoneId.of("Asia/Taipei"))

    val entries = mutableStateListOf<Entry>()
    /** 重試計數：各頁載入的 LaunchedEffect 以此為 key */
    var retryTick by mutableIntStateOf(0)
        private set
    /** 上次重試後是否又有載入失敗（網路恢復時才需要自動重試） */
    @Volatile private var failedSinceRetry = false
    private var file: File? = null

    fun init(c: Context) {
        if (file != null) return
        file = File(c.applicationContext.filesDir, FILE)
        runCatching {
            file?.takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
                val p = line.split('\t', limit = 3)
                if (p.size == 3) p[0].toLongOrNull()?.let { Entry(it, p[1], p[2]) } else null
            }?.takeLast(MAX)?.let { entries.addAll(it) }
        }
    }

    /** 記錄一筆錯誤（任何執行緒皆可呼叫）；同一來源同訊息 1 分鐘內不重複記 */
    fun record(where: String, message: String) {
        failedSinceRetry = true
        val e = Entry(System.currentTimeMillis(), where, message.replace('\n', ' ').replace('\t', ' ').take(300))
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            val last = entries.lastOrNull { it.where == e.where && it.message == e.message }
            if (last != null && e.at - last.at < 60_000) return@post
            entries.add(e)
            while (entries.size > MAX) entries.removeAt(0)
            val snapshot = entries.toList()
            Thread {
                runCatching { file?.writeText(snapshot.joinToString("\n") { it.at.toString() + "\t" + it.where + "\t" + it.message }) }
            }.start()
        }
    }

    fun record(where: String, t: Throwable) = record(where, t.message ?: t.javaClass.simpleName)

    /** 使用者按「重試」 */
    fun retry() { failedSinceRetry = false; retryTick++ }

    /** 網路恢復：有失敗過才自動重試，避免畫面無故重載 */
    fun onOnline() { if (failedSinceRetry) retry() }

    fun clear() {
        entries.clear()
        runCatching { file?.delete() }
    }

    fun text(): String = entries.asReversed().joinToString("\n") { it.text() }
}
