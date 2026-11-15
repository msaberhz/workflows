package com.netfusion.app.bonding

import android.net.Network
import android.net.VpnService
import com.netfusion.app.domain.model.BondingMetrics
import com.netfusion.app.domain.model.LimitationReason
import com.netfusion.app.domain.model.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

class BondingClient(
    private val vpnService: VpnService,
    private val serverConfig: ServerConfig,
    private val scope: CoroutineScope,
    private val onPacketForTun: (ByteArray) -> Unit,
    private val onMetricsUpdate: (BondingMetrics) -> Unit
) {
    private val crypto = PacketCrypto(serverConfig.authToken.ifBlank { "netfusion-default-key" })
    private val scheduler = MultipathScheduler()
    private val reorderBuffer = ReorderBuffer()
    private val sessionId: Long = SecureRandom().nextLong().let { if (it == 0L) 1L else it }

    private var serverAddress: InetAddress? = null
    private var wifiSocket: DatagramSocket? = null
    private var cellSocket: DatagramSocket? = null

    private var wifiRxJob: Job? = null
    private var cellRxJob: Job? = null
    private var probeJob: Job? = null
    private var flushJob: Job? = null

    private val wifiTxPackets = AtomicLong(0L)
    private val cellTxPackets = AtomicLong(0L)

    @Volatile
    private var wifiLastProbeAckMs = 0L
    @Volatile
    private var cellLastProbeAckMs = 0L
    @Volatile
    private var wifiRttMs = -1
    @Volatile
    private var cellRttMs = -1
    @Volatile
    var isHandshakeVerified = false
        private set

    fun start(wifiNetwork: Network?, cellNetwork: Network?): Boolean {
        return try {
            serverAddress = InetAddress.getByName(serverConfig.host)
            updateNetworks(wifiNetwork, cellNetwork)

            // Perform authenticated HELLO handshake on available socket(s)
            sendHello(wifiSocket, NfmpProtocol.PATH_WIFI)
            sendHello(cellSocket, NfmpProtocol.PATH_CELLULAR)

            startProbeLoop()
            startFlushLoop()
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun updateNetworks(wifiNetwork: Network?, cellNetwork: Network?) {
        val addr = serverAddress ?: return
        val remote = InetSocketAddress(addr, serverConfig.port)

        // Bind Wi-Fi protected UDP socket
        if (wifiNetwork != null && wifiSocket == null) {
            runCatching {
                val sock = DatagramSocket()
                vpnService.protect(sock)
                wifiNetwork.bindSocket(sock)
                sock.connect(remote)
                sock.soTimeout = 0
                wifiSocket = sock
                scheduler.updatePath(NfmpProtocol.PATH_WIFI, MultipathScheduler.PathMetrics(isUp = true))
                wifiRxJob = launchRxLoop(sock, NfmpProtocol.PATH_WIFI)
                sendHello(sock, NfmpProtocol.PATH_WIFI)
            }
        } else if (wifiNetwork == null && wifiSocket != null) {
            wifiRxJob?.cancel()
            runCatching { wifiSocket?.close() }
            wifiSocket = null
            scheduler.updatePath(NfmpProtocol.PATH_WIFI, MultipathScheduler.PathMetrics(isUp = false))
        }

        // Bind Cellular protected UDP socket
        if (cellNetwork != null && cellSocket == null) {
            runCatching {
                val sock = DatagramSocket()
                vpnService.protect(sock)
                cellNetwork.bindSocket(sock)
                sock.connect(remote)
                sock.soTimeout = 0
                cellSocket = sock
                scheduler.updatePath(NfmpProtocol.PATH_CELLULAR, MultipathScheduler.PathMetrics(isUp = true))
                cellRxJob = launchRxLoop(sock, NfmpProtocol.PATH_CELLULAR)
                sendHello(sock, NfmpProtocol.PATH_CELLULAR)
            }
        } else if (cellNetwork == null && cellSocket != null) {
            cellRxJob?.cancel()
            runCatching { cellSocket?.close() }
            cellSocket = null
            scheduler.updatePath(NfmpProtocol.PATH_CELLULAR, MultipathScheduler.PathMetrics(isUp = false))
        }

        publishMetrics()
    }

    fun sendIpPacket(ipPacket: ByteArray, length: Int): Boolean {
        val path = scheduler.selectPath(length) ?: return false
        val sock = if (path == NfmpProtocol.PATH_WIFI) wifiSocket else cellSocket
        if (sock == null || sock.isClosed) return false

        val seq = scheduler.nextSeq()
        val header = NfmpHeader(
            type = NfmpProtocol.TYPE_DATA,
            sessionId = sessionId,
            seqNum = seq,
            pathId = path,
            flags = NfmpProtocol.FLAG_ENCRYPTED,
            timestampMs = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
        )
        val encrypted = crypto.encryptDataPacket(header, ipPacket, 0, length)
        return try {
            sock.send(DatagramPacket(encrypted, encrypted.size))
            if (path == NfmpProtocol.PATH_WIFI) wifiTxPackets.incrementAndGet() else cellTxPackets.incrementAndGet()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun sendHello(sock: DatagramSocket?, pathId: Byte) {
        if (sock == null || sock.isClosed) return
        val ts = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
        val header = NfmpHeader(
            type = NfmpProtocol.TYPE_HELLO,
            sessionId = sessionId,
            seqNum = 0L,
            pathId = pathId,
            flags = 0,
            timestampMs = ts
        )
        val authTag = crypto.computeAuthTag(sessionId, ts)
        val pkt = ByteArray(NfmpProtocol.HEADER_SIZE + authTag.size)
        header.encodeHeader(ByteBuffer.wrap(pkt))
        System.arraycopy(authTag, 0, pkt, NfmpProtocol.HEADER_SIZE, authTag.size)
        runCatching { sock.send(DatagramPacket(pkt, pkt.size)) }
    }

    private fun launchRxLoop(sock: DatagramSocket, pathId: Byte): Job {
        return scope.launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            val dgram = DatagramPacket(buf, buf.size)
            while (isActive && !sock.isClosed) {
                try {
                    sock.receive(dgram)
                    val len = dgram.length
                    val header = NfmpHeader.decode(buf, len) ?: continue
                    if (header.sessionId != sessionId) continue

                    when (header.type) {
                        NfmpProtocol.TYPE_WELCOME -> {
                            isHandshakeVerified = true
                            if (pathId == NfmpProtocol.PATH_WIFI) {
                                wifiLastProbeAckMs = System.currentTimeMillis()
                            } else {
                                cellLastProbeAckMs = System.currentTimeMillis()
                            }
                        }
                        NfmpProtocol.TYPE_PROBE_ACK -> {
                            val now = System.currentTimeMillis()
                            val nowTs = (now and 0x7FFFFFFFL).toInt()
                            val rtt = (nowTs - header.timestampMs).coerceIn(1, 2000)
                            if (pathId == NfmpProtocol.PATH_WIFI) {
                                wifiRttMs = rtt
                                wifiLastProbeAckMs = now
                                scheduler.updatePath(pathId, MultipathScheduler.PathMetrics(isUp = true, rttMs = rtt))
                            } else {
                                cellRttMs = rtt
                                cellLastProbeAckMs = now
                                scheduler.updatePath(pathId, MultipathScheduler.PathMetrics(isUp = true, rttMs = rtt))
                            }
                        }
                        NfmpProtocol.TYPE_DATA -> {
                            val plain = crypto.decryptDataPacket(buf, len, header) ?: continue
                            val orderedPackets = reorderBuffer.push(header.seqNum, plain)
                            for (p in orderedPackets) {
                                onPacketForTun(p)
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (!isActive || sock.isClosed) break
                }
            }
        }
    }

    private fun startProbeLoop() {
        probeJob?.cancel()
        probeJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendProbe(wifiSocket, NfmpProtocol.PATH_WIFI)
                sendProbe(cellSocket, NfmpProtocol.PATH_CELLULAR)
                publishMetrics()
                delay(2_500L)
            }
        }
    }

    private fun startFlushLoop() {
        flushJob?.cancel()
        flushJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(20L)
                val expired = reorderBuffer.flushExpired()
                for (p in expired) {
                    onPacketForTun(p)
                }
            }
        }
    }

    private fun sendProbe(sock: DatagramSocket?, pathId: Byte) {
        if (sock == null || sock.isClosed) return
        val ts = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
        val header = NfmpHeader(
            type = NfmpProtocol.TYPE_PROBE,
            sessionId = sessionId,
            seqNum = 0L,
            pathId = pathId,
            flags = 0,
            timestampMs = ts
        )
        val pkt = ByteArray(NfmpProtocol.HEADER_SIZE)
        header.encodeHeader(ByteBuffer.wrap(pkt))
        runCatching { sock.send(DatagramPacket(pkt, pkt.size)) }
    }

    private fun publishMetrics() {
        val wCount = wifiTxPackets.get()
        val cCount = cellTxPackets.get()
        val total = (wCount + cCount).coerceAtLeast(1L)
        val wPct = ((wCount * 100L) / total).toInt()
        val cPct = if (cellSocket != null) (100 - wPct) else 0

        val activeCount = listOfNotNull(wifiSocket, cellSocket).size
        val trueBonding = activeCount == 2 && isHandshakeVerified

        val reason = when {
            trueBonding -> LimitationReason.NONE
            activeCount == 1 -> LimitationReason.SINGLE_INTERFACE_ONLY
            !isHandshakeVerified -> LimitationReason.SERVER_UNREACHABLE_SMART_FALLBACK
            else -> LimitationReason.NONE
        }

        val bestRtt = listOf(wifiRttMs, cellRttMs).filter { it > 0 }.minOrNull() ?: -1

        onMetricsUpdate(
            BondingMetrics(
                isTrueBondingActive = trueBonding,
                limitationReason = reason,
                wifiSharePercent = if (wifiSocket != null) wPct else 0,
                cellularSharePercent = cPct,
                reorderedPackets = reorderBuffer.reorderedCount,
                droppedOutOfOrderPackets = reorderBuffer.droppedLateCount,
                activePathsCount = activeCount,
                serverRttMs = bestRtt
            )
        )
    }

    fun stop() {
        probeJob?.cancel()
        flushJob?.cancel()
        wifiRxJob?.cancel()
        cellRxJob?.cancel()
        sendClose(wifiSocket, NfmpProtocol.PATH_WIFI)
        sendClose(cellSocket, NfmpProtocol.PATH_CELLULAR)
        runCatching { wifiSocket?.close() }
        runCatching { cellSocket?.close() }
        wifiSocket = null
        cellSocket = null
        reorderBuffer.reset()
        scheduler.reset()
    }

    private fun sendClose(sock: DatagramSocket?, pathId: Byte) {
        if (sock == null || sock.isClosed) return
        val header = NfmpHeader(
            type = NfmpProtocol.TYPE_CLOSE,
            sessionId = sessionId,
            seqNum = 0L,
            pathId = pathId,
            flags = 0,
            timestampMs = 0
        )
        val pkt = ByteArray(NfmpProtocol.HEADER_SIZE)
        header.encodeHeader(ByteBuffer.wrap(pkt))
        runCatching { sock.send(DatagramPacket(pkt, pkt.size)) }
    }
}
