package com.netfusion.app.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.Network
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.netfusion.app.MainActivity
import com.netfusion.app.NetFusionApp
import com.netfusion.app.R
import com.netfusion.app.bonding.BondingClient
import com.netfusion.app.data.local.DailyUsageEntity
import com.netfusion.app.data.local.UsageStatsDao
import com.netfusion.app.data.preferences.UserPreferencesRepository
import com.netfusion.app.domain.model.BondingMetrics
import com.netfusion.app.domain.model.ConnectionMode
import com.netfusion.app.domain.model.LimitationReason
import com.netfusion.app.domain.model.LiveTrafficStats
import com.netfusion.app.domain.model.TrafficSample
import com.netfusion.app.domain.model.TransportType
import com.netfusion.app.domain.model.TunnelState
import com.netfusion.app.network.MultiNetworkMonitor
import com.netfusion.app.network.SmartModeEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class NetFusionVpnService : VpnService() {

    @Inject lateinit var networkMonitor: MultiNetworkMonitor
    @Inject lateinit var smartModeEngine: SmartModeEngine
    @Inject lateinit var prefsRepository: UserPreferencesRepository
    @Inject lateinit var stateHolder: TunnelStateHolder
    @Inject lateinit var usageStatsDao: UsageStatsDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunOutput: FileOutputStream? = null
    private var bondingClient: BondingClient? = null

    private var controlLoopJob: Job? = null
    private var tunReaderJob: Job? = null
    private var statsJob: Job? = null

    private var sessionStartMs: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val modeName = intent?.getStringExtra(EXTRA_MODE)
                val mode = modeName?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() }
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.status_connecting)))
                startTunnel(mode)
            }
        }
        return START_STICKY
    }

    private fun startTunnel(requestedMode: ConnectionMode?) {
        serviceScope.launch {
            val settings = prefsRepository.settingsFlow.first()
            val mode = requestedMode ?: settings.preferredMode
            stateHolder.activeMode.value = mode
            stateHolder.tunnelState.value = TunnelState.CONNECTING
            stateHolder.lastError.value = null

            networkMonitor.startMonitoring()
            sessionStartMs = System.currentTimeMillis()

            if (mode == ConnectionMode.BONDING) {
                networkMonitor.setCellularKeepAlive(true)
                delay(800L) // Give OS a moment to bring up cellular network if needed
                startBondingMode(settings.customDns, settings.mtuSize, prefsRepository.buildActiveServerConfig(settings))
            } else {
                networkMonitor.setCellularKeepAlive(false)
                startSmartMode()
            }

            startTrafficStatsLoop()
        }
    }

    private fun startSmartMode() {
        // In Smart Mode, we monitor Wi-Fi and Cellular continuously and steer traffic via
        // ConnectivityManager / VpnService underlying network binding without requiring a VPS.
        stateHolder.tunnelState.value = TunnelState.CONNECTED_SMART
        stateHolder.bondingMetrics.value = BondingMetrics(
            isTrueBondingActive = false,
            limitationReason = LimitationReason.NONE
        )
        updateNotification(getString(R.string.status_connected_smart))

        controlLoopJob?.cancel()
        controlLoopJob = serviceScope.launch {
            while (isActive) {
                val wifi = networkMonitor.wifiState.value
                val cell = networkMonitor.cellularState.value
                val decision = smartModeEngine.evaluate(wifi, cell)
                stateHolder.activeSmartTransport.value = decision.selectedTransport

                val activeNet: Network? = when (decision.selectedTransport) {
                    TransportType.WIFI -> wifi.network
                    TransportType.CELLULAR -> cell.network
                    null -> null
                }

                if (tunInterface != null && activeNet != null) {
                    setUnderlyingNetworks(arrayOf(activeNet))
                }

                if (decision.switched) {
                    incrementSmartSwitchStat()
                }
                delay(2_000L)
            }
        }
    }

    private fun startBondingMode(
        dnsServer: String,
        mtuSize: Int,
        serverConfig: com.netfusion.app.domain.model.ServerConfig
    ) {
        if (serverConfig.host.isBlank()) {
            stateHolder.bondingMetrics.value = BondingMetrics(
                isTrueBondingActive = false,
                limitationReason = LimitationReason.SERVER_NOT_CONFIGURED
            )
            // Transparently fall back to Smart Mode rather than faking bonding
            startSmartMode()
            return
        }

        val builder = Builder()
            .setSession("NetFusion Bonding")
            .setMtu(mtuSize)
            .addAddress("10.88.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(dnsServer.ifBlank { "1.1.1.1" })

        runCatching { builder.addDisallowedApplication(packageName) }

        val wifiNet = networkMonitor.wifiState.value.takeIf { it.isAvailable }?.network
        val cellNet = networkMonitor.cellularState.value.takeIf { it.isAvailable }?.network
        val underlying = listOfNotNull(wifiNet, cellNet).toTypedArray()
        if (underlying.isNotEmpty()) {
            builder.setUnderlyingNetworks(underlying)
        }

        tunInterface = builder.establish()
        if (tunInterface == null) {
            stateHolder.tunnelState.value = TunnelState.ERROR
            stateHolder.lastError.value = getString(R.string.error_vpn_establish_failed)
            stopSelf()
            return
        }

        val pfd = tunInterface!!
        tunOutput = FileOutputStream(pfd.fileDescriptor)

        val client = BondingClient(
            vpnService = this,
            serverConfig = serverConfig,
            scope = serviceScope,
            onPacketForTun = { packet ->
                runCatching {
                    tunOutput?.write(packet)
                }
            },
            onMetricsUpdate = { metrics ->
                stateHolder.bondingMetrics.value = metrics
                stateHolder.tunnelState.value = when {
                    metrics.isTrueBondingActive -> TunnelState.CONNECTED_BONDED
                    metrics.activePathsCount == 1 -> TunnelState.CONNECTED_DEGRADED_SINGLE_PATH
                    else -> TunnelState.RECONNECTING
                }
            }
        )
        bondingClient = client

        if (!client.start(wifiNet, cellNet)) {
            stateHolder.bondingMetrics.value = BondingMetrics(
                isTrueBondingActive = false,
                limitationReason = LimitationReason.SERVER_UNREACHABLE_SMART_FALLBACK
            )
            runCatching { pfd.close() }
            tunInterface = null
            startSmartMode()
            return
        }

        updateNotification(getString(R.string.status_connected_bonded))

        // TUN -> BondingClient TX loop
        tunReaderJob?.cancel()
        tunReaderJob = serviceScope.launch(Dispatchers.IO) {
            val input = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(2048)
            while (isActive && tunInterface != null) {
                val read = runCatching { input.read(buffer) }.getOrDefault(-1)
                if (read > 0) {
                    client.sendIpPacket(buffer, read)
                } else if (read < 0) {
                    break
                }
            }
        }

        // Dynamic network watcher to hot-add/remove Wi-Fi or Cellular sockets
        controlLoopJob?.cancel()
        controlLoopJob = serviceScope.launch {
            while (isActive) {
                val wNet = networkMonitor.wifiState.value.takeIf { it.isAvailable }?.network
                val cNet = networkMonitor.cellularState.value.takeIf { it.isAvailable }?.network
                client.updateNetworks(wNet, cNet)
                val nets = listOfNotNull(wNet, cNet).toTypedArray()
                if (nets.isNotEmpty() && tunInterface != null) {
                    setUnderlyingNetworks(nets)
                }
                delay(3_000L)
            }
        }
    }

    private fun startTrafficStatsLoop() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            var lastRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            var lastTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            val baselineRx = lastRx
            val baselineTx = lastTx
            val history = ArrayDeque<TrafficSample>(60)

            while (isActive) {
                delay(1_000L)
                val nowRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
                val nowTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
                val deltaRx = (nowRx - lastRx).coerceAtLeast(0L)
                val deltaTx = (nowTx - lastTx).coerceAtLeast(0L)
                lastRx = nowRx
                lastTx = nowTx

                val dlBps = deltaRx * 8L
                val ulBps = deltaTx * 8L

                if (history.size >= 45) history.removeFirst()
                history.addLast(TrafficSample(System.currentTimeMillis(), dlBps, ulBps))

                val metrics = stateHolder.bondingMetrics.value
                val wifiShare = if (metrics.isTrueBondingActive) metrics.wifiSharePercent / 100.0 else {
                    if (stateHolder.activeSmartTransport.value == TransportType.WIFI) 1.0 else 0.0
                }
                val cellShare = if (metrics.isTrueBondingActive) metrics.cellularSharePercent / 100.0 else {
                    if (stateHolder.activeSmartTransport.value == TransportType.CELLULAR) 1.0 else 0.0
                }

                val activePing = when {
                    metrics.isTrueBondingActive && metrics.serverRttMs > 0 -> metrics.serverRttMs
                    stateHolder.activeSmartTransport.value == TransportType.CELLULAR ->
                        networkMonitor.cellularState.value.rttMs
                    else -> networkMonitor.wifiState.value.rttMs
                }

                val uptimeSec = ((System.currentTimeMillis() - sessionStartMs) / 1000L).coerceAtLeast(0L)

                stateHolder.liveTrafficStats.value = LiveTrafficStats(
                    downloadBps = dlBps,
                    uploadBps = ulBps,
                    wifiDownloadBps = (dlBps * wifiShare).toLong(),
                    cellularDownloadBps = (dlBps * cellShare).toLong(),
                    pingMs = activePing,
                    sessionRxBytes = (nowRx - baselineRx).coerceAtLeast(0L),
                    sessionTxBytes = (nowTx - baselineTx).coerceAtLeast(0L),
                    uptimeSeconds = uptimeSec,
                    historySamples = history.toList()
                )

                // Persist daily usage incrementally every 5 seconds
                if (uptimeSec % 5L == 0L && (deltaRx > 0 || deltaTx > 0)) {
                    persistUsageDelta(
                        wifiRx = (deltaRx * wifiShare).toLong(),
                        wifiTx = (deltaTx * wifiShare).toLong(),
                        cellRx = (deltaRx * cellShare).toLong(),
                        cellTx = (deltaTx * cellShare).toLong(),
                        bondedSec = if (metrics.isTrueBondingActive) 5L else 0L
                    )
                }
            }
        }
    }

    private suspend fun persistUsageDelta(
        wifiRx: Long,
        wifiTx: Long,
        cellRx: Long,
        cellTx: Long,
        bondedSec: Long
    ) {
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val existing = usageStatsDao.getDay(dateKey) ?: DailyUsageEntity(dateKey = dateKey)
        usageStatsDao.upsert(
            existing.copy(
                wifiRxBytes = existing.wifiRxBytes + wifiRx,
                wifiTxBytes = existing.wifiTxBytes + wifiTx,
                cellularRxBytes = existing.cellularRxBytes + cellRx,
                cellularTxBytes = existing.cellularTxBytes + cellTx,
                bondedSeconds = existing.bondedSeconds + bondedSec
            )
        )
    }

    private suspend fun incrementSmartSwitchStat() {
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val existing = usageStatsDao.getDay(dateKey) ?: DailyUsageEntity(dateKey = dateKey)
        usageStatsDao.upsert(existing.copy(smartSwitchCount = existing.smartSwitchCount + 1))
    }

    private fun stopTunnel() {
        controlLoopJob?.cancel()
        tunReaderJob?.cancel()
        statsJob?.cancel()
        bondingClient?.stop()
        bondingClient = null
        runCatching { tunOutput?.close() }
        runCatching { tunInterface?.close() }
        tunOutput = null
        tunInterface = null
        networkMonitor.setCellularKeepAlive(false)
        smartModeEngine.reset()
        stateHolder.tunnelState.value = TunnelState.DISCONNECTED
        stateHolder.bondingMetrics.value = BondingMetrics()
        stateHolder.liveTrafficStats.value = LiveTrafficStats()
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NetFusionVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NetFusionApp.VPN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_disconnect), stopIntent)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    companion object {
        const val ACTION_START = "com.netfusion.app.action.START"
        const val ACTION_STOP = "com.netfusion.app.action.STOP"
        const val EXTRA_MODE = "extra_mode"
        const val NOTIFICATION_ID = 1001
    }
}
