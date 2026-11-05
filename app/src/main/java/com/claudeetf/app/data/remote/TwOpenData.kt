package com.claudeetf.app.data.remote

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 台股公開資料（證交所 OpenAPI，免金鑰），以「關鍵字」比對欄位名稱，
 * 避免官方欄位寫法（底線／連字號）變動時解析失敗。每天只下載一次。
 */
object TwOpenData {
    private const val BASE = "https://openapi.twse.com.tw/v1"

    private val cache = HashMap<String, Pair<String, JSONArray>>()

    private fun today() = LocalDate.now(ZoneId.of("Asia/Taipei")).toString()

    @Synchronized
    private fun dataset(path: String): JSONArray {
        cache[path]?.let { (day, arr) -> if (day == today()) return arr }
        val arr = JSONArray(Http.get("$BASE$path", timeoutMs = 30_000))
        cache[path] = today() to arr
        return arr
    }

    private fun JSONObject.find(vararg keywords: String, exclude: String? = null): String? {
        val it = keys()
        while (it.hasNext()) {
            val k = it.next()
            if (keywords.all { kw -> k.contains(kw, ignoreCase = true) } && (exclude == null || !k.contains(exclude))) {
                val v = optString(k).trim()
                return v.ifBlank { null }
            }
        }
        return null
    }

    private fun JSONArray.rowFor(code: String): JSONObject? {
        for (i in 0 until length()) {
            val o = optJSONObject(i) ?: continue
            val c = o.find("公司代號") ?: o.find("Code") ?: continue
            if (c == code) return o
        }
        return null
    }

    // ---------- 本益比、殖利率、股價淨值比 ----------
    data class Valuation(val pe: String?, val yieldPct: String?, val pb: String?)

    fun valuation(code: String): Valuation? {
        val o = dataset("/exchangeReport/BWIBBU_ALL").rowFor(code) ?: return null
        return Valuation(
            pe = o.find("PE") ?: o.find("本益比"),
            yieldPct = o.find("Yield") ?: o.find("殖利率"),
            pb = o.find("PB") ?: o.find("淨值比"),
        )
    }

    // ---------- 月營收 ----------
    data class MonthlyRevenue(
        val month: String,
        val revenueK: Double?,
        val momPct: String?,
        val yoyPct: String?,
        val ytdK: Double?,
        val ytdYoyPct: String?,
        val industry: String?,
    )

    private fun toNum(s: String?) = s?.replace(",", "")?.trim()?.toDoubleOrNull()

    /** 民國年月 11410 → 2025/10 */
    private fun rocMonth(s: String?): String {
        val v = s?.filter { it.isDigit() } ?: return "—"
        if (v.length < 4) return v
        val y = v.dropLast(2).toIntOrNull() ?: return v
        return "${y + 1911}/${v.takeLast(2)}"
    }

    fun monthlyRevenue(code: String): MonthlyRevenue? {
        val o = dataset("/opendata/t187ap05_L").rowFor(code) ?: return null
        return MonthlyRevenue(
            month = rocMonth(o.find("資料年月")),
            revenueK = toNum(o.find("當月營收", exclude = "累計")),
            momPct = o.find("上月比較增減"),
            yoyPct = o.find("去年同月增減"),
            ytdK = toNum(o.find("累計", "當月累計營收")),
            ytdYoyPct = o.find("累計", "前期比較增減"),
            industry = o.find("產業別"),
        )
    }

    // ---------- 公司基本資料 ----------
    data class Company(val fields: List<Pair<String, String>>)

    private val industryCodes = mapOf(
        "01" to "水泥", "02" to "食品", "03" to "塑膠", "04" to "紡織纖維", "05" to "電機機械",
        "06" to "電器電纜", "08" to "玻璃陶瓷", "09" to "造紙", "10" to "鋼鐵", "11" to "橡膠",
        "12" to "汽車", "14" to "建材營造", "15" to "航運", "16" to "觀光餐旅", "17" to "金融保險",
        "18" to "貿易百貨", "20" to "其他", "21" to "化學", "22" to "生技醫療", "23" to "油電燃氣",
        "24" to "半導體", "25" to "電腦及週邊設備", "26" to "光電", "27" to "通信網路",
        "28" to "電子零組件", "29" to "電子通路", "30" to "資訊服務", "31" to "其他電子",
        "32" to "文化創意", "33" to "農業科技", "34" to "電子商務", "35" to "綠能環保",
        "36" to "數位雲端", "37" to "運動休閒", "38" to "居家生活",
    )

    fun company(code: String): Company? {
        val o = dataset("/opendata/t187ap03_L").rowFor(code) ?: return null
        val out = ArrayList<Pair<String, String>>()
        fun add(label: String, value: String?) { if (!value.isNullOrBlank()) out.add(label to value) }
        add("公司名稱", o.find("公司名稱"))
        add("產業別", o.find("產業別")?.let { industryCodes[it.padStart(2, '0')] ?: it })
        add("董事長", o.find("董事長"))
        add("總經理", o.find("總經理"))
        add("成立日期", o.find("成立日期")?.let { rocDate(it) })
        add("上市日期", o.find("上市日期")?.let { rocDate(it) })
        add("實收資本額", o.find("實收資本額")?.let { s -> toNum(s)?.let { "%,.0f 億元".format(Locale.US, it / 1e8) } ?: s })
        add("網址", o.find("網址"))
        add("地址", o.find("地址"))
        return Company(out)
    }

    /** 民國日期 0760221 / 76/02/21 → 1987/02/21 */
    private fun rocDate(s: String): String {
        val d = s.filter { it.isDigit() }
        if (d.length < 6) return s
        val y = d.dropLast(4).toIntOrNull() ?: return s
        return "${y + 1911}/${d.takeLast(4).take(2)}/${d.takeLast(2)}"
    }
}

/**
 * Google 新聞 RSS（中文新聞，免金鑰）
 * https://news.google.com/rss/search?q=…&hl=zh-TW&gl=TW&ceid=TW:zh-Hant
 */
object GoogleNews {
    private val itemRe = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?.replace("<![CDATA[", "")?.replace("]]>", "")
            ?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")
            ?.replace("&lt;", "<")?.replace("&gt;", ">")?.trim()

    fun search(query: String, count: Int = 12): List<YahooExtra.News> {
        val q = URLEncoder.encode(query, "UTF-8")
        val xml = Http.get("https://news.google.com/rss/search?q=$q&hl=zh-TW&gl=TW&ceid=TW:zh-Hant")
        return itemRe.findAll(xml).take(count).mapNotNull { m ->
            val item = m.groupValues[1]
            val title = tag(item, "title") ?: return@mapNotNull null
            val link = tag(item, "link") ?: return@mapNotNull null
            val source = tag(item, "source") ?: ""
            val time = tag(item, "pubDate")?.let {
                runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            } ?: 0L
            YahooExtra.News(title.removeSuffix(" - $source"), source, link, time)
        }.toList()
    }
}
