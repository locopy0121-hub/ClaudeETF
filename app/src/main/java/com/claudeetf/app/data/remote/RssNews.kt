package com.claudeetf.app.data.remote

import com.claudeetf.app.data.Market
import com.claudeetf.app.data.Quote
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * RSS 新聞：清單與內文都在 App 內顯示，不開外部瀏覽器。
 *
 * 台股：Yahoo 奇摩股市 RSS（個股 rss?s=代號；查不到時從「最新新聞／台股動態／研究報導」分類過濾代號與名稱）
 * 美股：Yahoo Finance 個股 RSS（feeds.finance.yahoo.com/rss/2.0/headline）
 * 最後備援：Google 新聞 RSS（只有標題與摘要）
 * 內文：讀取文章頁面，擷取 <p> 段落文字（Yahoo 文章的 caas-body 優先），只顯示純文字。
 */
object RssNews {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private val headers = mapOf("User-Agent" to UA, "Accept" to "application/rss+xml, application/xml, text/xml, text/html, */*")
    private val itemRe = Regex("<item[\\s>](.*?)</item>", RegexOption.DOT_MATCHES_ALL)

    val categories = listOf(
        "news" to "最新新聞", "tw-market" to "台股動態", "intl-markets" to "國際財經",
        "research" to "研究報導", "column" to "專家專欄", "funds-news" to "基金動態", "personal-finance" to "小資理財",
    )

    /**
     * 新聞來源（我的 › 新聞來源 可多選）。
     * market：一般財經新聞 RSS；stock：個股專屬 RSS（沒有時用 market 過濾代號與名稱）
     */
    data class Source(
        val id: String,
        val name: String,
        val market: String?,
        val stock: ((Quote) -> String?)? = null,
        val usOnly: Boolean = false,
        val note: String = "",
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    val builtIn = listOf(
        Source("yahoo", "Yahoo股市", "https://tw.stock.yahoo.com/rss?category=news",
            { q -> if (q.market == Market.TW) "https://tw.stock.yahoo.com/rss?s=${q.symbol}" else null }, note = "全文可在 App 內閱讀"),
        Source("google", "Google 新聞", "https://news.google.com/rss/search?q=${enc("台股 財經")}&hl=zh-TW&gl=TW&ceid=TW:zh-Hant",
            { q -> "https://news.google.com/rss/search?q=" + enc(q.symbol + " " + q.name + if (q.market == Market.TW) " 股" else " stock") + "&hl=zh-TW&gl=TW&ceid=TW:zh-Hant" },
            note = "彙整各家媒體；多數只有摘要"),
        Source("cna", "中央社 財經", "https://feeds.feedburner.com/rsscna/finance"),
        Source("ltn", "自由財經", "https://news.ltn.com.tw/rss/business.xml"),
        Source("udn", "經濟日報 證券", "https://money.udn.com/rssfeed/news/1001/5590?ch=money"),
        Source("moneydj", "MoneyDJ 理財網", "https://www.moneydj.com/KMDJ/RssCenter.aspx?svc=NR&fno=1&arg=MB010000"),
        Source("yahoous", "Yahoo Finance（美股）", "https://feeds.finance.yahoo.com/rss/2.0/headline?s=%5EGSPC&region=US&lang=en-US",
            { q -> if (q.market == Market.US) "https://feeds.finance.yahoo.com/rss/2.0/headline?s=${enc(q.symbol)}&region=US&lang=en-US" else null },
            usOnly = true, note = "美股個股新聞"),
    )

    /** 目前啟用的來源（內建＋自訂 RSS） */
    fun enabled(): List<Source> {
        val st = com.claudeetf.app.data.AppState
        return builtIn.filter { it.id in st.newsSources } +
            st.customFeeds.filter { "c:" + it.first in st.newsSources }.map { (n, u) -> Source("c:$n", n, u) }
    }

    /** 各來源最近一次載入結果（設定頁顯示） */
    val status = androidx.compose.runtime.mutableStateMapOf<String, String>()

    private fun stamp() = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("HH:mm"))

    private fun load(src: Source, url: String): List<YahooExtra.News> {
        val r = runCatching { parse(Http.get(url, headers, timeoutMs = 20_000), src.name) }
        val list = r.getOrDefault(emptyList())
        val msg = r.exceptionOrNull()?.let { "失敗：" + it.friendly() } ?: if (list.isEmpty()) "沒有內容（網址可能已變更）" else "${list.size} 則"
        android.os.Handler(android.os.Looper.getMainLooper()).post { status[src.id] = stamp() + " " + msg }
        return list
    }

    /** 測試單一來源（設定頁） */
    fun test(name: String, url: String): String {
        val r = runCatching { parse(Http.get(url, headers, timeoutMs = 20_000), name) }
        return r.exceptionOrNull()?.let { "失敗：" + it.friendly() } ?: r.getOrNull()!!.let { if (it.isEmpty()) "讀得到但沒有新聞項目（不是 RSS？）" else "成功：${it.size} 則，最新「${it.first().title.take(20)}」" }
    }

