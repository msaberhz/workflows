package com.netfusion.app

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.netfusion.app.domain.model.AppLanguage
import com.netfusion.app.domain.model.TunnelState
import com.netfusion.app.ui.NetFusionViewModel
import com.netfusion.app.ui.screens.ConnectionsScreen
import com.netfusion.app.ui.screens.DashboardScreen
import com.netfusion.app.ui.screens.SettingsScreen
import com.netfusion.app.ui.screens.SpeedTestScreen
import com.netfusion.app.ui.screens.StatsScreen
import com.netfusion.app.ui.theme.NetFusionTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: NetFusionViewModel = hiltViewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            val layoutDir = if (settings.language == AppLanguage.PERSIAN_AF) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }

            CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                NetFusionTheme(themePreference = settings.theme) {
                    NetFusionRoot(viewModel)
                }
            }
        }
    }
}

private enum class NavRoute(val route: String) {
    DASHBOARD("dashboard"),
    CONNECTIONS("connections"),
    SPEED_TEST("speedtest"),
    STATS("stats"),
    SETTINGS("settings")
}

@Composable
private fun NetFusionRoot(viewModel: NetFusionViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: NavRoute.DASHBOARD.route

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
    val wifiState by viewModel.wifiState.collectAsStateWithLifecycle()
    val cellularState by viewModel.cellularState.collectAsStateWithLifecycle()
    val activeSmartTransport by viewModel.activeSmartTransport.collectAsStateWithLifecycle()
    val bondingMetrics by viewModel.bondingMetrics.collectAsStateWithLifecycle()
    val trafficStats by viewModel.liveTrafficStats.collectAsStateWithLifecycle()
    val speedTestProgress by viewModel.speedTestProgress.collectAsStateWithLifecycle()
    val speedTestHistory by viewModel.speedTestHistory.collectAsStateWithLifecycle()
    val todayUsage by viewModel.todayUsage.collectAsStateWithLifecycle()
    val monthlyUsage by viewModel.monthlyUsage.collectAsStateWithLifecycle()

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpnService()
        }
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Notification permission handled gracefully */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val onToggleConnection: () -> Unit = {
        if (tunnelState == TunnelState.DISCONNECTED || tunnelState == TunnelState.ERROR) {
            val prep = viewModel.prepareVpnIntent()
            if (prep != null) {
                vpnPermissionLauncher.launch(prep)
            } else {
                viewModel.startVpnService()
            }
        } else {
            viewModel.stopVpnService()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentRoute == NavRoute.DASHBOARD.route,
                    onClick = { navController.navigate(NavRoute.DASHBOARD.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_dashboard)) }
                )
                NavigationBarItem(
                    selected = currentRoute == NavRoute.CONNECTIONS.route,
                    onClick = { navController.navigate(NavRoute.CONNECTIONS.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_connections)) }
                )
                NavigationBarItem(
                    selected = currentRoute == NavRoute.SPEED_TEST.route,
                    onClick = { navController.navigate(NavRoute.SPEED_TEST.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Rounded.Speed, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_speed_test)) }
                )
                NavigationBarItem(
                    selected = currentRoute == NavRoute.STATS.route,
                    onClick = { navController.navigate(NavRoute.STATS.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Rounded.BarChart, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_stats)) }
                )
                NavigationBarItem(
                    selected = currentRoute == NavRoute.SETTINGS.route,
                    onClick = { navController.navigate(NavRoute.SETTINGS.route) { launchSingleTop = true } },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_settings)) }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoute.DASHBOARD.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(NavRoute.DASHBOARD.route) {
                DashboardScreen(
                    tunnelState = tunnelState,
                    preferredMode = settings.preferredMode,
                    wifiState = wifiState,
                    cellularState = cellularState,
                    bondingMetrics = bondingMetrics,
                    trafficStats = trafficStats,
                    onToggleConnection = onToggleConnection
                )
            }
            composable(NavRoute.CONNECTIONS.route) {
                ConnectionsScreen(
                    selectedMode = settings.preferredMode,
                    wifiState = wifiState,
                    cellularState = cellularState,
                    activeSmartTransport = activeSmartTransport,
                    onSelectMode = viewModel::setMode
                )
            }
            composable(NavRoute.SPEED_TEST.route) {
                SpeedTestScreen(
                    progress = speedTestProgress,
                    history = speedTestHistory,
                    onStartTest = viewModel::runSpeedTest
                )
            }
            composable(NavRoute.STATS.route) {
                StatsScreen(
                    trafficStats = trafficStats,
                    bondingMetrics = bondingMetrics,
                    todayUsage = todayUsage,
                    monthlyUsage = monthlyUsage
                )
            }
            composable(NavRoute.SETTINGS.route) {
                SettingsScreen(
                    settings = settings,
                    onLanguageChange = viewModel::setLanguage,
                    onThemeChange = viewModel::setTheme,
                    onAutoConnectBootChange = viewModel::setAutoConnectOnBoot,
                    onSaveServerConfig = viewModel::saveServerConfig,
                    onSaveDns = viewModel::setCustomDns
                )
            }
        }
    }
}
