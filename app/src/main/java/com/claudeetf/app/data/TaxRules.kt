package com.claudeetf.app.data

import kotlin.math.roundToLong

/**
 * 配息稅務類型。台股 ETF 的配息依「收益分配組成」課稅：
 * - 股利所得：計入綜所稅；單次給付 ≥ 2 萬元扣二代健保 2.11%
 * - 選擇權權利金、證券交易所得：停徵所得稅，不扣二代健保
 * - 收益平準金：不計入所得，不扣二代健保
 * - 海外所得（海外股票、海外債券）：適用個人最低稅負制，不扣二代健保
 * 「應稅比例」= 股利所得占配息的比例，可依投信每期公告調整。
 */
enum class TaxKind(val label: String, val defaultTaxable: Double, val note: String) {
    TW_STOCK(
        "台股一般股票", 1.0,
        "現金股利為股利所得：計入綜所稅（可選 8.5% 抵減或 28% 分開計稅）；單次 ≥ NT$20,000 扣二代健保 2.11%。",
    ),
    TW_ETF_EQUITY(
        "國內股票型 ETF", 1.0,
        "配息可能含股利所得、收益平準金、資本利得；只有股利所得部分計稅並計算二代健保。請依投信公告的配息組成調整應稅比例。",
    ),
    TW_ETF_OPTION(
        "權利金收益型 ETF", 0.0,
        "配息以賣出選擇權權利金為主，屬證券期貨交易所得，目前停徵所得稅且不扣二代健保。若某期含股利所得，請調整應稅比例。",
    ),
    TW_ETF_BOND(
        "債券型 ETF", 0.0,
        "配息多為海外債券利息（海外所得），不計綜所稅、不扣二代健保，改適用個人最低稅負制（海外所得全年達 100 萬元需計入）。",
    ),
    TW_ETF_OVERSEAS(
        "海外股票型 ETF", 0.0,
        "配息屬海外所得，不計綜所稅、不扣二代健保，改適用個人最低稅負制。",
    ),
    US(
        "美股／美國 ETF", 1.0,
        "非美國稅務居民配息預扣 30% 所得稅，實收為 70%；不扣二代健保。海外所得適用個人最低稅負制。",
    ),
}

data class DividendBreakdown(
    val gross: Double,
    val taxable: Double,
    val withholding: Double,
    val nhi: Double,
    val net: Double,
    val lines: List<Pair<String, String>>,
)

object TaxRules {
    const val NHI_RATE = 0.0211
    const val NHI_THRESHOLD = 20_000.0
    const val US_WITHHOLD = 0.30

    /** 已查證配息組成的個別證券（依投信公告） */
    private val known = mapOf(
        "00406A" to TaxKind.TW_ETF_OPTION, // 主動中信台灣收益：公告配息組成 100% 選擇權權利金
    )

    private val overseasWords = listOf(
        "美國", "美股", "標普", "S&P", "NASDAQ", "那斯達克", "費城", "道瓊", "全球", "國際", "海外",
        "日本", "日經", "東證", "中國", "滬深", "上証", "上證", "恒生", "香港", "印度", "越南", "歐洲",
        "德國", "韓國", "新興", "亞洲", "美元",
    )

    private val optionWords = listOf("掩護", "選擇權", "Covered", "買權")

    fun detect(q: Quote): TaxKind {
        if (q.market == Market.US) return TaxKind.US
        known[q.symbol]?.let { return it }
        val etf = q.isEtf || q.symbol.startsWith("00")
        if (!etf) return TaxKind.TW_STOCK
        val name = q.name
        return when {
            q.symbol.endsWith("B") || name.contains("債") -> TaxKind.TW_ETF_BOND
            optionWords.any { name.contains(it, ignoreCase = true) } -> TaxKind.TW_ETF_OPTION
            overseasWords.any { name.contains(it, ignoreCase = true) } -> TaxKind.TW_ETF_OVERSEAS
            else -> TaxKind.TW_ETF_EQUITY
        }
    }

    fun kindOf(symbol: String): TaxKind {
        AppState.taxKindOverride[symbol]?.let { return it }
        val q = AppState.quote(symbol)
            ?: return if (symbol.firstOrNull()?.isDigit() == true) TaxKind.TW_STOCK else TaxKind.US
        return detect(q)
    }

    fun isAutoDetected(symbol: String) = !AppState.taxKindOverride.containsKey(symbol)

    /** 台股 ETF：代號 00 開頭、報價標示 ETF，或稅務類別為 ETF */
    fun isTwEtf(symbol: String): Boolean {
        if (symbol.startsWith("00")) return true
        if (AppState.quote(symbol)?.isEtf == true) return true
        return kindOf(symbol) in setOf(TaxKind.TW_ETF_EQUITY, TaxKind.TW_ETF_OPTION, TaxKind.TW_ETF_BOND, TaxKind.TW_ETF_OVERSEAS)
    }

