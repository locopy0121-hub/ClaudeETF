package com.claudeetf.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import com.claudeetf.app.data.AppState
import com.claudeetf.app.ui.theme.C
import com.claudeetf.app.util.UsSession
import kotlinx.coroutines.delay

/** 隱藏金額模式 */
fun mask(s: String): String = if (AppState.hideAmounts) "＊＊＊＊" else s

/** 每 30 秒更新一次的美股時段資訊 */
@Composable
fun rememberUsSession(): UsSession.Info {
    val info by produceState(initialValue = UsSession.info()) {
        while (true) {
            delay(30_000)
            value = UsSession.info()
        }
    }
    return info
}

fun phaseColor(p: UsSession.Phase): Color = when (p) {
    UsSession.Phase.REGULAR -> C.Primary
    UsSession.Phase.PRE, UsSession.Phase.POST -> C.Tw
    else -> C.Gray
}

object Routes {
    const val HOME = "home"
    const val WATCH = "watch"
    const val STOCK = "stock"
    const val PORTFOLIO = "portfolio"
    const val TRADE = "trade"
    const val DIVIDEND = "dividend"
    const val MARKET = "market"
    const val CALENDAR = "calendar"
    const val ANALYSIS = "analysis"
    const val ALERTS = "alerts"
    const val SETTINGS = "settings"
    const val SETTINGS_SECTION = "settings/{section}"
    fun settings(section: String) = "settings/$section"
    const val CHAT = "chat"
    const val TRADES = "trades"
    const val DCA = "dca"
    const val PNL = "pnl"
    const val WELCOME = "welcome"
    const val REPORT = "report"
    const val COST = "cost"
    const val DRIP = "drip"
    const val REVIEW = "review"
    const val HEALTH = "health"
    const val INDEX = "index"

    fun index(symbol: String) = "$INDEX/" + java.net.URLEncoder.encode(symbol, "UTF-8")
    const val FX = "fx"
    const val MARKET_CHANGES = "marketChanges"
    const val DAILY_REPORT = "dailyReport"

    fun alerts(symbol: String = "") = "$ALERTS?s=$symbol"
    const val SEARCH = "search"
    const val ERRORS = "errors"
    const val MANUAL = "manual"
    const val DATA_HEALTH = "data_health"
    const val MARKET_DD = "market_dd"
    const val REBALANCE = "rebalance"
    const val COMPARE = "compare"

    fun compare(symbols: String = "") = "$COMPARE?s=$symbols"

    fun stock(symbol: String) = "$STOCK/$symbol"
    fun trade(symbol: String = "", id: Long = -1L) = "$TRADE?symbol=$symbol&id=$id"
}
