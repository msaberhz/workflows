package com.netfusion.app.bonding

import java.util.TreeMap

/**
 * Sequence-numbered sliding-window reorder buffer for multipath packet reassembly.
 * Prevents excessive out-of-order TCP spurious retransmissions when striping across
 * asymmetric Wi-Fi and 4G/5G links.
 */
class ReorderBuffer(
    private val maxHoldbackMs: Long = 45L,
    private val maxWindowSize: Int = 256
) {
    private data class BufferedPacket(
        val seqNum: Long,
        val payload: ByteArray,
        val arrivalMs: Long
    )

    private val pending = TreeMap<Long, BufferedPacket>()
    private var nextExpectedSeq: Long = 1L
    private var initialized: Boolean = false

    var reorderedCount: Long = 0L
        private set
    var droppedLateCount: Long = 0L
        private set

    @Synchronized
    fun push(seqNum: Long, payload: ByteArray, nowMs: Long = System.currentTimeMillis()): List<ByteArray> {
        if (!initialized) {
            nextExpectedSeq = seqNum
            initialized = true
        }

        val ready = mutableListOf<ByteArray>()

        // Late or duplicate packet
        if (seqNum < nextExpectedSeq) {
            droppedLateCount++
            return ready
        }

        if (seqNum == nextExpectedSeq) {
            ready.add(payload)
            nextExpectedSeq++
            drainInOrder(ready)
            return ready
        }

        // Out-of-order arrival: buffer it
        if (!pending.containsKey(seqNum)) {
            pending[seqNum] = BufferedPacket(seqNum, payload, nowMs)
            reorderedCount++
        }

        // Enforce window bound or holdback timeout
        flushExpired(nowMs, ready)
        return ready
    }

    @Synchronized
    fun flushExpired(nowMs: Long = System.currentTimeMillis(), out: MutableList<ByteArray> = mutableListOf()): List<ByteArray> {
        while (pending.isNotEmpty()) {
            val firstEntry = pending.firstEntry()
            val ageMs = nowMs - firstEntry.value.arrivalMs
            if (firstEntry.key <= nextExpectedSeq) {
                pending.pollFirstEntry()
                out.add(firstEntry.value.payload)
                nextExpectedSeq = firstEntry.key + 1
                drainInOrder(out)
            } else if (ageMs >= maxHoldbackMs || pending.size > maxWindowSize) {
                // Advance expected sequence past missing gap
                pending.pollFirstEntry()
                out.add(firstEntry.value.payload)
                nextExpectedSeq = firstEntry.key + 1
                drainInOrder(out)
            } else {
                break
            }
        }
        return out
    }

    private fun drainInOrder(out: MutableList<ByteArray>) {
        while (true) {
            val next = pending.remove(nextExpectedSeq) ?: break
            out.add(next.payload)
            nextExpectedSeq++
        }
    }

    @Synchronized
    fun reset() {
        pending.clear()
        nextExpectedSeq = 1L
        initialized = false
        reorderedCount = 0L
        droppedLateCount = 0L
    }
}
