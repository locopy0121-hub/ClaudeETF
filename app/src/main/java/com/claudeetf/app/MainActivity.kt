package com.claudeetf.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.claudeetf.app.data.AppState
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
import com.claudeetf.app.ui.screens.WatchlistScreen
import com.claudeetf.app.ui.theme.AppTheme
import com.claudeetf.app.ui.theme.C

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppState.init(applicationContext)
        setContent {
            AppTheme { AppRoot() }
        }
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
    route.startsWith(Routes.TRADE) || route == Routes.DIVIDEND || route == Routes.ANALYSIS -> Routes.PORTFOLIO
    route == Routes.CALENDAR -> Routes.MARKET
    route == Routes.ALERTS -> Routes.HOME
    else -> route
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = tabOf(entry?.destination?.route)

    Scaffold(
        containerColor = C.Bg,
        bottomBar = {
            NavigationBar(containerColor = C.Bg) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
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
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize().padding(padding)) {
            composable(Routes.HOME) { HomeScreen(nav) }
            composable(Routes.WATCH) { WatchlistScreen(nav) }
            composable(
                Routes.STOCK + "/{symbol}",
                arguments = listOf(navArgument("symbol") { type = NavType.StringType }),
            ) { StockDetailScreen(nav, it.arguments?.getString("symbol") ?: "") }
            composable(Routes.PORTFOLIO) { PortfolioScreen(nav) }
            composable(
                Routes.TRADE + "?symbol={symbol}",
                arguments = listOf(navArgument("symbol") { type = NavType.StringType; defaultValue = "" }),
            ) { AddTradeScreen(nav, it.arguments?.getString("symbol") ?: "") }
            composable(Routes.DIVIDEND) { DividendScreen(nav) }
            composable(Routes.ANALYSIS) { AnalysisScreen(nav) }
            composable(Routes.MARKET) { MarketScreen(nav) }
            composable(Routes.CALENDAR) { CalendarScreen(nav) }
            composable(Routes.ALERTS) { AlertsScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
        }
    }
}