    /** 所有啟用來源合併的財經新聞（首頁卡片） */
    fun merged(count: Int = 40): List<YahooExtra.News> = parallel(enabled().filter { !it.usOnly || enabled().size == 1 }) { src -> src.market?.let { load(src, it) }.orEmpty() }
        .let { dedupe(it) }.take(count)

    private fun <T> parallel(list: List<Source>, f: (Source) -> List<T>): List<T> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(6, maxOf(1, list.size)))
        try {
            return list.map { s -> pool.submit<List<T>> { runCatching { f(s) }.getOrDefault(emptyList()) } }.flatMap { it.get() }
        } finally { pool.shutdown() }
    }

    private fun normTitle(t: String) = t.replace(Regex("[\\s　「」『』:：,，.。!！?？\\-–—|｜]"), "").take(24)

    private fun dedupe(list: List<YahooExtra.News>) =
        list.distinctBy { it.link }.distinctBy { normTitle(it.title) }.sortedByDescending { it.time }

    /** HTML／實體字元 → 純文字 */
    fun plain(html: String): String {
        val noScript = html.replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), "")
        @Suppress("DEPRECATION")
        val t = if (android.os.Build.VERSION.SDK_INT >= 24) android.text.Html.fromHtml(noScript, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        else android.text.Html.fromHtml(noScript).toString()
        return t.replace(' ', ' ').replace("￼", "").replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?.replace("<![CDATA[", "")?.replace("]]>", "")?.trim()

    private fun time(s: String?): Long = s?.let {
        runCatching { ZonedDateTime.parse(it.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    } ?: 0L

    fun parse(xml: String, defaultSource: String): List<YahooExtra.News> =
        itemRe.findAll(xml).mapNotNull { m ->
            val item = m.groupValues[1]
            val title = tag(item, "title")?.let { plain(it) }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link = tag(item, "link")?.let { plain(it) }?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val src = tag(item, "source")?.let { plain(it) }?.ifBlank { null } ?: defaultSource
            val desc = (tag(item, "content:encoded") ?: tag(item, "description"))?.let { plain(it) }.orEmpty()
            // Google 新聞的摘要就是標題本身，不重複顯示
            val summary = desc.takeIf { it.isNotBlank() && !it.startsWith(title.take(12)) }.orEmpty()
            YahooExtra.News(title.removeSuffix(" - $src"), src, link, time(tag(item, "pubDate")), summary)
        }.toList()

    private fun feed(url: String, source: String): List<YahooExtra.News> =
        runCatching { parse(Http.get(url, headers, timeoutMs = 20_000), source) }.getOrDefault(emptyList())

    fun category(cat: String): List<YahooExtra.News> = feed("https://tw.stock.yahoo.com/rss?category=$cat", "Yahoo股市")

    /** 個股新聞：所有啟用來源合併、去重，由新到舊 */
    fun stock(q: Quote, count: Int = 30): List<YahooExtra.News> {
        val name = q.name.takeIf { it != q.symbol && it.length >= 2 }
        fun mentions(n: YahooExtra.News) = n.title.contains(q.symbol, true) || n.summary.contains(q.symbol, true) ||
            (name != null && (n.title.contains(name) || n.summary.contains(name)))
        val srcs = enabled().ifEmpty { builtIn.filter { it.id == "yahoo" || it.id == "google" } }
        val all = parallel(srcs) { src ->
            val own = src.stock?.invoke(q)
            when {
                own != null -> load(src, own).ifEmpty {
                    // Yahoo 個股 RSS 沒資料：改從分類新聞過濾
                    if (src.id == "yahoo") listOf("news", "tw-market", "research").flatMap { category(it) }.filter(::mentions) else emptyList()
                }
                src.usOnly && q.market != Market.US -> emptyList()
                src.market != null -> load(src, src.market).filter(::mentions)
                else -> emptyList()
            }
        }
        return dedupe(all).take(count)
    }

    private val articleCache = HashMap<String, List<String>>()

    /** 文章內文段落（純文字）；需在 IO 執行緒呼叫 */
    fun article(link: String): List<String> {
        articleCache[link]?.let { return it }
        val html = Http.get(link, mapOf("User-Agent" to UA, "Accept" to "text/html"), timeoutMs = 20_000)
        // Yahoo 文章本文在 caas-body；其他網站取 <article> 或整頁
        // 本文可能含圖片等巢狀 div，不用 </div> 截斷：從 caas-body 開始一路取到文章結尾標記
        val body = html.indexOf("caas-body").takeIf { it >= 0 }?.let { start ->
            val ends = listOf("caas-readmore", "</article>", "caas-share", "caas-da").map { html.indexOf(it, start) }.filter { it > start }
            html.substring(start, ends.minOrNull() ?: html.length)
        } ?: Regex("<article[^>]*>(.*?)</article>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: html
        val paras = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(body)
            .map { plain(it.groupValues[1]) }
            .filter { it.length >= 8 && !it.startsWith("更多") && !it.contains("延伸閱讀") && !it.contains("©") }
            .toList()
            .let { ps -> if (ps.size >= 2 || body === html) ps else Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { plain(it.groupValues[1]) }.filter { it.length >= 8 }.toList() }
        if (paras.isEmpty()) throw IllegalStateException("這篇文章無法擷取內文")
        articleCache[link] = paras
        return paras
    }
}
