package com.claudeetf.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.claudeetf.app.alerts.AlertEngine
import com.claudeetf.app.data.AppState
import com.claudeetf.app.data.MarketData
import com.claudeetf.app.util.UsSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.claudeetf.app.ui.Routes
import com.claudeetf.app.ui.screens.AddTradeScreen
import com.claudeetf.app.ui.screens.AlertsScreen
import com.claudeetf.app.ui.screens.AnalysisScreen
import com.claudeetf.app.ui.screens.CalendarScreen
import com.claudeetf.app.ui.screens.DividendScreen
import com.claudeetf.app.ui.screens.HomeScreen
import com.claudeetf.app.ui.screens.MarketScreen
import com.claudeetf.app.ui.screens.PortfolioScreen
import com.claudeetf.app.ui.screens.SettingsScreen
import com.claudeetf.app.ui.screens.StockDetailScreen
import com.claudeetf.app.ui.screens.TradesScreen
import com.claudeetf.app.ui.screens.WatchlistScreen
import com.claudeetf.app.ui.theme.AppTheme
import com.claudeetf.app.ui.theme.C

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.claudeetf.app.util.CrashLog.install(applicationContext)
        AppState.init(applicationContext)
        watchNetwork()
        AppState.systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        runCatching { AlertEngine.schedule(applicationContext) }
        setContent {
            AppTheme { AppRoot() }
        }
        // App 在前景時自動更新行情：交易時段每分鐘、其餘每 15 分鐘
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    runCatching { MarketData.refreshAll() }
                    runCatching { AlertEngine.check(applicationContext) }
                    runCatching { AppState.recordNav() }
                    runCatching { com.claudeetf.app.widget.WidgetUpdater.update(applicationContext) }
                    val usOpen = UsSession.info().phase.let {
                        it == UsSession.Phase.PRE || it == UsSession.Phase.REGULAR || it == UsSession.Phase.POST
                    }
                    delay(if (usOpen || MarketData.isTwIntraday()) 60_000L else 15 * 60_000L)
                }
            }
        }
    }
}

/** 監聽網路狀態，離線時顯示提示 */
private fun ComponentActivity.watchNetwork() {
    val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    AppState.online = cm.activeNetwork?.let { n ->
        cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } == true
    runCatching {
        cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { runOnUiThread { AppState.online = true } }
            override fun onLost(network: android.net.Network) { runOnUiThread { AppState.online = false } }
        })
    }
}

object BuildConfigInfo {
    @Suppress("DEPRECATION")
    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    }.getOrDefault("")
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "總覽", Icons.Filled.Home),
    Tab(Routes.WATCH, "行情", Icons.AutoMirrored.Filled.List),
    Tab(Routes.PORTFOLIO, "持股", Icons.Filled.AccountBox),
    Tab(Routes.MARKET, "市場", Icons.Filled.Place),
    Tab(Routes.SETTINGS, "我的", Icons.Filled.Person),
)

