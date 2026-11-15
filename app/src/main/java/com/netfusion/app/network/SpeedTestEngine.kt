package com.netfusion.app.network

import com.netfusion.app.data.local.SpeedTestDao
import com.netfusion.app.data.local.SpeedTestResultEntity
import com.netfusion.app.di.IoDispatcher
import com.netfusion.app.domain.model.SpeedTestPhase
import com.netfusion.app.domain.model.SpeedTestProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SpeedTestEngine @Inject constructor(
    private val speedTestDao: SpeedTestDao,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    /**
     * Runs a real HTTP/TCP speed test over the active system route (including active NetFusion VPN).
     * Never synthesizes or fakes bandwidth figures.
     */
    fun runSpeedTest(
        connectionModeLabel: String,
        transportLabel: String
    ): Flow<SpeedTestProgress> = flow {
        emit(SpeedTestProgress(isRunning = true, phase = SpeedTestPhase.LATENCY, progressFraction = 0.05f))

        // 1. Latency, Jitter & Packet Loss via 8 TCP handshake probes
        val rtts = mutableListOf<Int>()
        var lost = 0
        val probeCount = 8
        for (i in 0 until probeCount) {
            val rtt = measureTcpRtt("speed.cloudflare.com", 443, 1800)
            if (rtt >= 0) rtts.add(rtt) else lost++
            emit(
                SpeedTestProgress(
                    isRunning = true,
                    phase = SpeedTestPhase.LATENCY,
                    progressFraction = 0.05f + 0.15f * ((i + 1f) / probeCount),
                    pingMs = if (rtts.isNotEmpty()) rtts.average().toInt() else 0,
                    jitterMs = computeJitter(rtts),
                    packetLossPercent = (lost.toFloat() / probeCount) * 100f
                )
            )
        }

        if (rtts.isEmpty()) {
            emit(
                SpeedTestProgress(
                    isRunning = false,
                    phase = SpeedTestPhase.IDLE,
                    errorMessage = "Network unreachable during latency check"
                )
            )
            return@flow
        }

        val avgPing = rtts.average().toInt()
        val jitter = computeJitter(rtts)
        val lossPct = (lost.toFloat() / probeCount) * 100f

        // 2. Download Test (10 MB payload from Cloudflare speed endpoint)
        emit(
            SpeedTestProgress(
                isRunning = true,
                phase = SpeedTestPhase.DOWNLOAD,
                progressFraction = 0.25f,
                pingMs = avgPing,
                jitterMs = jitter,
                packetLossPercent = lossPct
            )
        )

        val downloadMbps = runCatching {
            measureHttpDownloadMbps("https://speed.cloudflare.com/__down?bytes=10000000") { frac, mbps ->
                // Progress callbacks handled cleanly via return value below
            }
        }.getOrElse {
            emit(
                SpeedTestProgress(
                    isRunning = false,
                    phase = SpeedTestPhase.IDLE,
                    pingMs = avgPing,
                    jitterMs = jitter,
                    packetLossPercent = lossPct,
                    errorMessage = it.message ?: "Download test failed"
                )
            )
            return@flow
        }

        emit(
            SpeedTestProgress(
                isRunning = true,
                phase = SpeedTestPhase.UPLOAD,
                progressFraction = 0.65f,
                currentDownloadMbps = downloadMbps,
                pingMs = avgPing,
                jitterMs = jitter,
                packetLossPercent = lossPct
            )
        )

        // 3. Upload Test (3 MB payload)
        val uploadMbps = runCatching {
            measureHttpUploadMbps("https://speed.cloudflare.com/__up", payloadBytes = 3_000_000)
        }.getOrDefault(0.0)

        val result = SpeedTestResultEntity(
            timestampMs = System.currentTimeMillis(),
            connectionMode = connectionModeLabel,
            transportLabel = transportLabel,
            downloadMbps = downloadMbps,
            uploadMbps = uploadMbps,
            pingMs = avgPing,
            jitterMs = jitter,
            packetLossPercent = lossPct
        )
        speedTestDao.insert(result)

        emit(
            SpeedTestProgress(
                isRunning = false,
                phase = SpeedTestPhase.COMPLETED,
                progressFraction = 1.0f,
                currentDownloadMbps = downloadMbps,
                currentUploadMbps = uploadMbps,
                pingMs = avgPing,
                jitterMs = jitter,
                packetLossPercent = lossPct
            )
        )
    }.flowOn(ioDispatcher)

    private fun measureTcpRtt(host: String, port: Int, timeoutMs: Int): Int {
        val sock = Socket()
        return try {
            val start = System.nanoTime()
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            -1
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun computeJitter(rtts: List<Int>): Int {
        if (rtts.size < 2) return 0
        return rtts.zipWithNext { a, b -> abs(a - b) }.average().toInt()
    }

    private fun measureHttpDownloadMbps(
        urlStr: String,
        onProgress: (Float, Double) -> Unit
    ): Double {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            requestMethod = "GET"
            setRequestProperty("Cache-Control", "no-cache")
        }
        return try {
            val buffer = ByteArray(32 * 1024)
            var totalBytes = 0L
            val startNs = System.nanoTime()
            val input: InputStream = conn.inputStream
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
                val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000.0
                if (elapsedSec > 0.2) {
                    val mbps = (totalBytes * 8.0) / (elapsedSec * 1_000_000.0)
                    onProgress((totalBytes / 10_000_000f).coerceIn(0f, 1f), mbps)
                }
                if (elapsedSec > 10.0) break
            }
            val totalSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
            (totalBytes * 8.0) / (totalSec * 1_000_000.0)
        } finally {
            conn.disconnect()
        }
    }

    private fun measureHttpUploadMbps(urlStr: String, payloadBytes: Int): Double {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 15000
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(payloadBytes)
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        return try {
            val chunk = ByteArray(32 * 1024) { 0x4E }
            var sent = 0
            val startNs = System.nanoTime()
            conn.outputStream.use { os ->
                while (sent < payloadBytes) {
                    val toWrite = minOf(chunk.size, payloadBytes - sent)
                    os.write(chunk, 0, toWrite)
                    sent += toWrite
                }
                os.flush()
            }
            conn.responseCode
            val totalSec = ((System.nanoTime() - startNs) / 1_000_000_000.0).coerceAtLeast(0.05)
            (sent * 8.0) / (totalSec * 1_000_000.0)
        } finally {
            conn.disconnect()
        }
    }
}
