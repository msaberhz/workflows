package com.netfusion.app.network

import com.netfusion.app.domain.model.NetworkPathState
import com.netfusion.app.domain.model.TransportType
import javax.inject.Inject
import javax.inject.Singleton

data class SmartSelectionDecision(
    val selectedTransport: TransportType?,
    val reason: String,
    val switched: Boolean
)

/**
 * Intelligent failover & network selector with hysteresis to prevent ping-pong switching.
 */
@Singleton
class SmartModeEngine @Inject constructor() {

    private var currentActive: TransportType? = null
    private var lastSwitchTimestampMs: Long = 0L

    fun evaluate(
        wifi: NetworkPathState,
        cellular: NetworkPathState,
        nowMs: Long = System.currentTimeMillis()
    ): SmartSelectionDecision {
        val wifiUsable = wifi.isAvailable && wifi.isValidated && wifi.packetLossPercent < 50f
        val cellUsable = cellular.isAvailable && cellular.isValidated && cellular.packetLossPercent < 50f

        if (!wifiUsable && !cellUsable) {
            // Fallback to whichever is even partially available
            val fallback = when {
                wifi.isAvailable -> TransportType.WIFI
                cellular.isAvailable -> TransportType.CELLULAR
                else -> null
            }
            val switched = fallback != currentActive
            if (switched) {
                currentActive = fallback
                lastSwitchTimestampMs = nowMs
            }
            return SmartSelectionDecision(
                selectedTransport = fallback,
                reason = if (fallback == null) "no_network" else "unvalidated_fallback",
                switched = switched
            )
        }

        if (wifiUsable && !cellUsable) {
            return switchTo(TransportType.WIFI, "cellular_unavailable", nowMs)
        }
        if (cellUsable && !wifiUsable) {
            return switchTo(TransportType.CELLULAR, "wifi_unavailable", nowMs)
        }

        // Both Wi-Fi and Cellular are usable
        val current = currentActive
        if (current == null) {
            val initial = if (wifi.stabilityScore >= cellular.stabilityScore) {
                TransportType.WIFI
            } else {
                TransportType.CELLULAR
            }
            return switchTo(initial, "initial_best_score", nowMs)
        }

        val currentState = if (current == TransportType.WIFI) wifi else cellular
        val candidateType = if (current == TransportType.WIFI) TransportType.CELLULAR else TransportType.WIFI
        val candidateState = if (candidateType == TransportType.WIFI) wifi else cellular

        // Immediate failover if current path is severely degraded
        if (currentState.packetLossPercent >= 25f || (currentState.rttMs > 450 && candidateState.rttMs in 1..150)) {
            return switchTo(candidateType, "emergency_failover", nowMs)
        }

        // Anti-flap hysteresis: require minimum dwell time AND clear score advantage
        val elapsedSinceSwitch = nowMs - lastSwitchTimestampMs
        val scoreMargin = candidateState.stabilityScore - currentState.stabilityScore
        val requiredMargin = if (current == TransportType.WIFI) 15f else 8f // Prefer unmetered Wi-Fi slightly

        if (elapsedSinceSwitch >= MIN_DWELL_TIME_MS && scoreMargin >= requiredMargin) {
            return switchTo(candidateType, "better_stability_score", nowMs)
        }

        return SmartSelectionDecision(
            selectedTransport = current,
            reason = "hysteresis_hold",
            switched = false
        )
    }

    fun reset() {
        currentActive = null
        lastSwitchTimestampMs = 0L
    }

    private fun switchTo(
        target: TransportType,
        reason: String,
        nowMs: Long
    ): SmartSelectionDecision {
        val switched = currentActive != target
        if (switched) {
            currentActive = target
            lastSwitchTimestampMs = nowMs
        }
        return SmartSelectionDecision(
            selectedTransport = target,
            reason = reason,
            switched = switched
        )
    }

    companion object {
        const val MIN_DWELL_TIME_MS = 15_000L
    }
}
