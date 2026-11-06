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

    /** 個股新聞（RSS），由新到舊 */
    fun stock(q: Quote, count: Int = 20): List<YahooExtra.News> {
        val list = if (q.market == Market.US) {
            feed("https://feeds.finance.yahoo.com/rss/2.0/headline?s=${URLEncoder.encode(q.symbol, "UTF-8")}&region=US&lang=en-US", "Yahoo Finance")
        } else {
            feed("https://tw.stock.yahoo.com/rss?s=${q.symbol}", "Yahoo股市").ifEmpty {
                // 個股 RSS 沒資料：從分類 RSS 找標題或摘要提到代號、名稱的新聞
                val name = q.name.takeIf { it != q.symbol && it.length >= 2 }
                listOf("news", "tw-market", "research").flatMap { category(it) }.filter { n ->
                    n.title.contains(q.symbol) || n.summary.contains(q.symbol) ||
                        (name != null && (n.title.contains(name) || n.summary.contains(name)))
                }
            }
        }
        val out = list.ifEmpty {
            val kw = URLEncoder.encode(q.symbol + " " + q.name + if (q.market == Market.TW) " 股" else " stock", "UTF-8")
            feed("https://news.google.com/rss/search?q=$kw&hl=zh-TW&gl=TW&ceid=TW:zh-Hant", "Google 新聞")
        }
        return out.distinctBy { it.link }.sortedByDescending { it.time }.take(count)
    }

    private val articleCache = HashMap<String, List<String>>()

    /** 文章內文段落（純文字）；需在 IO 執行緒呼叫 */
    fun article(link: String): List<String> {
        articleCache[link]?.let { return it }
        val html = Http.get(link, mapOf("User-Agent" to UA, "Accept" to "text/html"), timeoutMs = 20_000)
        // Yahoo 文章本文在 caas-body；其他網站取 <article> 或整頁
        val body = Regex("class=\"caas-body\"[^>]*>(.*?)</div>\\s*</div>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: Regex("<article[^>]*>(.*?)</article>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
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
