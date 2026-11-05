package com.claudeetf.app.data

enum class Market(val label: String, val currency: String) {
    TW("TW", "TWD"),
    US("US", "USD"),
}

/** 報價來源 */
enum class QuoteSource(val label: String) {
    SAMPLE("示意"),
    FUGLE("富果即時"),
    TWSE("證交所收盤"),
    TPEX("櫃買收盤"),
    FINNHUB("Finnhub"),
}

/** 漲跌配色：台式紅漲綠跌、美式綠漲紅跌 */
enum class ColorMode { TW, US }

data class Quote(
    val symbol: String,
    val name: String,
    val market: Market,
    val price: Double,
    val prevClose: Double,
    val isEtf: Boolean = false,
    val spark: List<Float> = emptyList(),
    val stats: StockStats? = null,
    val source: QuoteSource = QuoteSource.SAMPLE,
    /** 最後更新時間（毫秒），0 表示從未更新 */
    val updatedAt: Long = 0L,
) {
    val change: Double get() = price - prevClose
    val changePct: Double get() = if (prevClose == 0.0) 0.0 else change / prevClose * 100
}

data class StockStats(
    val exchange: String,
    val marketCap: String,
    val pe: String,
    val eps: String,
    val yieldPct: String,
    val high52: String,
    val low52: String,
    val beta: String,
    val nextEarnings: String,
)

/** avgFx：美股持股的平均買入匯率（USD/TWD），台股為 1 */
data class Holding(
    val symbol: String,
    val shares: Double,
    val avgCost: Double,
    val avgFx: Double = 1.0,
)

enum class TradeType(val label: String) {
    BUY("買進"),
    SELL("賣出"),
    DIVIDEND("股息"),
    SPLIT("拆股"),
}

data class Trade(
    val type: TradeType,
    val symbol: String,
    val date: String,
    val shares: Double,
    val price: Double,
    val fx: Double,
    val fee: Double,
    val tax: Double,
)

data class IndexQuote(val name: String, val value: Double, val changePct: Double)

data class SectorMove(val name: String, val changePct: Double)

enum class EventKind(val label: String) {
    ECONOMIC("經濟數據"),
    EARNINGS("財報"),
    DIVIDEND("除息"),
    HOLIDAY("休市"),
}

data class CalendarEvent(
    val dateLabel: String,
    val time: String,
    val title: String,
    val detail: String,
    val kind: EventKind,
    val importance: Int = 0,
    val isHolding: Boolean = false,
)

data class AlertRule(
    val id: Int,
    val market: Market,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    val isPrice: Boolean,
)

data class UpcomingDividend(
    val dateTop: String,
    val dateMain: String,
    val title: String,
    val market: Market,
    val detail: String,
    val amount: String,
)
