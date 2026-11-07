package com.claudeetf.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
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
    // Android 8–8.1：用系統的裝置密碼確認畫面
    private val confirmCredential = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r -> if (r.resultCode == RESULT_OK) AppState.locked = false }

    /** 裝置是否設定了螢幕鎖（指紋需搭配螢幕鎖） */
    fun deviceSecure(): Boolean =
        (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure

    /** 指紋／臉部或裝置密碼解鎖 */
    fun unlock() {
        if (!deviceSecure()) { AppState.locked = false; return }
        val title = "解鎖台美股資產"
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            val b = android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle(title).setSubtitle("使用指紋或裝置密碼")
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
            } else {
                @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            }
            runCatching {
                b.build().authenticate(
                    android.os.CancellationSignal(), mainExecutor,
                    object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                            AppState.locked = false
                        }
                    },
                )
            }
        } else {
            val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
            @Suppress("DEPRECATION")
            val intent = km.createConfirmDeviceCredentialIntent(title, "輸入裝置密碼")
            if (intent != null) confirmCredential.launch(intent) else AppState.locked = false
        }
    }

    override fun onStop() {
        super.onStop()
        AppState.backgroundAt = System.currentTimeMillis()
        AppState.flush()
        com.claudeetf.app.data.remote.FugleStream.stop()
    }

    override fun onStart() {
        super.onStart()
        // 離開超過 1 分鐘重新上鎖
        if (AppState.appLock && AppState.backgroundAt > 0 && System.currentTimeMillis() - AppState.backgroundAt > 60_000) {
            AppState.locked = true
        }
        if (AppState.locked) window.decorView.post { unlock() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.claudeetf.app.util.CrashLog.install(applicationContext)
        AppState.init(applicationContext)
        com.claudeetf.app.chat.ChatPrefs.init(applicationContext)
        runCatching { com.claudeetf.app.data.AppUpdater.detectUpdated(applicationContext) }
        com.claudeetf.app.data.remote.TdccOpenData.cacheDir = filesDir
        com.claudeetf.app.data.StockDirectory.cacheDir = filesDir
        watchNetwork()
        AppState.systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        runCatching { AlertEngine.schedule(applicationContext) }
        // 鎖定時不讓最近使用的 App 畫面顯示金額
        if (AppState.appLock) window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            AppTheme {
                AppRoot()
                if (AppState.locked) LockScreen { unlock() }
            }
        }
        // App 開啟（回到前景）時更新除權息行事曆與持股配息預估，同一天只抓一次
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                delay(3_000L) // 先讓行情載入
                runCatching { com.claudeetf.app.data.DividendCalendar.refresh() }
                runCatching { com.claudeetf.app.alerts.AlertEngine.checkDividendDays(applicationContext) }
                // 配息現金流（下一筆入帳、本月明細）與入帳提醒
                runCatching { com.claudeetf.app.data.DividendFlow.refresh() }
                runCatching { com.claudeetf.app.alerts.AlertEngine.checkPayDays(applicationContext) }
            }
        }
        // 線上更新：每次回到 App（前景）時檢查 GitHub 最新版本，距上次超過 15 分鐘才重新查詢
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                delay(3_000L)
                if (AppState.autoUpdateCheck) runCatching { com.claudeetf.app.data.AppUpdater.check(applicationContext, auto = true) }
            }
        }
        // 美股盤中：依「美股更新頻率」獨立輪詢（1～10 秒），或 Finnhub 逐筆串流（即時）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var backoff = 0L
                var lastPoll = 0L
                try {
                    while (true) {
                        val live = MarketData.usLive()
                        val ms = AppState.usRefreshMs
                        val stream = live && ms == 0L && AppState.finnhubKey.isNotBlank()
                        runCatching {
                            com.claudeetf.app.data.remote.FinnhubStream.ensure(
                                stream, AppState.finnhubKey,
                                AppState.quotes.filter { it.market == com.claudeetf.app.data.Market.US }.map { it.symbol },
                            )
                        }
                        if (live) {
                            val streaming = stream && com.claudeetf.app.data.remote.FinnhubStream.fresh()
                            // 串流有成交時，Yahoo 每 15 秒補一次（昨收、高低、盤前盤後）；否則依設定頻率
                            val every = if (streaming) 15_000L else maxOf(ms, 1_000L)
                            if (System.currentTimeMillis() - lastPoll >= every + backoff) {
                                lastPoll = System.currentTimeMillis()
                                val ok = runCatching { MarketData.refreshUs(live = true, light = every < 5_000L) }.getOrDefault(false)
                                // 失敗（多半是被限流）時逐步拉長間隔，最多 10 秒
                                backoff = if (ok) 0L else (backoff * 2 + 1_000L).coerceAtMost(10_000L)
                            }
                        }
                        delay(if (live) 250L else 30_000L)
                    }
                } finally {
                    com.claudeetf.app.data.remote.FinnhubStream.stop()
                }
            }
        }
        // App 在前景時自動更新行情：
        // 交易時段每 5 秒即時更新報價、每 60 秒完整更新（指數、匯率、提醒、淨值、小工具）；收盤後每 15 分鐘
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastFull = 0L
                while (true) {
                    val live = MarketData.anyMarketLive()
                    // 台股盤中有富果金鑰：保持 WebSocket 串流，與交易所同步
                    runCatching {
                        com.claudeetf.app.data.remote.FugleStream.ensure(
                            MarketData.isTwIntraday() && AppState.twUseFugle, AppState.fugleKey,
                            AppState.quotes.filter { it.market == com.claudeetf.app.data.Market.TW }.map { it.symbol },
                        )
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastFull >= (if (live) 60_000L else 15 * 60_000L)) {
                        runCatching { MarketData.refreshAll() }
                        runCatching {
                            val t = AlertEngine.keyLevelTargets()
                            if (t.isNotEmpty()) com.claudeetf.app.ui.screens.KeyLevelsUi.load(t.mapNotNull { AppState.quote(it) })
                        }
                        runCatching { AlertEngine.check(applicationContext) }
                        runCatching { AppState.recordNav() }
                        runCatching { com.claudeetf.app.widget.WidgetUpdater.update(applicationContext) }
                        lastFull = System.currentTimeMillis()
                    } else if (live) {
                        runCatching { MarketData.refreshLive() }
                    }
                    if (live) runCatching { AlertEngine.checkRapid(applicationContext) }
                    if (live) runCatching { com.claudeetf.app.widget.WidgetUpdater.updateThrottled(applicationContext) }
                    delay(if (live) 5_000L else 60_000L)
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

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().background(C.Bg)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
        ) {
            Icon(androidx.compose.material.icons.Icons.Filled.Lock, contentDescription = null, tint = C.Primary,
                modifier = Modifier.size(56.dp))
            Text("台美股資產已鎖定", color = C.Text, fontSize = 18.sp)
            androidx.compose.foundation.layout.Box(
                Modifier.padding(horizontal = 48.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(C.Primary).clickable(onClick = onUnlock).padding(14.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) { Text("解鎖", color = C.OnPrimary, fontSize = 16.sp) }
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
    route.startsWith(Routes.COMPARE) -> Routes.WATCH
    route.startsWith(Routes.TRADE) || route == Routes.TRADES || route == Routes.DCA || route == Routes.REBALANCE || route == Routes.DIVIDEND || route == Routes.ANALYSIS -> Routes.PORTFOLIO
    route == Routes.CALENDAR -> Routes.MARKET
    route.startsWith(Routes.ALERTS) || route == Routes.PNL -> Routes.HOME
    route == Routes.REPORT || route == Routes.COST || route == Routes.DRIP || route == Routes.REVIEW -> Routes.PORTFOLIO
    route == Routes.FX || route.startsWith(Routes.INDEX) -> Routes.MARKET
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
            composable(Routes.REVIEW) { com.claudeetf.app.ui.screens.ReviewScreen(nav) }
            composable(Routes.HEALTH) { com.claudeetf.app.ui.screens.HealthScreen(nav) }
            composable(
                Routes.INDEX + "/{sym}",
                arguments = listOf(navArgument("sym") { type = NavType.StringType }),
            ) { com.claudeetf.app.ui.screens.IndexScreen(nav, java.net.URLDecoder.decode(it.arguments?.getString("sym") ?: "", "UTF-8")) }
            composable(Routes.FX) { com.claudeetf.app.ui.screens.FxScreen(nav) }
            composable(Routes.SETTINGS) { SettingsScreen(nav) }
            composable(Routes.CHAT) { com.claudeetf.app.ui.screens.ChatScreen(nav) }
        }
        // 群聊新訊息彈幕（不在聊天畫面時從右往左飄過，點一下開啟群聊）
        if (route != Routes.CHAT) com.claudeetf.app.ui.screens.DanmakuOverlay { nav.navigate(Routes.CHAT) }
        com.claudeetf.app.ui.screens.UpdatePrompt()
        }
        }
    }
}
