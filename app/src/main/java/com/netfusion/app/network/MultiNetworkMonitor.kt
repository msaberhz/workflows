package com.netfusion.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.netfusion.app.di.ApplicationScope
import com.netfusion.app.di.IoDispatcher
import com.netfusion.app.domain.model.NetworkPathState
import com.netfusion.app.domain.model.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class MultiNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _wifiState = MutableStateFlow(NetworkPathState(type = TransportType.WIFI))
    val wifiState: StateFlow<NetworkPathState> = _wifiState.asStateFlow()

    private val _cellularState = MutableStateFlow(NetworkPathState(type = TransportType.CELLULAR))
    val cellularState: StateFlow<NetworkPathState> = _cellularState.asStateFlow()

    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var cellListenCallback: ConnectivityManager.NetworkCallback? = null
    private var cellRequestCallback: ConnectivityManager.NetworkCallback? = null
    private var probeJob: Job? = null
    private var isCellularKeepAliveRequested = false

    fun startMonitoring() {
        if (wifiCallback != null) return

        val wifiReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        wifiCallback = createCallback(TransportType.WIFI, _wifiState).also {
            cm.registerNetworkCallback(wifiReq, it)
        }

        val cellReq = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        cellListenCallback = createCallback(TransportType.CELLULAR, _cellularState).also {
            cm.registerNetworkCallback(cellReq, it)
        }

        startHealthProbes()
    }

    /**
     * Requests the OS to bring up and retain the Cellular interface even when Wi-Fi is default.
     * Required for simultaneous Wi-Fi + 4G/5G Bonding Mode.
     */
    fun setCellularKeepAlive(enabled: Boolean) {
        if (enabled == isCellularKeepAliveRequested) return
        if (enabled) {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = createCallback(TransportType.CELLULAR, _cellularState)
            try {
                cm.requestNetwork(req, cb)
                cellRequestCallback = cb
                isCellularKeepAliveRequested = true
            } catch (_: SecurityException) {
                isCellularKeepAliveRequested = false
            } catch (_: Exception) {
                isCellularKeepAliveRequested = false
            }
        } else {
            cellRequestCallback?.let {
                runCatching { cm.unregisterNetworkCallback(it) }
            }
            cellRequestCallback = null
            isCellularKeepAliveRequested = false
        }
    }

    fun stopMonitoring() {
        wifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cellListenCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        setCellularKeepAlive(false)
        wifiCallback = null
        cellListenCallback = null
        probeJob?.cancel()
        probeJob = null
    }

    private fun createCallback(
        type: TransportType,
        targetFlow: MutableStateFlow<NetworkPathState>
    ): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                updateFromCapabilities(targetFlow, type, network, caps, available = true)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                updateFromCapabilities(targetFlow, type, network, caps, available = true)
            }

            override fun onLost(network: Network) {
                targetFlow.update { current ->
                    if (current.network == network) {
                        NetworkPathState(type = type, isAvailable = false)
                    } else current
                }
            }
        }
    }

    private fun updateFromCapabilities(
        flow: MutableStateFlow<NetworkPathState>,
        type: TransportType,
        network: Network,
        caps: NetworkCapabilities?,
        available: Boolean
    ) {
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val downKbps = caps?.linkDownstreamBandwidthKbps ?: 0
        val upKbps = caps?.linkUpstreamBandwidthKbps ?: 0
        val signalDbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            caps?.signalStrength?.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
        } else null

        flow.update { prev ->
            val updated = prev.copy(
                network = network,
                isAvailable = available,
                isValidated = validated,
                isMetered = metered,
                downstreamKbps = downKbps,
                upstreamKbps = upKbps,
                signalStrengthDbm = signalDbm
            )
            updated.copy(stabilityScore = computeStabilityScore(updated))
        }
    }

    private fun startHealthProbes() {
        probeJob?.cancel()
        probeJob = scope.launch(ioDispatcher) {
            while (isActive) {
                val wifiNet = _wifiState.value.takeIf { it.isAvailable }?.network
                val cellNet = _cellularState.value.takeIf { it.isAvailable }?.network

                if (wifiNet != null) {
                    probeNetwork(wifiNet, _wifiState)
                }
                if (cellNet != null) {
                    probeNetwork(cellNet, _cellularState)
                }
                delay(4_000L)
            }
        }
    }

    private suspend fun probeNetwork(
        network: Network,
        targetFlow: MutableStateFlow<NetworkPathState>
    ) = withContext(ioDispatcher) {
        val samples = mutableListOf<Int>()
        var failures = 0
        val targets = listOf("1.1.1.1" to 443, "8.8.8.8" to 443, "9.9.9.9" to 443)

        for ((host, port) in targets) {
            val rtt = measureTcpConnectRtt(network, host, port, timeoutMs = 1500)
            if (rtt >= 0) {
                samples.add(rtt)
            } else {
                failures++
            }
        }

        val avgRtt = if (samples.isNotEmpty()) samples.average().toInt() else -1
        val jitter = if (samples.size >= 2) {
            samples.zipWithNext { a, b -> abs(a - b) }.average().toInt()
        } else 0
        val lossPct = (failures.toFloat() / targets.size.toFloat()) * 100f

        targetFlow.update { prev ->
            if (prev.network != network) return@update prev
            val updated = prev.copy(
                rttMs = avgRtt,
                jitterMs = jitter,
                packetLossPercent = lossPct
            )
            updated.copy(stabilityScore = computeStabilityScore(updated))
        }
    }

    private fun measureTcpConnectRtt(
        network: Network,
        host: String,
        port: Int,
        timeoutMs: Int
    ): Int {
        val socket = Socket()
        return try {
            network.bindSocket(socket)
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val elapsedMs = ((System.nanoTime() - start) / 1_000_000L).toInt()
            elapsedMs.coerceAtLeast(1)
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        fun computeStabilityScore(state: NetworkPathState): Float {
            if (!state.isAvailable) return 0f
            var score = if (state.isValidated) 40f else 10f

            // Latency component (up to 30 pts)
            score += when {
                state.rttMs < 0 -> 0f
                state.rttMs <= 35 -> 30f
                state.rttMs <= 80 -> 24f
                state.rttMs <= 150 -> 15f
                else -> 6f
            }

            // Packet loss penalty (up to 20 pts)
            score += (20f - (state.packetLossPercent * 0.5f)).coerceIn(0f, 20f)

            // Capacity bonus (up to 10 pts)
            val downMbps = state.downstreamKbps / 1000f
            score += (downMbps / 10f).coerceIn(0f, 10f)

            return score.coerceIn(0f, 100f)
        }
    }
}
