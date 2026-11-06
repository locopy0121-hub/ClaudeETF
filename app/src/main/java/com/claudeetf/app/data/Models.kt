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
    TWSE_LIVE("證交所即時"),
    FUGLE_STREAM("富果串流"),
    FINNHUB("Finnhub"),
    YAHOO("Yahoo"),
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
    val id: Long = 0L,
    /** TradeOrigin：一般、期初部位、舊版紀錄 */
    val origin: String = "",
    val note: String = "",
    /** 帳戶編號，0 = 未指定 */
    val account: Int = 0,
    /** 附件檔名（App 內部儲存），標籤 */
    val attachments: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
)

/** 目標價與停損價（對應自動建立的提醒編號） */
data class PriceMark(val target: Double?, val stop: Double?, val targetAlert: Int = 0, val stopAlert: Int = 0)

/** FIFO 分批：剩餘股數、每股成本（含手續費）、匯率 */
data class Lot(val date: String, val shares: Double, val unitCost: Double, val fx: Double, val opening: Boolean)

/** 自選群組 */
data class WatchGroup(val id: Int, val name: String, val symbols: List<String>)

/** 定期定額計畫：每月 day 日以 amount（原幣）買進 */
data class DcaPlan(
    val id: Int,
    val symbol: String,
    val amount: Double,
    val day: Int,
    val account: Int = 0,
    val enabled: Boolean = true,
    /** 已記錄的月份 yyyy-MM */
    val lastDone: String = "",
    /** 已通知的月份 yyyy-MM */
    val lastNotified: String = "",
)

/** 券商帳戶與手續費設定 */
data class Account(
    val id: Int,
    val name: String,
    val market: Market,
    /** 台股手續費折扣（1 = 不打折，0.28 = 2.8 折） */
    val discount: Double = 1.0,
    val minFee: Double = 20.0,
    /** 美股手續費率與最低收費（美元） */
    val usRate: Double = 0.001,
    val usMinFee: Double = 0.0,
)

data class IndexQuote(
    val name: String,
    val value: Double,
    val changePct: Double,
    val symbol: String = "",
    val updatedAt: Long = 0L,
    /** 昨收；0 表示未知，改由漲跌幅反推 */
    val prev: Double = 0.0,
) {
    /** 漲跌點數 */
    val change: Double
        get() = when {
            prev > 0 -> value - prev
            changePct > -100.0 -> value - value / (1 + changePct / 100)
            else -> 0.0
        }
}

data class SectorMove(val name: String, val changePct: Double)

enum class EventKind(val label: String) {
    ECONOMIC("經濟數據"),
    EARNINGS("財報"),
    DIVIDEND("除息"),
    HOLIDAY("休市"),
}

data class CalendarEvent(
    val dateLabel: String,
    val date: java.time.LocalDate? = null,
    val time: String,
    val title: String,
    val detail: String,
    val kind: EventKind,
    val importance: Int = 0,
    val isHolding: Boolean = false,
)

enum class AlertType(val label: String, val isPrice: Boolean, val unit: String) {
    PRICE_ABOVE("價格高於", true, "價格"),
    PRICE_BELOW("價格低於", true, "價格"),
    CHANGE_UP("單日漲幅達", true, "%"),
    CHANGE_DOWN("單日跌幅達", true, "%"),
    EARNINGS("財報前", false, "天"),
    EXDIV("除息前", false, "天"),
    US_OPEN("美股開盤", false, ""),
    RAPID("5 分鐘急漲急跌", true, "%"),
    BIG_TRADE("逐筆大單", true, "張"),
    KEY_LEVEL("三關突破跌破", true, ""),
    ENTRY_SIGNAL("進場訊號變化", true, ""),
}

data class AlertRule(
    val id: Int,
    /** US_OPEN 不需要代號 */
    val symbol: String,
    val type: AlertType,
    val value: Double,
    val enabled: Boolean,
    /** 已觸發的標記（日期或事件日），避免重複通知 */
    val lastFired: String = "",
) {
    val market: Market get() = if (symbol.firstOrNull()?.isDigit() == true) Market.TW else Market.US
    val isPrice: Boolean get() = type.isPrice

    val title: String
        get() = when (type) {
            AlertType.US_OPEN -> "美股開盤推播"
            AlertType.RAPID -> (if (symbol.isBlank()) "全部持股" else symbol) + " 5 分鐘內漲跌 ≥ ${trimNum(value)}%"
            AlertType.BIG_TRADE -> (if (symbol.isBlank()) "全部台股持股" else symbol) + " 單筆成交 ≥ ${trimNum(value)} 張"
            AlertType.KEY_LEVEL -> (if (symbol.isBlank()) "全部持股" else symbol) + " 突破／跌破三關價"
            AlertType.ENTRY_SIGNAL -> (if (symbol.isBlank()) "全部持股與自選" else symbol) + " K 線進場建議改變"
            AlertType.PRICE_ABOVE, AlertType.PRICE_BELOW -> "$symbol ${type.label} ${trimNum(value)}"
            AlertType.CHANGE_UP, AlertType.CHANGE_DOWN -> "$symbol ${type.label} ${trimNum(value)}%"
            AlertType.EARNINGS, AlertType.EXDIV -> "$symbol ${type.label} ${trimNum(value)} 天"
        }

    val subtitle: String
        get() = when (type) {
            AlertType.US_OPEN -> "開盤後推播一次，自動依夏令／冬令調整"
            AlertType.RAPID -> "盤中即時偵測（App 開著時），同一檔 30 分鐘內不重複"
            AlertType.BIG_TRADE -> "依逐筆成交即時偵測（App 開著時），同一檔 10 分鐘內不重複"
            AlertType.KEY_LEVEL -> "現價穿越上關、中關、下關時推播（App 開著時），同一檔 15 分鐘內不重複"
            AlertType.ENTRY_SIGNAL -> "進場建議改變時推播（例：觀望 → 可分批進場），盤中與背景更新時檢查，同一檔 30 分鐘內不重複"
            AlertType.EARNINGS -> "依 Yahoo 公布的財報日"
            AlertType.EXDIV -> "依 Yahoo 公布的除息日"
            else -> "盤中與背景更新時檢查，每天最多通知一次"
        }

    companion object {
        fun trimNum(v: Double): String =
            // BigDecimal.valueOf 用最短十進位表示（6.85 不會變成 6.8499999…），最多 4 位小數
            java.math.BigDecimal.valueOf(v).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}

data class AlertHit(val time: Long, val text: String)

/** 逐筆成交：時間（毫秒）、價格、張數、內外盤（1 外盤、-1 內盤、0 不明） */
data class TapeTick(val time: Long, val price: Double, val lots: Double, val side: Int)

data class UpcomingDividend(
    val dateTop: String,
    val dateMain: String,
    val title: String,
    val market: Market,
    val detail: String,
    val amount: String,
)
