package com.claudeetf.app.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.ZoneId
import java.time.ZonedDateTime

/** 閃退時把錯誤寫到 App 內部檔案，下次開啟可在「錯誤紀錄」查看與分享 */
object CrashLog {
    private const val FILE = "crash.txt"
    private const val SEP = "\n\n════════════════════\n\n"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val ver = runCatching {
                    @Suppress("DEPRECATION")
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull() ?: ""
                val text = "時間：" + ZonedDateTime.now(ZoneId.of("Asia/Taipei")) + "\n版本：" + ver +
                    "\nAndroid：" + android.os.Build.VERSION.RELEASE + "（" + android.os.Build.MODEL + "）\n執行緒：" + t.name + "\n\n" + sw
                // 保留最近 5 次閃退（新的在前）
                val f = File(app.filesDir, FILE)
                val old = runCatching { f.takeIf { it.exists() }?.readText() }.getOrNull()
                val parts = (listOf(text) + (old?.split(SEP) ?: emptyList())).filter { it.isNotBlank() }.take(5)
                f.writeText(parts.joinToString(SEP))
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun read(context: Context): String? = runCatching {
        File(context.applicationContext.filesDir, FILE).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clear(context: Context) { runCatching { File(context.applicationContext.filesDir, FILE).delete() } }
}