    /**
     * 台股賣出證券交易稅：(稅率, 說明)。
     * 股票 0.3%、現股當沖 0.15%（減半至 2027/12/31）；ETF 0.1%；債券 ETF 停徵至 2026/12/31。
     * date 為 yyyy/MM/dd 或 yyyy-MM-dd。
     */
    fun sellTaxRate(symbol: String, date: String, dayTrade: Boolean): Pair<Double, String> {
        val d = runCatching { java.time.LocalDate.parse(date.trim().replace("/", "-")) }.getOrNull() ?: java.time.LocalDate.now()
        if (isTwEtf(symbol)) {
            return if (kindOf(symbol) == TaxKind.TW_ETF_BOND && !d.isAfter(java.time.LocalDate.of(2026, 12, 31))) 0.0 to "債券 ETF 停徵證交稅（至 2026/12/31）"
            else 0.001 to "ETF 證交稅 0.1%"
        }
        if (dayTrade && !d.isAfter(java.time.LocalDate.of(2027, 12, 31))) return 0.0015 to "現股當沖證交稅減半 0.15%"
        return 0.003 to "股票證交稅 0.3%"
    }

    /** 證交稅金額：元以下不計（無條件捨去） */
    fun sellTax(symbol: String, amount: Double, date: String, dayTrade: Boolean): Double =
        com.claudeetf.app.util.TwRules.tax(amount, sellTaxRate(symbol, date, dayTrade).first)

    /** 台股賣出紀錄中，證交稅與規則不符者：(交易, 應為稅額) */
    fun taxMismatches(): List<Pair<Trade, Double>> = AppState.trades.filter { t ->
        t.type == TradeType.SELL && t.symbol.firstOrNull()?.isDigit() == true && t.origin != TradeOrigin.LEGACY
    }.mapNotNull { t ->
        val expect = sellTax(t.symbol, t.gross, t.date, looksLikeDayTrade(t.symbol, t.date, t.id))
        if (kotlin.math.abs(expect - t.tax) >= 1.0) t to expect else null
    }

    /** 同一天已有同代號買進（且非期初）→ 視為現股當沖 */
    fun looksLikeDayTrade(symbol: String, date: String, excludeId: Long = -1): Boolean {
        val norm = date.trim().replace("-", "/")
        return AppState.trades.any { it.id != excludeId && it.symbol == symbol && it.type == TradeType.BUY && it.date.replace("-", "/") == norm }
    }

    /** 股利所得占配息的比例（0–1） */
    fun taxableRatio(symbol: String): Double =
        AppState.taxRatioOverride[symbol] ?: kindOf(symbol).defaultTaxable

    /**
     * 試算一次配息的扣除。
     * @param gross 配息總額（原幣）
     * @param ratio 這一期的應稅比例；null 時用該證券的設定
     */
    fun dividend(symbol: String, gross: Double, ratio: Double? = null): DividendBreakdown {
        val kind = kindOf(symbol)
        if (kind == TaxKind.US) {
            val wh = Math.round(gross * US_WITHHOLD * 100) / 100.0
            return DividendBreakdown(
                gross, gross, wh, 0.0, gross - wh,
                listOf("預扣所得稅 30%" to "−\$" + "%.2f".format(java.util.Locale.US, wh), "二代健保" to "不扣"),
            )
        }
        val r = (ratio ?: taxableRatio(symbol)).coerceIn(0.0, 1.0)
        val taxable = gross * r
        val nhi = if (taxable >= NHI_THRESHOLD) (taxable * NHI_RATE).roundToLong().toDouble() else 0.0
        val lines = ArrayList<Pair<String, String>>()
        lines += "應稅股利所得（${Math.round(r * 100)}%）" to "%,.0f".format(java.util.Locale.US, taxable)
        lines += "二代健保 2.11%" to when {
            taxable <= 0.0 -> "不扣"
            nhi > 0 -> "−%,.0f".format(java.util.Locale.US, nhi)
            else -> "未達 2 萬免扣"
        }
        lines += "所得稅" to when (kind) {
            TaxKind.TW_ETF_BOND, TaxKind.TW_ETF_OVERSEAS -> "海外所得，最低稅負制"
            else -> if (taxable > 0) "併入綜所稅（不預扣）" else "停徵／免計"
        }
        return DividendBreakdown(gross, taxable, 0.0, nhi, gross - nhi, lines)
    }

    /** 投信公告配息組成的查詢頁（證交所 ETF e添富） */
    fun compositionUrl(symbol: String): String = "https://www.twse.com.tw/zh/ETFortune/etfInfo/$symbol"
}