/** 次層頁面所屬的分頁，用來標示底部導覽 */
private fun tabOf(route: String?): String? = when {
    route == null -> null
    route.startsWith(Routes.STOCK) -> Routes.WATCH
    route.startsWith(Routes.COMPARE) -> Routes.WATCH
    route.startsWith(Routes.TRADE) || route == Routes.TRADES || route == Routes.DCA || route == Routes.REBALANCE || route == Routes.DIVIDEND || route == Routes.ANALYSIS -> Routes.PORTFOLIO
    route == Routes.CALENDAR -> Routes.MARKET
    route.startsWith(Routes.ALERTS) || route == Routes.PNL -> Routes.HOME
    route == Routes.REPORT || route == Routes.COST || route == Routes.DRIP -> Routes.PORTFOLIO
    route == Routes.FX -> Routes.MARKET
    else -> route
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = tabOf(entry?.destination?.route)
    val route = entry?.destination?.route
    val hideBar = route == Routes.WELCOME
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    // 平板或橫向（寬度 ≥ 600dp）改用側邊導覽列，內容最寬 760dp 置中
    val wide = config.screenWidthDp >= 600
    fun go(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        containerColor = C.Bg,
        topBar = {
            if (!AppState.online) {
                Text(
                    "離線中：顯示上次更新的資料",
                    color = C.OnPrimary, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(C.Tw).padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        },
        bottomBar = {
            if (!hideBar && !wide) NavigationBar(containerColor = C.Bg) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { go(t.route) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = C.Primary,
                            selectedTextColor = C.Primary,
                            indicatorColor = C.Selected,
                            unselectedIconColor = C.Muted,
                            unselectedTextColor = C.Muted,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (!AppState.onboarded) nav.navigate(Routes.WELCOME)
        }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().padding(padding)) {
        if (wide && !hideBar) {
            androidx.compose.material3.NavigationRail(containerColor = C.Bg) {
                tabs.forEach { t ->
                    androidx.compose.material3.NavigationRailItem(
                        selected = current == t.route,
                        onClick = { go(t.route) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label, fontSize = 11.sp) },
                        colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                            selectedIconColor = C.Primary,
                            selectedTextColor = C.Primary,
                            indicatorColor = C.Selected,
                            unselectedIconColor = C.Muted,
                            unselectedTextColor = C.Muted,
                        ),
                    )
                }
            }
        }
        androidx.compose.foundation.layout.Box(
            Modifier.weight(1f).fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
        NavHost(
            nav, startDestination = Routes.HOME,
            modifier = (if (wide) Modifier.widthIn(max = 760.dp) else Modifier).fillMaxSize(),
        ) {
            composable(Routes.WELCOME) { com.claudeetf.app.ui.screens.WelcomeScreen(nav) }
            composable(Routes.SEARCH) { com.claudeetf.app.ui.screens.SearchScreen(nav) }
            composable(Routes.ERRORS) { com.claudeetf.app.ui.screens.ErrorLogScreen(nav) }
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.WATCH) { WatchlistScreen(nav) }
            composable(
                Routes.STOCK + "/{symbol}",
                arguments = listOf(navArgument("symbol") { type = NavType.StringType }),
            ) { StockDetailScreen(nav, it.arguments?.getString("symbol") ?: "") }
            composable(Routes.PORTFOLIO) { PortfolioScreen(nav) }
            composable(
                Routes.TRADE + "?symbol={symbol}&id={id}",
                arguments = listOf(
                    navArgument("symbol") { type = NavType.StringType; defaultValue = "" },
                    navArgument("id") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) { AddTradeScreen(nav, it.arguments?.getString("symbol") ?: "", it.arguments?.getLong("id") ?: -1L) }
            composable(Routes.TRADES) { TradesScreen(nav) }
            composable(Routes.PNL) { com.claudeetf.app.ui.screens.PnlCalendarScreen(nav) }
            composable(Routes.DCA) { com.claudeetf.app.ui.screens.DcaScreen(nav) }
            composable(Routes.REBALANCE) { com.claudeetf.app.ui.screens.RebalanceScreen(nav) }
            composable(
                Routes.COMPARE + "?s={s}",
                arguments = listOf(navArgument("s") { type = NavType.StringType; defaultValue = "" }),
            ) { com.claudeetf.app.ui.screens.CompareScreen(nav, it.arguments?.getString("s") ?: "") }
            composable(Routes.DIVIDEND) { DividendScreen(nav) }
            composable(Routes.ANALYSIS) { AnalysisScreen(nav) }
            composable(Routes.MARKET) { MarketScreen(nav) }
            composable(Routes.CALENDAR) { CalendarScreen(nav) }
            composable(
                Routes.ALERTS + "?s={s}",
                arguments = listOf(navArgument("s") { type = NavType.StringType; defaultValue = "" }),
            ) { AlertsScreen(nav, it.arguments?.getString("s") ?: "") }
            composable(Routes.REPORT) { com.claudeetf.app.ui.screens.ReportScreen(nav) }
            composable(Routes.COST) { com.claudeetf.app.ui.screens.CostScreen(nav) }
            composable(Routes.DRIP) { com.claudeetf.app.ui.screens.DripScreen(nav) }
            composable(Routes.FX) { com.claudeetf.app.ui.screens.FxScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
        }
        }
        }
    }
}
