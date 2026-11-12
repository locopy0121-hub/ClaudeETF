package com.claudeetf.app.data.remote

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 證交所「國際證券辨識號碼（ISIN）」一覽表：含尚未有成交資料的新上市／上櫃股票與 ETF、上市日、產業別。
 * strMode=2 上市、4 上櫃；網頁為 Big5（MS950）編碼，逐列串流解析，只保留股票、ETF、ETN、特別股、TDR 等代號（排除權證）。
 */
object TwIsin {
    data class Sec(val code: String, val name: String, val listDate: String, val market: String, val industry: String, val type: String)

    private val CODE = Regex("^(\\d{4}[A-Z]?|00\\d{3,4}[A-Z]?|\\d{4}[A-Z]{1,2})$")
    private val TD = Regex("<td[^>]*>(.*?)</td>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]+>")

    /** 證交所 ISIN 一覽表網頁很大、伺服器偏慢，偶有連線中斷：失敗自動重試一次 */
    fun fetch(mode: Int): List<Sec> = runCatching { fetchOnce(mode) }.getOrElse { e ->
        if (e is java.io.IOException) { Thread.sleep(2_000); fetchOnce(mode) } else throw e
    }

    private fun fetchOnce(mode: Int): List<Sec> {
        val conn = URL("https://isin.twse.com.tw/isin/C_public.jsp?strMode=$mode").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mobile")
            if (conn.responseCode !in 200..299) throw java.io.IOException("ISIN HTTP " + conn.responseCode)
            val cs = runCatching { Charset.forName("MS950") }.getOrElse { Charset.forName("Big5") }
            val out = ArrayList<Sec>(4000)
            var section = ""
            // 整頁讀入後以 indexOf 切列：先只看第一格代號，不符合（權證等數萬列）就跳過，避免逐列跑正規表示式
            val text = conn.inputStream.reader(cs).buffered(256 * 1024).use { it.readText() }
            val low = text.lowercase()
            var pos = 0
            while (true) {
                val end = low.indexOf("</tr>", pos)
                if (end < 0) break
                val rowStart = pos
                pos = end + 5
                val td0 = low.indexOf("<td", rowStart).takeIf { it in 0 until end } ?: continue
                val tdCount = run { var c = 0; var i = td0; while (i in 0 until end) { c++; i = low.indexOf("<td", i + 3) }; c }
                val c0 = text.indexOf('>', td0) + 1
                val c1 = low.indexOf("</td", c0).takeIf { it in c0..end } ?: end
                val firstCell = text.substring(c0, c1).replace(TAG, "").replace("&nbsp;", " ").trim()
                if (tdCount < 5) { if (firstCell.isNotEmpty()) section = firstCell; continue }
                val first = firstCell.replace('　', ' ').trim()
                val code = first.substringBefore(' ').trim()
                val name = first.substringAfter(' ', "").trim()
                if (name.isEmpty() || !CODE.matches(code)) continue
                val tds = TD.findAll(text.substring(rowStart, end)).map { it.groupValues[1].replace(TAG, "").replace("&nbsp;", " ").trim() }.toList()
                val type = when {
                    section.contains("ETF", true) || (code.startsWith("00") && !section.contains("ETN", true)) -> "ETF"
                    section.contains("ETN", true) -> "ETN"
                    section.contains("特別股") -> "特別股"
                    section.contains("存託") -> "TDR"
                    section.contains("受益") -> "受益證券"
                    else -> "股票"
                }
                out += Sec(code, name, tds.getOrElse(2) { "" }, tds.getOrElse(3) { "" }, tds.getOrElse(4) { "" }, type)
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}
