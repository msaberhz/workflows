package com.netfusion.app.domain.model

import android.net.Network

enum class ConnectionMode {
    SMART,
    BONDING
}

enum class TunnelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED_SMART,
    CONNECTED_BONDED,
    CONNECTED_DEGRADED_SINGLE_PATH,
    RECONNECTING,
    ERROR
}

enum class TransportType {
    WIFI,
    CELLULAR
}

enum class AppLanguage(val tag: String, val displayName: String) {
    ENGLISH("en", "English"),
    PERSIAN_AF("fa-AF", "فارسی (افغانستان)")
}

enum class ThemePreference {
    SYSTEM,
    DARK,
    LIGHT
}

data class NetworkPathState(
    val type: TransportType,
    val network: Network? = null,
    val isAvailable: Boolean = false,
    val isValidated: Boolean = false,
    val isMetered: Boolean = false,
    val downstreamKbps: Int = 0,
    val upstreamKbps: Int = 0,
    val rttMs: Int = -1,
    val jitterMs: Int = 0,
    val packetLossPercent: Float = 0f,
    val signalStrengthDbm: Int? = null,
    val stabilityScore: Float = 0f,
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val activeForTunnel: Boolean = false
)

data class BondingMetrics(
    val isTrueBondingActive: Boolean = false,
    val limitationReason: LimitationReason? = null,
    val wifiSharePercent: Int = 0,
    val cellularSharePercent: Int = 0,
    val reorderedPackets: Long = 0L,
    val droppedOutOfOrderPackets: Long = 0L,
    val activePathsCount: Int = 0,
    val serverRttMs: Int = -1
)

enum class LimitationReason {
    NONE,
    SINGLE_INTERFACE_ONLY,
    CELLULAR_RESTRICTED_OR_UNAVAILABLE,
    SERVER_UNREACHABLE_SMART_FALLBACK,
    SERVER_NOT_CONFIGURED,
    AUTHENTICATION_FAILED
}

data class LiveTrafficStats(
    val downloadBps: Long = 0L,
    val uploadBps: Long = 0L,
    val wifiDownloadBps: Long = 0L,
    val cellularDownloadBps: Long = 0L,
    val pingMs: Int = -1,
    val sessionRxBytes: Long = 0L,
    val sessionTxBytes: Long = 0L,
    val uptimeSeconds: Long = 0L,
    val historySamples: List<TrafficSample> = emptyList()
)

data class TrafficSample(
    val timestampMs: Long,
    val downloadBps: Long,
    val uploadBps: Long
)

data class ServerConfig(
    val name: String,
    val host: String,
    val port: Int,
    val authToken: String,
    val region: String
)

data class SpeedTestProgress(
    val isRunning: Boolean = false,
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    val progressFraction: Float = 0f,
    val currentDownloadMbps: Double = 0.0,
    val currentUploadMbps: Double = 0.0,
    val pingMs: Int = 0,
    val jitterMs: Int = 0,
    val packetLossPercent: Float = 0f,
    val errorMessage: String? = null
)

enum class SpeedTestPhase {
    IDLE,
    LATENCY,
    DOWNLOAD,
    UPLOAD,
    COMPLETED
}
