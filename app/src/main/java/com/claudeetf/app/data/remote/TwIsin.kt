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

    fun fetch(mode: Int): List<Sec> {
        val conn = URL("https://isin.twse.com.tw/isin/C_public.jsp?strMode=$mode").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Mobile")
            if (conn.responseCode !in 200..299) throw java.io.IOException("ISIN HTTP " + conn.responseCode)
            val cs = runCatching { Charset.forName("MS950") }.getOrElse { Charset.forName("Big5") }
            val out = ArrayList<Sec>(4000)
            var section = ""
            java.util.Scanner(conn.inputStream.reader(cs).buffered(64 * 1024)).use { sc ->
                sc.useDelimiter("(?i)</tr>")
                while (sc.hasNext()) {
                    val row = sc.next()
                    val tds = TD.findAll(row).map { it.groupValues[1].replace(TAG, "").replace("&nbsp;", " ").trim() }.toList()
                    if (tds.size == 1 || (tds.isNotEmpty() && tds.size < 5)) { section = tds.first().trim(); continue }
                    if (tds.size < 5) continue
                    val first = tds[0].replace('　', ' ').trim()
                    val code = first.substringBefore(' ').trim()
                    val name = first.substringAfter(' ', "").trim()
                    if (name.isEmpty() || !CODE.matches(code)) continue
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
            }
            return out
        } finally {
            conn.disconnect()
        }
    }
}
