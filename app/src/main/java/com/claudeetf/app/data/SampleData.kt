package com.claudeetf.app.data

/** 第一階段的示意資料（之後由即時行情取代） */
object SampleData {
    private val up = listOf(18f, 16f, 17f, 12f, 13f, 8f, 6f).map { 24f - it }
    private val down = listOf(6f, 8f, 7f, 12f, 11f, 16f, 18f).map { 24f - it }

    val quotes = listOf(
        Quote("2330", "台積電", Market.TW, 1465.00, 1445.00, spark = up,
            stats = StockStats("TWSE", "38.0 兆", "24.1", "60.70", "1.23%", "1,505.00", "780.00", "1.12", "1 月中旬（預估）")),
        Quote("0050", "元大台灣50", Market.TW, 63.85, 63.35, isEtf = true, spark = up),
        Quote("00878", "國泰永續高股息", Market.TW, 21.42, 21.47, isEtf = true, spark = down),
        Quote("AAPL", "Apple", Market.US, 268.40, 265.69, spark = up,
            stats = StockStats("NASDAQ", "3.99 兆", "35.9", "7.48", "0.39%", "277.32", "169.21", "1.09", "1 月下旬（預估）")),
        Quote("NVDA", "NVIDIA", Market.US, 198.75, 194.59, spark = up,
            stats = StockStats("NASDAQ", "4.84 兆", "56.5", "3.52", "0.02%", "212.19", "86.62", "1.65", "11/19 盤後（預估）")),
        Quote("VOO", "Vanguard S&P 500 ETF", Market.US, 625.30, 622.87, isEtf = true, spark = up),
        Quote("TSLA", "Tesla", Market.US, 438.10, 445.04, spark = down),
        Quote("MSFT", "Microsoft", Market.US, 512.65, 513.57, spark = down),
    )

    val holdings = listOf(
        Holding("2330", 800.0, 1120.00),
        Holding("0050", 8000.0, 58.20),
        Holding("00878", 10000.0, 22.10),
        Holding("AAPL", 40.0, 192.30, 31.20),
        Holding("NVDA", 60.0, 132.50, 31.20),
        Holding("VOO", 25.5, 548.00, 31.20),
    )

    val twIndices = listOf(
        IndexQuote("加權指數", 27436.18, 0.82, "^TWII"),
        IndexQuote("櫃買指數", 268.45, 0.35, "^TWOII"),
    )

    val usIndices = listOf(
        IndexQuote("道瓊", 47385.20, 0.28, "^DJI"),
        IndexQuote("S&P 500", 6812.40, 0.41, "^GSPC"),
        IndexQuote("Nasdaq", 23105.66, 0.67, "^IXIC"),
        IndexQuote("費城半導體", 7248.90, -0.35, "^SOX"),
        IndexQuote("羅素 2000", 2468.31, 0.52, "^RUT"),
        IndexQuote("VIX 恐慌", 17.42, -3.10, "^VIX"),
    )

    /** 美 10 年債殖利率、美元指數 */
    val macro = listOf(
        IndexQuote("美 10 年債", 4.12, 0.0, "^TNX"),
        IndexQuote("美元指數", 99.40, 0.0, "DX-Y.NYB"),
    )

    val sectors = listOf(
        SectorMove("資訊科技", 1.2), SectorMove("通訊服務", 0.8), SectorMove("非必需消費", -0.4),
        SectorMove("金融", 0.3), SectorMove("醫療保健", -0.6), SectorMove("工業", 0.2),
        SectorMove("必需消費", -0.2), SectorMove("能源", -1.1), SectorMove("公用事業", 0.1),
        SectorMove("原物料", 0.5), SectorMove("房地產", -0.3),
    )

    val movers = listOf(
        Triple("SMCI", "Super Micro", 8.4),
        Triple("AMD", "Advanced Micro", 5.2),
        Triple("PLTR", "Palantir", 4.7),
    )

    val alerts = listOf(
        AlertRule(1, "AAPL", AlertType.PRICE_ABOVE, 280.0, true),
        AlertRule(2, "2330", AlertType.CHANGE_DOWN, 3.0, true),
        AlertRule(3, "NVDA", AlertType.EARNINGS, 1.0, true),
        AlertRule(4, "00878", AlertType.EXDIV, 3.0, true),
        AlertRule(5, "", AlertType.US_OPEN, 0.0, false),
    )

    val upcomingDividends = listOf(
        UpcomingDividend("11 月", "18", "00878 除息", Market.TW, "每股 0.40 · 10,000 股", "NT$4,000"),
        UpcomingDividend("12 月", "中", "2330 除息", Market.TW, "每股 5.00 · 800 股", "NT$4,000"),
        UpcomingDividend("12 月", "下", "VOO 季配息", Market.US, "25.5 股 · 實收已扣 30%", "待公告"),
    )

    /** 每月股息（千元），最後一個月為預估 */
    val monthlyDividendK = listOf(4.0f, 1.2f, 3.6f, 4.4f, 1.0f, 9.8f, 15.2f, 3.9f, 12.6f, 22.1f, 8.6f, 6.5f)

    /** 一年報酬走勢（%）：我的組合、0050、SPY */
    val perfMine = listOf(0f, 3f, 2f, 7f, 6f, 10f, 9.5f, 14f, 12.5f, 16f, 17f, 18.4f)
    val perf0050 = listOf(0f, 2.5f, 1.5f, 6f, 5f, 8.8f, 8.3f, 12.4f, 11f, 15f, 15.8f, 16.9f)
    val perfSpy = listOf(0f, 2f, 1f, 5f, 4f, 7.6f, 7f, 11.2f, 10f, 13.6f, 14.4f, 15.2f)
}
