package com.claudeetf.app.data

import androidx.compose.runtime.mutableStateMapOf
import com.claudeetf.app.data.remote.YahooExtra
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 個股標題的 ETF 標籤：類型（主動／被動、市值、高股息、債券、槓桿、反向…）與配息方式（月配、季配…）。
 * 類型依代號字尾與名稱判斷；配息方式依近幾年實際配息間隔推算，名稱有寫則以名稱為準。
 */
object EtfTags {
    enum class Kind { STYLE, TYPE, DIV }
    data class Tag(val text: String, val kind: Kind)

    /** symbol → 配息方式（背景載入後更新畫面） */
    val divFreq = mutableStateMapOf<String, String>()
    private val loading = HashSet<String>()

    fun isEtf(q: Quote) = q.isEtf || (q.market == Market.TW && q.symbol.startsWith("00"))

    fun typeTags(q: Quote): List<Tag> {
        if (!isEtf(q)) return emptyList()
        val n = q.name
        val s = q.symbol.uppercase()
        val out = ArrayList<Tag>()
        fun has(vararg k: String) = k.any { n.contains(it, ignoreCase = true) }
        if (q.market == Market.TW) {
            out += Tag(if (s.endsWith("A") || has("主動")) "主動式" else "被動式", Kind.STYLE)
            when {
                s.endsWith("L") || has("正2", "正二", "槓桿") -> out += Tag("槓桿型", Kind.TYPE)
                s.endsWith("R") || has("反1", "反一", "反向") -> out += Tag("反向型", Kind.TYPE)
                s.endsWith("U") || has("期貨", "原油", "黃金", "白銀", "黃豆", "商品") -> out += Tag("期貨/商品", Kind.TYPE)
                s.endsWith("B") || has("債", "公司債", "公債") -> out += Tag("債券型", Kind.TYPE)
                else -> {
                    when {
                        has("高息", "高股息", "股息", "收益", "優息", "息收", "配息", "月月", "股利") -> out += Tag("高股息", Kind.TYPE)
                        has("台灣50", "臺灣50", "中型100", "市值", "台灣 50", "MSCI", "加權", "200", "150") -> out += Tag("市值型", Kind.TYPE)
                        has("半導體", "科技", "電子", "5G", "AI", "電動車", "金融", "生技", "ESG", "永續", "綠能", "網通", "智慧", "創新") -> out += Tag("主題型", Kind.TYPE)
                        else -> out += Tag("股票型", Kind.TYPE)
                    }
                    if (has("美國", "美股", "日本", "日經", "中國", "滬深", "A50", "香港", "恒生", "印度", "越南", "全球", "歐洲", "韓國", "NASDAQ", "那斯達克", "標普", "費城", "道瓊"))
                        out += Tag("海外", Kind.TYPE)
                }
            }
        } else {
            out += Tag(if (has("Active")) "主動式" else "被動式", Kind.STYLE)
            when {
                has("2X", "3X", "Ultra", "Leveraged", "Bull") -> out += Tag("槓桿型", Kind.TYPE)
                has("Short", "Inverse", "Bear") -> out += Tag("反向型", Kind.TYPE)
                has("Bond", "Treasury", "Income", "Aggregate", "Corporate") && !has("Equity") -> out += Tag("債券型", Kind.TYPE)
                has("Dividend", "Yield", "Covered Call", "Premium") -> out += Tag("高股息", Kind.TYPE)
                has("Gold", "Silver", "Oil", "Commodity") -> out += Tag("商品", Kind.TYPE)
                has("S&P 500", "Total", "Nasdaq 100", "QQQ", "Russell", "Dow") -> out += Tag("市值型", Kind.TYPE)
                else -> out += Tag("主題型", Kind.TYPE)
            }
        }
        return out
    }

    /** 名稱有寫明的配息方式 */
    private fun fromName(n: String): String? = when {
        n.contains("月配") || n.contains("月月") || n.contains("Monthly", true) -> "月配"
        n.contains("季配") -> "季配"
        n.contains("半年配") -> "半年配"
        n.contains("年配") -> "年配"
        else -> null
    }

    /** 依配息日期推算：取近 4 次間隔的中位數 */
    fun freqOf(dates: List<Long>): String {
        val d = dates.sorted()
        if (d.isEmpty()) return "不配息"
        val yearAgo = System.currentTimeMillis() - 400L * 24 * 3600 * 1000
        if (d.last() < yearAgo - 365L * 24 * 3600 * 1000) return "不配息"
        if (d.size == 1) return "已配息 1 次"
        val gaps = d.zipWithNext { a, b -> (b - a) / 86_400_000.0 }.takeLast(4).sorted()
        val g = gaps[gaps.size / 2]
        return when {
            g < 45 -> "月配"
            g < 125 -> "季配"
            g < 250 -> "半年配"
            else -> "年配"
        }
    }

    suspend fun ensureFreq(q: Quote) {
        if (!isEtf(q) || divFreq.containsKey(q.symbol) || !loading.add(q.symbol)) return
        fromName(q.name)?.let { divFreq[q.symbol] = it; return }
        val f = withContext(Dispatchers.IO) {
            runCatching { YahooExtra.dividends(StockInfoRepo.yahooSymbol(q)) }.getOrNull()
        }
        if (f == null) { loading.remove(q.symbol); return }
        // 剛上市（名稱或代號判斷不出、且無配息紀錄）顯示「尚未配息」
        divFreq[q.symbol] = if (f.isEmpty()) "尚未配息" else freqOf(f.map { it.time })
    }
}
