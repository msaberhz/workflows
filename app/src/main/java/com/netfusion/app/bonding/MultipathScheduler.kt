package com.netfusion.app.bonding

import java.util.concurrent.atomic.AtomicLong

/**
 * Weighted Deficit Round-Robin (WDRR) scheduler calibrated by each path's RTT and loss.
 * Automatically falls back to the healthy path when one interface degrades or disconnects.
 */
class MultipathScheduler {
    data class PathMetrics(
        val isUp: Boolean = false,
        val rttMs: Int = 50,
        val lossPercent: Float = 0f,
        val estimatedKbps: Int = 10_000
    )

    private var wifiMetrics = PathMetrics()
    private var cellMetrics = PathMetrics()
    private var deficitWifi = 0.0
    private var deficitCell = 0.0
    private val seqCounter = AtomicLong(1L)

    @Synchronized
    fun updatePath(pathId: Byte, metrics: PathMetrics) {
        if (pathId == NfmpProtocol.PATH_WIFI) {
            wifiMetrics = metrics
        } else if (pathId == NfmpProtocol.PATH_CELLULAR) {
            cellMetrics = metrics
        }
    }

    fun nextSeq(): Long = seqCounter.getAndIncrement()

    /**
     * Selects PATH_WIFI or PATH_CELLULAR for the next packet, or null if both paths are down.
     */
    @Synchronized
    fun selectPath(packetSizeBytes: Int): Byte? {
        val wifiHealthy = wifiMetrics.isUp && wifiMetrics.lossPercent < 40f
        val cellHealthy = cellMetrics.isUp && cellMetrics.lossPercent < 40f

        if (!wifiHealthy && !cellHealthy) {
            return when {
                wifiMetrics.isUp -> NfmpProtocol.PATH_WIFI
                cellMetrics.isUp -> NfmpProtocol.PATH_CELLULAR
                else -> null
            }
        }
        if (wifiHealthy && !cellHealthy) return NfmpProtocol.PATH_WIFI
        if (cellHealthy && !wifiHealthy) return NfmpProtocol.PATH_CELLULAR

        // Both paths healthy: compute weights from bandwidth & inverse RTT
        val wifiWeight = computeWeight(wifiMetrics)
        val cellWeight = computeWeight(cellMetrics)
        val total = wifiWeight + cellWeight
        val quantum = 1500.0

        deficitWifi += quantum * (wifiWeight / total)
        deficitCell += quantum * (cellWeight / total)

        return if (deficitWifi >= deficitCell) {
            deficitWifi -= packetSizeBytes.coerceAtLeast(64)
            NfmpProtocol.PATH_WIFI
        } else {
            deficitCell -= packetSizeBytes.coerceAtLeast(64)
            NfmpProtocol.PATH_CELLULAR
        }
    }

    @Synchronized
    fun reset() {
        deficitWifi = 0.0
        deficitCell = 0.0
        seqCounter.set(1L)
    }

    private fun computeWeight(m: PathMetrics): Double {
        val rttFactor = 100.0 / m.rttMs.coerceIn(10, 500)
        val bwFactor = (m.estimatedKbps.coerceAtLeast(1000) / 10_000.0).coerceIn(0.2, 10.0)
        val lossPenalty = (1.0 - (m.lossPercent / 100.0)).coerceIn(0.1, 1.0)
        return (rttFactor * 0.4 + bwFactor * 0.6) * lossPenalty
    }
}
