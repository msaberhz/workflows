package com.netfusion.app.ui

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netfusion.app.data.local.DailyUsageEntity
import com.netfusion.app.data.local.SpeedTestDao
import com.netfusion.app.data.local.SpeedTestResultEntity
import com.netfusion.app.data.local.UsageStatsDao
import com.netfusion.app.data.preferences.UserPreferencesRepository
import com.netfusion.app.data.preferences.UserSettings
import com.netfusion.app.domain.model.AppLanguage
import com.netfusion.app.domain.model.BondingMetrics
import com.netfusion.app.domain.model.ConnectionMode
import com.netfusion.app.domain.model.LiveTrafficStats
import com.netfusion.app.domain.model.NetworkPathState
import com.netfusion.app.domain.model.SpeedTestProgress
import com.netfusion.app.domain.model.ThemePreference
import com.netfusion.app.domain.model.TransportType
import com.netfusion.app.domain.model.TunnelState
import com.netfusion.app.network.MultiNetworkMonitor
import com.netfusion.app.network.SpeedTestEngine
import com.netfusion.app.vpn.NetFusionVpnService
import com.netfusion.app.vpn.TunnelStateHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class NetFusionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkMonitor: MultiNetworkMonitor,
    private val prefsRepository: UserPreferencesRepository,
    private val stateHolder: TunnelStateHolder,
    private val speedTestEngine: SpeedTestEngine,
    speedTestDao: SpeedTestDao,
    usageStatsDao: UsageStatsDao
) : ViewModel() {

    val wifiState: StateFlow<NetworkPathState> = networkMonitor.wifiState
    val cellularState: StateFlow<NetworkPathState> = networkMonitor.cellularState

    val tunnelState: StateFlow<TunnelState> = stateHolder.tunnelState
    val activeMode: StateFlow<ConnectionMode> = stateHolder.activeMode
    val activeSmartTransport: StateFlow<TransportType?> = stateHolder.activeSmartTransport
    val bondingMetrics: StateFlow<BondingMetrics> = stateHolder.bondingMetrics
    val liveTrafficStats: StateFlow<LiveTrafficStats> = stateHolder.liveTrafficStats

    val settings: StateFlow<UserSettings> = prefsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = UserSettings()
    )

    val speedTestHistory: StateFlow<List<SpeedTestResultEntity>> = speedTestDao.observeRecentResults().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList()
    )

    private val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private val monthStartKey: String = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -30)
    }.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it.time) }

    val todayUsage: StateFlow<DailyUsageEntity?> = usageStatsDao.observeDay(todayKey).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = null
    )

    val monthlyUsage: StateFlow<List<DailyUsageEntity>> = usageStatsDao.observeSince(monthStartKey).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList()
    )

    private val _speedTestProgress = MutableStateFlow(SpeedTestProgress())
    val speedTestProgress: StateFlow<SpeedTestProgress> = _speedTestProgress.asStateFlow()
    private var speedTestJob: Job? = null

    init {
        networkMonitor.startMonitoring()
    }

    fun prepareVpnIntent(): Intent? = VpnService.prepare(context)

    fun startVpnService() {
        val mode = settings.value.preferredMode
        val intent = Intent(context, NetFusionVpnService::class.java)
            .setAction(NetFusionVpnService.ACTION_START)
            .putExtra(NetFusionVpnService.EXTRA_MODE, mode.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopVpnService() {
        val intent = Intent(context, NetFusionVpnService::class.java)
            .setAction(NetFusionVpnService.ACTION_STOP)
        context.startService(intent)
    }

    fun setMode(mode: ConnectionMode) {
        viewModelScope.launch {
            prefsRepository.setPreferredMode(mode)
            if (tunnelState.value != TunnelState.DISCONNECTED) {
                startVpnService()
            }
        }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch {
            prefsRepository.setLanguage(language)
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
        }
    }

    fun setTheme(theme: ThemePreference) {
        viewModelScope.launch { prefsRepository.setTheme(theme) }
    }

    fun setAutoConnectOnBoot(enabled: Boolean) {
        viewModelScope.launch { prefsRepository.setAutoConnectOnBoot(enabled) }
    }

    fun setCellularLimitMb(limitMb: Long) {
        viewModelScope.launch { prefsRepository.setCellularLimitMb(limitMb) }
    }

    fun setCustomDns(dns: String) {
        viewModelScope.launch { prefsRepository.setCustomDns(dns) }
    }

    fun saveServerConfig(host: String, port: Int, token: String) {
        viewModelScope.launch { prefsRepository.setServerConfig(host, port, token) }
    }

    fun runSpeedTest() {
        if (_speedTestProgress.value.isRunning) return
        val modeLabel = if (bondingMetrics.value.isTrueBondingActive) "BONDING" else settings.value.preferredMode.name
        val transportLabel = when {
            bondingMetrics.value.isTrueBondingActive -> "Wi-Fi + Cellular"
            wifiState.value.isAvailable -> "Wi-Fi"
            cellularState.value.isAvailable -> "Cellular"
            else -> "Unknown"
        }
        speedTestJob = viewModelScope.launch {
            speedTestEngine.runSpeedTest(modeLabel, transportLabel).collect { progress ->
                _speedTestProgress.value = progress
            }
        }
    }

    override fun onCleared() {
        speedTestJob?.cancel()
        super.onCleared()
    }
}
